(ns net.lewisship.dex.deps-reader
  "Reads a deps.edn file using org.clojure/tools.deps to resolve the
  full transitive dependency graph, and produces the data structure
  expected by net.lewisship.dex.deps/build-db.

  The output format is a flat map of {artifact-key -> artifact-info}:
  - Each artifact-key is a symbol (e.g., org.clojure/clojure)
  - The special key ROOT represents the project itself
  - artifact-info has :version (string), optional :label, optional :deps
  - :deps is {artifact-key -> {:version requested-version-string}}"
  (:require [babashka.fs :as fs]
            [clojure.tools.deps :as td]
            [clojure.tools.deps.tree :as tree])
  (:import (java.io File)))

(defn- coord->version
  "Extracts a version string from a tools.deps coordinate map.
  Handles Maven (:mvn/version), git (:git/sha or :git/tag), and local (:local/root)."
  [coord]
  (or (:mvn/version coord)
      (:git/tag coord)
      (when-let [sha (:git/sha coord)]
        (subs sha 0 (min 8 (count sha))))
      (:local/root coord)
      "unknown"))

(defn- tree-node->deps
  "Extracts the direct dependencies from a tree node's :children map.
  Returns a map of {lib-symbol -> {:version requested-version}} for all
  children, regardless of whether their version was selected.  The dependency
  relationship exists even when a different version was ultimately resolved."
  [children]
  (reduce-kv
   (fn [m lib {:keys [coord]}]
     (assoc m lib {:version (coord->version coord)}))
   {}
   children))

(defn- walk-tree
  "Recursive helper for collect-artifacts."
  [children acc lib-map]
  (reduce-kv
   (fn [acc lib {:keys [coord include children]}]
     (if include
       (let [resolved-coord (get lib-map lib)
             resolved-version (if resolved-coord
                                (coord->version resolved-coord)
                                (coord->version coord))
             child-deps (tree-node->deps children)
             entry (cond-> {:version resolved-version}
                     (seq child-deps) (assoc :deps child-deps))
             acc (assoc acc lib entry)]
         (walk-tree children acc lib-map))
       acc))
   acc
   children))

(defn- collect-artifacts
  "Walks the trace tree and collects all included artifacts into a flat map.
  Each artifact maps to {:version resolved-version :deps {child -> {:version requested}}}.

  lib-map is the resolved lib map from create-basis, used to get final resolved versions."
  [tree-children lib-map]
  (walk-tree tree-children {} lib-map))

(defn- build-root-entry
  "Builds the ROOT artifact entry from the trace tree's top-level children,
  which reflect the effective deps after alias merging."
  [top-children project-label]
  (let [root-deps (tree-node->deps top-children)]
    (cond-> {:version "0.0.0"}
      (seq project-label) (assoc :label project-label)
      (seq root-deps) (assoc :deps root-deps))))


(defn read-deps
  "Reads a deps.edn file, resolves its transitive dependencies, and returns
  a raw data map suitable for passing to deps/build-db.

  Options:
  - :aliases - collection of alias strings to include in resolution
  - :label   - display label for the ROOT entry (defaults to directory name)"
  [^File deps-edn-path {:keys [aliases label]}]
  (let [deps-file     (fs/absolutize deps-edn-path)
        project-dir   (fs/parent deps-file)
        project-label (or label
                          (when project-dir
                            (fs/file-name project-dir)))
        opts          (cond-> {:project (str deps-file)
                      :user nil
                               :dir     (str project-dir)}
                        (seq aliases) (assoc :aliases (mapv keyword aliases)))
        ;; Get the trace for tree structure
        trace (tree/calc-trace opts)
        dep-tree (tree/trace->tree trace)
        top-children (:children dep-tree)
        ;; Get the basis for resolved versions
        basis (td/create-basis opts)
        lib-map (:libs basis)
        ;; Walk tree to collect all artifacts
        artifacts (collect-artifacts top-children lib-map)
        ;; Build ROOT entry from the tree's top-level children (includes alias deps)
        root-entry (build-root-entry top-children project-label)]
    (assoc artifacts 'ROOT root-entry)))
