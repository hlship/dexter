(ns net.lewisship.dex.service
  "HTTP server lifecycle: routes, handler, start/stop."
  (:require [hyper.core :as h]
            [hyper.state :as state]
            [net.lewisship.dex.views :as views]))

;; --- Routes & Handler ---

(def ^:private routes
  [["/" {:name :home
         :title "Dexter"
         :get #'views/home-page}]])

(defn- create-handler
  "Creates the Ring handler, seeding dependency-data into Hyper's app-state.
  home-page builds and caches the active db on demand from dependency-data."
  [dependency-data]
  (h/create-handler
   #'routes
   :app-state (atom (assoc (state/init-state) :dependency-data dependency-data))
   :static-resources "public"
   :datastar-script [:script {:type "module"
                              :src "/js/main.js"}]
   :head [[:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
          [:link {:rel "stylesheet" :href "/style.css"}]
          ;; DaisyUI
          [:script {:src "/js/browser.js"}]]))

(defonce *app (atom nil))

(defn start!
  [{:keys [port dependency-data]}]
  (if @*app
    :already-running
    (do
      (reset! *app
              (h/start! (create-handler dependency-data) {:port (or port 10240)}))
      :started)))

(defn stop!
  []
  (if-let [app @*app]
    (do
      (h/stop! app)
      (reset! *app nil)
      :stopped)
    :not-running))
