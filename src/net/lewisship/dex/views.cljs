(ns net.lewisship.dex.views
  (:require
    ["react" :as react]
    ["react-flow-renderer" :as rfr :default ReactFlow]
    [re-frame.core :as rf :refer [reg-sub reg-event-db]]
    [clojure.spec.alpha :as s]
    [medley.core :as medley]
    [net.lewisship.dex.common :refer [<sub event-dispatch]]))


(s/def ::input-model (s/keys :req-un [::nodes ::root-node-id]))
(s/def ::nodes (s/coll-of ::node))
(s/def ::root-node-id symbol?)

;; This is a little too focused on artifacts with a version, but that's ok for just getting started.

(s/def ::node (s/keys :req-un [::id
                               ::version]
                      :opt-un [::dependencies]))
(s/def ::id symbol?)
(s/def ::version string?)
(s/def ::label string?)

(s/def ::dependencies (s/coll-of ::dependency))
;; As maps to support future case where we want to highlight the dependency on a version mis-match
(s/def ::dependency (s/keys :req-un [::on]))

(s/def ::on symbol?)

;; Will likely need an intermediate step to handle nodes in model but not on screen.
;; Need to figure out positioning.

(defn input-node->view-node
  [node]
  (let [{:keys [id version dependencies]} node
        id' (str id)
        edges (for [{on-id :on} dependencies
                    :let [on-id' (str on-id)]]
                {:id (str id' "->" on-id')
                 :source id'
                 :target on-id'
                 :sourcePosition "right"})]
    {:id id'
     :type "default"                                        ; can have inputs and outputs
     :data {:label (str id' " " version)}
     :edges edges
     :dependencies (->> edges
                        (map :target)
                        set)}))

(def placeholder-input-model
  '{:root-node-id org.clojure/core.async
    :nodes [{:id walmartlabs/active-status
             :version "0.1.15"
             :dependencies [{:on org.clojure/clojure}
                            {:on io.aviso/pretty}
                            {:on io.aviso/config}
                            {:on org.clojure/core.async}]
             :label "walmartlabs/active-status 0.1.15"}
            {:id org.clojure/clojure
             :version "1.9.0-alpha14"}
            {:id io.aviso/pretty
             :version "0.1.33"}
            {:id io.aviso/config
             :version "0.2.2"
             :dependencies [{:on com.stuartsierra/component}]}
            {:id com.stuartsierra/component
             :version "0.3.1"
             :dependencies [{:on com.stuartsierra/dependency}]}
            {:id com.stuartsierra/dependency
             :version "0.2.0"}
            {:id org.clojure/core.async
             :version "0.2.395"
             :dependencies [{:on org.clojure/tools.analyzer.jvm}]}
            {:id org.clojure/tools.analyzer.jvm
             :version "0.6.10"}]})

(defn build-view-nodes
  [input-model]
  (map input-node->view-node (:nodes input-model)))


(def ^:const node-width 160)

(defn layout-row
  [y nodes]
  ;; TODO: Spread them out evenly across the row, maybe with a kind of arc shape
  ;; TODO: Deal with too many to fit with buttons to scroll the list.
  (let [n (count nodes)
        ;; TODO: Get the actual width of the flow and center based on that
        x-base (- (/ 1300 2)
                  (/ (* node-width n) 2))]
    (map-indexed (fn [i node]
                   (assoc node :position {:x (+ x-base (* node-width i))
                                          :y y}))
                 nodes)))

(defn layout-nodes+edges
  [view-nodes focus-node-id]
  (let [id->node (medley/index-by :id view-nodes)
        focus-node (get id->node focus-node-id)
        ;; Left column: nodes that depend on the focus node
        dependants (->> view-nodes
                        (filter #(contains? (:dependencies %) focus-node-id))
                        (sort-by :id)
                        (layout-row 50))
        ;; Right column: dependencies of the focus node
        dependencies (->> (map #(get id->node %) (:dependencies focus-node))
                          (sort-by :id)
                          (layout-row 300))
        all-nodes (concat (layout-row 200 [focus-node])
                          dependants
                          dependencies)
        relevant-ids (->> all-nodes
                          (map :id)
                          set)
        relevant-edge? (fn [{:keys [source target]}]
                         (and (contains? relevant-ids source)
                              (contains? relevant-ids target)))
        edges (->> all-nodes
                   (mapcat :edges)
                   (filter relevant-edge?))]
    [all-nodes edges]))

(reg-event-db ::set-input-model
              [rf/unwrap]
              (fn [db input-model]
                (assoc db ::input-model input-model
                          ::focus-node-id (-> input-model :root-node-id str))))

;; Just putting a key into the app db doesn't trigger anything; instead register a sub that extracts the
;; data (with an engineered coincidence.
(reg-sub ::input-model
         :-> ::input-model)

(reg-sub ::view-nodes
         :<- [::input-model]
         :-> (fn [input-model]
               (let [nodes (map input-node->view-node (:nodes input-model))]
                 (medley/index-by :id nodes))))

(reg-sub ::focus-node-id
         :-> ::focus-node-id)

(reg-sub ::focus-node
         :<- [::view-nodes]
         :<- [::focus-node-id]
         ;; Gets a single argument, no event
         :-> (fn [[view-nodes focus-node-id]]
               (get view-nodes focus-node-id)))

(reg-sub ::dependents
         :<- [::view-nodes]
         :<- [::focus-node-id]
         :-> (fn [[view-nodes focus-node-id]]
               (->> view-nodes
                    vals
                    (filter #(contains? (:dependencies %) focus-node-id)))))

(reg-sub ::dependencies
         :<- [::view-nodes]
         :<- [::focus-node]
         :-> (fn [[view-nodes focus-node]]
               (map view-nodes (:dependencies focus-node))))

(reg-sub ::view-ready-nodes
         :<- [::dependents]
         :<- [::focus-node]
         :<- [::dependencies]
         (fn [[dependents focus-node dependencies]]
           (let [dependents' (->> dependents
                                  (sort-by :id)
                                  (layout-row 50))
                 dependencies' (->> dependencies
                                    (sort-by :id)
                                    (layout-row 300))
                 focus-node' (layout-row 200 [focus-node])]
             (-> dependents'
                 (into dependencies')
                 (into focus-node')))))

(reg-sub ::edges
         :<- [::view-ready-nodes]
         :-> (fn [nodes]
               ;; Only edges where both sides of the connection are "on screen" should be
               ;; included.
               (let [relevant-ids (->> nodes (map :id) set)
                     relevant-edge? (fn [{:keys [source target]}]
                                      (and (contains? relevant-ids source)
                                           (contains? relevant-ids target)))]
                 (->> nodes
                      (mapcat :edges)
                      (filter relevant-edge?)))))

(defn flow
  []
  (let [nodes (<sub [::view-ready-nodes])
        edges (<sub [::edges])]
    (when nodes
      [:div.app-flow-container
       ;; :> converts the top level map to a JS map, but doesn't do so recursively.
       [:> ReactFlow {:nodes (clj->js nodes)
                      :edges (clj->js edges)}]])))

(defn flow-panel
  []
  [:div
   [flow]
   [:hr]
   [:b "After Flow"]])

(defn init
  []
  (rf/dispatch [::set-input-model placeholder-input-model])
  #_
  (rf/dispatch-sync [::set-count 0]))
