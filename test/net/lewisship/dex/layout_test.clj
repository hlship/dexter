(ns net.lewisship.dex.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.layout :as layout]))

(defn- many-dependants-data
  "A hub artifact depended on by n libraries, each of which ROOT depends on, and
  which itself depends on `n` leaf libraries."
  [n]
  (let [libs  (mapv #(symbol "lib" (str "lib-" %)) (range n))
        leafs (mapv #(symbol "leaf" (str "leaf-" %)) (range n))]
    (merge
     {'ROOT {:version "1" :deps (into {} (map (fn [k] [k {:version "1"}])) libs)}
      'hub/hub {:version "1" :deps (into {} (map (fn [k] [k {:version "1"}])) leafs)}}
     (into {} (map (fn [k] [k {:version "1" :deps {'hub/hub {:version "1"}}}])) libs)
     (into {} (map (fn [k] [k {:version "1"}])) leafs))))

(deftest compute-layout-includes-every-box
  (let [n  150
        db (deps/build-db (many-dependants-data n))
        {:keys [left right selected-box connections]} (layout/compute-layout db 'hub/hub)]
    (testing "no windowing: every dependant and dependency is laid out"
      (is (= n (:total left) (count (:boxes left))))
      (is (= n (:total right) (count (:boxes right)))))
    (testing "boxes are in column order with row numbers"
      (is (= (range n) (map :row (:boxes left))))
      (is (= (range n) (map :row (:boxes right)))))
    (testing "the selected box is in the center column"
      (is (= 'hub/hub (:key selected-box)))
      (is (= :center (:column selected-box))))
    (testing "every dependant and dependency has a connection to the selected artifact"
      (is (= n (count (filter #(= 'hub/hub (:to %)) connections))))
      (is (= n (count (filter #(= 'hub/hub (:from %)) connections)))))))

(deftest compute-layout-empty-columns
  (let [db (deps/build-db (many-dependants-data 3))
        {:keys [left right]} (layout/compute-layout db 'ROOT)]
    (is (= 0 (:total left)) "ROOT has no dependants")
    (is (= [] (:boxes left)))
    (is (= 3 (:total right)))))
