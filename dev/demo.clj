(ns demo
  "REPL scratch pad for running and testing Dexter interactively."
  (:require [babashka.fs :as fs]
            [net.lewisship.cli-tools :as cli]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.deps-reader :as deps-reader]
            [net.lewisship.dex.service :as service]))

(comment

  ;; --- Running from the CLI entry point ---

  (cli/set-prevent-exit! true)
  
  (-> ".." fs/absolutize fs/normalize)

  ;; --- Loading data manually ---

  ;; Load from pre-built test data
  (deps/load-db! "test-resources/dex/project-deps.edn")

  ;; Or resolve live from a deps.edn (this project as an example)
  (let [raw-data (deps-reader/read-deps (fs/file "deps.edn") {:aliases ["dev" "test"]})]
    (reset! deps/*db (deps/build-db raw-data)))

  ;; --- Server lifecycle ---

  (service/start! {})

  (service/stop!)

  (do
    ((requiring-resolve 'clj-reload.core/reload))
    (service/stop!)
    (service/start! {}))

  ;;
  )
