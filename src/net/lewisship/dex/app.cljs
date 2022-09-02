(ns net.lewisship.dex.app
  (:require
    [net.lewisship.dex.views :as views]
    [reagent.dom :as rd]))

(defn init
  []
  (rd/render [views/hello-world]
             (js/document.getElementById "root")))