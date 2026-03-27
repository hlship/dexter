(ns net.lewisship.dex.main
  (:require [clojure.java.browse :as browse]
            [clojure.string :as str]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.deps-reader :as deps-reader]
            [net.lewisship.dex.service :as service]))

(def ^:private default-port 10240)

(defn- parse-aliases
  "Parses alias arguments into a vector of keywords.
  Accepts forms like ':dev', ':dev:test', or 'dev'."
  [alias-args]
  (into []
        (comp
         (mapcat #(str/split % #":"))
         (remove str/blank?)
         (map keyword))
        alias-args))

(defn- deps-edn-file?
  "Returns true if the path looks like a deps.edn file (as opposed to a
  pre-built project-deps.edn)."
  [path]
  (str/ends-with? path "deps.edn"))

(defn -main
  [& args]
  (let [path (or (first args) "test-resources/dex/project-deps.edn")
        alias-args (rest args)
        aliases (when (seq alias-args) (parse-aliases alias-args))]
    (println (str "Loading dependency data from: " path))
    (if (deps-edn-file? path)
      (do
        (when (seq aliases)
          (println (str "  with aliases: " (str/join ", " (map name aliases)))))
        (println "  Resolving dependencies (this may take a moment)...")
        (let [raw-data (deps-reader/read-deps path {:aliases aliases})]
          (reset! deps/*db (deps/build-db raw-data))))
      (deps/load-db! path))
    (println (str "Loaded " (count (deps/artifact-keys @deps/*db)) " artifacts"))
    (service/start!)
    (let [url (str "http://localhost:" default-port)]
      (println (str "Server started at " url))
      (browse/browse-url url))))
