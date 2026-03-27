(ns net.lewisship.dex.service
  (:require [cheshire.core :as json]
            [hyper.core :as h]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.layout :as layout]))

;; --- SVG Layout Constants ---
;; These define the geometry used to compute arrow paths.
;; Box dimensions should stay in sync with the Tailwind classes on rendered boxes.

(def ^:const col-width 280)
(def ^:const col-gap 120)
(def ^:const box-height 56)
(def ^:const box-gap 12)

(def ^:const content-width
  "Total width of the three columns plus gaps."
  (+ (* 3 col-width) (* 2 col-gap)))

(defn- col-x
  "Returns the x-coordinate of a column's left edge, accounting for
  horizontal centering within the container."
  [column container-width]
  (let [x-offset (/ (- container-width content-width) 2.0)]
    (+ x-offset
       (case column
         :left 0
         :center (+ col-width col-gap)
         :right (* 2 (+ col-width col-gap))))))

(defn- column-top-y
  "Returns the y offset for the top of a column, centered vertically within total-height."
  [item-count total-height]
  (let [col-height (+ (* item-count box-height)
                      (* (max 0 (dec item-count)) box-gap))]
    (/ (- total-height col-height) 2.0)))

(defn- box-center-y
  "Returns the vertical center of a box at the given row within a column."
  [row item-count total-height]
  (let [top (column-top-y item-count total-height)]
    (+ top (* row (+ box-height box-gap)) (/ box-height 2.0))))

(defn- column-item-count
  "Returns the number of visible items for a column keyword given the layout data."
  [layout-data column]
  (case column
    :left (count (get-in layout-data [:left :boxes]))
    :center 1
    :right (count (get-in layout-data [:right :boxes]))))

(defn- svg-arrow-path
  "Generates an SVG cubic bezier path string for a connection arrow."
  [{:keys [from-col from-row from-count to-col to-row to-count
           total-height container-width connection-type]}]
  (if (= connection-type :intra-column)
    ;; Intra-column: arc that bows outward from the column
    (let [x (if (= from-col :left)
              (col-x :left container-width)
              (+ (col-x from-col container-width) col-width))
          y1 (box-center-y from-row from-count total-height)
          y2 (box-center-y to-row to-count total-height)
          bow (if (= from-col :left) -40 40)
          cx (+ x bow)]
      (str "M" x "," y1
           " C" cx "," y1 " " cx "," y2 " " x "," y2))
    ;; Cross-column: gentle horizontal bezier
    (let [x1 (+ (col-x from-col container-width) col-width)
          y1 (box-center-y from-row from-count total-height)
          x2 (col-x to-col container-width)
          y2 (box-center-y to-row to-count total-height)
          cx (/ (+ x1 x2) 2)]
      (str "M" x1 "," y1
           " C" cx "," y1 " " cx "," y2 " " x2 "," y2))))

(defn- render-arrow-defs
  "SVG <defs> with arrowhead markers."
  []
  [:defs
   [:marker {:id "arrowhead"
             :markerWidth "8"
             :markerHeight "6"
             :refX "8"
             :refY "3"
             :orient "auto"
             :markerUnits "strokeWidth"}
    [:path {:d "M0,0 L8,3 L0,6 Z" :fill "#64748b"}]]])

(defn- render-arrows
  "Renders the SVG overlay with connection arrows between visible artifacts.
  container-width and container-height are the actual pixel dimensions of
  the dep-viewer, reported by the browser via ResizeObserver.
  The SVG uses 100% width/height with no viewBox, so coordinates map
  directly to CSS pixels — no scaling."
  [layout-data container-width container-height]
  [:svg {:class "absolute inset-0 pointer-events-none"
         :width "100%"
         :height "100%"
         :xmlns "http://www.w3.org/2000/svg"}
   (render-arrow-defs)
   (into [:g]
         (map (fn [{:keys [from-column from-row to-column to-row connection-type]}]
                (let [from-count (column-item-count layout-data from-column)
                      to-count (column-item-count layout-data to-column)]
                  [:path {:d (svg-arrow-path
                              {:from-col from-column
                               :from-row from-row
                               :from-count from-count
                               :to-col to-column
                               :to-row to-row
                               :to-count to-count
                               :total-height container-height
                               :container-width container-width
                               :connection-type connection-type})
                          :stroke "#64748b"
                          :stroke-width "2"
                          :fill "none"
                          :marker-end "url(#arrowhead)"}])))
         (:connections layout-data))])

;; --- Box Rendering ---

(defn- render-box
  "Renders a single artifact box as a Tailwind-styled div.
  select-action is a Datastar expression string returned by h/action."
  [{:keys [key name version]} selected? select-action]
  [:div {:id (str "box-" key)
         :class (str "w-full px-4 py-2 rounded-lg border-2 cursor-pointer "
                     "transition-colors duration-150 "
                     (if selected?
                       "border-blue-500 bg-blue-50 shadow-md"
                       "border-slate-300 bg-white hover:border-blue-300 hover:bg-blue-50"))
         :data-on:click select-action}
   [:div {:class "font-semibold text-sm text-slate-800 truncate"} name]
   [:div {:class "text-xs text-slate-500"} version]])

(defn- render-overflow-indicator
  "Renders a clickable 'N more above/below' indicator for windowed columns.
  scroll-action is a Datastar expression string returned by h/action."
  [n direction scroll-action]
  (when (pos? n)
    [:div {:class "text-center text-xs text-slate-400 py-1 cursor-pointer
                            hover:text-blue-500 transition-colors"
           :data-on:click scroll-action}
     (str (case direction :up "▲ " :down "▼ ") n " more")]))

