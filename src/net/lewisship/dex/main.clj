(ns net.lewisship.dex.main
  (:require [babashka.fs :as fs]
            [clj-commons.ansi :refer [pout perr]]
            [clj-commons.humanize :as h]
            [clojure.string :as string]
            [net.lewisship.cli-tools :as cli :refer [defcommand abort]]
            [net.lewisship.dex.deps :as deps])
  (:import (java.net ServerSocket)))

(defn- free-port
  []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- parse-aliases
  "Parses alias arguments into a vector of keywords.
  Accepts forms like ':dev', ':dev:test', or 'dev'."
  [alias-args]
  (into []
        (comp
          (mapcat #(string/split % #":"))
          (remove string/blank?)
         (map keyword))
        alias-args))

(def ^:private readers
  [["deps.edn" 'net.lewisship.dex.deps-reader/deps-reader]
   ["project.clj" 'net.lewisship.dex.lein-reader/lein-reader]])

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
   file ["-f" "--file PATH" "Dependency file to read"]
   aliases ["-a" "--alias NAME" "Add an alias (also known as a profile) used when resolving dependencies"
            :multi true
            :update-fn (fnil conj [])]
   no-open? ["-O" "--no-open" "Do not automatically open a browser"]
   :command "dexter"]
  (let [port' (or port (free-port))
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
    ;; TODO: As an argument to start! not this funky mess
    (reset! deps/*db (deps/build-db data))
    ((requiring-resolve 'net.lewisship.dex.service/start!) {:port port'})
    (pout "Hit " [:bold "Ctrl+C"] " when done")
    (when-not no-open?
      ((requiring-resolve 'clojure.java.browse/browse-url) url))
    ;; Hang forever (until ^C)
    @(promise)))


(comment
  (cli/set-prevent-exit! true)
  (-main)
  (-main "-adev")
  (-main "-f..")

  (-> ".." fs/absolutize fs/normalize)

  ;; Load from pre-built test data
  (deps/load-db! "test-resources/dex/project-deps.edn")

  ;; Or resolve live from a deps.edn (this project as an example)
  (do
    (require '[net.lewisship.dex.deps-reader :as deps-reader])
    (let [raw-data (deps-reader/read-deps (fs/file "deps.edn") {:aliases ["dev" "test"]})]
      (reset! deps/*db (deps/build-db raw-data))))
  
  (service/start! nil)

  (service/stop!)

  (do
    (require '[net.lewisship.dex.service :as service])
    ((requiring-resolve 'clj-reload.core/reload))
    (service/stop!)
    (service/start! nil))

  ;;
  )
