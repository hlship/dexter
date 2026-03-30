(ns net.lewisship.dex.layout
  "Computes the visual layout for the dependency viewer.

  Given a selected artifact and the dependency database, produces a data structure
  describing:
  - Three columns of artifact boxes (dependants, selected, dependencies)
  - Windowed subsets when columns overflow
  - Connections between all visible artifacts that have dependency relationships,
    tagged with version info and connection type."
  (:require [net.lewisship.dex.deps :as deps]
            [version-clj.core :as ver]))

;; Maximum number of nodes visible in a single column before windowing kicks in.
(def ^:const default-max-visible 8)

(def default-hidden-libs
  "Default set of libs to hide from dependency/dependant columns.
  These are ubiquitous dependencies that add clutter without insight."
  #{'org.clojure/clojure})

;; --- Version Compatibility ---

(def ^:private git-sha-pattern
  "Matches short (7-8 char) or full (40 char) hex strings typical of git SHAs."
  #"^[0-9a-f]{7,40}$")

(defn- git-sha?
  "Returns true if the version string looks like a git SHA."
  [v]
  (boolean (re-matches git-sha-pattern v)))

(defn- parse-major-minor
  "Extracts [major minor] from a version string using version-clj.
  Returns nil if the version can't be parsed into numeric components."
  [v]
  (try
    (let [{:keys [version]} (ver/parse v)
          ;; version is a vector of sub-sequences, e.g. [(1 2 3)] for "1.2.3"
          nums (first version)]
      (when (and (sequential? nums) (>= (count nums) 2)
                 (number? (first nums)) (number? (second nums)))
        [(first nums) (second nums)]))
    (catch Exception _ nil)))

(defn version-match
  "Classifies the compatibility between a requested and resolved version.

  Returns a keyword:
  - :exact        - versions are identical strings
  - :compatible   - same major version (1.x+) or same major.minor (0.x)
  - :incompatible - different major version (1.x+) or different major.minor (0.x)
  - :unknown      - git SHA, local path, or unparseable versions"
  [requested resolved]
  (cond
    (= requested resolved)
    :exact

    (or (nil? requested) (nil? resolved)
        (git-sha? requested) (git-sha? resolved)
        (= "unknown" requested) (= "unknown" resolved))
    :unknown

    :else
    (let [req-mm (parse-major-minor requested)
          res-mm (parse-major-minor resolved)]
      (if (and req-mm res-mm)
        (let [[req-major req-minor] req-mm
              [res-major res-minor] res-mm]
          (if (if (zero? req-major)
                ;; For 0.x, minor version must also match
                (and (= req-major res-major) (= req-minor res-minor))
                ;; For 1.x+, just major must match
                (= req-major res-major))
            :compatible
            :incompatible))
        :unknown))))

(def ^:private match->color
  "Maps version match classification to arrow color."
  {:exact        "#000000"   ; black
   :compatible   "#16a34a"   ; green
   :incompatible "#dc2626"   ; red
   :unknown      "#ca8a04"}) ; yellow

(defn version-match-color
  "Returns the arrow color for a version compatibility classification."
  [requested resolved]
  (get match->color (version-match requested resolved) "#64748b"))

(def ^:private match-severity
  "Severity ordering for version match classifications.
  Higher values are more significant (worse)."
  {:exact 0
   :compatible 1
   :unknown 2
   :incompatible 3})

(defn- window-items
  "Applies windowing to a collection of items.

  Returns a map with:
  - :items       - the visible slice
  - :total       - total item count
  - :offset      - current offset
  - :before      - count of items above the window
  - :after       - count of items below the window"
  [items offset n]
  (let [all (vec items)
        total (count all)
        offset (max 0 (min offset (max 0 (- total n))))
        end (min total (+ offset n))
        visible (subvec all offset end)]
    {:items visible
     :total total
     :offset offset
     :before offset
     :after (max 0 (- total end))}))

(defn- artifact-box
  "Creates a box descriptor for an artifact.
  hidden-libs is used to determine leaf status — an artifact is a leaf if
  it has no dependencies after filtering out hidden libs."
  [db artifact-key column row hidden-libs]
  (let [info (deps/artifact-info db artifact-key)
        dep-keys (keys (:deps info))
        visible-deps (if (seq hidden-libs)
                       (remove #(contains? hidden-libs %) dep-keys)
                       dep-keys)]
    {:id (str "box-" artifact-key)
     :key artifact-key
     :name (:label info)
     :version (:version info)
     :leaf? (empty? visible-deps)
     :column column
     :row row}))

(defn- find-visible-connections
  "Finds all dependency connections between visible artifacts.

  For every pair of visible artifacts where one depends on the other,
  emits a connection record with version info and connection type.

  Connection types:
  - :cross-column  - connects nodes in different columns
  - :intra-column  - connects nodes within the same column"
  [db visible-keys box-lookup]
  (let [visible-set (set visible-keys)]
    (into []
          (mapcat
           (fn [from-key]
             (keep (fn [{:keys [to requested-version resolved-version]}]
                     (when (visible-set to)
                       (let [from-box (box-lookup from-key)
                             to-box (box-lookup to)]
                         {:from from-key
                          :to to
                          :from-id (:id from-box)
                          :to-id (:id to-box)
                          :from-column (:column from-box)
                          :from-row (:row from-box)
                          :to-column (:column to-box)
                          :to-row (:row to-box)
                          :requested-version requested-version
                          :resolved-version resolved-version
                          :color (version-match-color requested-version resolved-version)
                          :connection-type (cond
                                             (= (:column from-box) (:column to-box))
                                             :intra-column

                                             (and (= :left (:column from-box))
                                                  (= :right (:column to-box)))
                                             :bypass

                                             :else
                                             :cross-column)})))
                   (deps/dependencies db from-key))))
          visible-keys)))

(defn compute-layout
  "Computes the full layout for the dependency viewer.

  Parameters:
  - db             - the dependency database
  - selected       - the currently selected artifact key
  - left-offset    - windowing offset for the dependants column
  - right-offset   - windowing offset for the dependencies column
  - hidden-libs    - set of lib keys to hide from dependency/dependant columns
                     (unless the hidden lib itself is selected)
  - max-visible    - maximum nodes per column (computed from viewport height)

  Returns a map with:
  - :selected-box   - the center box descriptor
  - :left           - windowed dependants column {:items :boxes :total :offset :before :after}
  - :right          - windowed dependencies column {:items :boxes :total :offset :before :after}
  - :connections    - vector of connection records between visible artifacts"
  [db selected left-offset right-offset hidden-libs max-visible]
  (let [max-visible (or max-visible default-max-visible)

        ;; Center box
        selected-box (artifact-box db selected :center 0 hidden-libs)

        ;; Hidden libs are filtered out of columns unless they are the selected artifact
        hide? (fn [k] (and (seq hidden-libs)
                           (contains? hidden-libs k)
                           (not= k selected)))

        ;; Build full lists of dependency/dependant keys
        dep-connections (deps/dependencies db selected)
        dept-connections (deps/dependants db selected)

        dep-keys (into [] (remove hide?) (mapv :to dep-connections))
        dept-keys (into [] (remove hide?) (mapv :from dept-connections))

        ;; Apply windowing
        left-window (window-items dept-keys left-offset max-visible)
        right-window (window-items dep-keys right-offset max-visible)

        ;; Build box descriptors for visible items
        left-boxes (vec (map-indexed
                         (fn [row k] (artifact-box db k :left row hidden-libs))
                         (:items left-window)))
        right-boxes (vec (map-indexed
                          (fn [row k] (artifact-box db k :right row hidden-libs))
                          (:items right-window)))

        ;; All visible keys and a lookup map
        all-boxes (into [selected-box] (concat left-boxes right-boxes))
        box-lookup (into {} (map (juxt :key identity)) all-boxes)
        visible-keys (mapv :key all-boxes)

        ;; Find all connections between visible artifacts
        connections (find-visible-connections db visible-keys box-lookup)

        ;; Compute worst version mismatch color per origin box across all connections.
        ;; A box may depend on multiple artifacts, each with different version match
        ;; levels. The border shows the most significant (worst) mismatch.
        worst-match-by-origin
        (reduce (fn [acc {:keys [from requested-version resolved-version]}]
                  (let [m (version-match requested-version resolved-version)
                        prev (get acc from :exact)]
                    (if (> (match-severity m) (match-severity prev))
                      (assoc acc from m)
                      acc)))
                {}
                connections)

        ;; Annotate boxes with version-color for the most significant mismatch
        annotate-boxes (fn [boxes]
                         (mapv (fn [box]
                                 (let [m (get worst-match-by-origin (:key box))]
                                   (if (and m (not= :exact m))
                                     (assoc box :version-color (match->color m))
                                     box)))
                               boxes))]

    {:selected-box (first (annotate-boxes [selected-box]))
     :left (assoc left-window :boxes (annotate-boxes left-boxes))
     :right (assoc right-window :boxes (annotate-boxes right-boxes))
     :connections connections}))
