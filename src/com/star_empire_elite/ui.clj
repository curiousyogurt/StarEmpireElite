(ns com.star-empire-elite.ui
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [com.star-empire-elite.settings :as settings]
            [com.biffweb :as biff]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.response :as ring-response]
            [rum.core :as rum]))

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn format-number
  "Format numbers for display with hybrid approach:
   - Small numbers (< 100K): Display as-is (e.g., 999, 1234, 99999)
   - Large numbers (≥ 100K): Use abbreviated suffixes (e.g., 123K, 1.2M, 5.7B)
   
   Returns a hiccup span with title attribute for tooltip on hover.
   Abbreviations: K (thousand), M (million), B (billion), T (trillion), Q (quadrillion)"
  [n]
  (let [formatted (cond
                    ;; If n needs a suffix       divide by the value and round                 sym
                    (>= n 1000000000000000) (str (/ (Math/round (/ n 100000000000000.0)) 10.0) "Q")
                    (>= n 1000000000000)    (str (/ (Math/round (/ n 100000000000.0))    10.0) "T")
                    (>= n 1000000000)       (str (/ (Math/round (/ n 100000000.0))       10.0) "B")
                    (>= n 1000000)          (str (/ (Math/round (/ n 100000.0))          10.0) "M")
                    (>= n 100000)           (str (/ (Math/round (/ n 100.0))             10.0) "K")
                    :else (str n))
        is-abbreviated? (>= n 100000)]
    (if is-abbreviated?
      [:span {:title (str n)} formatted] ; If Abbreviated: add tooltip with full number
      formatted)))                       ; Otherwise     : just return the string

;;; Phase progress indicator showing current position in the 6-phase turn cycle. Uses filled circles
;;; for completed phases, a highlighted circle for current phase, and empty circles for future phases.
(defn phase-indicator [current-phase]
  [:div.flex.items-center.gap-2
   ;; Phase progress circles
   (for [phase (range 1 7)]
     [:div.flex.items-center.gap-1 {:key phase}
      ;; Circle indicator with larger numbers
      [:div.w-5.h-5.rounded-full.border.border-green-400.flex.items-center.justify-center
       {:class (if (= phase current-phase) 
                 "bg-green-400 ring-2 ring-green-300"            ; Current phase only
                 "bg-transparent")}                               ; All other phases
       ;; Larger, non-bold phase number inside circle
       [:span.text-base
        {:class (if (= phase current-phase) "text-black" "text-green-400")}
        phase]]
      ;; Arrow between phases (except after last phase)
      (when (< phase 6)
        [:span.text-green-400.text-xs.ml-1 "→"])])])

;;; Complete phase header with title on the left and progress indicator on the right. Takes current 
;;; phase number and phase name string to generate the full header with progress visualization.
(defn phase-header [current-phase phase-name]
  [:div.mb-6
   ;; Mobile: Stack vertically
   [:div.flex.flex-col.gap-3.lg:hidden
    [:h2.text-xl.font-bold (str "PHASE " current-phase ": " phase-name)]
    (phase-indicator current-phase)]
   ;; Wide screen: Horizontal with space between
   [:div.hidden.lg:flex.lg:items-center.lg:justify-between
    [:h2.text-xl.font-bold (str "PHASE " current-phase ": " phase-name)]
    (phase-indicator current-phase)]])

(defn base [{:keys [::recaptcha] :as ctx} & body]
  (apply
    biff/base-html
    (-> ctx
        (merge #:base{:title settings/app-name
                      :lang "en-US"
                      :icon "/img/glider.png"
                      :description "Shape destiny. Write history. Become legend."
                      :image "https://clojure.org/images/clojure-logo-120b.png"})
        (update :base/head (fn [head]
                             (concat [[:link {:rel "stylesheet" :href (static-path "/css/main.css")}]
                                      [:script {:src (static-path "/js/main.js")}]
                                      [:script {:src "https://unpkg.com/htmx.org@2.0.7"}]
                                      [:script {:src "https://unpkg.com/htmx-ext-ws@2.0.2/ws.js"}]
                                      [:script {:src "https://unpkg.com/hyperscript.org@0.9.14"}]
                                      (when recaptcha
                                        [:script {:src "https://www.google.com/recaptcha/api.js"
                                                  :async "async" :defer "defer"}])]
                                     head))))
    body))

