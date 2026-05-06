(ns net.lewisship.dex.maven-reader
  "Reads Maven project dependency data by running Maven as a subprocess.

  Uses `mvn dependency:tree` with `-DoutputFile` to get a clean text tree
  of the resolved dependency graph, then parses it into the flat artifact
  map expected by deps/build-db.

  Maven executable lookup order:
  1. mvnw or mvnw.cmd in the project directory (Maven wrapper)
  2. mvn on PATH

  It is an error if a pom.xml exists but no Maven executable can be found.

  Uses `-Dverbose` so that Maven includes duplicate/omitted entries in the
  tree.  Without verbose mode, Maven prunes each artifact to its first
  occurrence, losing dependency edges when multiple parents share a
  transitive dependency.  The verbose tree repeats those entries (marked
  \"omitted for duplicate\"), which allows us to capture the full graph.

  The output format matches the standard reader contract:
  {artifact-key -> {:version string, :label string?, :deps {artifact-key -> {:version string}}}}"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clj-commons.ansi :refer [perr]]
            [clojure.string :as string]
            [net.lewisship.cli-tools :refer [abort]]))

(defn- find-maven-executable
  "Finds the Maven executable to use.  Prefers the Maven wrapper
  (mvnw / mvnw.cmd) in the project directory, then falls back
  to mvn on PATH.  Returns an absolute path string, or nil."
  [project-dir]
  (or (some (fn [wrapper-name]
              (let [f (fs/file project-dir wrapper-name)]
                (when (fs/exists? f)
                  (str (fs/absolutize f)))))
            ["mvnw.cmd" "mvnw"])
      (some-> (fs/which "mvn") str)))

