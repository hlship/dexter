(ns net.lewisship.dex.lein-reader-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.lein-reader :as lein-reader]))

;; Tests use pre-captured output from `lein deps :tree-data` stored as EDN.

(def ^:private test-tree-data
  (edn/read-string (slurp "test-resources/dex/test-project.clj")))

(def ^:private test-db
  (deps/build-db (lein-reader/parse-tree-data test-tree-data "test-project")))

(deftest parse-tree-data-produces-valid-db-structure
  (testing "ROOT entry exists with label and deps"
    (let [root (deps/artifact-info test-db 'ROOT)]
      (is (some? root) "ROOT entry should exist")
      (is (= "0.0.0" (:version root)))
      (is (= "test-project" (:label root)))
      (is (map? (:deps root)) "ROOT should have deps")
      (is (contains? (:deps root) 'cheshire)
          "ROOT should depend on cheshire")
      (is (contains? (:deps root) 'org.clojure/clojure)
          "ROOT should depend on clojure")))

  (testing "top-level artifacts have correct versions"
    (let [cheshire (deps/artifact-info test-db 'cheshire)]
      (is (some? cheshire) "cheshire should be in db")
      (is (= "5.13.0" (:version cheshire)))))

  (testing "transitive dependencies are present"
    (let [cheshire-deps (deps/dependencies test-db 'cheshire)]
      (is (some #(= 'tigris (:to %)) cheshire-deps)
          "cheshire should depend on tigris")))

  (testing "deeply nested transitive deps are captured"
    (let [info (deps/artifact-info test-db 'org.apache.httpcomponents/httpcore)]
      (is (some? info)
          "httpcore (nested under aws-sdk-core) should be in db")
      (is (= "4.4.13" (:version info)))))

  (testing "leaf nodes have no deps"
    (let [tigris (deps/artifact-info test-db 'tigris)]
      (is (some? tigris))
      (is (nil? (:deps tigris)) "leaf nodes should have no :deps")))

  (testing "dependants reverse index works"
    (let [clj-dependants (deps/dependants test-db 'org.clojure/clojure)]
      (is (some #(= 'ROOT (:from %)) clj-dependants)
          "clojure should have ROOT as a dependant"))))

(deftest version-mismatch-detection
  (testing "requested vs resolved versions are available"
    (let [root-deps (deps/dependencies test-db 'ROOT)]
      (is (every? #(string? (:requested-version %)) root-deps)
          "all ROOT deps should have requested versions")
      (is (every? #(string? (:resolved-version %)) root-deps)
          "all ROOT deps should have resolved versions"))))

(deftest artifact-count
  (testing "reasonable number of artifacts parsed"
    (let [all-keys (deps/artifact-keys test-db)]
      (is (> (count all-keys) 50)
          "should have many artifacts from the test project")
      (is (some #(= 'ROOT %) all-keys)
          "ROOT should be among the keys"))))

(deftest exclusions-and-scopes-ignored
  (testing "artifacts with :exclusions are still parsed"
    (is (some? (deps/artifact-info test-db 'colorize))
        "colorize (has :exclusions) should be in db"))

  (testing "artifacts with :scope are still parsed"
    (is (some? (deps/artifact-info test-db 'criterium))
        "criterium (has :scope test) should be in db")))
