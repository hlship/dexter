(ns net.lewisship.dex.tree-parser
  "Parses the clj -Stree output."
  (:require [clojure.string :as str]))

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
