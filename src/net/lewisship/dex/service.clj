(ns net.lewisship.dex.service
  "HTTP server lifecycle: routes, handler, start/stop."
  (:require [cheshire.core :as json]
            [hyper.core :as h]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.views :as views]))

;; --- Routes & Handler ---

(def routes
  [["/" {:name :home
         :title "DEX - Dependency Explorer"
         :get #'views/home-page}]])

(def handler (h/create-handler
              #'routes
              :static-resources "public"
              :head [[:script {:type "importmap"}
                      (json/generate-string
                       {:imports
                        {:datastar "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}})]
                     [:link {:rel "stylesheet" :href "/style.css"}]
                     [:script {:type "module" :src "/js/main.js"}]]))

(defonce *app (atom nil))

(defn start!
  []
  (if @*app
    :already-running
    (do
      (reset! *app
              (h/start! handler {:port 10240}))
      :started)))

(defn stop!
  []
  (if-let [app @*app]
    (do
      (h/stop! app)
      (reset! *app nil)
      :stopped)
    :not-running))

(comment

  ;; Load from pre-built test data
  (deps/load-db! "test-resources/dex/project-deps.edn")

  ;; Or resolve live from a deps.edn (this project as an example)
  (do
    (require '[net.lewisship.dex.deps-reader :as deps-reader])
    (let [raw-data (deps-reader/read-deps "deps.edn" {:aliases [:dev :test]})]
      (reset! deps/*db (deps/build-db raw-data))))

  (start!)

  (stop!)

  (do
    ((requiring-resolve 'clj-reload.core/reload))
    (stop!)
    (start!))

  ;;
  )
