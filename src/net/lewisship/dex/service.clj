(ns net.lewisship.dex.service
  "HTTP server lifecycle: routes, handler, start/stop."
  (:require [cheshire.core :as json]
            [hyper.core :as h]
            [hyper.state :as state]
            [net.lewisship.dex.views :as views]))

;; --- Routes & Handler ---

(def ^:private routes
  [["/" {:name :home
         :title "Dexter"
         :get #'views/home-page}]])

(defn- create-handler
  "Creates the Ring handler, seeding `db` into Hyper's app-state so it
  is available on both initial page loads and SSE re-renders."
  [db]
  (h/create-handler
   #'routes
   :app-state (atom (assoc (state/init-state) :db db))
   :static-resources "public"
   :datastar-script [:script {:type "module"
                              :src "/js/main.js"}]
   :head [[:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
          [:link {:rel "stylesheet" :href "/style.css"}]
          ;; DaisyUI
          [:script {:src "/js/browser.js"}]]))

(defonce *app (atom nil))

(defn start!
  [{:keys [port db]}]
  (if @*app
    :already-running
    (do
      (reset! *app
              (h/start! (create-handler db) {:port (or port 10240)}))
      :started)))

(defn stop!
  []
  (if-let [app @*app]
    (do
      (h/stop! app)
      (reset! *app nil)
      :stopped)
    :not-running))
