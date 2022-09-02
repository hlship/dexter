(ns net.lewisship.dex.views
  (:require
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

(defn click-view
  []
  [:div
   [inc-count-label]
   [:br]
   [increment-button]
   [reset-button]])

(defn init
  []
  (rf/dispatch-sync [::set-count 0]))