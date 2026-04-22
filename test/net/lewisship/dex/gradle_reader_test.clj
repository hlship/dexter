(ns net.lewisship.dex.gradle-reader-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.lewisship.dex.deps :as deps]))

;; Tests use a manually constructed flat artifact map in the same format
;; that gradle-reader/read-deps produces.  This validates that the data
;; shape integrates correctly with deps/build-db.
;;
;; The test data simulates a typical Gradle/Android project with:
;; - gson (leaf dependency)
;; - okhttp depending on okio and kotlin-stdlib
;; - okio also depending on kotlin-stdlib (diamond)
;; - kotlin-stdlib requested as "1.9.22" by okhttp but resolved to "1.9.23"

(def ^:private test-raw-data
  {'com.google.code.gson/gson                {:version "2.10.1"}

   'com.squareup.okhttp3/okhttp             {:version "4.12.0"
                                              :deps    {'com.squareup.okio/okio       {:version "3.6.0"}
                                                        'org.jetbrains.kotlin/kotlin-stdlib {:version "1.9.22"}}}
   'com.squareup.okio/okio                   {:version "3.6.0"
                                              :deps    {'org.jetbrains.kotlin/kotlin-stdlib {:version "1.9.22"}}}
   'org.jetbrains.kotlin/kotlin-stdlib       {:version "1.9.23"}  ;; resolved version differs

   'org.slf4j/slf4j-api                      {:version "2.0.12"
                                               :deps    {}}

   'ROOT                                     {:version "1.0.0"
                                              :label   "my-gradle-project"
                                              :deps    {'com.google.code.gson/gson         {:version "2.10.1"}
                                                        'com.squareup.okhttp3/okhttp       {:version "4.12.0"}
                                                        'org.slf4j/slf4j-api               {:version "2.0.12"}}}})

(def ^:private test-db (deps/build-db test-raw-data))

(deftest all-artifacts-present
  (testing "all artifacts are present"
    (is (some? (deps/artifact-info test-db 'com.squareup.okhttp3/okhttp)))
    (is (some? (deps/artifact-info test-db 'com.squareup.okio/okio)))
    (is (some? (deps/artifact-info test-db 'org.jetbrains.kotlin/kotlin-stdlib)))
    (is (some? (deps/artifact-info test-db 'com.google.code.gson/gson))))

  (testing "artifact versions use resolved versions"
    (is (= "1.9.23" (:version (deps/artifact-info test-db 'org.jetbrains.kotlin/kotlin-stdlib)))
        "kotlin-stdlib should use resolved version, not requested"))

  (testing "ROOT entry has correct structure"
    (let [root (deps/artifact-info test-db 'ROOT)]
      (is (= "1.0.0" (:version root)))
      (is (= "my-gradle-project" (:label root)))
      (is (contains? (:deps root) 'com.google.code.gson/gson))
      (is (contains? (:deps root) 'com.squareup.okhttp3/okhttp))
      (is (contains? (:deps root) 'org.slf4j/slf4j-api)))))

(deftest transitive-dependencies-tracked
  (testing "okhttp has correct direct deps"
    (let [deps (deps/dependencies test-db 'com.squareup.okhttp3/okhttp)]
      (is (some #(= 'com.squareup.okio/okio (:to %)) deps))
      (is (some #(= 'org.jetbrains.kotlin/kotlin-stdlib (:to %)) deps))))

  (testing "okio has correct direct deps"
    (let [deps (deps/dependencies test-db 'com.squareup.okio/okio)]
      (is (some #(= 'org.jetbrains.kotlin/kotlin-stdlib (:to %)) deps)))))

(deftest version-conflict-detection
  (testing "requested vs resolved versions on dependency edges"
    (let [okhttp-deps (deps/dependencies test-db 'com.squareup.okhttp3/okhttp)
          kotlin-dep  (first (filter #(= 'org.jetbrains.kotlin/kotlin-stdlib (:to %))
                                     okhttp-deps))]
      (is (some? kotlin-dep))
      (is (= "1.9.22" (:requested-version kotlin-dep))
          "requested version should be what okhttp declared")
      (is (= "1.9.23" (:resolved-version kotlin-dep))
          "resolved version should be the Gradle-selected version"))))

(deftest leaf-nodes
  (testing "leaf nodes have no deps"
    (let [gson (deps/artifact-info test-db 'com.google.code.gson/gson)]
      (is (some? gson))
      (is (nil? (:deps gson))))))

(deftest dependants-reverse-index
  (testing "kotlin-stdlib has multiple dependants"
    (let [dependants (deps/dependants test-db 'org.jetbrains.kotlin/kotlin-stdlib)]
      (is (some #(= 'com.squareup.okhttp3/okhttp (:from %)) dependants))
      (is (some #(= 'com.squareup.okio/okio (:from %)) dependants)))))
