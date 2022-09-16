(ns net.lewisship.dex.app
  (:require
    [net.lewisship.dex.views :as views]
    [reagent.dom :as rd]))

;; The metadata causes this to be re-executed after code reloads:
(defn ^:dev/after-load mount-views
  []
  (rd/render [views/flow-panel]
             (js/document.getElementById "root")))

;; This is referenced from shadow-cljs.edn:
(defn init
  []
  (views/init)
  (mount-views))
