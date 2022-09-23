(ns net.lewisship.dex.tree-parser
  "Parses the clj -Stree output."
  (:require [clojure.string :as str]))

(comment
  (defn- convert-symbol
    [s]
    (let [[ns-part symbol-part] (str/split s #"/")]
      (symbol ns-part symbol-part)))

  (defn- parse-line
    [line]
    (let [[_ _ indent flag artifact-id artifact-version :as groups]
          (re-matches #"((\s+)([\.X]) )?(\S+) (\S+).*" line)]
      ;; Need special handling for :excluded, etc. ?
      {:indent (if-not indent
                 0
                 (/ (count indent) 2))
       :overridden? (= flag "X")
       :artifact-id (convert-symbol artifact-id)
       :version artifact-version})
    )

  (comment
    (parse-line "org.clojure/clojure 1.11.1")
    (parse-line "    X org.slf4j/slf4j-api 1.7.30 :parent-omitted")
    )


  (defn- consume-line
    [state line]
    (let [{:keys []} state
          {:keys [indent overridden? artifact-id version]} (parse-line line)])
    )

  (defn parse-print
    [root-artifact-id root-version document]
    (let [initial-state {}
          final-state (reduce consume-line initial-state (str/split-lines document))]
      final-state)))

(defn- extract-version
  [coord-id]
  (or (:mvn/version coord-id)
      (:git/sha coord-id)
      (:sha coord-id)                                       ; deprecated
      "UNKNOWN"))

(defn- conj-nil
  [coll n]
  (if (some? coll)
    (conj coll n)
    [n]))

(defn- gather
  [prior-artifacts artifact-id children]
  (reduce-kv (fn [artifacts child-artifact-id child]
               (let [{:keys [coord-id include]
                      lib-children :children} child
                     version (extract-version coord-id)
                     artifacts' (if-not include
                                  artifacts
                                  (assoc artifacts child-artifact-id
                                         {:id child-artifact-id
                                          :version version}))
                     new-dependency {:id child-artifact-id
                                     :version version}]
                 ;; Always add a dependency from this artifact to the child, and if the child is
                 ;; included, recursively add its dependencies.
                 (cond-> (update-in artifacts' [artifact-id :dependencies] conj-nil new-dependency)
                   include (gather child-artifact-id lib-children))))
             prior-artifacts
             children))

(defn parse-edn
  "Parse the EDN output of `clj -X:deps tree :format :edn` into the input model used by the views."
  [root-artifact-id root-version tree-edn]
  (let [artifacts (gather {root-artifact-id {:id root-artifact-id
                                             :version root-version}}
                          root-artifact-id
                          (:children tree-edn))]
    {:nodes (->> artifacts vals (sort-by :id) (into []))
     :root-node-id root-artifact-id}))
