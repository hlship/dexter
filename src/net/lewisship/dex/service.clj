(ns net.lewisship.dex.service
  "HTTP server lifecycle: routes, handler, start/stop."
  (:require [cheshire.core :as json]
            [hyper.core :as h]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.views :as views]))

;; --- Routes & Handler ---

(def routes
  [["/" {:name :home
         :title "Dexter"
         :get #'views/home-page}]])

(def handler (h/create-handler
              #'routes
              :static-resources "public"
              :head [[:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
                     [:script {:type "importmap"}
                      (json/generate-string
                       {:imports
                        {:datastar "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}})]
                     [:link {:rel "stylesheet" :href "/style.css"}]
                     [:script {:type "module" :src "/js/main.js"}]]))

(defonce *app (atom nil))

(defn start!
  [{:keys [port]}]
  (if @*app
    :already-running
    (do
      (reset! *app
              (h/start! handler {:port (or port 10240)}))
      :started)))

(defn stop!
  []
  (if-let [app @*app]
    (do
      (h/stop! app)
      (reset! *app nil)
      :stopped)
    :not-running))
