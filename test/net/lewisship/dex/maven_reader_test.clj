(ns net.lewisship.dex.maven-reader-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.lewisship.dex.deps :as deps]))

;; Tests use a manually constructed flat artifact map in the same format
;; that maven-reader/read-deps produces.  This validates that the data
;; shape integrates correctly with deps/build-db.
;;
;; The test data simulates a typical Maven project with:
;; - spring-web depending on spring-core and spring-beans
;; - spring-beans also depending on spring-core (diamond)
;; - jackson-databind depending on jackson-core and jackson-annotations
;; - logback-classic (leaf dependency)

(def ^:private test-raw-data
  {'org.springframework/spring-web          {:version "6.1.4"
                                             :deps    {'org.springframework/spring-core  {:version "6.1.4"}
                                                       'org.springframework/spring-beans {:version "6.1.4"}}}
   'org.springframework/spring-core         {:version "6.1.4"}
   'org.springframework/spring-beans        {:version "6.1.4"
                                             :deps    {'org.springframework/spring-core {:version "6.1.4"}}}

   'com.fasterxml.jackson.core/jackson-databind    {:version "2.16.1"
                                                    :deps    {'com.fasterxml.jackson.core/jackson-core        {:version "2.16.1"}
                                                              'com.fasterxml.jackson.core/jackson-annotations {:version "2.16.1"}}}
   'com.fasterxml.jackson.core/jackson-core        {:version "2.16.1"}
   'com.fasterxml.jackson.core/jackson-annotations {:version "2.16.1"}

   'ch.qos.logback/logback-classic          {:version "1.4.14"}

   'ROOT                                    {:version "1.0.0-SNAPSHOT"
                                             :label   "my-maven-project"
                                             :deps    {'org.springframework/spring-web              {:version "6.1.4"}
                                                       'com.fasterxml.jackson.core/jackson-databind {:version "2.16.1"}
                                                       'ch.qos.logback/logback-classic              {:version "1.4.14"}}}})

(def ^:private test-db (deps/build-db test-raw-data))

(deftest all-artifacts-present
  (testing "all artifacts are present"
    (is (some? (deps/artifact-info test-db 'org.springframework/spring-web)))
    (is (some? (deps/artifact-info test-db 'org.springframework/spring-core)))
    (is (some? (deps/artifact-info test-db 'com.fasterxml.jackson.core/jackson-databind)))
    (is (some? (deps/artifact-info test-db 'ch.qos.logback/logback-classic))))

  (testing "ROOT entry has correct structure"
    (let [root (deps/artifact-info test-db 'ROOT)]
      (is (= "1.0.0-SNAPSHOT" (:version root)))
      (is (= "my-maven-project" (:label root)))
      (is (contains? (:deps root) 'org.springframework/spring-web))
      (is (contains? (:deps root) 'com.fasterxml.jackson.core/jackson-databind))
      (is (contains? (:deps root) 'ch.qos.logback/logback-classic)))))

(deftest transitive-dependencies-tracked
  (testing "spring-web has correct direct deps"
    (let [deps (deps/dependencies test-db 'org.springframework/spring-web)]
      (is (some #(= 'org.springframework/spring-core (:to %)) deps))
      (is (some #(= 'org.springframework/spring-beans (:to %)) deps))))

  (testing "jackson-databind has correct direct deps"
    (let [deps (deps/dependencies test-db 'com.fasterxml.jackson.core/jackson-databind)]
      (is (some #(= 'com.fasterxml.jackson.core/jackson-core (:to %)) deps))
      (is (some #(= 'com.fasterxml.jackson.core/jackson-annotations (:to %)) deps)))))

(deftest leaf-nodes
  (testing "leaf nodes have no deps"
    (let [logback (deps/artifact-info test-db 'ch.qos.logback/logback-classic)]
      (is (some? logback))
      (is (nil? (:deps logback))))))

(deftest dependants-reverse-index
  (testing "spring-core has multiple dependants"
    (let [dependants (deps/dependants test-db 'org.springframework/spring-core)]
      (is (some #(= 'org.springframework/spring-web (:from %)) dependants))
      (is (some #(= 'org.springframework/spring-beans (:from %)) dependants)))))
