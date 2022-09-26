(ns net.lewisship.dex.views
  (:require
    ["react" :as react]
    ["react-flow-renderer" :as rfr :default ReactFlow]
    ["semver" :as semver]
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

(s/def ::dependencies (s/coll-of ::dependency))
(s/def ::dependency (s/keys :req-un [::id
                                     ::version]))

(s/def ::version string?)

;; Will likely need an intermediate step to handle nodes in model but not on screen.
;; Need to figure out positioning.

(defn input-node->view-node
  [node]
  (let [{:keys [id version dependencies]} node
        id' (str id)
        edges (for [{target-id :id
                     target-version :version} dependencies
                    :let [target-id' (str target-id)]]
                {:id (str id' "->" target-id')
                 :source id'
                 :target target-id'
                 :requested-version target-version
                 :markerEnd {:type "arrowclosed"}})]
    {:id id'
     :data {:label (str id' " " version)}
     :edges edges
     :version version
     :targetPosition :left
     :sourcePosition :right
     :dependencies (->> edges
                        (map :target)
                        set)}))

(defn node-type
  [{:keys [id dependencies]} target-nodes]
  (let [has-outgoing (seq dependencies)
        has-incoming (contains? target-nodes id)]
    (cond
      (and has-incoming has-outgoing) :regular
      ;; This is slightly confusing, as react-flow is intended for data flowing from an input node
      ;; (one that has no incoming dependencies) through regular nodes, to an output node
      ;; (one that has no outgoing dependencies).  So these two seem slightly backwards
      ;; as we care about the artifact relationships.
      has-outgoing :input
      has-incoming :output

      :else
      (do
        (prn "What's up with node" id)
        "regular"))))

(defn build-view-nodes
  [input-model]
  (let [nodes (map input-node->view-node (:nodes input-model))
        target-nodes (->> nodes
                          (map :dependencies)
                          (reduce into #{}))]
    (->> nodes
         (map #(assoc % :type (node-type % target-nodes)))
         (medley/index-by :id))))

(def ^:const node-width 160)
(def ^:const node-height 50)

(defn layout-column
  [x nodes]
  ;; TODO: Spread them out evenly across the row, maybe with a kind of arc shape
  ;; TODO: Deal with too many to fit with buttons to scroll the list.
  (let [n (count nodes)
        y-base (- (/ 600 2)
                  (/ (* node-height n) 2))]
    (map-indexed (fn [i node]
                   (assoc node :position {:x x
                                          :y (+ y-base (* node-height i))}))
                 nodes)))

(reg-event-db ::set-input-model
              [rf/unwrap]
              (fn [db input-model]
                (assoc db ::input-model input-model
                          ::focus-node-id (-> input-model :root-node-id str))))

(defn- simple-event-db
  [k]
  (fn [db [_ v]]
    (assoc db k v)))

;; Triggered by clicking on a node
(reg-event-db ::select-focus-node
              (simple-event-db ::focus-node-id))

;; The active edge is the one the mouse is over
(reg-event-db ::set-active-edge-id
              (simple-event-db ::active-edge-id))

(reg-sub ::root-node-id
         :<- [::input-model]
         :-> #(-> % :root-node-id str))

(reg-sub ::active-edge-id
         :-> ::active-edge-id)

(reg-event-db ::set-hide-clojure
              (simple-event-db ::hide-clojure))

(reg-sub ::hide-clojure
         :-> (fn [db]
               (get db ::hide-clojure true)))

;; Just putting a key into the app db doesn't trigger anything; instead register a sub that extracts the
;; data (with an engineered coincidence).
(reg-sub ::input-model
         :-> ::input-model)

(reg-sub ::view-nodes
         :<- [::input-model]
         :-> build-view-nodes)

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
         :<- [::active-edge-node-ids]
         (fn [[dependents focus-node dependencies active-node-ids]]
           (let [dependents' (->> dependents
                                  (sort-by :id)
                                  (layout-column 100))
                 dependencies' (->> dependencies
                                    (sort-by :id)
                                    (layout-column 1000))
                 focus-node' (layout-column 600 [focus-node])
                 all-nodes (-> dependents'
                               (into dependencies')
                               (into focus-node'))
                 f (fn [{:keys [id] :as node}]
                     (if (contains? active-node-ids id)
                       (assoc node :className "active")
                       node))]
             (map f all-nodes))))

;; ::edges is a map of edges ready to be drawn on screen, keyed by edge id.
(reg-sub ::edges
         :<- [::dependents]
         :<- [::focus-node]
         :<- [::dependencies]
         :<- [::hide-clojure]
         :<- [::root-node-id]
         :-> (fn [[dependents focus-node dependencies hide-clojure root-node-id]]
               ;; Only edges where both sides of the connection are "on screen" should be
               ;; included.
               (let [nodes (-> dependents
                               (into dependencies)
                               (conj focus-node))
                     relevant-ids (->> nodes (map :id) set)
                     relevant-edge? (fn [{:keys [source target]}]
                                      (and (contains? relevant-ids source)
                                           (contains? relevant-ids target)))]
                 (->> nodes
                      (mapcat :edges)
                      (filter relevant-edge?)
                      ;; The dependencies on Clojure can be noisy; by default, remove edges
                      ;; to clojure, except for the root node.
                      (remove (fn [edge]
                                (and hide-clojure
                                     (not= (:source edge) root-node-id)
                                     (= (:target edge) "org.clojure/clojure"))))
                      (medley/index-by :id)))))

;; A set of ids of the two nodes on either side of the active edge.
(reg-sub ::active-edge-node-ids
         :<- [::edges]
         :<- [::active-edge-id]
         :-> (fn [[edges active-edge-id]]
               (when-let [edge (get edges active-edge-id)]
                 (set [(:source edge)
                       (:target edge)]))))

(defn- make-edge-view-ready
  [view-nodes {:keys [id requested-version target] :as edge} active-edge-id]
  (let [target-version (get-in view-nodes [target :version])
        version-mismatch? (not= requested-version
                               target-version)
        compatible? (semver/satisfies target-version (str "~" requested-version))]
    (cond
      (= id active-edge-id)
      (assoc edge :selected true
                  :zIndex 1000
                  :label (when version-mismatch?
                           requested-version)
                  :markerEnd {:type :arrowclosed
                              ;; Slightly ugly but gets the job done. Would rather find a way to
                              ;; do this in CSS.
                              :color :black})

      version-mismatch?
      (assoc edge :className (if compatible? "compatible-version" "incompatible-version")
                  :label requested-version
                  :markerEnd {:type :arrowclosed
                              :color (if compatible? :blue :red)})

      :else
      edge)))

(reg-sub ::view-ready-edges
         :<- [::view-nodes]
         :<- [::edges]
         :<- [::active-edge-id]
         :-> (fn [[view-nodes edges-map active-edge-id]]
               (->> edges-map
                    vals
                    (map #(make-edge-view-ready view-nodes % active-edge-id)))))

(defn active-edge-details
  []
  (let [active-edge-id (<sub [::active-edge-id])
        edges (<sub [::edges])
        edge (get edges active-edge-id)]
    (when-let [{:keys [source target]} edge]
      [:div
       (str (name source) " -> " (name target))])))

(defn hide-show-clojure-button
  []
  (let [hide-clojure (<sub [::hide-clojure])]
    [:label
     [:input {:type :checkbox
              :checked hide-clojure
              :name :hide-clojure
              :on-click (fn [event _]
                           (.preventDefault event)
                          (rf/dispatch [::set-hide-clojure (not hide-clojure)]))}]
     "Hide Clojure Dependencies"]))

(defn flow-panel
  []
  (let [nodes (<sub [::view-ready-nodes])
        edges (<sub [::view-ready-edges])]
    [:<>
     [hide-show-clojure-button]
     [:div.app-flow-container
      ;; :> converts the top level map to a JS map, but doesn't do so recursively.
      (when (seq nodes)
        [:> ReactFlow {:nodes (clj->js nodes)
                       :edges (clj->js edges)
                       :nodesConnectable false
                       :onEdgeMouseEnter (fn [_event edge]
                                           (rf/dispatch [::set-active-edge-id (.-id edge)]))
                       :onEdgeMouseLeave (fn [_event _edge]
                                           (rf/dispatch [::set-active-edge-id nil]))
                       :onNodeClick (fn [_event node]
                                      (rf/dispatch [::select-focus-node (.-id node)]))}
         [:> rfr/Controls {:showInteractive false}]])
      [active-edge-details]]]))

(defn- parse-sample
  []
  '{:nodes [{:id babashka/fs, :version "0.1.11", :dependencies [{:id org.clojure/clojure, :version "1.9.0"}]}
            {:id ch.qos.logback/logback-classic,
             :version "1.2.3",
             :dependencies [{:id ch.qos.logback/logback-core, :version "1.2.3"}
                            {:id org.slf4j/slf4j-api, :version "1.7.25"}]}
            {:id ch.qos.logback/logback-core, :version "1.2.3"}
            {:id clj-fuzzy/clj-fuzzy, :version "0.4.1", :dependencies [{:id org.clojure/clojure, :version "1.7.0"}]}
            {:id com.datomic/datomic-lucene-core, :version "3.3.0"}
            {:id com.datomic/datomic-pro,
             :version "1.0.6522",
             :dependencies [{:id org.clojure/clojure, :version "1.8.0"}
                            {:id commons-codec/commons-codec, :version "1.15"}
                            {:id org.clojure/tools.cli, :version "1.0.206"}
                            {:id org.slf4j/jcl-over-slf4j, :version "1.7.36"}
                            {:id com.h2database/h2, :version "1.3.171"}
                            {:id org.apache.tomcat/tomcat-jdbc, :version "7.0.109"}
                            {:id org.slf4j/jul-to-slf4j, :version "1.7.36"}
                            {:id com.datomic/memcache-asg-java-client, :version "1.1.0.33"}
                            {:id com.datomic/query-support, :version "0.8.28"}
                            {:id org.fressian/fressian, :version "0.6.6"}
                            {:id org.apache.httpcomponents/httpclient, :version "4.5.9"}
                            {:id org.apache.activemq/artemis-core-client, :version "2.19.1"}
                            {:id org.slf4j/slf4j-api, :version "1.7.36"}
                            {:id com.github.ben-manes.caffeine/caffeine, :version "2.8.1"}
                            {:id org.codehaus.janino/commons-compiler-jdk, :version "3.0.12"}
                            {:id org.slf4j/log4j-over-slf4j, :version "1.7.36"}
                            {:id com.datomic/datomic-lucene-core, :version "3.3.0"}]}
            {:id com.datomic/memcache-asg-java-client, :version "1.1.0.33"}
            {:id com.datomic/query-support, :version "0.8.28"}
            {:id com.github.ben-manes.caffeine/caffeine,
             :version "2.8.1",
             :dependencies [{:id org.checkerframework/checker-qual, :version "3.1.0"}
                            {:id com.google.errorprone/error_prone_annotations, :version "2.3.4"}]}
            {:id com.google.errorprone/error_prone_annotations, :version "2.3.4"}
            {:id com.h2database/h2, :version "1.3.171"}
            {:id commons-beanutils/commons-beanutils,
             :version "1.9.4",
             :dependencies [{:id commons-logging/commons-logging, :version "1.2"}
                            {:id commons-collections/commons-collections, :version "3.2.2"}]}
            {:id commons-codec/commons-codec, :version "1.15"}
            {:id commons-collections/commons-collections, :version "3.2.2"}
            {:id example/proj,
             :version "0.0.1",
             :dependencies [{:id org.clojure/clojure, :version "1.11.1"}
                            {:id io.aviso/logging, :version "1.0"}
                            {:id com.datomic/datomic-pro, :version "1.0.6522"}
                            {:id org.clojure/data.fressian, :version "1.0.0"}
                            {:id org.clojure/java.jmx, :version "1.0.0"}
                            {:id org.hdrhistogram/HdrHistogram, :version "2.1.12"}
                            {:id babashka/fs, :version "0.1.11"}
                            {:id io.github.hlship/cli-tools, :version "0.5"}]}
            {:id io.aviso/logging,
             :version "1.0",
             :dependencies [{:id org.clojure/clojure, :version "1.8.0"}
                            {:id org.slf4j/slf4j-api, :version "1.7.30"}
                            {:id ch.qos.logback/logback-classic, :version "1.2.3"}
                            {:id org.clojure/tools.logging, :version "1.1.0"}
                            {:id io.aviso/pretty, :version "1.1"}
                            {:id org.slf4j/jcl-over-slf4j, :version "1.7.30"}]}
            {:id io.aviso/pretty, :version "1.1.1", :dependencies [{:id org.clojure/clojure, :version "1.7.0"}]}
            {:id io.github.hlship/cli-tools,
             :version "0.5",
             :dependencies [{:id org.clojure/clojure, :version "1.10.3"}
                            {:id org.clojure/tools.cli, :version "1.0.206"}
                            {:id io.aviso/pretty, :version "1.1.1"}
                            {:id clj-fuzzy/clj-fuzzy, :version "0.4.1"}]}
            {:id io.netty/netty-buffer,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-codec,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}]}
            {:id io.netty/netty-codec-http,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec, :version "4.1.73.Final"}
                            {:id io.netty/netty-handler, :version "4.1.73.Final"}]}
            {:id io.netty/netty-codec-socks,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec, :version "4.1.73.Final"}]}
            {:id io.netty/netty-common, :version "4.1.73.Final"}
            {:id io.netty/netty-handler,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-resolver, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec, :version "4.1.73.Final"}
                            {:id io.netty/netty-tcnative-classes, :version "2.0.46.Final"}]}
            {:id io.netty/netty-handler-proxy,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec-socks, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec-http, :version "4.1.73.Final"}]}
            {:id io.netty/netty-resolver,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-tcnative-classes, :version "2.0.46.Final"}
            {:id io.netty/netty-transport,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-resolver, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-classes-epoll,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-classes-kqueue,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-native-epoll$linux-x86_64,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport-classes-epoll, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-native-kqueue$osx-x86_64,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport-classes-kqueue, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-native-unix-common,
             :version "4.1.73.Final",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}]}
            {:id jakarta.json/jakarta.json-api, :version "1.1.6"}
            {:id org.apache.activemq/artemis-commons,
             :version "2.19.1",
             :dependencies [{:id org.jboss.logging/jboss-logging, :version "3.4.2.Final"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-handler, :version "4.1.73.Final"}
                            {:id commons-beanutils/commons-beanutils, :version "1.9.4"}
                            {:id jakarta.json/jakarta.json-api, :version "1.1.6"}]}
            {:id org.apache.activemq/artemis-core-client,
             :version "2.19.1",
             :dependencies [{:id io.netty/netty-common, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport-native-kqueue$osx-x86_64, :version "4.1.73.Final"}
                            {:id io.netty/netty-handler-proxy, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec-socks, :version "4.1.73.Final"}
                            {:id org.apache.johnzon/johnzon-core, :version "0.9.5"}
                            {:id org.apache.activemq/artemis-commons, :version "2.19.1"}
                            {:id io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:id io.netty/netty-handler, :version "4.1.73.Final"}
                            {:id jakarta.json/jakarta.json-api, :version "1.1.6"}
                            {:id io.netty/netty-transport-native-epoll$linux-x86_64, :version "4.1.73.Final"}
                            {:id io.netty/netty-transport, :version "4.1.73.Final"}
                            {:id io.netty/netty-codec-http, :version "4.1.73.Final"}]}
            {:id org.apache.httpcomponents/httpclient,
             :version "4.5.9",
             :dependencies [{:id org.apache.httpcomponents/httpcore, :version "4.4.11"}
                            {:id commons-codec/commons-codec, :version "1.11"}]}
            {:id org.apache.httpcomponents/httpcore, :version "4.4.11"}
            {:id org.apache.johnzon/johnzon-core, :version "0.9.5"}
            {:id org.apache.tomcat/tomcat-jdbc,
             :version "7.0.109",
             :dependencies [{:id org.apache.tomcat/tomcat-juli, :version "7.0.109"}]}
            {:id org.apache.tomcat/tomcat-juli, :version "7.0.109"}
            {:id org.checkerframework/checker-qual, :version "3.1.0"}
            {:id org.clojure/clojure,
             :version "1.11.1",
             :dependencies [{:id org.clojure/spec.alpha, :version "0.3.218"}
                            {:id org.clojure/core.specs.alpha, :version "0.2.62"}]}
            {:id org.clojure/core.specs.alpha, :version "0.2.62"}
            {:id org.clojure/data.fressian,
             :version "1.0.0",
             :dependencies [{:id org.clojure/clojure, :version "1.7.0"} {:id org.fressian/fressian, :version "0.6.6"}]}
            {:id org.clojure/java.jmx, :version "1.0.0", :dependencies [{:id org.clojure/clojure, :version "1.4.0"}]}
            {:id org.clojure/spec.alpha, :version "0.3.218"}
            {:id org.clojure/tools.cli, :version "1.0.206"}
            {:id org.clojure/tools.logging, :version "1.1.0", :dependencies [{:id org.clojure/clojure, :version "1.4.0"}]}
            {:id org.codehaus.janino/commons-compiler, :version "3.0.12"}
            {:id org.codehaus.janino/commons-compiler-jdk,
             :version "3.0.12",
             :dependencies [{:id org.codehaus.janino/commons-compiler, :version "3.0.12"}]}
            {:id org.fressian/fressian, :version "0.6.6"}
            {:id org.hdrhistogram/HdrHistogram, :version "2.1.12"}
            {:id org.jboss.logging/jboss-logging, :version "3.4.2.Final"}
            {:id org.slf4j/jcl-over-slf4j, :version "1.7.36", :dependencies [{:id org.slf4j/slf4j-api, :version "1.7.36"}]}
            {:id org.slf4j/jul-to-slf4j, :version "1.7.36", :dependencies [{:id org.slf4j/slf4j-api, :version "1.7.36"}]}
            {:id org.slf4j/log4j-over-slf4j,
             :version "1.7.36",
             :dependencies [{:id org.slf4j/slf4j-api, :version "1.7.36"}]}
            {:id org.slf4j/slf4j-api, :version "1.7.36"}],
    :root-node-id example/proj})

(defn init
  []
  (rf/dispatch [::set-input-model (parse-sample)]))
