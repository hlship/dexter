(ns net.lewisship.dex.lein-reader-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.lewisship.dex.deps :as deps]))

;; Tests use a manually constructed flat artifact map in the same format
;; that read-deps produces. This validates that deps/build-db correctly
;; indexes the data and supports the expected queries.
;;
;; The test data simulates a project with:
;; - cheshire depending on jackson-core and tigris
;; - ring-core depending on commons-codec, commons-io, and ring-codec
;; - ring-codec also depending on commons-codec (diamond dependency)
;; - commons-codec requested as "1.10" by ring libs but resolved to "1.11"

(def ^:private test-raw-data
  {'cheshire                                {:version "5.13.0"
                                             :deps    {'com.fasterxml.jackson.core/jackson-core {:version "2.17.0"}
                                                       'tigris                                  {:version "0.1.2"}}}
   'com.fasterxml.jackson.core/jackson-core {:version "2.17.0"}
   'tigris                                  {:version "0.1.2"}

   'ring/ring-core                          {:version "1.5.0"
                                             :deps    {'commons-codec  {:version "1.10"}
                                                       'commons-io     {:version "2.5"}
                                                       'ring/ring-codec {:version "1.2.0"}}}
   'commons-codec                           {:version "1.11"} ;; resolved version differs from requested
   'commons-io                              {:version "2.5"}
   'ring/ring-codec                         {:version "1.2.0"
                                             :deps    {'commons-codec {:version "1.10"}}}

   'org.clojure/clojure                     {:version "1.12.4"
                                             :deps    {'org.clojure/spec.alpha        {:version "0.5.238"}
                                                       'org.clojure/core.specs.alpha   {:version "0.4.74"}}}
   'org.clojure/spec.alpha                  {:version "0.5.238"}
   'org.clojure/core.specs.alpha            {:version "0.4.74"}

   'ROOT                                    {:version "1.0.0"
                                             :label   "test-project"
                                             :deps    {'cheshire            {:version "5.13.0"}
                                                       'ring/ring-core      {:version "1.5.0"}
                                                       'org.clojure/clojure {:version "1.12.4"}}}})

(def ^:private test-db (deps/build-db test-raw-data))

(deftest all-artifacts-present
  (testing "all artifacts are present"
    (is (some? (deps/artifact-info test-db 'cheshire)))
    (is (some? (deps/artifact-info test-db 'ring/ring-core)))
    (is (some? (deps/artifact-info test-db 'commons-codec)))
    (is (some? (deps/artifact-info test-db 'org.clojure/clojure))))

  (testing "artifact versions use resolved versions"
    (is (= "1.11" (:version (deps/artifact-info test-db 'commons-codec)))
        "commons-codec should use resolved version, not requested"))

  (testing "ROOT entry has correct structure"
    (let [root (deps/artifact-info test-db 'ROOT)]
      (is (= "1.0.0" (:version root)))
      (is (= "test-project" (:label root)))
      (is (contains? (:deps root) 'cheshire))
      (is (contains? (:deps root) 'ring/ring-core))
      (is (contains? (:deps root) 'org.clojure/clojure)))))

(deftest transitive-dependencies-tracked
  (testing "cheshire has correct direct deps"
    (let [deps (deps/dependencies test-db 'cheshire)]
      (is (some #(= 'tigris (:to %)) deps))
      (is (some #(= 'com.fasterxml.jackson.core/jackson-core (:to %)) deps))))

  (testing "ring-core has correct direct deps"
    (let [deps (deps/dependencies test-db 'ring/ring-core)]
      (is (some #(= 'commons-codec (:to %)) deps))
      (is (some #(= 'ring/ring-codec (:to %)) deps)))))

(deftest version-conflict-detection
  (testing "requested vs resolved versions available on dependency edges"
    (let [ring-deps (deps/dependencies test-db 'ring/ring-core)
          codec-dep (first (filter #(= 'commons-codec (:to %)) ring-deps))]
      (is (some? codec-dep))
      (is (= "1.10" (:requested-version codec-dep))
          "requested version should be what ring-core declared")
      (is (= "1.11" (:resolved-version codec-dep))
          "resolved version should be the Aether-selected version"))))

(deftest leaf-nodes
  (testing "leaf nodes have no deps"
    (let [tigris (deps/artifact-info test-db 'tigris)]
      (is (some? tigris))
      (is (nil? (:deps tigris))))))

(deftest dependants-reverse-index
  (testing "commons-codec has multiple dependants"
    (let [dependants (deps/dependants test-db 'commons-codec)]
      (is (some #(= 'ring/ring-core (:from %)) dependants))
      (is (some #(= 'ring/ring-codec (:from %)) dependants)))))
