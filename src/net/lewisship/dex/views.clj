(ns net.lewisship.dex.views
  "Hiccup rendering functions for the dependency explorer UI.

  Produces the page layout, artifact boxes, columns, toolbar, and
  connection data for the client-side arrow overlay."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [hyper.core :as h]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.layout :as layout]))

;; Extend Hyper's client-param registry so that $scroll-delta-y can be used
;; inside h/action forms to receive the wheel event's deltaY on the server.
;; This must execute before any h/action macro that references the symbol.
(alter-var-root #'h/client-param-registry
                assoc '$scroll-delta-y {:js "evt.deltaY" :key "scrollDeltaY"})

;; --- Tab & View State Helpers ---

(defn- active-view
  "Returns the view map for the currently active tab."
  [state]
  (get-in state [:views (:active-tab state)]))

(defn- update-active-view
  "Applies f to the active tab's view map within the full state."
  [state f]
  (update-in state [:views (:active-tab state)] f))

(defn- active-tab-root
  "Returns the root artifact key for the active tab."
  [state]
  (let [active-id (:active-tab state)]
    (:root (some #(when (= (:id %) active-id) %) (:tabs state)))))

(defn- tab-root-set
  "Returns a set of all artifact keys that are roots of open tabs."
  [state]
  (into #{} (map :root) (:tabs state)))

;; --- Navigation History ---

(defn- navigate!
  "Pushes the current view state onto :nav-history, then navigates to the
  given artifact key with offsets reset to 0. No-op if already viewing
  the target artifact. Operates on the active tab's view."
  [cursor artifact-key]
  (let [view (active-view @cursor)]
    (when (not= (:selected view) artifact-key)
      (swap! cursor update-active-view
             (fn [view]
               (-> view
                   (update :nav-history (fnil conj [])
                           {:selected     (:selected view)
                            :left-offset  (:left-offset view)
                            :right-offset (:right-offset view)})
                   (assoc :selected artifact-key
                          :left-offset 0
                          :right-offset 0)))))))

(defn- navigate-back!
  "Pops the most recent entry from :nav-history and restores its state.
  No-op if history is empty. Operates on the active tab's view."
  [cursor]
  (let [view (active-view @cursor)]
    (when (seq (:nav-history view))
      (swap! cursor update-active-view
             (fn [view]
               (let [prev (peek (:nav-history view))]
                 (-> view
                     (assoc :selected (:selected prev)
                            :left-offset (:left-offset prev)
                            :right-offset (:right-offset prev))
                     (update :nav-history pop))))))))

;; --- Tab Management ---

(defn- open-tab!
  "Creates a new tab rooted at the given artifact, switches to it."
  [cursor db artifact-key]
  (swap! cursor
         (fn [state]
           (let [id    (:next-id state)
                 label (:label (deps/artifact-info db artifact-key))]
             (-> state
                 (update :tabs conj {:id id :root artifact-key :label label})
                 (assoc-in [:views id] {:selected     artifact-key
                                        :left-offset  0
                                        :right-offset 0
                                        :nav-history  []})
                 (assoc :active-tab id)
                 (update :next-id inc)
                 (update :tab-history conj id))))))

(defn- close-tab!
  "Closes a tab (must not be the ROOT tab, id 0). Selects the most
  recently viewed remaining tab."
  [cursor tab-id]
  (swap! cursor
         (fn [state]
           (let [history' (into [] (remove #{tab-id}) (:tab-history state))
                 new-active (or (peek history') 0)]
             (-> state
                 (update :tabs (fn [tabs] (into [] (remove #(= (:id %) tab-id)) tabs)))
                 (update :views dissoc tab-id)
                 (assoc :tab-history history'
                        :active-tab new-active))))))

(defn- select-tab!
  "Switches to the given tab, updating the tab-history for recency tracking."
  [cursor tab-id]
  (swap! cursor
         (fn [state]
           (-> state
               (assoc :active-tab tab-id)
               (update :tab-history
                       (fn [h] (conj (into [] (remove #{tab-id}) h) tab-id)))))))

;; --- Column Scrolling ---

(defn- scroll-offset
  "Returns a function that adjusts a column offset by `delta` (+1 or -1),
  clamping to [0, total - max-visible].  `column-key` is the layout key
  (:left or :right) used to look up the windowed column data."
  [db cursor column-key delta]
  (let [state @cursor
        view  (active-view state)
        {:keys [selected left-offset right-offset]} view
        {:keys [hidden-libs max-visible]} state
        layout-data (layout/compute-layout db selected left-offset right-offset
                                           hidden-libs max-visible)
        {:keys [total]} (get layout-data column-key)
        max-visible (or max-visible layout/default-max-visible)
        max-offset  (max 0 (- total max-visible))]
    (fn [current]
      (max 0 (min max-offset (+ current delta))))))

;; --- Box Rendering ---

(defn- render-box
  "Renders a single artifact box as a Tailwind-styled div.
  select-action is a Datastar expression string returned by h/action.
  open-tab-action, when non-nil, renders a small icon in the upper-left
  corner to open a new tab rooted at this artifact.
  Leaf nodes (no dependencies) are annotated with a grey right border marker.
  Dependencies with version mismatches get a colored right border."
  [{:keys [key name version leaf? version-color]} selected? select-action open-tab-action]
  [:div {:id (str "box-" key)
         :class (str "group w-full rounded-lg border-2 cursor-pointer "
                     "transition-colors duration-150 "
                     (if selected?
                       "border-blue-500 bg-blue-50 shadow-md"
                       "border-slate-300 bg-white hover:border-blue-300 hover:bg-blue-50")
                     (cond
                       ;; Version mismatch takes priority — wide colored right border
                       version-color " border-r-[10px]"
                       ;; Leaf node — wide grey right border
                       leaf? " border-r-[10px] border-r-slate-400"))
         :style (when version-color
                  (str "border-right-color: " version-color))
         :data-on:click select-action}
   ;; Flex row: text content on the left, optional open-tab button on the right.
   ;; The button is in the content flow so it aligns consistently regardless
   ;; of right border width. The group class enables group-hover on the button.
   [:div {:class (str "flex items-start gap-1 pl-4 py-2 "
                      ;; Compensate for the 8px difference between border-r-[10px]
                      ;; (annotated) and border-2 (normal) so that content width —
                      ;; and thus the open-tab button — aligns across all boxes.
                      (if (or version-color leaf?) "pr-4" "pr-6"))}
    [:div {:class "flex-1 min-w-0"}
     [:div {:class "font-semibold text-sm text-slate-800 truncate" :title name} name]
     [:div {:class "text-xs text-slate-500"} version]]
    (when open-tab-action
      [:button {:class (str "shrink-0 w-5 h-5 -mt-1.5 -mr-3 rounded-full bg-white border "
                            "border-slate-300 flex items-center justify-center shadow-sm "
                            "text-slate-400 cursor-pointer "
                            "opacity-0 group-hover:opacity-100 "
                            "hover:text-blue-500 hover:border-blue-400 transition-all")
                :title "Open in new tab"
                :data-on:click__stop open-tab-action}
       ;; Plus icon
       [:svg {:class "w-3 h-3" :viewBox "0 0 12 12" :fill "none"
              :stroke "currentColor" :stroke-width "2" :stroke-linecap "round"
              :xmlns "http://www.w3.org/2000/svg"}
        [:path {:d "M6 2v8M2 6h8"}]]])]])

(defn- render-overflow-indicator
  "Renders an 'N more above/below' indicator for windowed columns.
  Always present in the layout (to reserve stable vertical space), but
  text is transparent and clicks are disabled when there are no overflow items."
  [n direction scroll-action]
  (let [active? (pos? n)]
    [:div {:class (str "text-center text-xs py-1 "
                       (if active?
                         "text-slate-400 cursor-pointer hover:text-blue-500 transition-colors"
                         "text-transparent select-none"))
           :data-on:click (when active? scroll-action)}
     ;; Always render text content so the element keeps its intrinsic height.
     ;; Unicode arrows + "N more" when active; a non-breaking space placeholder when not.
     (if active?
       (str (case direction :up "▲ " :down "▼ ") n " more")
       "\u00a0")]))

(defn- render-column
  "Renders a column of artifact boxes with optional overflow indicators.
  The column fills its parent's height and vertically centers its boxes.
  tab-roots is a set of artifact keys that already have tabs."
  [{:keys [boxes before after]} column selected-key cursor db tab-roots]
  (let [offset-key (case column :left :left-offset :right :right-offset)]
    [:div {:class "dep-column relative flex flex-col justify-center gap-3 w-[280px] h-full"
           :data-on:wheel__prevent__throttle.150ms
           (h/action
            (let [delta (if (pos? $scroll-delta-y) 1 -1)]
              (swap! cursor update-active-view
                     (fn [view]
                       (update view offset-key
                               (scroll-offset db cursor column delta))))))}
     (render-overflow-indicator
      before :up
      (h/action (swap! cursor update-active-view
                       (fn [view]
                         (update view offset-key
                                 (scroll-offset db cursor column -1))))))
     (for [box boxes]
       (render-box box (= (:key box) selected-key)
                   (h/action (navigate! cursor (:key box)))
                   (when-not (tab-roots (:key box))
                     (h/action (open-tab! cursor db (:key box))))))
     (render-overflow-indicator
      after :down
      (h/action (swap! cursor update-active-view
                       (fn [view]
                         (update view offset-key
                                 (scroll-offset db cursor column 1))))))]))

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

;; --- Footer ---

(def ^:private category-config
  "Display configuration for each version-match category."
  {:compatible   {:label       "compatible"
                  :title       "Compatible Dependencies"
                  :text-class  "text-green-600"
                  :badge-class "badge-success"}
   :incompatible {:label       "incompatible"
                  :title       "Incompatible Dependencies"
                  :text-class  "text-red-600 font-semibold"
                  :badge-class "badge-error"}
   :unknown      {:label       "unknown"
                  :title       "Unknown Compatibility"
                  :text-class  "text-yellow-600"
                  :badge-class "badge-warning"}})

(defn- render-footer-popup
  "Renders a modal popup listing artifacts for a version-match category.
  Includes a search field to filter the list and clickable artifact names
  that navigate to the artifact and close the popup."
  [cursor db category]
  (let [{:keys [title badge-class]} (category-config category)
        search        (h/tab-cursor :footer-search "")
        search-text   (string/lower-case (str @search))
        all-artifacts (get (layout/artifacts-by-match db) category)
        artifacts     (if (seq search-text)
                        (filterv #(string/includes?
                                   (string/lower-case (:label %))
                                   search-text)
                                 all-artifacts)
                        all-artifacts)
        close-action  (h/action
                        (swap! cursor assoc :footer-popup nil)
                        (reset! search ""))]
    [:div {:class "modal modal-open"
           :data-on:keydown__window
           (str "if (evt.key !== 'Escape') return; evt.preventDefault(); "
                close-action)}
     [:div {:class "modal-box max-w-md flex flex-col max-h-[80vh]"
            :data-arrow-nav "true"}
      ;; Header with title, count badge, and close button
      [:div {:class "flex items-center justify-between mb-3"}
       [:h3 {:class "font-bold text-lg flex items-center gap-2"}
        title
        [:span {:class (str "badge badge-sm " badge-class)}
         (count all-artifacts)]]
       [:button {:class "btn btn-sm btn-circle btn-ghost"
                 :data-on:click close-action}
        "✕"]]
      ;; Search field (shown when enough items to warrant filtering)
      (when (> (count all-artifacts) 8)
        [:input {:class "input input-bordered input-sm w-full mb-3"
                 :type        "text"
                 :placeholder "Filter..."
                 :value       @search
                 :data-init   "el.focus()"
                 :data-on:input
                 (str (h/action (reset! search $value)))
                 ;; Enter navigates to the first visible match
                 :data-on:keydown
                 (when-let [first-key (:key (first artifacts))]
                   (str "if (evt.key !== 'Enter') return; evt.preventDefault(); "
                        (h/action
                          (navigate! cursor first-key)
                          (swap! cursor assoc :footer-popup nil)
                          (reset! search ""))
                        "; el.value = ''; el.blur()"))}])
      ;; Scrollable, keyboard-navigable artifact list.
      ;; Each item is focusable (tabindex) so Tab cycles through matches.
      ;; Enter or click on a focused item navigates to it.
      (let [has-search? (> (count all-artifacts) 8)]
        [:ul {:class "overflow-y-auto flex-1 min-h-0"}
         (if (seq artifacts)
           (map-indexed
            (fn [idx {:keys [key label]}]
              (let [select-action (h/action
                                    (navigate! cursor key)
                                    (swap! cursor assoc :footer-popup nil)
                                    (reset! search ""))]
                [:li {:class    "py-1.5 px-2 rounded cursor-pointer text-sm truncate
                                 transition-colors outline-none
                                 hover:bg-base-200 focus:bg-base-200 focus:ring-1 focus:ring-blue-400"
                      :tabindex "0"
                      :title    label
                      ;; Auto-focus first item when there's no search field
                      :data-init (when (and (zero? idx) (not has-search?))
                                   "el.focus()")
                      :data-on:click   (str select-action)
                      :data-on:keydown (str "if (evt.key !== 'Enter') return; evt.preventDefault(); "
                                            select-action)}
                 label]))
            artifacts)
           [:li {:class "py-4 text-center text-sm text-slate-400"}
            "No matches"])])]
     ;; Backdrop closes the popup
     [:div {:class "modal-backdrop"
            :data-on:click close-action}]]))

(def ^:private category-accel
  "Keyboard accelerator keys for each category."
  {:compatible "1" :incompatible "2" :unknown "3"})

(defn- render-footer-indicator
  "Renders a single clickable footer indicator for a version-match category.
  Also registers a keyboard accelerator (Cmd/Ctrl+1/2/3) via data-accel."
  [cursor category count]
  (let [{:keys [label text-class]} (category-config category)
        accel (category-accel category)]
    [:button {:class (str "tooltip tooltip-top " text-class
                          " hover:underline cursor-pointer bg-transparent border-none p-0")
              :data-accel         accel
              :data-preserve-attr "data-tip"
              :data-on:click (h/action (swap! cursor assoc :footer-popup category))}
     (str count " " label)]))

(defn- render-footer
  "Renders a footer bar with summary statistics about the dependency graph.
  The colored indicators are clickable and open a popup listing the matching
  artifacts."
  [cursor db]
  (let [{:keys [artifact-count dep-count compatible incompatible unknown]}
        (layout/summary-stats db)
        summary   (str artifact-count " artifacts; " dep-count " dependencies")
        popup-cat (:footer-popup @cursor)
        parts (cond-> []
                (pos? (or compatible 0))
                (conj (render-footer-indicator cursor :compatible compatible))
                (pos? (or incompatible 0))
                (conj (render-footer-indicator cursor :incompatible incompatible))
                (pos? (or unknown 0))
                (conj (render-footer-indicator cursor :unknown unknown)))]
    (list
     [:div {:class "bg-white border-t border-slate-200 px-4 py-1.5 text-xs text-slate-500 shrink-0"}
      (if (seq parts)
        (str summary " — ")
        summary)
      (for [[i part] (map-indexed vector parts)]
        (if (zero? i)
          part
          [:span " · " part]))]
     ;; Render popup when a category is selected
     (when popup-cat
       (render-footer-popup cursor db popup-cat)))))

;; --- Page Rendering ---

(defn- render-tab
  "Renders a single tab button. The ROOT tab (id 0) has no close button."
  [cursor {:keys [id label]} active?]
  [:div {:class (str "flex items-center gap-1 px-3 py-1 text-sm rounded-t border-b-2 "
                     "whitespace-nowrap cursor-pointer select-none "
                     (if active?
                       "border-blue-500 bg-blue-50 text-blue-700 font-semibold"
                       "border-transparent text-slate-500 hover:text-blue-500 hover:bg-slate-50"))
         :data-on:click (h/action (select-tab! cursor id))}
   [:span label]
   ;; Close button (not for root tab)
   (when (pos? id)
     [:button {:class (str "ml-1 w-4 h-4 rounded-full text-xs leading-none "
                           "flex items-center justify-center "
                           "hover:bg-red-100 hover:text-red-600 transition-colors")
               :title "Close tab"
               :data-on:click__stop (h/action (close-tab! cursor id))}
      "×"])])

(defn- render-toolbar
  "Renders the top toolbar with navigation controls, tabs, and artifact search."
  [cursor db]
  (let [state     @cursor
        view      (active-view state)
        tab-root  (active-tab-root state)
        selected  (:selected view)
        tabs      (:tabs state)
        active-id (:active-tab state)]
    [:div {:class "bg-white border-b border-slate-200 shadow-sm px-4 py-2 flex items-center gap-3 shrink-0"}
     ;; Home button — navigates to the root artifact of the active tab
     [:button.btn.btn-square.btn-sm.tooltip.tooltip-bottom
      {:disabled           (= selected tab-root)
       :data-accel         "h"
       :data-preserve-attr "data-tip"
       :data-on:click      (h/action
                             (let [root (active-tab-root @cursor)]
                               (navigate! cursor root)))}
      ;; Home icon (SVG)
      [:svg {:class "w-5 h-5" :viewBox "0 0 20 20" :fill "currentColor"
             :xmlns "http://www.w3.org/2000/svg"}
       [:path {:fill-rule "evenodd" :clip-rule "evenodd"
               :d         "M9.293 2.293a1 1 0 011.414 0l7 7A1 1 0 0117 11h-1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-3a1 1 0 00-1-1H9a1 1 0 00-1 1v3a1 1 0 01-1 1H5a1 1 0 01-1-1v-6H3a1 1 0 01-.707-1.707l7-7z"}]]]
     ;; Back button — per-tab navigation history
     [:button.btn.btn-square.btn-sm.tooltip.tooltip-bottom
      {:disabled           (empty? (:nav-history view))
       :data-accel         "b"
       :data-preserve-attr "data-tip"
       :data-on:click      (h/action
                             (navigate-back! cursor))}
      ;; Back arrow icon (SVG)
      [:svg {:class "w-5 h-5" :viewBox "0 0 20 20" :fill "currentColor"
             :xmlns "http://www.w3.org/2000/svg"}
       [:path {:fill-rule "evenodd" :clip-rule "evenodd"
               :d         "M17 10a.75.75 0 01-.75.75H5.612l4.158 3.96a.75.75 0 11-1.04 1.08l-5.5-5.25a.75.75 0 010-1.08l5.5-5.25a.75.75 0 111.04 1.08L5.612 9.25H16.25A.75.75 0 0117 10z"}]]]

     ;; Separator
     [:div {:class "w-px h-6 bg-slate-200"}]

     ;; Tabs — ROOT tab is fixed, others scroll horizontally
     [:div {:class "flex items-center gap-1 min-w-0 flex-1"}
      ;; ROOT tab (always visible, never scrolls)
      (render-tab cursor (first tabs) (zero? active-id))
      ;; Other tabs in a scrollable container
      (when (> (count tabs) 1)
        [:div {:class "flex items-center gap-1 overflow-x-auto min-w-0"}
         (for [tab (rest tabs)]
           (render-tab cursor tab (= (:id tab) active-id)))])]

     ;; Artifact search
     (let [search (h/tab-cursor :search "")]
       [:div.relative.shrink-0
        ;; NOTE: No accelerator tooltip for the text field because it would be active
        ;; anytime the field was focused, which is too much.
        [:input {:id             "artifact-search"
                 :class          "w-64 px-3 py-1.5 text-sm border border-slate-300 rounded-lg
                         focus:outline-none focus:ring-2 focus:ring-blue-300 focus:border-blue-400"
                 :type           "text"
                 :placeholder    "Search..."
                 :data-accel     "f"
                 :list           "artifact-list"
                 :value          @search
                 ;; Idiomorph preserves focused input values, so we need both:
                 ;; - server-side reset! to keep cursor state clean
                 ;; - client-side el.value/blur to clear the visible input immediately
                 :data-on:change (str (h/action
                                        (when-let [k (deps/find-artifact db $value)]
                                          (navigate! cursor k))
                                        (reset! search ""))
                                      "; el.value = ''; el.blur()")
                 ;; Enter: find first substring match and navigate to it
                 :data-on:keydown
                 (str "if (evt.key !== 'Enter') return; evt.preventDefault(); "
                      (h/action
                        (when-let [found (deps/find-artifact db $value)]
                          (navigate! cursor found))
                        (reset! search ""))
                      "; el.value = ''; el.blur()")}]
        ;; Datalist provides browser-native autocomplete using artifact labels
        [:datalist {:id "artifact-list"}
         (for [k (sort (deps/artifact-keys db))
               :let [label (:label (deps/artifact-info db k))]]
           [:option {:value label}])]])]))

(defn home-page [req]
  (let [db          (:db @(:hyper/app-state req))
        root-label  (:label (deps/artifact-info db 'ROOT))
        cursor      (h/tab-cursor :view
                                  {:tabs        [{:id 0 :root 'ROOT :label root-label}]
                                   :active-tab  0
                                   :next-id     1
                                   :tab-history [0]
                                   :views       {0 {:selected     'ROOT
                                                    :left-offset  0
                                                    :right-offset 0
                                                    :nav-history  []}}
                                   :hidden-libs layout/default-hidden-libs
                                   :max-visible nil
                                   :footer-popup nil})
        state       @cursor
        view        (active-view state)
        {:keys [selected left-offset right-offset]} view
        {:keys [hidden-libs max-visible]} state
        tab-roots   (tab-root-set state)
        ;; Defer layout computation until the client has reported viewport
        ;; dimensions (max-visible). The dep-viewer container must always
        ;; render so data-track-height can measure it and fire the change
        ;; event that seeds max-visible. The JS fallback constants are
        ;; conservative so the first visible render is correct.
        layout-data (when max-visible
                      (layout/compute-layout db selected left-offset right-offset
                                             hidden-libs max-visible))]
    ;; Full-viewport flex column: toolbar on top, content fills the rest
    [:div {:class "h-screen flex flex-col bg-slate-100"}
     ;; Toolbar with nav buttons, tabs, and search
     (render-toolbar cursor db)

     ;; Content area fills remaining space, centers the graph.
     ;; data-draw-arrows passes connection JSON to the client-side arrow plugin.
     [:div (cond-> {:id "dep-viewer"
                    :class "flex-1 min-h-0 relative flex justify-center gap-[120px] overflow-auto"
                    :data-track-height "true"
                    :data-on:change__debounce.300ms
                    (h/action
                     (when-let [mv (some-> $value not-empty parse-long)]
                       (when (and (pos? mv) (not= mv (:max-visible @cursor)))
                         (swap! cursor assoc :max-visible mv))))}
             layout-data
             (assoc :data-draw-arrows (connections->json (:connections layout-data)
                                                         (:id (:selected-box layout-data)))))

      (when layout-data
        (list
         ;; Empty SVG container — client-side JS populates arrow paths
         [:svg {:id "arrow-overlay"
                :class "absolute inset-0 pointer-events-none"
                :width "100%"
                :height "100%"
                :xmlns "http://www.w3.org/2000/svg"
                :data-ignore-morph true}]

         ;; Left column: dependants
         (render-column (:left layout-data) :left selected cursor db tab-roots)

         ;; Center: selected artifact
         [:div {:class "relative flex flex-col justify-center w-[280px] h-full"}
          (render-box (:selected-box layout-data) true
                      (h/action)
                      (when-not (tab-roots selected)
                        (h/action (open-tab! cursor db selected))))]

         ;; Right column: dependencies
         (render-column (:right layout-data) :right selected cursor db tab-roots)))]

     ;; Footer with summary statistics and optional category popup
     (render-footer cursor db)

     ;; Disconnect modal — invisible by default, revealed by client-side JS.
     ;; data-ignore-morph prevents Datastar's DOM morph from reverting the
     ;; visibility change after a server re-render.
     [:div {:id "modal-container" :data-ignore-morph true}
      [:div {:id "disconnect-modal" :class "modal"}
       [:div {:class "modal-box text-center"}
        [:p {:class "text-lg"} "You may close this window now."]]
       [:div {:class "modal-backdrop"}]]]]))
