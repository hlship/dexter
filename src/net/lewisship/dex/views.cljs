(ns net.lewisship.dex.views
  (:require
    ["react" :as react]
    ["react-flow-renderer" :as rfr :default ReactFlow]
    [reagent.core :as r]
    [re-frame.core :as rf]
    [net.lewisship.dex.common :refer [<sub event-dispatch]]))

;; Just playing around

(rf/reg-event-db
  ::clicked
  (fn [db _]
    (update db ::count inc)))

(rf/reg-event-db
  ::set-count
  (fn [db [_ value]]
    (assoc db ::count value)))

(rf/reg-sub
  ::count
  :-> ::count)

(defn increment-button
  []
  [:button
   {:on-click (event-dispatch ::clicked)}
   "Click me!"])

(defn reset-button
  []
  (let [v (<sub [::count])]
    [:button
     {:disabled (= 0 v)
      :on-click #(rf/dispatch [::set-count 0])}
     "Reset Count"]))

(rf/reg-sub
  ::label
  :<- [::count]
  (fn [v _]
    (if (pos? v)
      (str "Clicked " v " times.")
      "Not yet clicked.")))

(defn inc-count-label
  []
  [:div (<sub [::label])])

(defn flow
  []
  (let [nodes [{:id "1"
                :type "input"
                :data {:label "io.pedestal/jetty 1.0.0"}
                :position {:x 250 :y 25}}
               {:id "2"
                :type "output"
                :data {:label "io.pedestal/service\n1.0.0"}
                :position {:x 250 :y 100}}]
        edges [{:id "1-2"
                :source "1"
                :target "2"}]]
    [:div.app-flow-container
     [:> ReactFlow {:nodes (clj->js nodes)
                    :edges (clj->js edges)}]]))

(defn click-view
  []
  [:div
   [inc-count-label]
   [:br]
   [increment-button]
   [reset-button]
   [:hr]
   [flow]
   [:hr]
   [:b "After Flow"]])

(defn init
  []
  (rf/dispatch-sync [::set-count 0]))