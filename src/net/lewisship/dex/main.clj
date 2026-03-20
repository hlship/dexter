(ns net.lewisship.dex.main
  (:require [net.lewisship.dex.service :as service]))

(defn main
  [& _args]
  (service/app))
