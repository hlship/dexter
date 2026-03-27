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

(defn- col-x
  "Returns the x-coordinate of a column's left edge."
  [column]
  (case column
    :left 0
    :center (+ col-width col-gap)
    :right (* 2 (+ col-width col-gap))))

(defn- column-top-y
  "Returns the y offset for the top of a column, centered vertically within total-height."
  [item-count total-height]
  (let [col-height (+ (* item-count box-height)
                      (* (max 0 (dec item-count)) box-gap))]
    (/ (- total-height col-height) 2)))

(defn- box-center-y
  "Returns the vertical center of a box at the given row within a column."
  [row item-count total-height]
  (let [top (column-top-y item-count total-height)]
    (+ top (* row (+ box-height box-gap)) (/ box-height 2))))

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
           total-height connection-type]}]
  (if (= connection-type :intra-column)
    ;; Intra-column: arc that bows outward from the column
    (let [x (if (= from-col :left)
              (col-x :left)
              (+ (col-x from-col) col-width))
          y1 (box-center-y from-row from-count total-height)
          y2 (box-center-y to-row to-count total-height)
          bow (if (= from-col :left) -40 40)
          cx (+ x bow)]
      (str "M" x "," y1
           " C" cx "," y1 " " cx "," y2 " " x "," y2))
    ;; Cross-column: gentle horizontal bezier
    (let [x1 (+ (col-x from-col) col-width)
          y1 (box-center-y from-row from-count total-height)
          x2 (col-x to-col)
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
  "Renders the SVG overlay with connection arrows between visible artifacts."
  [layout-data total-height]
  (let [total-width (+ (* 3 col-width) (* 2 col-gap))]
    [:svg {:class "absolute inset-0 pointer-events-none"
           :viewBox (str "0 0 " total-width " " total-height)
           :preserveAspectRatio "xMidYMid meet"
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
                                 :total-height total-height
                                 :connection-type connection-type})
                            :stroke "#64748b"
                            :stroke-width "2"
                            :fill "none"
                            :marker-end "url(#arrowhead)"}])))
           (:connections layout-data))]))

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
   [:div {:class "text-xs text-slate-500"} (str "v" version)]])

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
  total-height is the container height; the column uses it to vertically center
  its content, matching the SVG arrow coordinate math."
  [{:keys [boxes before after]} column selected-key cursor total-height]
  (let [offset-key (case column :left :left-offset :right :right-offset)]
    [:div {:class "flex flex-col justify-center gap-3 w-[280px]"
           :style (str "height:" total-height "px")}
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

(defn home-page [_]
  (let [db @deps/*db
        cursor (h/tab-cursor :view {:selected 'ROOT
                                    :left-offset 0
                                    :right-offset 0})
        {:keys [selected left-offset right-offset]} @cursor
        layout-data (layout/compute-layout db selected left-offset right-offset)
        max-col-count (max 1
                           (count (get-in layout-data [:left :boxes]))
                           (count (get-in layout-data [:right :boxes])))
        total-height (+ (* max-col-count box-height)
                        (* (max 0 (dec max-col-count)) box-gap)
                        40)]
    [:div {:class "min-h-screen bg-slate-100 flex items-center justify-center p-8"}
     [:div {:id "dep-viewer"
            :class "relative flex items-center gap-[120px]"
            :style (str "height:" total-height "px")}

      ;; SVG arrows layer
      (render-arrows layout-data total-height)

      ;; Left column: dependants
      (render-column (:left layout-data) :left selected cursor total-height)

      ;; Center: selected artifact
      [:div {:class "flex flex-col justify-center w-[280px]"
             :style (str "height:" total-height "px")}
       (render-box (:selected-box layout-data) true
                   (h/action))]

      ;; Right column: dependencies
      (render-column (:right layout-data) :right selected cursor total-height)]]))

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

  (deps/load-db! "test-resources/dex/project-deps.edn")

  (start!)

  (stop!)

  (do
    (stop!)
    (start!))

  ;;
  )
