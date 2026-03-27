(ns net.lewisship.dex.deps-reader-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.deps-reader :as deps-reader]))

;; All tests use a stable copy of deps.edn at test-resources/dex/test-deps.edn
;; so that changes to the project's deps.edn don't break these tests.

(def ^:private test-deps-path "test-resources/dex/test-deps.edn")

(deftest read-deps-produces-valid-db-structure
  (testing "resolving test-deps.edn produces expected structure"
    (let [raw-data (deps-reader/read-deps test-deps-path {})
          db (deps/build-db raw-data)]

      (testing "ROOT entry exists with label and deps"
        (let [root (deps/artifact-info db 'ROOT)]
          (is (some? root) "ROOT entry should exist")
          (is (string? (:version root)))
          (is (map? (:deps root)) "ROOT should have deps")
          (is (contains? (:deps root) 'org.clojure/clojure)
              "ROOT should depend on clojure")
          (is (contains? (:deps root) 'dynamic-alpha/hyper)
              "ROOT should depend on hyper")))

      (testing "resolved artifacts have versions"
        (let [clj-info (deps/artifact-info db 'org.clojure/clojure)]
          (is (some? clj-info) "clojure should be in db")
          (is (string? (:version clj-info))
              "clojure should have a version string")))

      (testing "transitive dependencies are present"
        ;; clojure depends on spec.alpha
        (let [clj-deps (deps/dependencies db 'org.clojure/clojure)]
          (is (some #(= 'org.clojure/spec.alpha (:to %)) clj-deps)
              "clojure should have spec.alpha as a dependency")))

      (testing "dependants reverse index works"
        (let [clj-dependants (deps/dependants db 'org.clojure/clojure)]
          (is (some #(= 'ROOT (:from %)) clj-dependants)
              "clojure should have ROOT as a dependant"))))))

(deftest read-deps-with-aliases
  (testing "resolving with :dev alias includes extra deps"
    (let [raw-data (deps-reader/read-deps test-deps-path {:aliases [:dev]})
          db (deps/build-db raw-data)]

      (testing ":dev alias deps are present"
        (is (some? (deps/artifact-info db 'io.github.hlship/trace))
            "trace should be present when :dev alias is active")))))

(deftest read-deps-without-aliases
  (testing "resolving without :dev alias excludes dev-only deps"
    (let [raw-data (deps-reader/read-deps test-deps-path {})
          db (deps/build-db raw-data)]

      (testing ":dev-only deps are absent"
        (is (nil? (deps/artifact-info db 'io.github.hlship/trace))
            "trace should not be present without :dev alias")))))

(deftest version-extraction
  (testing "Maven artifacts have proper version strings"
    (let [raw-data (deps-reader/read-deps test-deps-path {})
          db (deps/build-db raw-data)
          clj-info (deps/artifact-info db 'org.clojure/clojure)]
      (is (re-matches #"\d+\.\d+\.\d+.*" (:version clj-info))
          "clojure version should look like semver")))

  (testing "git deps have a version (tag or short sha)"
    (let [raw-data (deps-reader/read-deps test-deps-path {})
          db (deps/build-db raw-data)
          hyper-info (deps/artifact-info db 'dynamic-alpha/hyper)]
      (is (some? hyper-info) "hyper should be in db")
      (is (string? (:version hyper-info))
          "hyper should have a version string")
      (is (pos? (count (:version hyper-info)))
          "hyper version should not be empty"))))