(defn- parse-coordinate
  "Parses a Maven coordinate string into a map.
  Handles both standard (group:artifact:packaging:version:scope) and
  classifier (group:artifact:packaging:classifier:version:scope) formats.
  Returns {:group :artifact :version} or nil."
  [coord-str]
  (let [parts (string/split (string/trim coord-str) #":")]
    (case (count parts)
      ;; group:artifact:packaging:version
      4 {:group (nth parts 0) :artifact (nth parts 1)
         :version (nth parts 3)}
      ;; group:artifact:packaging:version:scope
      5 {:group (nth parts 0) :artifact (nth parts 1)
         :version (nth parts 3)}
      ;; group:artifact:packaging:classifier:version:scope
      6 {:group (nth parts 0) :artifact (nth parts 1)
         :version (nth parts 4)}
      nil)))

(defn- coord->key
  "Converts a parsed coordinate to a Clojure symbol suitable as an artifact key.
  Uses the short form when group == artifact (e.g., commons-codec)."
  [{:keys [group artifact]}]
  (if (= group artifact)
    (symbol artifact)
    (symbol group artifact)))

(defn- parse-requested-version
  "Extracts the originally-requested version from a verbose tree annotation.
  In verbose mode, Maven annotates managed versions like:
    ... (version managed from 1.5.0)
    ... (version managed from 1.5.0; omitted for duplicate)
  Returns the managed-from version string, or nil if no annotation is present
  or the managed version equals the resolved version."
  [line resolved-version]
  (when-let [[_ managed] (re-find #"version managed from ([^;)]+)" line)]
    (let [managed (string/trim managed)]
      (when (not= managed resolved-version)
        managed))))

(defn- parse-tree-line
  "Parses a single line from Maven's dependency:tree text output.
  Returns {:depth int :coord map :requested-version string?} or nil
  for blank/unparseable lines.  When the verbose tree includes a
  'version managed from X' annotation with a version that differs from
  the resolved version, :requested-version carries the original value."
  [line]
  (when (and (some? line) (seq (string/trim line)))
    (let [;; The coordinate starts at the first letter; everything before is tree drawing
          prefix-len (count (re-find #"^[^a-zA-Z]*" line))
          coord-str (subs line prefix-len)
          depth (quot prefix-len 3)]
      (when-let [coord (parse-coordinate coord-str)]
        (let [base {:depth depth :coord coord}]
          (if-let [req (parse-requested-version line (:version coord))]
            (assoc base :requested-version req)
            base))))))

(defn- parse-tree
  "Parses the full dependency tree text into structured data.
  Returns [root-key artifacts children] where:
  - root-key is the artifact key of the project itself
  - artifacts is {key -> {:version V}}
  - children is {parent-key -> [{:key K :version V}]}
    where :version is the originally-requested version (which may differ
    from the artifact's resolved version when dependency management overrides it)"
  [tree-text]
  (let [lines (string/split-lines tree-text)
        parsed (vec (keep parse-tree-line lines))]
    (when (seq parsed)
      (let [root-key (coord->key (:coord (first parsed)))]
        (loop [i 0
               stack []
               artifacts {}
               children {}]
          (if (< i (count parsed))
            (let [{:keys [depth coord requested-version]} (nth parsed i)
                  key (coord->key coord)
                  version (:version coord)
                  ;; For the dependency edge, use the requested version if available,
                  ;; otherwise fall back to the resolved version
                  edge-version (or requested-version version)
                  parent-stack (vec (take-while #(< (first %) depth) stack))
                  parent-key (when (seq parent-stack)
                               (second (peek parent-stack)))]
              (recur (inc i)
                     (conj parent-stack [depth key])
                     (assoc artifacts key {:version version})
                     (cond-> children
                       parent-key (update parent-key (fnil conj [])
                                         {:key key :version edge-version}))))
            [root-key artifacts children]))))))

(defn- build-artifact-map
  "Combines artifacts and children maps into the flat artifact map format
  expected by deps/build-db. Converts the root project to the ROOT entry."
  [root-key artifacts children project-label]
  (let [root-info (get artifacts root-key)
        root-children (get children root-key)
        root-deps (when (seq root-children)
                    (reduce (fn [m {:keys [key version]}]
                              (assoc m key {:version version}))
                            {} root-children))
        root-entry (cond-> {:version (:version root-info)}
                     (seq project-label) (assoc :label project-label)
                     (seq root-deps) (assoc :deps root-deps))
        other-artifacts (reduce-kv
                          (fn [m k info]
                            (if (= k root-key)
                              m
                              (let [child-list (get children k)
                                    deps (when (seq child-list)
                                           (reduce (fn [dm {:keys [key version]}]
                                                     (assoc dm key {:version version}))
                                                   {} child-list))]
                                (assoc m k (cond-> info
                                             (seq deps) (assoc :deps deps))))))
                          {}
                          artifacts)]
    (assoc other-artifacts 'ROOT root-entry)))

(defn read-deps
  "Reads a Maven project's dependencies by running `mvn dependency:tree`
  and parsing the text tree output.

  Options:
  - :aliases - collection of Maven profile names to activate
  - :label   - display label for the ROOT entry (defaults to directory name)"
  [pom-xml-path {:keys [aliases label]}]
  (let [pom-file      (fs/absolutize pom-xml-path)
        project-dir   (fs/parent pom-file)
        project-label (or label (str (fs/file-name project-dir)))
        mvn           (find-maven-executable project-dir)
        _             (when-not mvn
                        (abort [:yellow
                                "Cannot find " [:bold "mvnw"]
                                " in " [:bold (str project-dir)]
                                " or " [:bold "mvn"] " on PATH"]))
        profiles      (when (seq aliases)
                        (string/join "," aliases))
        tree-file     (str (fs/create-temp-file {:prefix "dexter-" :suffix ".tree"}))
        cmd           (cond-> [mvn
                               "-f" (str pom-file)
                               "--batch-mode"
                               "--quiet"
                               "dependency:tree"
                               "-Dverbose"
                               (str "-DoutputFile=" tree-file)]
                        profiles (conj (str "-P" profiles)))
        _             (perr [:faint "Running Maven dependency:tree ..."])
        result        (try
                        (apply process/shell
                               {:dir      (str project-dir)
                                :out      :string
                                :err      :string
                                :continue true}
                               cmd)
                        (catch Exception e
                          {:exit 1 :err (.getMessage e)}))]
    (when-not (zero? (:exit result))
      (fs/delete-if-exists tree-file)
      (abort [:yellow "Maven failed:\n"
              (or (not-empty (:err result))
                  (:out result))]))
    (let [tree-text (try
                      (slurp tree-file)
                      (finally
                        (fs/delete-if-exists tree-file)))
          parsed    (parse-tree tree-text)]
      (when-not parsed
        (abort [:yellow "No dependency data found in Maven output"]))
      (let [[root-key artifacts children] parsed]
        (build-artifact-map root-key artifacts children project-label)))))
