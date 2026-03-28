(ns net.lewisship.dex.views
  "Hiccup rendering functions for the dependency explorer UI.

  Produces the page layout, artifact boxes, columns, toolbar, and
  connection data for the client-side arrow overlay."
  (:require [cheshire.core :as json]
            [hyper.core :as h]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.layout :as layout]))

;; --- Box Rendering ---

(defn- render-box
  "Renders a single artifact box as a Tailwind-styled div.
  select-action is a Datastar expression string returned by h/action.
  Leaf nodes (no dependencies) are annotated with a grey right border marker."
  [{:keys [key name version leaf?]} selected? select-action]
  [:div {:id (str "box-" key)
         :class (str "w-full px-4 py-2 rounded-lg border-2 cursor-pointer "
                     "transition-colors duration-150 "
                     (if selected?
                       "border-blue-500 bg-blue-50 shadow-md"
                       "border-slate-300 bg-white hover:border-blue-300 hover:bg-blue-50")
                     (when leaf?
                       " border-r-4 border-r-slate-400"))
         :data-on:click select-action}
   [:div {:class "font-semibold text-sm text-slate-800 truncate" :title name} name]
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

;; --- Connection Data ---

(defn- connections->json
  "Converts layout connection data to JSON for client-side arrow rendering.
  Each connection includes from/to element IDs, version info, and connection type.
  Bypass connections also include the center box ID for routing around it."
  [connections center-id]
  (json/generate-string
   (mapv (fn [{:keys [from-id to-id requested-version resolved-version connection-type color]}]
           (cond-> {:fromId from-id
                    :toId to-id
                    :requestedVersion requested-version
                    :resolvedVersion resolved-version
                    :type (name connection-type)
                    :color color}
             (= :bypass connection-type)
             (assoc :centerId center-id)))
         connections)))

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
    (str selected)]])

(defn home-page [_]
  (let [db @deps/*db
        cursor (h/tab-cursor :view {:selected 'ROOT
                                    :left-offset 0
                                    :right-offset 0
                                    :hidden-libs layout/default-hidden-libs})
        {:keys [selected left-offset right-offset hidden-libs]} @cursor
        layout-data (layout/compute-layout db selected left-offset right-offset hidden-libs)]
    ;; Full-viewport flex column: toolbar on top, content fills the rest
    [:div {:class "h-screen flex flex-col bg-slate-100"}
     ;; Toolbar (shrink-0 keeps it at natural size)
     (render-toolbar cursor selected)

     ;; Content area fills remaining space, centers the graph
     ;; data-draw-arrows passes connection JSON to the client-side arrow plugin
     [:div {:id "dep-viewer"
            :class "flex-1 relative flex justify-center gap-[120px] overflow-auto"
            :data-draw-arrows (connections->json (:connections layout-data)
                                                  (:id (:selected-box layout-data)))}

      ;; Empty SVG container — client-side JS populates arrow paths
      [:svg {:id "arrow-overlay"
             :class "absolute inset-0 pointer-events-none"

             :width "100%"
             :height "100%"
             :xmlns "http://www.w3.org/2000/svg"}]

      ;; Left column: dependants
      (render-column (:left layout-data) :left selected cursor)

      ;; Center: selected artifact
      [:div {:class "flex flex-col justify-center w-[280px] h-full"}
       (render-box (:selected-box layout-data) true
                   (h/action))]

      ;; Right column: dependencies
      (render-column (:right layout-data) :right selected cursor)]]))
