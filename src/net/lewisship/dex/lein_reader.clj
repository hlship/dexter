(ns net.lewisship.dex.lein-reader
  "Reads Leiningen project dependency data and produces the flat artifact map
  expected by net.lewisship.dex.deps/build-db.

  The data is obtained by running `lein deps :tree-data` as a subprocess,
  which produces a nested map where:
  - Keys are dependency vectors: [artifact-symbol version-string & opts]
  - Values are either nil (leaf) or a nested map of the same shape

  This namespace walks that tree to produce:
  {artifact-key -> {:version string, :deps {artifact-key -> {:version string}}}}"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [net.lewisship.cli-tools :refer [abort]]))

(defn- dep-vec->key
  "Extracts the artifact key (symbol) from a lein dependency vector.
  e.g., [cheshire \"5.13.0\" :exclusions [...]] -> cheshire"
  [dep-vec]
  (first dep-vec))

(defn- dep-vec->version
  "Extracts the version string from a lein dependency vector.
  e.g., [cheshire \"5.13.0\" :exclusions [...]] -> \"5.13.0\""
  [dep-vec]
  (second dep-vec))

(defn- walk-tree
  "Recursively walks the lein tree-data, collecting artifacts into a flat map.

  Each node's direct children become that node's :deps map.
  The walk continues into each child to collect transitive artifacts."
  [tree-data acc]
  (reduce-kv
   (fn [acc dep-vec children]
     (let [artifact-key (dep-vec->key dep-vec)
           version (dep-vec->version dep-vec)
           child-deps (when (seq children)
                        (reduce-kv
                         (fn [m child-vec _]
                           (assoc m (dep-vec->key child-vec)
                                  {:version (dep-vec->version child-vec)}))
                         {}
                         children))
           entry (cond-> {:version version}
                   (seq child-deps) (assoc :deps child-deps))
           acc (assoc acc artifact-key entry)]
       (if (seq children)
         (walk-tree children acc)
         acc)))
   acc
   tree-data))

(defn- parse-project-clj
  "Extracts the project name and version from a project.clj file.
  project.clj has the form: (defproject name \"version\" ...)
  Returns {:name string, :version string} or nil if parsing fails."
  [project-clj-path]
  (try
    (let [form (read-string (slurp (str project-clj-path)))]
      (when (list? form)
        (let [[defp project-name project-version] form]
          (when (and (= 'defproject defp) project-name project-version)
            {:name (str project-name)
             :version (str project-version)}))))
    (catch Exception _ nil)))

(defn parse-tree-data
  "Parses lein deps :tree-data output into the flat artifact map
  expected by deps/build-db.

  tree-data is the nested map structure produced by `lein deps :tree-data`.
  project-info is a map with optional :label and :version for the ROOT entry."
  [tree-data {:keys [label version]}]
  (let [;; ROOT's direct deps are the top-level keys
        root-deps (reduce-kv
                   (fn [m dep-vec _]
                     (assoc m (dep-vec->key dep-vec)
                            {:version (dep-vec->version dep-vec)}))
                   {}
                   tree-data)
        root-entry (cond-> {:version (or version "0.0.0")}
                     (seq label) (assoc :label label)
                     (seq root-deps) (assoc :deps root-deps))
        ;; Walk the tree to collect all artifacts
        artifacts (walk-tree tree-data {})]
    (assoc artifacts 'ROOT root-entry)))

(defn- run-lein
  "Runs `lein deps :tree-data` in the given directory and returns the parsed EDN.
  aliases is a collection of profile names to activate via `with-profiles`.
  Multiple profiles are combined with commas, e.g. `+dev,+test`."
  [dir aliases]
  (let [profiles (if (seq aliases)
                   (string/join "," (map #(str "+" %) aliases))
                   "")
        cmd ["lein" "with-profiles" profiles "deps" ":tree-data"]
        result (process/shell {:dir (str dir)
                               :out :string
                               :err :string
                               :continue true
                               :cmd cmd})]
    (when-not (zero? (:exit result))
      (abort (:exit result)
             "lein deps :tree-data failed:\n"
             (:err result)))
    (edn/read-string (:out result))))

(defn read-deps
  "Reads a Leiningen project's dependencies by running `lein deps :tree-data`.

  project-clj-path is the path to the project.clj file.
  Options:
  - :aliases - collection of Leiningen profile names to activate
  - :label   - display label for the ROOT entry (defaults to directory name)"
  [project-clj-path {:keys [aliases label]}]
  (let [project-file (fs/absolutize project-clj-path)
        project-dir (fs/parent project-file)
        project-info (parse-project-clj project-file)
        project-label (or label
                          (:name project-info)
                          (when project-dir
                            (str (fs/file-name project-dir))))
        tree-data (run-lein project-dir aliases)]
    (parse-tree-data tree-data {:label project-label
                                :version (:version project-info)})))