(defn- render-column
  "Renders a column of artifact boxes with optional overflow indicators.
  The column fills its parent's height and vertically centers its boxes."
  [{:keys [boxes before after]} column selected-key cursor]
  (let [offset-key (case column :left :left-offset :right :right-offset)]
    [:div {:class "flex flex-col justify-center gap-3 w-[280px] h-full"}
     (render-overflow-indicator
      before :up
      (h/action (swap! cursor update offset-key dec)))
     (for [box boxes]
       (render-box box (= (:key box) selected-key)
                   (h/action
                    (swap! cursor assoc
                           :selected (:key box)
                           :left-offset 0
                           :right-offset 0))))
     (render-overflow-indicator
      after :down
      (h/action (swap! cursor update offset-key inc)))]))

;; --- Page Rendering ---

(defn- render-toolbar
  "Renders the top toolbar with navigation controls."
  [cursor selected]
  [:div {:class "bg-white border-b border-slate-200 shadow-sm px-4 py-2 flex items-center gap-4 shrink-0"}
   ;; Home button
   [:button {:class (str "p-2 rounded-lg transition-colors "
                         (if (= selected 'ROOT)
                           "text-slate-300 cursor-default"
                           "text-slate-600 hover:bg-slate-100 hover:text-blue-600"))
             :title "Go to root"
             :data-on:click (h/action
                             (swap! cursor assoc
                                    :selected 'ROOT
                                    :left-offset 0
                                    :right-offset 0))}
    ;; Home icon (SVG)
    [:svg {:class "w-5 h-5" :viewBox "0 0 20 20" :fill "currentColor"
           :xmlns "http://www.w3.org/2000/svg"}
     [:path {:fill-rule "evenodd" :clip-rule "evenodd"
             :d "M9.293 2.293a1 1 0 011.414 0l7 7A1 1 0 0117 11h-1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-3a1 1 0 00-1-1H9a1 1 0 00-1 1v3a1 1 0 01-1 1H5a1 1 0 01-1-1v-6H3a1 1 0 01-.707-1.707l7-7z"}]]]
   ;; Current selection label
   [:span {:class "text-sm text-slate-500"}
    (str (get-in @cursor [:selected]))]])

(defn home-page [_]
  (let [db @deps/*db
        cursor (h/tab-cursor :view {:selected 'ROOT
                                    :left-offset 0
                                    :right-offset 0
                                    :hidden-libs layout/default-hidden-libs
                                    :viewport nil})
        {:keys [selected left-offset right-offset hidden-libs viewport]} @cursor
        layout-data (layout/compute-layout db selected left-offset right-offset hidden-libs)
        ;; Use actual viewport dimensions when available, fall back to calculated estimates
        container-width (or (:width viewport) content-width)
        container-height (let [max-col-count (max 1
                                                  (count (get-in layout-data [:left :boxes]))
                                                  (count (get-in layout-data [:right :boxes])))
                               content-height (+ (* max-col-count box-height)
                                                 (* (max 0 (dec max-col-count)) box-gap)
                                                 40)]
                           (if viewport
                             (max content-height (:height viewport))
                             content-height))]
    ;; Full-viewport flex column: toolbar on top, content fills the rest
    [:div {:class "h-screen flex flex-col bg-slate-100"}
     ;; Toolbar (shrink-0 keeps it at natural size)
     (render-toolbar cursor selected)

     ;; Content area fills remaining space, centers the graph
     ;; data-report-size uses ResizeObserver to POST {width, height} via $form-data
     [:div {:id "dep-viewer"
            :class "flex-1 relative flex justify-center gap-[120px] overflow-auto"
            :data-report-size (h/action
                               (when $form-data
                                 (swap! cursor assoc :viewport
                                        {:width (parse-long (str (:width $form-data)))
                                         :height (parse-long (str (:height $form-data)))})))}

      ;; SVG arrows layer (uses actual container dimensions for 1:1 pixel mapping)
      (render-arrows layout-data container-width container-height)

      ;; Left column: dependants
      (render-column (:left layout-data) :left selected cursor)

      ;; Center: selected artifact
      [:div {:class "flex flex-col justify-center w-[280px] h-full"}
       (render-box (:selected-box layout-data) true
                   (h/action))]

      ;; Right column: dependencies
      (render-column (:right layout-data) :right selected cursor)]]))

;; --- Routes & Handler ---

(def routes
  [["/" {:name :home
         :title "DEX - Dependency Explorer"
         :get #'home-page}]])

(def handler (h/create-handler
              #'routes
              :static-resources "public"
              :head [[:script {:type "importmap"}
                      (json/generate-string
                       {:imports
                        {:datastar "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}})]
                     [:link {:rel "stylesheet" :href "/style.css"}]
                     [:script {:type "module" :src "/js/main.js"}]]))

(defonce *app (atom nil))

(defn start!
  []
  (if @*app
    :already-running
    (do
      (reset! *app
              (h/start! handler {:port 10240}))
      :started)))

(defn stop!
  []
  (if-let [app @*app]
    (do
      (h/stop! app)
      (reset! *app nil)
      :stopped)
    :not-running))

(comment

  ;; Load from pre-built test data
  (deps/load-db! "test-resources/dex/project-deps.edn")

  ;; Or resolve live from a deps.edn (this project as an example)
  (require '[net.lewisship.dex.deps-reader :as deps-reader])
  (let [raw-data (deps-reader/read-deps "deps.edn" {:aliases [:dev :test]})]
    (reset! deps/*db (deps/build-db raw-data)))

  (start!)

  (stop!)

  (do
    ((requiring-resolve 'clj-reload.core/reload))
    (stop!)
    (start!))

  ;;
  )
