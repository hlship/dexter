(ns net.lewisship.dex.main
  (:require [babashka.fs :as fs]
            [clj-commons.ansi :refer [pout perr]]
            [clj-commons.humanize :as h]
            [net.lewisship.cli-tools :as cli :refer [defcommand abort]]
            [net.lewisship.dex.deps :as deps])
  (:import (java.net HttpURLConnection ServerSocket URI)))

(defn- free-port
  []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(def ^:private readers
  [["deps.edn" 'net.lewisship.dex.deps-reader/read-deps]
   ["project.clj" 'net.lewisship.dex.lein-reader/read-deps]])

(defn- invoke [reader-sym file opts]
  (when (fs/exists? file)
    (perr [:faint "Reading " file " ..."])
    ((requiring-resolve reader-sym) file opts)))

(defn- read-dependency-data
  [path opts]
  (if (fs/directory? path)
    (some (fn [[file-name reader]]
            (let [file (fs/file path file-name)]
              (invoke reader file opts)))
          readers)
    (let [target-file (fs/file-name path)]
      (some (fn [[file-name reader]]
              (when (= file-name target-file)
                (invoke reader path opts)))
            readers))))

(defn- http-get
  "Makes a simple HTTP GET request and returns the response body as a string.
  Returns nil on any error."
  [url]
  (try
    (let [^HttpURLConnection conn
          (doto (.openConnection (.toURL (URI. url)))
            (.setRequestMethod "GET")
            (.setConnectTimeout 5000)
            (.setReadTimeout 10000))]
      (try
        (slurp (.getInputStream conn))
        (finally
          (.disconnect conn))))
    (catch Exception _
      nil)))

(defn- warm-up!
  "Exercises the server with HTTP requests to load classes for AOT training.
  Makes multiple requests to cover: initial page render, layout computation
  (via simulated viewport size), and static resource loading."
  [url]
  (perr [:faint "Warming up server ..."])
  ;; Initial page render: exercises Ring handler, Hyper rendering,
  ;; deps/db lookups, toolbar, footer stats, datalist generation.
  (http-get url)
  ;; Hit the page a couple more times to exercise SSE/connection paths
  (http-get url)
  ;; Request static resources to load those class paths
  (http-get (str url "/style.css"))
  (http-get (str url "/js/main.js"))
  (http-get (str url "/favicon.svg"))
  (perr [:faint "Warm-up complete."]))

(defcommand -main
  "Dexter is used to explore the dependency graph of a Clojure (or JVM) based project.
  The dependencies, relationships, and versions are analyzed and a browser is launched
  to allow interactive exploration of the dependency hierarchy.
  
  Normally, will find the correct dependency file (deps.edn, project.clj, etc.) in the
  current directory, though the --file option can override this."
  [port ["-p" "--port NUMBER" "Port to use for web server"
         :parse-fn parse-long
         :validate [some? "Not a number"
                    #(<= 1000 %) "Must be at least 1000"]]
   file ["-f" "--file PATH" "Dependency file to read, or directory to search"]
   aliases ["-a" "--alias NAME" "Add an alias (also known as a profile) used when resolving dependencies"
            :multi true
            :update-fn (fnil conj [])]
   no-open? [nil "--no-open" "Do not automatically open a browser"]
   :command "dexter"]
  (let [port' (or port (free-port))
        dry-run? (some? (System/getProperty "dexter.dry-run"))
        path  (-> (or file ".") fs/absolutize fs/normalize)
        data  (read-dependency-data path {:aliases aliases})
        url   (str "http://localhost:" port')]
    ;; TODO: Update oxford to override "and" to "or"
    (when-not data
      (abort [:yellow
              "No project file found in " [:bold path]
              ", expecting one of: "
              (h/oxford
                (->> readers
                     (map first)
                     sort)
                :maximum-display 100)]))
    (pout [:faint "Running web server at "] [:bold url] " ...")
    ((requiring-resolve 'net.lewisship.dex.service/start!) {:port port'
                                                            :db (deps/build-db data)})
    (if dry-run?
      (do
        (warm-up! url)
        ((requiring-resolve 'net.lewisship.dex.service/stop!)))
      (do
        (pout "Hit " [:bold "Ctrl+C"] " when done")
        (when-not no-open?
          ((requiring-resolve 'clojure.java.browse/browse-url) url))
        ;; Hang forever (until ^C)
        @(promise)))))
