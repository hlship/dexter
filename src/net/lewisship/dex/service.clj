(ns net.lewisship.dex.service
  (:require [cheshire.core :as json]
            [hyper.core :as h]))

(defn home-page [_]
  [:div
   [:h1 "Home Page"]
   ["... just getting started 4"]])

(def routes
  [["/" {:name  :home
         :title "DEX Home"
         :get   #'home-page}]])

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

  (start!)

  (stop!)

  (do
    (stop!)
    (start!))

  ;;
  )
