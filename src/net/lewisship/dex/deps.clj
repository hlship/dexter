(ns net.lewisship.dex.deps
  "Reads and indexes artifact dependency data.

  The source EDN is a flat map of {artifact-key -> artifact-info} where:
  - artifact-key is a symbol (e.g., org.clojure/clojure) or the special key ROOT
  - artifact-info has :version (string), optional :label (string), optional :deps (map)
  - :deps is {artifact-key -> {:version requested-version-string}}

  This namespace builds an indexed database that supports fast lookup of both
  dependencies and reverse dependencies (dependants) for any artifact."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- build-dependants-index
  "Builds a map of {artifact-key -> [{:from artifact-key :requested-version string}]}
  by inverting all :deps relationships in the raw data."
  [raw-data]
  (reduce-kv
   (fn [index artifact-key {:keys [deps]}]
     (reduce-kv
      (fn [index dep-key {:keys [version]}]
        (update index dep-key (fnil conj [])
                {:from artifact-key
                 :requested-version version}))
      index
      deps))
   {}
   raw-data))

(defn build-db
  "Builds an indexed dependency database from raw EDN data.

  Returns a map with:
  - :artifacts     - the raw artifact map {key -> {:version :label :deps}}
  - :dependants    - reverse index {key -> [{:from key :requested-version v}]}"
  [raw-data]
  {:artifacts raw-data
   :dependants (build-dependants-index raw-data)})

(defn artifact-keys
  "Returns all artifact keys in the database."
  [db]
  (keys (:artifacts db)))

(defn artifact-info
  "Returns the artifact info for the given key, or nil if not found."
  [db artifact-key]
  (get-in db [:artifacts artifact-key]))

(defn dependencies
  "Returns a vector of connection maps for the direct dependencies of the given artifact.

  Each connection map contains:
  - :to                - the dependency artifact key
  - :requested-version - the version requested by the artifact
  - :resolved-version  - the actual version in the database (may differ)"
  [db artifact-key]
  (let [deps (get-in db [:artifacts artifact-key :deps])]
    (mapv (fn [[dep-key {:keys [version]}]]
            {:to dep-key
             :requested-version version
             :resolved-version (get-in db [:artifacts dep-key :version])})
          deps)))

(defn dependants
  "Returns a vector of connection maps for artifacts that depend on the given artifact.

  Each connection map contains:
  - :from               - the dependant artifact key
  - :requested-version  - the version the dependant requested
  - :resolved-version   - the actual resolved version of this artifact"
  [db artifact-key]
  (let [resolved (get-in db [:artifacts artifact-key :version])]
    (mapv (fn [{:keys [from requested-version]}]
            {:from from
             :requested-version requested-version
             :resolved-version resolved})
          (get-in db [:dependants artifact-key]))))

(defn load-db
  "Reads an EDN dependency file and returns an indexed database."
  [path]
  (-> path
      io/reader
      slurp
      edn/read-string
      build-db))

;; Holds the current database; set via load-db! for REPL use,
;; or by the main entry point before starting the server.
(defonce *db (atom nil))

(defn load-db!
  "Loads a dependency file and stores the db in the *db atom.
  Returns the db."
  [path]
  (let [db (load-db path)]
    (reset! *db db)
    db))
