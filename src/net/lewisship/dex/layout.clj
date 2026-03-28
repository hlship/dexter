(ns net.lewisship.dex.layout
  "Computes the visual layout for the dependency viewer.

  Given a selected artifact and the dependency database, produces a data structure
  describing:
  - Three columns of artifact boxes (dependants, selected, dependencies)
  - Windowed subsets when columns overflow
  - Connections between all visible artifacts that have dependency relationships,
    tagged with version info and connection type."
  (:require [net.lewisship.dex.deps :as deps]))

;; Maximum number of nodes visible in a single column before windowing kicks in.
(def ^:const max-visible 8)

(def default-hidden-libs
  "Default set of libs to hide from dependency/dependant columns.
  These are ubiquitous dependencies that add clutter without insight."
  #{'org.clojure/clojure})

(defn- window-items
  "Applies windowing to a collection of items.

  Returns a map with:
  - :items       - the visible slice
  - :total       - total item count
  - :offset      - current offset
  - :before      - count of items above the window
  - :after       - count of items below the window"
  [items offset]
  (let [all (vec items)
        total (count all)
        offset (max 0 (min offset (max 0 (- total max-visible))))
        end (min total (+ offset max-visible))
        visible (subvec all offset end)]
    {:items visible
     :total total
     :offset offset
     :before offset
     :after (max 0 (- total end))}))

(defn- artifact-box
  "Creates a box descriptor for an artifact."
  [db artifact-key column row]
  (let [info (deps/artifact-info db artifact-key)]
    {:id (str "box-" artifact-key)
     :key artifact-key
     :name (or (:label info) (str artifact-key))
     :version (:version info)
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

  Returns a map with:
  - :selected-box   - the center box descriptor
  - :left           - windowed dependants column {:items :boxes :total :offset :before :after}
  - :right          - windowed dependencies column {:items :boxes :total :offset :before :after}
  - :connections    - vector of connection records between visible artifacts"
  [db selected left-offset right-offset hidden-libs]
  (let [;; Center box
        selected-box (artifact-box db selected :center 0)

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
        left-window (window-items dept-keys left-offset)
        right-window (window-items dep-keys right-offset)

        ;; Build box descriptors for visible items
        left-boxes (vec (map-indexed
                         (fn [row k] (artifact-box db k :left row))
                         (:items left-window)))
        right-boxes (vec (map-indexed
                          (fn [row k] (artifact-box db k :right row))
                          (:items right-window)))

        ;; All visible keys and a lookup map
        all-boxes (into [selected-box] (concat left-boxes right-boxes))
        box-lookup (into {} (map (juxt :key identity)) all-boxes)
        visible-keys (mapv :key all-boxes)

        ;; Find all connections between visible artifacts
        connections (find-visible-connections db visible-keys box-lookup)]

    {:selected-box selected-box
     :left (assoc left-window :boxes left-boxes)
     :right (assoc right-window :boxes right-boxes)
     :connections connections}))
