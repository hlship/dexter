(ns net.lewisship.dex.gradle-reader
  "Reads Gradle project dependency data by running Gradle as a subprocess
  with an init script that resolves the dependency graph and emits EDN.

  The init script registers a `dexterDependencies` task that walks
  Gradle's ResolutionResult API to produce the flat artifact map
  expected by deps/build-db.

  Gradle executable lookup order:
  1. gradlew or gradlew.bat in the project directory
  2. gradle on PATH

  It is an error if a build.gradle exists but no Gradle executable can be found.

  The output format matches the standard reader contract:
  {artifact-key -> {:version string, :label string?, :deps {artifact-key -> {:version string}}}}"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clj-commons.ansi :refer [perr]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [net.lewisship.cli-tools :refer [abort]]))

(defn- find-gradle-executable
  "Finds the Gradle executable to use.  Prefers the Gradle wrapper
  (gradlew / gradlew.bat) in the project directory, then falls back
  to gradle on PATH.  Returns an absolute path string, or nil."
  [project-dir]
  (or (some (fn [wrapper-name]
              (let [f (fs/file project-dir wrapper-name)]
                (when (fs/exists? f)
                  (str (fs/absolutize f)))))
            ["gradlew" "gradlew.bat"])
      (some-> (fs/which "gradle") str)))

(defn- extract-init-script!
  "Copies the bundled Gradle init script from classpath resources to a
  temp file.  Returns the temp file path.  Caller is responsible for
  deleting it."
  []
  (let [tmp (fs/create-temp-file {:prefix "dexter-" :suffix ".gradle"})]
    (spit (str tmp)
          (slurp (io/resource "net/lewisship/dex/dexter-deps.gradle")))
    tmp))

(def ^:private start-marker "===DEXTER-EDN-START===")
(def ^:private end-marker "===DEXTER-EDN-END===")

(defn- extract-edn
  "Extracts the EDN string from between sentinel markers in Gradle's
  stdout.  Returns the trimmed EDN string, or nil if markers are absent."
  [output]
  (when-let [start-idx (string/index-of output start-marker)]
    (let [edn-start (+ start-idx (count start-marker))
          end-idx   (string/index-of output end-marker edn-start)]
      (when end-idx
        (string/trim (subs output edn-start end-idx))))))

(defn read-deps
  "Reads a Gradle project's dependencies by running Gradle with an init
  script that emits the resolved dependency graph as EDN.

  Options:
  - :aliases - collection of Gradle configuration names; the first one
               is used as the target configuration (default: runtimeClasspath)
  - :label   - display label for the ROOT entry (defaults to directory name)"
  [build-gradle-path {:keys [aliases label]}]
  (let [build-file    (fs/absolutize build-gradle-path)
        project-dir   (fs/parent build-file)
        project-label (or label (str (fs/file-name project-dir)))
        gradle        (find-gradle-executable project-dir)
        _             (when-not gradle
                        (abort [:yellow
                                "Cannot find " [:bold "gradlew"]
                                " in " [:bold (str project-dir)]
                                " or " [:bold "gradle"] " on PATH"]))
        configuration (or (first aliases) "runtimeClasspath")
        init-script   (extract-init-script!)
        _             (perr [:faint "Using Gradle configuration " [:bold configuration]])
        result        (try
                        (process/shell
                          {:dir      (str project-dir)
                           :out      :string
                           :err      :string
                           :continue true}
                          gradle
                          "--init-script" (str init-script)
                          "--quiet"
                          (str "-PdexConfiguration=" configuration)
                          "dexterDependencies")
                        (finally
                          (fs/delete-if-exists init-script)))]
    (when-not (zero? (:exit result))
      (abort [:yellow "Gradle failed:\n" (:err result)]))
    (let [edn-str (extract-edn (:out result))]
      (when-not edn-str
        (abort [:yellow "No dependency data found in Gradle output"]))
      (-> (edn/read-string edn-str)
          (assoc-in ['ROOT :label] project-label)))))
