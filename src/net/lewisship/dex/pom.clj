(ns net.lewisship.dex.pom
  "Reads metadata (description, homepage URL) from Maven POM files
  in the local repository (~/.m2/repository).

  POM files are located by convention:
    ~/.m2/repository/{group-path}/{artifact}/{version}/{artifact}-{version}.pom

  where group-path replaces dots with directory separators."
  (:require [clj-commons.humanize :as humanize]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.xml :as xml]))

(def ^:private m2-repo
  "Default local Maven repository path."
  (io/file (System/getProperty "user.home") ".m2" "repository"))

(defn- pom-file
  "Returns the File for a POM given a Maven-style artifact key (symbol)
  and version string. The artifact key uses the `group/artifact` convention;
  when group equals artifact, the key may be just `artifact`."
  [artifact-key version]
  (let [s     (str artifact-key)
        slash (string/index-of s "/")
        group (if slash (subs s 0 slash) s)
        artifact (if slash (subs s (inc slash)) s)
        group-path (string/replace group "." "/")]
    (io/file m2-repo group-path artifact version
             (str artifact "-" version ".pom"))))

(defn- jar-file
  "Returns the File for the JAR adjacent to a POM file."
  [pom]
  (let [n (.getName pom)]
    (io/file (.getParentFile pom)
             (string/replace n #"\.pom$" ".jar"))))

(defn- child-text
  "Returns the text content of the first child element with the given tag,
  or nil if not found."
  [el tag]
  (some (fn [child]
          (when (and (map? child) (= tag (:tag child)))
            (first (:content child))))
        (:content el)))

(defn- parse-licenses
  "Extracts license entries from a parsed POM root element.
  Returns a vector of {:name ... :url ...} maps, or nil if none."
  [root]
  (when-let [licenses-el (first (filter #(= :licenses (:tag %)) (:content root)))]
    (not-empty
     (into []
           (keep (fn [el]
                   (when (= :license (:tag el))
                     (let [n (child-text el :name)
                           u (child-text el :url)]
                       (when n {:name n :url u})))))
           (:content licenses-el)))))

(defn- parse-pom
  "Parses a POM file and returns a map with :fields (tag->text for simple
  top-level elements) and :licenses (vector of license maps)."
  [file]
  (with-open [in (io/input-stream file)]
    (let [root (xml/parse in)]
      {:fields   (into {}
                       (keep (fn [el]
                               (when (and (map? el)
                                          (= 1 (count (:content el)))
                                          (string? (first (:content el))))
                                 [(:tag el) (first (:content el))])))
                       (:content root))
       :licenses (parse-licenses root)})))

(defn pom-metadata
  "Returns a map with :description and :url extracted from the POM file
  for the given artifact key and version. Returns nil for missing keys.
  Returns nil entirely if the POM file does not exist or cannot be parsed."
  [artifact-key version]
  (try
    (let [f (pom-file artifact-key version)]
      (when (.exists f)
        (let [{:keys [fields licenses]} (parse-pom f)
              jar (jar-file f)]
          (cond-> {:description (:description fields)
                   :url         (:url fields)
                   :licenses    licenses}
            (.exists jar)
            (assoc :jar-size (humanize/filesize (.length jar)))))))
    (catch Exception _
      nil)))
