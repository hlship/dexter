(ns net.lewisship.dex.lein-reader
  "Reads Leiningen project dependency data using leiningen-core APIs.

  Uses leiningen.core.project/read to parse project.clj and
  leiningen.core.classpath to resolve the full transitive dependency
  graph via Aether/Pomegranate.

  The Aether hierarchy tree prunes subtrees for artifacts already resolved
  via another path, so we can't rely on it for complete dependency edges.
  Instead, we resolve each artifact individually to discover its true
  immediate children — the same approach used by vizdeps.

  Prior art: github.com/walmartlabs/vizdeps

  The output is the flat artifact map expected by deps/build-db:
  {artifact-key -> {:version string, :label string?, :deps {artifact-key -> {:version string}}}}"
  (:require [babashka.fs :as fs]
            [leiningen.core.classpath :as classpath]
            [leiningen.core.project :as project]))

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

(defn- build-resolved-versions
  "Builds a map of {artifact-key -> dep-vec} from the flat dependency graph
  returned by classpath/get-dependencies. The graph keys are dependency
  vectors with resolved versions."
  [dep-graph]
  (reduce (fn [m dep-vec]
            (assoc m (dep-vec->key dep-vec) dep-vec))
          {}
          (keys dep-graph)))

(defn- immediate-dependencies
  "Resolves the immediate (direct) dependencies of a single artifact by
  performing a targeted Aether resolution.

  The Aether hierarchy tree prunes subtrees for artifacts whose version was
  already resolved via a different path, so many artifacts appear as leaves
  even though they have transitive dependencies. This function resolves a
  synthetic single-dependency project to discover the artifact's true
  immediate children.

  Approach borrowed from vizdeps (github.com/clj-commons/vizdeps).

  Returns a seq of dependency vectors, or nil if the artifact has no deps."
  [project dep-vec]
  (when (some? dep-vec)
    (-> (#'classpath/get-dependencies
          :dependencies nil
          (assoc project :dependencies [dep-vec]))
        (get dep-vec)
        seq)))

(defn- walk-artifacts
  "Builds the flat artifact map by walking every resolved artifact and
  discovering its immediate dependencies via per-artifact Aether resolution.

  resolved-versions is {artifact-key -> dep-vec} from the project-wide
  resolution. For each artifact, we call immediate-dependencies to get its
  true children (which may have been pruned from the hierarchy tree)."
  [project resolved-versions]
  (reduce-kv
    (fn [acc artifact-key dep-vec]
      (let [version (dep-vec->version dep-vec)
            children (immediate-dependencies project dep-vec)
            child-deps (when (seq children)
                         (reduce
                           (fn [m child-vec]
                             (assoc m (dep-vec->key child-vec)
                                    {:version (dep-vec->version child-vec)}))
                           {}
                           children))]
        (assoc acc artifact-key
               (cond-> {:version version}
                 (seq child-deps) (assoc :deps child-deps)))))
    {}
    resolved-versions))

(defn- read-project
  "Reads a project.clj file using leiningen-core and applies profiles.
  profiles is a collection of profile keywords to activate.

  project/read internally calls load-plugins which uses pomegranate to add
  plugin jars to the classloader. On Java 9+, this requires a
  DynamicClassLoader in the classloader chain (since AppClassLoader is no
  longer a modifiable URLClassLoader)."
  [project-clj-path profiles]
  (project/ensure-dynamic-classloader)
  (project/read (str project-clj-path) (or profiles [])))

(defn read-deps
  "Reads a Leiningen project's dependencies using leiningen-core APIs.

  Parses project.clj, resolves the full transitive dependency graph
  via Aether, and produces the flat artifact map for deps/build-db.

  For each resolved artifact, performs a per-artifact Aether resolution
  to discover its immediate dependencies (the hierarchy tree from
  managed-dependency-hierarchy prunes subtrees for already-resolved
  artifacts, losing dependency edges).

  Options:
  - :aliases - collection of Leiningen profile names to activate
  - :label   - display label for the ROOT entry (defaults to project name)"
  [project-clj-path {:keys [aliases label]}]
  (let [project-file (fs/absolutize project-clj-path)
        profiles (when (seq aliases)
                   (mapv keyword aliases))
        project (read-project project-file profiles)
        project-name (str (:group project) "/" (:name project))
        project-version (str (:version project))
        project-label (or label project-name)

        ;; Get the flat resolved graph: {dep-vec -> children}
        ;; This gives us every artifact and its resolved version,
        ;; but not necessarily its true children (Aether prunes).
        dep-graph (#'classpath/get-dependencies
                    :dependencies :managed-dependencies project)

        ;; Map of artifact-key -> dep-vec (with resolved version)
        resolved-versions (build-resolved-versions dep-graph)

        ;; Build artifact map by resolving each artifact's immediate deps
        artifacts (walk-artifacts project resolved-versions)

        ;; Build ROOT entry: its deps are the project's direct (top-level)
        ;; dependencies. We use the hierarchy to identify which artifacts
        ;; are direct deps (its top-level keys), with their resolved versions.
        hierarchy (classpath/managed-dependency-hierarchy
                    :dependencies :managed-dependencies project)

        root-deps (reduce-kv
                    (fn [m dep-vec _]
                      (assoc m (dep-vec->key dep-vec)
                             {:version (dep-vec->version dep-vec)}))
                    {}
                    hierarchy)

        root-entry (cond-> {:version project-version}
                     (seq project-label) (assoc :label project-label)
                     (seq root-deps) (assoc :deps root-deps))]

    (assoc artifacts 'ROOT root-entry)))
