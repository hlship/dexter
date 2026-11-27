(ns net.lewisship.dex.deps-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.lewisship.dex.deps :as deps]))

(def ^:private raw-data
  {'ROOT {:version "1.0.0"
          :label "test-project"
          :deps {'zebra/lib {:version "1.0"}
                 'alpha/lib {:version "2.0"}
                 'middle/lib {:version "3.0"}}}
   'zebra/lib {:version "1.0"
               :deps {'omega/dep {:version "0.1"}
                      'alpha/dep {:version "0.2"}}}
   'alpha/lib {:version "2.0"
               :deps {'zebra/lib {:version "1.0"}}}
   'middle/lib {:version "3.0"}
   'omega/dep {:version "0.1"}
   'alpha/dep {:version "0.2"}})

(def ^:private db (deps/build-db raw-data))

(deftest dependencies-are-sorted-alphabetically
  (testing "ROOT dependencies are in alphabetical order"
    (let [dep-keys (mapv :to (deps/dependencies db 'ROOT))]
      (is (= ['alpha/lib 'middle/lib 'zebra/lib] dep-keys))))

  (testing "nested dependencies are in alphabetical order"
    (let [dep-keys (mapv :to (deps/dependencies db 'zebra/lib))]
      (is (= ['alpha/dep 'omega/dep] dep-keys)))))

(deftest dependants-are-sorted-alphabetically
  (testing "dependants of zebra/lib are in alphabetical order"
    (let [dept-keys (mapv :from (deps/dependants db 'zebra/lib))]
      (is (= ['ROOT 'alpha/lib] dept-keys))))

  (testing "dependants of a single-dependant artifact"
    (let [dept-keys (mapv :from (deps/dependants db 'alpha/dep))]
      (is (= ['zebra/lib] dept-keys)))))

(deftest unresolved-deps-are-filtered
  (testing "deps referencing artifacts not in raw-data are excluded from the db"
    (let [raw {'ROOT {:version "1.0.0"
                      :label "proj"
                      :deps {'real/lib {:version "1.0"}
                             'phantom/lib {:version "2.0"}}}
               'real/lib {:version "1.0"
                          :deps {'phantom/lib {:version "2.0"}}}}
          db (deps/build-db raw)]
      (is (= ['real/lib] (mapv :to (deps/dependencies db 'ROOT)))
          "phantom/lib should be filtered from ROOT deps")
      (is (= [] (deps/dependencies db 'real/lib))
          "phantom/lib should be filtered from real/lib deps")
      (is (nil? (deps/artifact-info db 'phantom/lib))
          "phantom/lib should not appear in artifacts"))))

(deftest labels-are-guaranteed
  (testing "explicit label is preserved"
    (is (= "test-project" (:label (deps/artifact-info db 'ROOT)))))

  (testing "missing label defaults to string of key"
    (is (= "zebra/lib" (:label (deps/artifact-info db 'zebra/lib))))))

(deftest label-index-search
  (testing "exact label match"
    (is (= 'ROOT (deps/find-artifact db "test-project"))))

  (testing "case-insensitive label match"
    (is (= 'ROOT (deps/find-artifact db "Test-Project"))))

  (testing "substring label match"
    (is (= 'alpha/dep (deps/find-artifact db "alpha/d"))))

  (testing "no match returns nil"
    (is (nil? (deps/find-artifact db "nonexistent")))))

(def ^:private sized-data
  {'ROOT {:version "1" :size 5
          :deps    {'a/a {:version "1"} 'b/b {:version "1"}}}
   'a/a  {:version "1" :size 10
          :deps    {'c/c {:version "1"} 'gone/gone {:version "1"}}}
   'b/b  {:version "1" :size 20
          :deps    {'c/c {:version "1"}}}
   'c/c  {:version "1" :size 100
          :deps    {'a/a {:version "1"}}}}) ; cycle back to a/a

(deftest footprints-count-each-artifact-once
  (let [fp (deps/footprints sized-data)]
    (is (= 135 (get fp 'ROOT)) "ROOT counts c/c once, though reachable two ways")
    (is (= 110 (get fp 'a/a)) "cycles terminate; a + c")
    (is (= 130 (get fp 'b/b)) "b + c + a, via c's cycle")
    (is (= 110 (get fp 'c/c)))))

(deftest footprints-empty-without-sizes
  (is (= {} (deps/footprints {'ROOT {:version "1"} 'a/a {:version "1"}}))))

(deftest db-has-footprints-and-scale
  (let [db (deps/build-db sized-data)]
    (is (= 130 (deps/footprint db 'b/b)))
    (is (= 135 (:max-footprint db)))))

(deftest filter-db-footprints-cover-visible-artifacts-only
  (let [hidden (deps/filter-db sized-data #{'c/c})
        shown  (deps/filter-db sized-data #{})]
    (is (nil? (deps/artifact-info hidden 'c/c)) "hidden artifact is not displayed")
    (is (= 35 (deps/footprint hidden 'ROOT)) "5 + 10 + 20; hidden c/c doesn't count")
    (is (= 10 (deps/footprint hidden 'a/a)))
    (is (= 35 (:max-footprint hidden)))
    (testing "showing hidden artifacts includes them in footprints"
      (is (= 135 (deps/footprint shown 'ROOT)))
      (is (= 110 (deps/footprint shown 'a/a)))
      (is (= 135 (:max-footprint shown))))))
