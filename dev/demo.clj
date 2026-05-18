(ns demo
  "REPL scratch pad for running and testing Dexter interactively."
  (:require [babashka.fs :as fs]
            [net.lewisship.cli-tools :as cli]
            [clj-reload.core :refer [reload]]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.deps-reader :as deps-reader]
            [net.lewisship.dex.lein-reader :as lein-reader]
            [net.lewisship.dex.maven-reader :as maven-reader]
            [net.lewisship.dex.service :as service]))

;; Holds the current dependency data for REPL convenience.
;; Load data into this atom, then call start!.
(defonce *dependency-data (atom nil))

(defn- start!
  "Starts the service with the current dependency data."
  []
  (service/start! {:dependency-data @*dependency-data}))

(comment

  ;; --- Running from the CLI entry point ---

  (cli/set-prevent-exit! true)

  ;; --- Loading data manually ---

  ;; Load from pre-built test data
  (reset! *dependency-data (deps/load-dependency-data "test-resources/dex/project-deps.edn"))

  ;; Or resolve live from a deps.edn (this project as an example)
  (reset! *dependency-data
          (deps-reader/read-deps (fs/file "deps.edn") {:aliases ["dev" "test"]}))

  (reset! *dependency-data
          (lein-reader/read-deps (fs/file "../../nubank/balatro/project.clj") nil))

  (reset! *dependency-data
          (maven-reader/read-deps (fs/file "../spring-petclinic/pom.xml") nil))

  ;; --- Server lifecycle ---

  (start!)

  (do
    (reload)
    (service/stop!)
    (start!))

  ;;
  )
