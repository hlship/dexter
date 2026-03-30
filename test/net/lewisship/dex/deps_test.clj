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