(defn page [ctx & body]
  (base
    ctx
    [:.flex-grow]
    ;; CORRECTED: All classes are now on one line, attached to :div
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.mx-auto.text-green-400.font-mono.p-4.border-8.border-green-400.rounded-lg.bg-black.bg-opacity-10
     (merge
       {:class "m-2 w-full sm:m-4 md:m-10 md:w-11/12"}
       (when (bound? #'csrf/*anti-forgery-token*)
         {:hx-headers (cheshire/generate-string
                        {:x-csrf-token csrf/*anti-forgery-token*})}))
     body]
    [:.flex-grow]
    [:.flex-grow]))

(defn on-error [{:keys [status ex] :as ctx}]
  {:status status
   :headers {"content-type" "text/html"}
   :body (rum/render-static-markup
           (page
             ctx
             [:h1.text-lg.font-bold
              (if (= status 404)
                "Page not found."
                "Something went wrong.")]))})

;;; Resource display grid showing player's current or projected resources. Used across all
;;; phases to display resources in a consistent format. Can highlight negative values in red.
(defn resource-display-grid
  "Display player resources in a responsive grid layout.
   
   Args:
     resources - map of resource values to display (uses either player entity or custom map)
     title - optional title for the display section
     highlight-negative? - if true, shows negative values in red (default false)"
  ([resources title]
   (resource-display-grid resources title false))
  ([resources title highlight-negative?]
   [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
    [:h3.font-bold.mb-4 title]
    [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
     [:div
      [:p.text-xs "Credits"]
      [:p.font-mono {:class (when (and highlight-negative? (< (or (:credits resources) (:player/credits resources)) 0)) "text-red-400")} 
       (format-number (or (:credits resources) (:player/credits resources)))]]
     [:div
      [:p.text-xs "Fuel"]
      [:p.font-mono {:class (when (and highlight-negative? (< (or (:fuel resources) (:player/fuel resources)) 0)) "text-red-400")} 
       (format-number (or (:fuel resources) (:player/fuel resources)))]]
     [:div
      [:p.text-xs "Galaxars"]
      [:p.font-mono (format-number (or (:galaxars resources) (:player/galaxars resources)))]]
     [:div
      [:p.text-xs "Food"]
      [:p.font-mono {:class (when (and highlight-negative? (< (or (:food resources) (:player/food resources)) 0)) "text-red-400")} 
       (format-number (or (:food resources) (:player/food resources)))]]
     [:div
      [:p.text-xs "Soldiers"]
      [:p.font-mono (format-number (or (:soldiers resources) (:player/soldiers resources)))]]
     [:div
      [:p.text-xs "Fighters"]
      [:p.font-mono (format-number (or (:fighters resources) (:player/fighters resources)))]]
     [:div
      [:p.text-xs "Stations"]
      [:p.font-mono (format-number (or (:stations resources) (:player/stations resources)))]]
     [:div
      [:p.text-xs "Agents"]
      [:p.font-mono (format-number (or (:agents resources) (:player/agents resources)))]]]]))

;;; Extended resource display grid including all unit types and planets. Used in building phase
;;; where players need to see all their assets, not just basic resources.
(defn extended-resource-display-grid
  "Display all player resources including units and planets in a responsive grid layout.
   
   Args:
     resources - map of resource values to display (uses either player entity or custom map)
     title - optional title for the display section
     highlight-negative? - if true, shows negative values in red (default false)"
  ([resources title]
   (extended-resource-display-grid resources title false))
  ([resources title highlight-negative?]
   [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
    [:h3.font-bold.mb-4 title]
    [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
     [:div
      [:p.text-xs "Credits"]
      [:p.font-mono {:class (when (and highlight-negative? (< (or (:credits resources) (:player/credits resources)) 0)) "text-red-400")} 
       (or (:credits resources) (:player/credits resources))]]
     [:div
      [:p.text-xs "Food"]
      [:p.font-mono (or (:food resources) (:player/food resources))]]
     [:div
      [:p.text-xs "Fuel"]
      [:p.font-mono (or (:fuel resources) (:player/fuel resources))]]
     [:div
      [:p.text-xs "Galaxars"]
      [:p.font-mono (or (:galaxars resources) (:player/galaxars resources))]]
     [:div
      [:p.text-xs "Soldiers"]
      [:p.font-mono (or (:soldiers resources) (:player/soldiers resources))]]
     [:div
      [:p.text-xs "Transports"]
      [:p.font-mono (or (:transports resources) (:player/transports resources))]]
     [:div
      [:p.text-xs "Generals"]
      [:p.font-mono (or (:generals resources) (:player/generals resources))]]
     [:div
      [:p.text-xs "Carriers"]
      [:p.font-mono (or (:carriers resources) (:player/carriers resources))]]
     [:div
      [:p.text-xs "Fighters"]
      [:p.font-mono (or (:fighters resources) (:player/fighters resources))]]
     [:div
      [:p.text-xs "Admirals"]
      [:p.font-mono (or (:admirals resources) (:player/admirals resources))]]
     [:div
      [:p.text-xs "Stations"]
      [:p.font-mono (or (:stations resources) (:player/stations resources))]]
     [:div
      [:p.text-xs "Cmd Ships"]
      [:p.font-mono (or (:cmd-ships resources) (:player/cmd-ships resources))]]
     [:div
      [:p.text-xs "Mil Plts"]
      [:p.font-mono (or (:mil-planets resources) (:player/mil-planets resources))]]
     [:div
      [:p.text-xs "Food Plts"]
      [:p.font-mono (or (:food-planets resources) (:player/food-planets resources))]]
     [:div
      [:p.text-xs "Ore Plts"]
      [:p.font-mono (or (:ore-planets resources) (:player/ore-planets resources))]]]]))

(defn numeric-input 
  "Creates a numeric-only input field with HTMX integration and reset button.

  Args:
  name - input field name
  value - default/initial value
  player-id - player UUID for HTMX endpoint
  hx-post-path - relative path for HTMX post (e.g. '/calculate-expenses')
  hx-include - selector string for fields to include in HTMX request
  opts - optional map:
  {:display-only? true        ; strip name and HTMX wiring (for read-only mirrors)
   :input-class   \"...\"     ; extra classes for the <input>
   :sync-key      \"soldiers\"} ; optional key for JS sync across views"
  [name value player-id hx-post-path hx-include & [{:keys [display-only? input-class sync-key]}]]
  [:div.relative
   [:input
    (cond-> {:type "text"
             :value value
             :autocomplete "off"
             :autocapitalize "off"
             :autocorrect "off"
             :spellcheck "false"
             :data-lpignore "true"
             :data-form-type "other"
             :class (str "w-full bg-black border border-green-400 text-green-400 "
                         "p-2 pr-6 font-mono "
                         (or input-class ""))
             ;; Strip non-numeric characters immediately while preserving cursor position
             :oninput (str
                        "let start=this.selectionStart;"
                        "let end=this.selectionEnd;"
                        "let oldVal=this.value;"
                        "let newVal=oldVal.replace(/[^0-9]/g,'');"
                        "if(oldVal!==newVal){"
                                             "  this.value=newVal;"
                                             "  let diff=oldVal.length-newVal.length;"
                                             "  this.setSelectionRange(start-diff,end-diff);"
                                             "}"
                        ;; Optional sync to matching proxy inputs
                        (when sync-key
                          (str "if(window.seeSyncBuildingField){"
                                                                "  window.seeSyncBuildingField('" sync-key "', this);"
                                                                "}")))
             :onblur (str
                       "let val=this.value.replace(/[^0-9]/g,'');"
                       "if(val===''||val==='0'){val='0';}"
                       "else{val=String(parseInt(val,10));}"
                       "this.value=val;")}
      ;; Only real, HTMX-enabled input gets name + hx-*
      (not display-only?)
      (merge {:name name
              :hx-post (str "/app/game/" player-id hx-post-path)
              :hx-target "#resources-after"
              :hx-swap "outerHTML"
              :hx-trigger "load, input"
              :hx-include hx-include})
      ;; JS-sync key, if provided
      sync-key (assoc :data-sync-key sync-key))]
   ;; Reset button only for non-display inputs
   (when (not display-only?)
     [:button
      {:type "button"
       :tabindex "-1"
       :class "absolute right-2 top-1/2 -translate-y-1/2 text-green-400 hover:text-green-300 transition-colors text-2xl font-bold p-0 bg-none border-none cursor-pointer"
       :onclick (str "this.previousElementSibling.value='" value "';"
                     "this.previousElementSibling.dispatchEvent(new Event('input',{bubbles:true}));")
       :title "Reset"}
      "\u25e6"])])

(defn phase-table-header
  "Renders a header row for phase tables on wide screens only.

  Args:
  columns - Vector of column definitions. Each can be either:
  - A string (simple label, left-aligned)
  - A map with :label and optional :class for custom styling"
  [columns]
  (let [col-count (count columns)
        ;; If this is the 5-column building header, bias the first column wider
        template (if (= col-count 5)
                   "1.5fr 1fr 1fr 1fr 1fr"
                   (str "repeat(" col-count ", minmax(0, 1fr))"))]
    [:div.hidden.lg:grid.lg:gap-4.lg:px-4.lg:py-2.lg:bg-green-400.lg:bg-opacity-10.lg:font-bold.border-b.border-green-400
     {:style {:grid-template-columns template}}
     (for [col columns]
       (if (string? col)
         [:div {:key col} col]
         [:div {:key (:label col) :class (:class col)} (:label col)]))]))

;;; Responsive phase row component for spreadsheet-like layout on wide screens
(defn phase-row
  "Renders a responsive row that displays as a card on mobile/tablet and as a table row on wide screens.
  This is a display-only component: it does not render interactive inputs.

  Args:
  columns - Vector of column maps. Each map can have:
  :label - Column label/header
  :value - Simple text value or Hiccup to display
  :class - Additional CSS classes for the column
  :highlight? - If true, highlights negative values in red (for numbers)
  :hide-on-mobile? - If true, hides this field on mobile (but shows on wide screen)"
  [columns]
  [:div.border-b.border-green-400.last:border-b-0

   ;; Mobile/Tablet: Vertical card layout (stacked fields)
   [:div.lg:hidden.p-4.space-y-2
    (for [{:keys [label value highlight? hide-on-mobile?]} columns]
      (when (and (some? value) (not hide-on-mobile?))
        [:div {:key (str "m-" label)}
         [:p.text-xs.text-green-300 label]
         (if (vector? value)
           value
           [:p.font-mono 
            {:class (when (and highlight? (number? value) (neg? value)) "text-red-400")}
            value])]))]

   ;; Wide screen: Horizontal row layout (spreadsheet-like)
   [:div.hidden.lg:grid.lg:gap-4.lg:items-center.lg:px-4.lg:py-3
    {:style {:grid-template-columns (str "repeat(" (count columns) ", minmax(0, 1fr))")}}
    (for [{:keys [label value class highlight?]} columns]
      [:div {:key (str "d-" label) :class class}
       (if (vector? value)
         value
         [:span.font-mono
          {:class (when (and highlight? (number? value) (neg? value)) "text-red-400")}
          value])])]])
