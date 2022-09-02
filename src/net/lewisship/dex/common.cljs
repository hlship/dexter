(ns net.lewisship.dex.common
  (:require
    [re-frame.core :as rf]))

(defn <sub
  [v]
  @(rf/subscribe v))

(defn event-dispatch
  [event-name]
  (fn [_]
    (rf/dispatch [event-name])))
