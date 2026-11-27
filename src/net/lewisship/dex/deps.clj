(ns net.lewisship.dex.deps
  "Reads and indexes artifact dependency data.

  The source EDN is a flat map of {artifact-key -> artifact-info} where:
  - artifact-key is a symbol (e.g., org.clojure/clojure) or the special key ROOT
  - artifact-info has :version (string), optional :label (string), optional :deps (map)
  - :deps is {artifact-key -> {:version requested-version-string}}

  This namespace builds an indexed database that supports fast lookup of both
  dependencies and reverse dependencies (dependants) for any artifact."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn- build-dependants-index
  "Builds a map of {artifact-key -> [{:from artifact-key :requested-version string}]}
  by inverting all :deps relationships in the dependency data."
  [dependency-data]
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
   dependency-data))

(defn footprints
  "Computes the footprint of every artifact in the dependency data: the size
  (bytes) of the artifact plus all of its transitive dependencies, each
  counted once even when reachable by several paths.

  An artifact with no :size counts as 0. Returns {artifact-key -> bytes}, or an
  empty map when the data carries no sizes at all (so the UI can omit size
  indicators entirely)."
  [dependency-data]
  (if-not (some :size (vals dependency-data))
    {}
    (let [reachable (fn [start]
                      (loop [seen #{start} queue [start]]
                        (if-let [k (peek queue)]
                          (let [children (->> (keys (get-in dependency-data [k :deps]))
                                              (filter #(contains? dependency-data %))
                                              (remove seen))]
                            (recur (into seen children)
                                   (into (pop queue) children)))
                          seen)))]
      (reduce-kv (fn [m k _]
                   (assoc m k (transduce (map #(or (get-in dependency-data [% :size]) 0))
                                         + 0 (reachable k))))
                 {}
                 dependency-data))))

(defn- with-footprints
  "Attaches :footprints and :max-footprint (the scale for size bars) to the db."
  [db footprints]
  (assoc db
         :footprints footprints
         :max-footprint (when (seq footprints)
                          (apply max (vals footprints)))))

(defn footprint
  "Returns the footprint in bytes (the artifact's size plus that of all its
  transitive dependencies) of the artifact, or nil if sizes are unknown."
  [db artifact-key]
  (get-in db [:footprints artifact-key]))

(defn build-db
  "Builds an indexed dependency database from dependency data.

  Ensures every artifact has a :label (defaults to the string form of the key).
  Builds a label index for fast search.

  Returns a map with:
  - :artifacts     - {key -> {:version :label :deps ...}} with :label guaranteed
  - :dependants    - reverse index {key -> [{:from key :requested-version v}]}
  - :by-label      - {lowercase-label -> key} for search
  - :footprints    - {key -> bytes} artifact size plus all transitive dependencies
                     (empty when the data has no sizes)
  - :max-footprint - largest footprint, the scale for size bars"
  [dependency-data]
  (let [;; Ensure every artifact has a :label, and sort :deps alphabetically.
        ;; Filter out deps that reference unresolved artifacts — these are
        ;; version-evicted transitive dependencies that appear in the trace
        ;; tree but were never included in the resolved set.
        artifacts (reduce-kv
                   (fn [m k info]
                     (assoc m k (-> info
                                    (update :label #(or % (str k)))
                                    (update :deps (fn [deps]
                                                    (when (seq deps)
                                                      (into (sorted-map)
                                                            (filter #(contains? dependency-data (key %)))
                                                            deps)))))))
                   {}
                   dependency-data)
        ;; Build label index: lowercase label -> artifact key
        by-label (reduce-kv
                  (fn [m k {:keys [label]}]
                    (assoc m (string/lower-case label) k))
                  {}
                  artifacts)
        ;; Build dependants index with each entry sorted by :from key
        dependants (reduce-kv
                    (fn [m k entries]
                      (assoc m k (vec (sort-by :from entries))))
                    {}
                    (build-dependants-index dependency-data))]
    (with-footprints {:artifacts  artifacts
                      :dependants dependants
                      :by-label   by-label}
                     (footprints dependency-data))))

(defn artifact-keys
  "Returns all artifact keys in the database."
  [db]
  (keys (:artifacts db)))

(defn artifact-info
  "Returns the artifact info for the given key, or nil if not found."
  [db artifact-key]
  (get-in db [:artifacts artifact-key]))

(defn find-artifact
  "Finds the first artifact key matching the search text.
  Tries exact label match first, then case-insensitive substring match
  against labels. Returns the artifact key (symbol) or nil."
  [db text]
  (when (and text (seq text))
    (let [lower (string/lower-case (str text))]
      ;; Exact label match
      (or (get-in db [:by-label lower])
          ;; Substring match against labels (sorted for deterministic results)
          (->> (:by-label db)
               (some (fn [[label k]]
                       (when (string/includes? label lower)
                         k))))))))

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

(defn- filter-dependency-data
  "Walks dependency-data from ROOT, excluding hidden-libs and any artifact only
  reachable through them. Returns a filtered map suitable for passing to build-db."
  [dependency-data hidden-libs]
  (loop [result {} visited #{} queue ['ROOT]]
    (if (empty? queue)
      result
      (let [[current & rest-q] queue]
        (if (contains? visited current)
          (recur result visited rest-q)
          (let [info          (get dependency-data current)
                filtered-deps (into (sorted-map)
                                    (remove #(contains? hidden-libs (key %)))
                                    (or (:deps info) {}))
                children      (->> (keys (:deps info))
                                   (remove hidden-libs)
                                   (remove visited))]
            (recur (assoc result current (assoc info :deps filtered-deps))
                   (conj visited current)
                   (into (vec rest-q) children))))))))

(defn filter-db
  "Builds a new database from dependency-data, excluding hidden-libs and any
  artifact only reachable through them.

  Walks dependency-data from ROOT without traversing into hidden libs, then
  calls build-db on the result so all secondary indexes are consistent.
  The original dependency-data is always stored under :dependency-data in the
  returned db so it can be used to rebuild with different filters later.
  Returns build-db(dependency-data) when hidden-libs is empty.

  Footprints (and the size-bar scale) are computed from the filtered data, so
  they cover only visible artifacts: hidden libraries, and artifacts reachable
  only through them, don't count. Showing hidden libraries rebuilds the db and
  the footprints grow to include them."
  [dependency-data hidden-libs]
  (build-db (if (empty? hidden-libs)
              dependency-data
              (filter-dependency-data dependency-data hidden-libs))))

(defn load-dependency-data
  "Reads an EDN dependency file and returns the dependency data map
  (suitable for passing to build-db or filter-db)."
  [path]
  (-> path io/reader slurp edn/read-string))


