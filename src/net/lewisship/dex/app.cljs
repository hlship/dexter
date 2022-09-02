(ns net.lewisship.dex.app
  (:require
    [net.lewisship.dex.views :as views]
    [reagent.dom :as rd]))


(defn ^:dev/after-load mount-views
  []
  (rd/render [views/click-view]
             (js/document.getElementById "root")))

;; The metadata causes this to be re-executed after code reloads
(defn init
  []
  (views/init)
  (mount-views))