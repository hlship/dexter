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

(s/def ::dependencies (s/coll-of ::dependency))
(s/def ::dependency (s/keys :req-un [::on
                                     ::version]))

(s/def ::on symbol?)
(s/def ::version string?)

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
                 :markerEnd {:type "arrowclosed"}})]
    {:id id'
     :data {:label (str id' " " version)}
     :edges edges
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

;; Triggered by clicking on a node
(reg-event-db ::select-focus-node
              (fn [db [_ node-id]]
                (assoc db ::focus-node-id node-id)))

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
         (fn [[dependents focus-node dependencies]]
           (let [dependents' (->> dependents
                                  (sort-by :id)
                                  (layout-column 100))
                 dependencies' (->> dependencies
                                    (sort-by :id)
                                    (layout-column 1000))
                 focus-node' (layout-column 600 [focus-node])]
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

(defn flow-panel
  []
  (let [nodes (<sub [::view-ready-nodes])
        edges (<sub [::edges])]
    [:div.app-flow-container
     ;; :> converts the top level map to a JS map, but doesn't do so recursively.
     (when (seq nodes)
       [:> ReactFlow {:nodes (clj->js nodes)
                      :edges (clj->js edges)
                      :onNodeClick (fn [_event node]
                                     (rf/dispatch [::select-focus-node (.-id node)]))}])]))

(defn- parse-sample
  []
  '{:nodes [{:id babashka/fs, :version "0.1.11", :dependencies [{:on org.clojure/clojure, :version "1.9.0"}]}
            {:id ch.qos.logback/logback-classic,
             :version "1.2.3",
             :dependencies [{:on ch.qos.logback/logback-core, :version "1.2.3"}
                            {:on org.slf4j/slf4j-api, :version "1.7.25"}]}
            {:id ch.qos.logback/logback-core, :version "1.2.3"}
            {:id clj-fuzzy/clj-fuzzy, :version "0.4.1", :dependencies [{:on org.clojure/clojure, :version "1.7.0"}]}
            {:id com.datomic/datomic-lucene-core, :version "3.3.0"}
            {:id com.datomic/datomic-pro,
             :version "1.0.6522",
             :dependencies [{:on org.clojure/clojure, :version "1.8.0"}
                            {:on commons-codec/commons-codec, :version "1.15"}
                            {:on org.clojure/tools.cli, :version "1.0.206"}
                            {:on org.slf4j/jcl-over-slf4j, :version "1.7.36"}
                            {:on com.h2database/h2, :version "1.3.171"}
                            {:on org.apache.tomcat/tomcat-jdbc, :version "7.0.109"}
                            {:on org.slf4j/jul-to-slf4j, :version "1.7.36"}
                            {:on com.datomic/memcache-asg-java-client, :version "1.1.0.33"}
                            {:on com.datomic/query-support, :version "0.8.28"}
                            {:on org.fressian/fressian, :version "0.6.6"}
                            {:on org.apache.httpcomponents/httpclient, :version "4.5.9"}
                            {:on org.apache.activemq/artemis-core-client, :version "2.19.1"}
                            {:on org.slf4j/slf4j-api, :version "1.7.36"}
                            {:on com.github.ben-manes.caffeine/caffeine, :version "2.8.1"}
                            {:on org.codehaus.janino/commons-compiler-jdk, :version "3.0.12"}
                            {:on org.slf4j/log4j-over-slf4j, :version "1.7.36"}
                            {:on com.datomic/datomic-lucene-core, :version "3.3.0"}]}
            {:id com.datomic/memcache-asg-java-client, :version "1.1.0.33"}
            {:id com.datomic/query-support, :version "0.8.28"}
            {:id com.github.ben-manes.caffeine/caffeine,
             :version "2.8.1",
             :dependencies [{:on org.checkerframework/checker-qual, :version "3.1.0"}
                            {:on com.google.errorprone/error_prone_annotations, :version "2.3.4"}]}
            {:id com.google.errorprone/error_prone_annotations, :version "2.3.4"}
            {:id com.h2database/h2, :version "1.3.171"}
            {:id commons-beanutils/commons-beanutils,
             :version "1.9.4",
             :dependencies [{:on commons-logging/commons-logging, :version "1.2"}
                            {:on commons-collections/commons-collections, :version "3.2.2"}]}
            {:id commons-codec/commons-codec, :version "1.15"}
            {:id commons-collections/commons-collections, :version "3.2.2"}
            {:id example/proj,
             :version "0.0.1",
             :dependencies [{:on org.clojure/clojure, :version "1.11.1"}
                            {:on io.aviso/logging, :version "1.0"}
                            {:on com.datomic/datomic-pro, :version "1.0.6522"}
                            {:on org.clojure/data.fressian, :version "1.0.0"}
                            {:on org.clojure/java.jmx, :version "1.0.0"}
                            {:on org.hdrhistogram/HdrHistogram, :version "2.1.12"}
                            {:on babashka/fs, :version "0.1.11"}
                            {:on io.github.hlship/cli-tools, :version "0.5"}]}
            {:id io.aviso/logging,
             :version "1.0",
             :dependencies [{:on org.clojure/clojure, :version "1.8.0"}
                            {:on org.slf4j/slf4j-api, :version "1.7.30"}
                            {:on ch.qos.logback/logback-classic, :version "1.2.3"}
                            {:on org.clojure/tools.logging, :version "1.1.0"}
                            {:on io.aviso/pretty, :version "1.1"}
                            {:on org.slf4j/jcl-over-slf4j, :version "1.7.30"}]}
            {:id io.aviso/pretty, :version "1.1.1", :dependencies [{:on org.clojure/clojure, :version "1.7.0"}]}
            {:id io.github.hlship/cli-tools,
             :version "0.5",
             :dependencies [{:on org.clojure/clojure, :version "1.10.3"}
                            {:on org.clojure/tools.cli, :version "1.0.206"}
                            {:on io.aviso/pretty, :version "1.1.1"}
                            {:on clj-fuzzy/clj-fuzzy, :version "0.4.1"}]}
            {:id io.netty/netty-buffer,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-codec,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}]}
            {:id io.netty/netty-codec-http,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec, :version "4.1.73.Final"}
                            {:on io.netty/netty-handler, :version "4.1.73.Final"}]}
            {:id io.netty/netty-codec-socks,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec, :version "4.1.73.Final"}]}
            {:id io.netty/netty-common, :version "4.1.73.Final"}
            {:id io.netty/netty-handler,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-resolver, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec, :version "4.1.73.Final"}
                            {:on io.netty/netty-tcnative-classes, :version "2.0.46.Final"}]}
            {:id io.netty/netty-handler-proxy,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec-socks, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec-http, :version "4.1.73.Final"}]}
            {:id io.netty/netty-resolver,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-tcnative-classes, :version "2.0.46.Final"}
            {:id io.netty/netty-transport,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-resolver, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-classes-epoll,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-classes-kqueue,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-native-epoll$linux-x86_64,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport-classes-epoll, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-native-kqueue$osx-x86_64,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport-native-unix-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport-classes-kqueue, :version "4.1.73.Final"}]}
            {:id io.netty/netty-transport-native-unix-common,
             :version "4.1.73.Final",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}]}
            {:id jakarta.json/jakarta.json-api, :version "1.1.6"}
            {:id org.apache.activemq/artemis-commons,
             :version "2.19.1",
             :dependencies [{:on org.jboss.logging/jboss-logging, :version "3.4.2.Final"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-handler, :version "4.1.73.Final"}
                            {:on commons-beanutils/commons-beanutils, :version "1.9.4"}
                            {:on jakarta.json/jakarta.json-api, :version "1.1.6"}]}
            {:id org.apache.activemq/artemis-core-client,
             :version "2.19.1",
             :dependencies [{:on io.netty/netty-common, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport-native-kqueue$osx-x86_64, :version "4.1.73.Final"}
                            {:on io.netty/netty-handler-proxy, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec-socks, :version "4.1.73.Final"}
                            {:on org.apache.johnzon/johnzon-core, :version "0.9.5"}
                            {:on org.apache.activemq/artemis-commons, :version "2.19.1"}
                            {:on io.netty/netty-buffer, :version "4.1.73.Final"}
                            {:on io.netty/netty-handler, :version "4.1.73.Final"}
                            {:on jakarta.json/jakarta.json-api, :version "1.1.6"}
                            {:on io.netty/netty-transport-native-epoll$linux-x86_64, :version "4.1.73.Final"}
                            {:on io.netty/netty-transport, :version "4.1.73.Final"}
                            {:on io.netty/netty-codec-http, :version "4.1.73.Final"}]}
            {:id org.apache.httpcomponents/httpclient,
             :version "4.5.9",
             :dependencies [{:on org.apache.httpcomponents/httpcore, :version "4.4.11"}
                            {:on commons-codec/commons-codec, :version "1.11"}]}
            {:id org.apache.httpcomponents/httpcore, :version "4.4.11"}
            {:id org.apache.johnzon/johnzon-core, :version "0.9.5"}
            {:id org.apache.tomcat/tomcat-jdbc,
             :version "7.0.109",
             :dependencies [{:on org.apache.tomcat/tomcat-juli, :version "7.0.109"}]}
            {:id org.apache.tomcat/tomcat-juli, :version "7.0.109"}
            {:id org.checkerframework/checker-qual, :version "3.1.0"}
            {:id org.clojure/clojure,
             :version "1.11.1",
             :dependencies [{:on org.clojure/spec.alpha, :version "0.3.218"}
                            {:on org.clojure/core.specs.alpha, :version "0.2.62"}]}
            {:id org.clojure/core.specs.alpha, :version "0.2.62"}
            {:id org.clojure/data.fressian,
             :version "1.0.0",
             :dependencies [{:on org.clojure/clojure, :version "1.7.0"} {:on org.fressian/fressian, :version "0.6.6"}]}
            {:id org.clojure/java.jmx, :version "1.0.0", :dependencies [{:on org.clojure/clojure, :version "1.4.0"}]}
            {:id org.clojure/spec.alpha, :version "0.3.218"}
            {:id org.clojure/tools.cli, :version "1.0.206"}
            {:id org.clojure/tools.logging, :version "1.1.0", :dependencies [{:on org.clojure/clojure, :version "1.4.0"}]}
            {:id org.codehaus.janino/commons-compiler, :version "3.0.12"}
            {:id org.codehaus.janino/commons-compiler-jdk,
             :version "3.0.12",
             :dependencies [{:on org.codehaus.janino/commons-compiler, :version "3.0.12"}]}
            {:id org.fressian/fressian, :version "0.6.6"}
            {:id org.hdrhistogram/HdrHistogram, :version "2.1.12"}
            {:id org.jboss.logging/jboss-logging, :version "3.4.2.Final"}
            {:id org.slf4j/jcl-over-slf4j, :version "1.7.36", :dependencies [{:on org.slf4j/slf4j-api, :version "1.7.36"}]}
            {:id org.slf4j/jul-to-slf4j, :version "1.7.36", :dependencies [{:on org.slf4j/slf4j-api, :version "1.7.36"}]}
            {:id org.slf4j/log4j-over-slf4j,
             :version "1.7.36",
             :dependencies [{:on org.slf4j/slf4j-api, :version "1.7.36"}]}
            {:id org.slf4j/slf4j-api, :version "1.7.36"}],
    :root-node-id example/proj})

(defn init
  []
  (rf/dispatch [::set-input-model (parse-sample)]))
