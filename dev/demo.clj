(ns demo
  "REPL scratch pad for running and testing Dexter interactively."
  (:require [babashka.fs :as fs]
            [net.lewisship.cli-tools :as cli]
            [clj-reload.core :refer [reload]]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.deps-reader :as deps-reader]
            [net.lewisship.dex.lein-reader :as lein-reader]
            [net.lewisship.dex.service :as service]))

;; Holds the current db for REPL convenience; load data into this atom,
;; then pass @*db to service/start!.
(defonce *db (atom nil))

(comment

  ;; --- Running from the CLI entry point ---

  (cli/set-prevent-exit! true)
  
  ;; --- Loading data manually ---

  ;; Load from pre-built test data
  (reset! *db (deps/load-db "test-resources/dex/project-deps.edn"))

  ;; Or resolve live from a deps.edn (this project as an example)
  (reset! *db
          (-> (deps-reader/read-deps (fs/file "deps.edn") {:aliases ["dev" "test"]})
              deps/build-db))

  (reset! *db
          (-> (lein-reader/read-deps (fs/file "../../nubank/balatro/project.clj") nil)
              deps/build-db))

  ;; --- Server lifecycle ---

  (service/start! {:db @*db})

  (service/stop!)

  (do
    (reload)
    (service/stop!)
    (service/start! {:db @*db}))

  ;;
  )
