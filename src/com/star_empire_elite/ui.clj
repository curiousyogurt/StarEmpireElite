;;;;;
;;;;; UI - Shared Page Components and Layout
;;;;;
;;;;; Base HTML shell, page wrapper, and reusable hiccup components used across all phases. Components 
;;;;; here are display-only and have no DB access or side effects. The resource display grid, phase 
;;;;; header/indicator, numeric input, and alert polling fragment are the primary shared building 
;;;;; blocks.
;;;;;

(ns com.star-empire-elite.ui
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.star-empire-elite.settings :as settings]
            [com.biffweb :as biff]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.response :as ring-response]
            [rum.core :as rum]))

;;;;
;;;; Utilities
;;;;

(defn static-path
  "Append a cache-busting ?t= timestamp to a static asset path, derived from the file's
  last-modified time. Returns the path unchanged if the resource is not found.

  [path string] -> string"
  [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn- format-number-core
  "Returns [formatted-string abbreviated?] for a number.
  Abbreviations: K (100K+), M (1M+), B (1B+), T (1T+),
  Qd (quadrillion), Qn (quintillion), Sx (sextillion), Sp (septillion),
  Oc (octillion), No (nonillion), Dc (decillion)."
  [n]
  (let [abs-n        (abs n)
        is-negative? (< n 0)
        formatted    (cond
                       (>= abs-n 1e33) (str (/ (Math/round (/ abs-n 1e32)) 10.0) "Dc")
                       (>= abs-n 1e30) (str (/ (Math/round (/ abs-n 1e29)) 10.0) "No")
                       (>= abs-n 1e27) (str (/ (Math/round (/ abs-n 1e26)) 10.0) "Oc")
                       (>= abs-n 1e24) (str (/ (Math/round (/ abs-n 1e23)) 10.0) "Sp")
                       (>= abs-n 1e21) (str (/ (Math/round (/ abs-n 1e20)) 10.0) "Sx")
                       (>= abs-n 1e18) (str (/ (Math/round (/ abs-n 1e17)) 10.0) "Qn")
                       (>= abs-n 1e15) (str (/ (Math/round (/ abs-n 1e14)) 10.0) "Qd")
                       (>= abs-n 1e12) (str (/ (Math/round (/ abs-n 1e11)) 10.0) "T")
                       (>= abs-n 1e9)  (str (/ (Math/round (/ abs-n 1e8))  10.0) "B")
                       (>= abs-n 1e6)  (str (/ (Math/round (/ abs-n 1e5))  10.0) "M")
                       (>= abs-n 1e5)  (str (/ (Math/round (/ abs-n 1e2))  10.0) "K")
                       :else           (str n))
        s            (if (and is-negative? (>= abs-n 1e5)) (str "-" formatted) formatted)]
    [s (>= abs-n 1e5)]))

(defn format-number
  "Format a number for display. Small numbers (< 100K) are returned as-is; large numbers
  use abbreviated suffixes (123K, 1.2M, etc.) wrapped in a hiccup span with a tooltip.

  [n number] -> string | hiccup"
  [n]
  (when n
    (let [[s abbreviated?] (format-number-core n)]
      (if abbreviated? [:span {:title (str n)} s] s))))

(defn format-number-str
  "Like format-number but always returns a plain string. Use where hiccup is not appropriate,
  e.g. when building slash-separated compound display values.

  [n number] -> string"
  [n]
  (when n (first (format-number-core n))))

(defn format-scale-tick-str
  "Format a number for compact scale ticks. Abbreviates at 1K+.

  [n number] -> string"
  [n]
  (when n
    (let [r     (long (Math/round (double n)))
          abs-r (Math/abs r)]
      (cond
        (zero? r)       "0"
        (>= abs-r 1e6)  (format-number-str r)
        (>= abs-r 1e3)  (str (long (Math/round (/ (double r) 1000.0))) "K")
        :else           (str r)))))

;;;;
;;;; SVG Indicator Bar
;;;;

(def ^:private nice-scale-multipliers [1 2.5 5 7.5])

(def ^:private nice-scales
  "Nice scale ceilings for SVG resource bars."
  (vec (for [exp (range 1 35) m nice-scale-multipliers]
         (* m (Math/pow 10 exp)))))

(defn- choose-scale-max
  "Choose a nice scale ceiling for a resource bar.

  [before number, after number] -> number"
  [before after]
  (let [needed (max 1 (double before) (double after))]
    (or (first (drop-while #(< % needed) nice-scales))
        needed)))

(defn- fmt-tick
  "Format a number for a scale-bar tick label. Values below 1000 are rendered as plain
  integers; thousands as e.g. '2.5K' with trailing '.0' stripped; larger values delegate
  to format-number-str.

  [v number] -> string"
  [v]
  (let [abs-v (Math/abs (double v))
        sign  (if (neg? (double v)) "-" "")]
    (cond
      (< abs-v 1000)    (format "%.0f" (double v))
      (< abs-v 1000000) (str sign (str/replace (format "%.1f" (/ abs-v 1000.0)) #"\.0$" "") "K")
      :else             (format-number-str v))))

(defn svg-indicator-bar
  "Render an SVG arrow indicator bar with HTML tick labels below.

  direction :gain renders a right-pointing arrow showing a resource increase;
  direction :loss renders a left-pointing arrow showing a resource deduction.

  For :gain pass amount as the 'after' value; for :loss pass amount as the 'payment'.

  [direction keyword, before number, amount number, filter-id str] -> hiccup"
  [direction before amount filter-id]
  (let [after      (if (= direction :gain)
                     (double amount)
                     (max 0.0 (- (double before) (double amount))))
        scale-max  (choose-scale-max before after)
        x-before   (min (* (/ (double before) scale-max) 100.0) 100.0)
        x-after    (min (* (/ after           scale-max) 100.0) 100.0)
        has-arrow? (if (= direction :gain) (> after before) (pos? amount))
        ly         5
        arrow-w    3.5
        arrow-h    2.5]
    [:div.flex.flex-col.justify-center.h-full.px-8
     [:svg.block.w-full.overflow-visible.mt-1.mb-1
      {:viewBox "0 0 100 10" :preserveAspectRatio "none" :style {:height "8px"}}
      [:defs
       [:filter {:id filter-id :x "-50%" :y "-50%" :width "200%" :height "200%"}
        [:feGaussianBlur {:stdDeviation "0.8" :result "blur"}]
        [:feMerge
         [:feMergeNode {:in "blur"}]
         [:feMergeNode {:in "SourceGraphic"}]]]]
      [:line {:x1 0 :y1 ly :x2 100 :y2 ly :stroke "#152a1e" :stroke-width "0.8"}]
      (when has-arrow?
        (let [[tip-x base-x line-x2]
              (if (= direction :gain)
                [(- x-after arrow-w)
                 (- x-after (* 2 arrow-w))
                 (- x-after (* 2.55 arrow-w))]
                [x-after
                 (+ x-after (* 2 arrow-w))
                 (+ x-after (* 2.55 arrow-w))])]
          (list
            [:line {:x1 x-before :y1 ly :x2 line-x2 :y2 ly
                    :stroke "#4ade80" :stroke-width "1.2"
                    :filter (str "url(#" filter-id ")")}]
            [:polygon {:points (str tip-x "," ly " "
                                    base-x "," (- ly arrow-h) " "
                                    base-x "," (+ ly arrow-h))
                       :fill "#4ade80"
                       :filter (str "url(#" filter-id ")")}])))
      [:line {:x1 0   :y1 (+ ly 1) :x2 0   :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 25  :y1 (+ ly 1) :x2 25  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 50  :y1 (+ ly 1) :x2 50  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 75  :y1 (+ ly 1) :x2 75  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 100 :y1 (+ ly 1) :x2 100 :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]]
     [:div.text-xs.text-gray-400.relative.mb-2
      {:style {:height "1em"}}
      [:span.absolute.left-0 "0"]
      [:span.absolute {:style {:left "50%" :transform "translateX(-50%)"}} (fmt-tick (* 0.5 scale-max))]
      [:span.absolute.right-0 (fmt-tick scale-max)]]]))

;;;;
;;;; Phase Navigation
;;;;

(defn phase-indicator
  "Render the 6-phase turn progress indicator. Current phase is highlighted; others are plain circles.

  [current-phase int] -> hiccup"
  [current-phase]
  [:div.flex.items-center.gap-2
   (for [phase (range 1 7)]
     [:div.flex.items-center.gap-1 {:key phase}
      [:div.w-5.h-5.rounded-full.border.border-green-400.flex.items-center.justify-center
       {:class (if (= phase current-phase)
                 "bg-green-400 ring-2 ring-green-300"
                 "bg-transparent")}
       [:span.text-base
        {:class (if (= phase current-phase) "text-black" "text-green-400")}
        phase]]
      (when (< phase 6)
        [:span.text-green-400.text-xs.ml-1 "→"])])])

(defn phase-stepper
  "Render the terminal-shell topbar phase stepper. Current phase is highlighted green with active
  border; completed phases show a checkmark; future phases are muted. Used across all game phases.

  [current-phase int] -> hiccup"
  [current-phase]
  (let [labels ["1" "2" "3" "4" "5" "6"]]
    [:div.flex.items-center.gap-1
     (for [[i label] (map-indexed vector labels)
           :let [phase   (inc i)
                 active? (= phase current-phase)
                 done?   (< phase current-phase)]]
       [:div.flex.items-center.gap-1 {:key phase}
        [:div.text-xs.flex.items-center.justify-center.rounded-full
         {:style (merge {:width "22px" :height "22px"
                         :border "1.5px solid #1e6e44"}
                        (cond
                          active? {:border-color "#4ade80" :color "#4ade80" :background "#1a3a28"}
                          done?   {:border-color "#1e6e44" :background "#162a1e" :color "#4ade80"}
                          :else   {:color "#7ab88a"}))}
         (if done? "✓" label)]
        (when (< phase 6)
          [:span.text-xs {:style {:color "#7ab88a"}} "›"])])]))

(defn phase-header
  "Render the full phase header: phase name on the left, progress indicator on the right.
  Stacks vertically on mobile, horizontal on large screens. Optional info-str appears as
  small muted text beside the phase name.

  ([current-phase phase-name]) ([current-phase phase-name info-str]) -> hiccup"
  ([current-phase phase-name] (phase-header current-phase phase-name nil))
  ([current-phase phase-name info-str]
   [:div.mb-6
    [:div.flex.flex-col.gap-3.lg:hidden
     [:div.flex.items-baseline.gap-3
      [:h2.text-xl.font-bold (str phase-name " PHASE")]
      (when info-str [:span.text-xs.text-green-400.text-opacity-75 info-str])]
     (phase-indicator current-phase)]
    [:div.hidden.lg:flex.lg:items-center.lg:justify-between.lg:gap-8
     [:div.flex.items-baseline.gap-3
      [:h2.text-xl.font-bold (str phase-name " PHASE")]
      (when info-str [:span.text-xs.text-green-400.text-opacity-75 info-str])]
     (phase-indicator current-phase)]]))

;;;;
;;;; Terminal Shell Components
;;;;

(defn scanline-overlay
  "Render the absolute-positioned scanline overlay used in all terminal-shell pages.

  [] -> hiccup"
  []
  [:div.absolute.inset-0.pointer-events-none.z-10
   {:style {:background "repeating-linear-gradient(to bottom, transparent 0px, transparent 2px, rgba(0,0,0,0.07) 2px, rgba(0,0,0,0.07) 3px)"}}])

(defn phase-topbar
  "Render the terminal-shell topbar with empire name, turn/round subtitle, and phase stepper.

  [player player-map, phase-label str] -> hiccup"
  [player phase-label]
  [:div.flex.items-center.justify-between
   {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "7px 14px"}}
   [:div
    [:div.text-3xl.font-bold.text-green-400
     {:style {:letter-spacing "0.05em"}}
     (:player/empire-name player)]
    [:div.text-sm.mt-px
     {:style {:color "#9adaaa"}}
     (str phase-label " · Turn " (:player/current-turn player)
          " · Round " (:player/current-round player))]]
   (phase-stepper (:player/current-phase player))])

;;;;
;;;; Page Shell
;;;;

(defn base
  "Render the full HTML document shell with CSS, JS, HTMX, and optional reCAPTCHA script tags.

  [ctx ring-ctx & body hiccup] -> hiccup"
  [{:keys [::recaptcha] :as ctx} & body]
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

(defn page
  "Wrap body content in the standard game page shell (base HTML + centered card container).
  Automatically injects the CSRF token into hx-headers when inside a request context.

  [ctx ring-ctx & body hiccup] -> hiccup"
  [ctx & body]
  (base
    ctx
    [:.flex-grow]
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.mx-auto.text-green-400.font-mono.p-4.border-8.border-green-400.rounded-lg.bg-black.bg-opacity-10
     (merge
       {:class "m-2 w-full sm:m-4 md:m-10 md:w-11/12"}
       (when (bound? #'csrf/*anti-forgery-token*)
         {:hx-headers (cheshire/generate-string
                        {:x-csrf-token csrf/*anti-forgery-token*})}))
     body]
    [:.flex-grow]
    [:.flex-grow]))

(defn on-error
  "Render a minimal error page for 404 and 5xx responses.

  [{:keys [status ex] :as ctx}] -> ring-response"
  [{:keys [status ex] :as ctx}]
  {:status status
   :headers {"content-type" "text/html"}
   :body (rum/render-static-markup
           (page
             ctx
             [:h1.text-lg.font-bold
              (if (= status 404)
                "Page not found."
                "Something went wrong.")]))})

;;;;
;;;; Shared Phase Components
;;;;

(defn submit-button
  "Render the terminal-shell submit button. Disabled state shows muted appearance.
  extra-attrs is merged into the button element — used for hx-swap-oob in HTMX responses.

  ([affordable? bool, label str])
  ([affordable? bool, label str, extra-attrs map]) -> hiccup"
  ([affordable? label] (submit-button affordable? label {}))
  ([affordable? label extra-attrs]
   [:button#submit-button.text-sm.tracking-wider.rounded-sm
    (merge {:type "submit"
            :disabled (not affordable?)
            :style (merge {:padding "5px 14px" :font-family "'Courier New', monospace"
                           :letter-spacing "0.05em" :border-radius "2px"}
                          (if affordable?
                            {:border "1px solid #4ade80" :background "#1a3a28" :color "#4ade80"}
                            {:border "1px solid #253530" :background "transparent"
                             :color "#7ab88a" :opacity "0.5" :cursor "not-allowed"}))}
           extra-attrs)
    label]))

(defn section-label
  "Render a small uppercase section-label div used above every phase section.

  [text str] -> hiccup"
  [text]
  [:div.text-xs.uppercase.mb-1
   {:style {:letter-spacing "0.12em" :color "#7ab88a"}}
   text])

(defn- action-bar-secondary-style
  "Shared inline style map for secondary action-bar elements (links and buttons).

  [] -> map"
  []
  {:padding "5px 14px" :border "1px solid #1e6e44" :background "transparent"
   :color "#9adaaa" :border-radius "2px" :letter-spacing "0.05em"
   :font-family "'Courier New', monospace" :font-size "13px"})

(defn action-bar-link
  "Render a navigation link styled to match the terminal-shell action bar.

  [href str, label str] -> hiccup"
  [href label]
  [:a
   {:href  href
    :style (merge (action-bar-secondary-style)
                  {:text-decoration "none" :display "inline-block"})}
   label])

(defn action-bar-button
  "Render a terminal-shell action bar button (type=button, not submit).
  hs is a hyperscript string placed in the _ attribute for client-side behaviour.
  extra-attrs is merged into the button element — use it to add CSS classes (e.g. cancel-target).

  ([hs str, label str])
  ([hs str, label str, extra-attrs map]) -> hiccup"
  ([hs label] (action-bar-button hs label {}))
  ([hs label extra-attrs]
   [:button.text-sm
    (merge {:type "button"
            :_    hs
            :style (merge (action-bar-secondary-style) {:cursor "pointer"})}
           extra-attrs)
    label]))

(defn snapshot-section
  "Render the 2-row × 9-column empire snapshot grid used on the building and exchange pages.
  Row 1: Credits, Food, Fuel, Galaxars, Population, Stability, Ore Plts, Erg Plts, Mil Plts.
  Row 2: Soldiers, Transports, Generals, Fighters, Carriers, Admirals, Stations, Cmd Ships, Agents.

  [player player-map] -> hiccup"
  [player]
  (let [row1 [["CREDITS"    (:player/credits player)      nil]
               ["FOOD"       (:player/food player)        nil]
               ["FUEL"       (:player/fuel player)        nil]
               ["GALAXARS"   (:player/galaxars player)    nil]
               ["POPULATION" (:player/population player)
                #(str (str/replace (format "%.1f" (double %)) #"\.0$" "") "M")]
               ["STABILITY"  (:player/stability player)
                #(str % "%")]
               ["ORE PLTS"   (:player/ore-planets player) nil]
               ["ERG PLTS"   (:player/erg-planets player) nil]
               ["MIL PLTS"   (:player/mil-planets player) nil]]
        row2 [["SOLDIERS"   (:player/soldiers player)     nil]
               ["TRANSPORTS" (:player/transports player)  nil]
               ["GENERALS"   (:player/generals player)    nil]
               ["FIGHTERS"   (:player/fighters player)    nil]
               ["CARRIERS"   (:player/carriers player)    nil]
               ["ADMIRALS"   (:player/admirals player)    nil]
               ["STATIONS"   (:player/stations player)    nil]
               ["CMD SHIPS"  (:player/cmd-ships player)   nil]
               ["AGENTS"     (:player/agents player)      nil]]
        render-row
        (fn [items]
          [:div {:style {:display "grid" :grid-template-columns "repeat(9, 1fr)" :gap "4px"}}
           (for [[label v display-fn] items]
             [:div {:key label}
              [:div {:style {:color "#4a6a58" :letter-spacing "0.04em" :font-size "9px"
                             :text-transform "uppercase" :overflow "hidden"
                             :text-overflow "ellipsis" :white-space "nowrap"}}
               label]
              [:div.font-bold {:style {:color "#9adaaa" :font-size "13px"}}
               (if display-fn (display-fn v) (format-number v))]])])]
    [:div
     {:style {:background "#0a120d" :border "1px solid #1e3a2a"
              :border-radius "3px" :padding "7px 10px" :overflow-x "auto"}}
     [:div.flex.justify-between.items-center.mb-2
      [:span.text-xs.uppercase
       {:style {:letter-spacing "0.15em" :color "#4ade80"}}
       "Snapshot"]
      [:span.text-xs
       {:style {:color "#7ab88a" :letter-spacing "0.1em"}}
       "STATS @ T-0"]]
     [:div.flex.flex-col {:style {:gap "6px" :min-width "500px"}}
      (render-row row1)
      (render-row row2)]]))

(defn projection-pill
  "Render one projection pill card with a title, right-aligned total, and breakdown rows.

  opts may contain:
  - :total-id — element id for HTMX OOB updates on the total span
  - :signed?  — when true, prefix total with a sign and color it red when negative (default false)

  [title str, total number, rows [{:keys [label value suffix id]}], opts map] -> hiccup"
  [title total rows & [{:keys [total-id signed?]}]]
  [:div.flex.flex-col.gap-1
   {:style {:border "1px solid #253530" :border-radius "3px"
            :padding "6px 8px" :background "#1e1e1e"}}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 title]
    [:span.text-xs
     (cond-> {:style {:color (if (and signed? (neg? total)) "#f87171" "#7ab88a")}}
       total-id (assoc :id total-id))
     (if signed?
       (list (if (neg? total) "-" "+") (format-number (Math/abs (long total))))
       (format-number total))]]
   [:div {:class "flex flex-col gap-0.5"}
    (for [{:keys [label value suffix id]} rows]
      [:span.text-xs.inline-block.rounded-sm.text-green-400
       (cond-> {:style {:padding "1px 5px" :background "#1a3a28"}}
         id (assoc :id id))
       label " "
       (if (neg? value) "-" "+")
       (format-number (Math/abs (long value)))
       " " suffix])]])

(defn deduction-table-header
  "Render the Item/Before/Change/After column-label row for expense and building deduction tables.
  Emits two variants: mobile (4-col, no bar) and desktop (5-col, with bar placeholder).

  [] -> hiccup"
  []
  (let [header-style {:background "#151f1a" :border-bottom "1px solid #253530"}
        header-class "gap-4 py-1 px-2 items-center"
        col-style    {:letter-spacing "0.08em" :color "#4ade80"}
        item-style   (assoc col-style :letter-spacing "0.1em")
        label        (fn [text style extra-class]
                       [:span.text-xs.uppercase {:class extra-class :style style} text])
        r            (fn [text] (label text col-style "text-right justify-self-end"))
        c            (fn [text] (label text col-style "text-center justify-self-center"))]
    [:<>
     [:div.expense-row-mobile {:class (str "md:hidden " header-class) :style header-style}
      (label "Item" item-style nil) (r "Before") (c "Change") (r "After")]
     [:div.expense-row-desktop {:class (str "hidden md:grid " header-class) :style header-style}
      (label "Item" item-style nil) [:span] (r "Before") (c "Change") (r "After")]]))

(defn oob-pill
  "Render a signed OOB pill span for HTMX out-of-band updates.
  Prefixes value with + or - sign and appends suffix.

  [id str, label str, value number, suffix str] -> hiccup"
  [id label value suffix]
  [:span.text-xs.inline-block.rounded-sm.text-green-400
   {:id id :hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
   label " "
   (if (neg? value) "-" "+")
   (format-number (Math/abs (long value)))
   " " suffix])

;;;;
;;;; Resource Display
;;;;

;; Spec drives both the field list and labels — add an entry here to show it everywhere.
(def ^:private resource-specs
  [{:label "Credits"    :key :credits    :player-key :player/credits}
   {:label "Food"       :key :food       :player-key :player/food}
   {:label "Fuel"       :key :fuel       :player-key :player/fuel}
   {:label "Galaxars"   :key :galaxars   :player-key :player/galaxars}
   {:label "Population" :key :population :player-key :player/population :display-fn #(format-number (* % 1000000))}
   {:label "Stability"  :key :stability  :player-key :player/stability  :display-fn #(str % "%")}
   {:label "Ore Plts"   :key :ore-planets :player-key :player/ore-planets}
   {:label "Erg Plts"   :key :erg-planets :player-key :player/erg-planets}
   {:label "Mil Plts"   :key :mil-planets :player-key :player/mil-planets}
   {:label "Soldiers"   :key :soldiers   :player-key :player/soldiers}
   {:label "Transports" :key :transports :player-key :player/transports}
   {:label "Generals"   :key :generals   :player-key :player/generals}
   {:label "Fighters"   :key :fighters   :player-key :player/fighters}
   {:label "Carriers"   :key :carriers   :player-key :player/carriers}
   {:label "Admirals"   :key :admirals   :player-key :player/admirals}
   {:label "Stations"   :key :stations   :player-key :player/stations}
   {:label "Cmd Ships"  :key :cmd-ships  :player-key :player/cmd-ships}
   {:label "Agents"     :key :agents     :player-key :player/agents}])

(defn resource-display-grid
  "Display all player resources, units, and planets in a responsive grid.
  Accepts either a player entity (uses :player/credits etc.) or a plain resource map (:credits etc.).
  When highlight-negative? is true, values below zero are rendered in red.

  ([resources title]) ([resources title highlight-negative?]) -> hiccup"
  ([resources title] (resource-display-grid resources title false))
  ([resources title highlight-negative?]
   [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
    [:h3.font-bold.mb-4 title]
    [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
     (for [{:keys [label key player-key display-fn]} resource-specs
           :let [v (or (get resources key) (get resources player-key) 0)]]
       [:div {:key label}
        [:p.text-xs label]
        [:p.font-mono {:class (when (and highlight-negative? (< v 0)) "text-red-400")}
         (if display-fn (display-fn v) (format-number v))]])]]))

;;;;
;;;; Incoming Alerts
;;;;

(defn incoming-alert-content
  "Render the alert banner content when the player has unread incoming attacks or espionage failures.
  Returns nil when there are no alerts.

  [player player-map] -> hiccup | nil"
  [player]
  (let [attacks   (seq (:player/incoming-attacks player))
        esp-fails (or (:player/incoming-espionage-fails player) 0)]
    (when (or attacks (pos? esp-fails))
      [:p.mb-4.text-red-400.text-sm
       (str "\u26a0 "
            (when attacks "Your empire was attacked. ")
            (when (pos? esp-fails) "Espionage against your empire was discovered. ")
            "See details in Outcomes phase.")])))

(defn incoming-alert
  "Render the HTMX polling container that checks for new alerts every 10 seconds and
  triggers a full page refresh when new ones arrive.

  [player player-map] -> hiccup"
  [player]
  (let [has-alerts? (or (seq (:player/incoming-attacks player))
                        (pos? (or (:player/incoming-espionage-fails player) 0)))
        player-id   (:xt/id player)]
    [:div#incoming-alert
     {:hx-get     (str "/app/game/" player-id "/alerts")
      :hx-trigger "every 10s"
      :hx-swap    "innerHTML"
      :hx-include "this"}
     [:input {:type "hidden" :name "had-alerts" :value (if has-alerts? "true" "false")}]
     (incoming-alert-content player)]))

;;;;
;;;; Phase Table Components
;;;;

(defn numeric-input
  "Render a numeric-only input field with HTMX live-update integration and a reset button.
  The oninput handler strips non-numeric characters while preserving cursor position.

  Args:
  - name          — input field name
  - value         — default/initial value
  - player-id     — player UUID for HTMX endpoint construction
  - hx-post-path  — relative path for HTMX POST (e.g. \"/calculate-expenses\")
  - hx-include    — CSS selector for fields to include in the HTMX request
  - opts (optional map):
      :display-only? — strip name and HTMX wiring (for read-only mirrors)
      :mirror-of     — field name to sync value into on input; also enables HTMX without a name attr
      :input-class   — extra CSS classes for the <input>
      :sync-key      — JS key for syncing value across views

  [name value player-id hx-post-path hx-include & [opts]] -> hiccup"
  [name value player-id hx-post-path hx-include & [{:keys [display-only? mirror-of input-class input-style sync-key prefix]}]]
  [:div.relative
   (when prefix
     [:span {:class "absolute top-1/2 -translate-y-1/2 pointer-events-none select-none"
             :style {:left "6px" :color "#7ab88a" :font-size "inherit"}}
      prefix])
   [:input
    (cond->
      {:type "text"
       :value value
       :autocomplete "off"
       :autocapitalize "off"
       :autocorrect "off"
       :spellcheck "false"
       :data-lpignore "true"
       :data-form-type "other"
       :class (str "w-full bg-black border border-green-400 text-green-400 "
                   "p-2 font-mono "
                   (when prefix "pl-4 ")
                   (or input-class ""))
       :style (or input-style {})
       :oninput
       (str
         "let start=this.selectionStart;"
         "let end=this.selectionEnd;"
         "let oldVal=this.value;"
         "let newVal=oldVal.replace(/[^0-9]/g,'');"
         "if(oldVal!==newVal){"
         "  this.value=newVal;"
         "  let diff=oldVal.length-newVal.length;"
         "  this.setSelectionRange(start-diff,end-diff);"
         "}"
         (when sync-key
           (str "if(window.seeSyncBuildingField){"
                "  window.seeSyncBuildingField('" sync-key "', this);"
                "}"
                ))
         (when mirror-of
           (str "var t=document.querySelector('[name=\"" mirror-of "\"]');if(t)t.value=this.value;")))
       :onblur
       (str
         "let val=this.value.replace(/[^0-9]/g,'');"
         "if(val===''||val==='0'){val='0';}"
         "else{val=String(parseInt(val,10));}"
         "this.value=val;"
         (when mirror-of
           (str "var t=document.querySelector('[name=\"" mirror-of "\"]');if(t)t.value=this.value;")))}
      (not display-only?) (assoc :name name)
      (or (not display-only?) mirror-of)
      (merge {:hx-post (str "/app/game/" player-id hx-post-path)
              :hx-target "#resources-after"
              :hx-swap "outerHTML"
              :hx-trigger (if mirror-of "input" "load, input")
              :hx-include hx-include})
      sync-key (assoc :data-sync-key sync-key))]
   ])

(defn phase-table-header
  "Render a header row for phase tables, visible on wide screens only.
  Each column can be a plain string label or a map with :label and optional :class.

  [columns vector] -> hiccup"
  [columns]
  (let [col-count (count columns)
        template  (if (= col-count 5)
                    "1.5fr 1fr 1fr 1fr 1fr"
                    (str "repeat(" col-count ", minmax(0, 1fr))"))]
    [:div.hidden.lg:grid.lg:gap-4.lg:px-4.lg:py-2.lg:bg-green-400.lg:bg-opacity-10.lg:font-bold.border-b.border-green-400
     {:style {:grid-template-columns template}}
     (for [col columns]
       (if (string? col)
         [:div {:key col} col]
         [:div {:key (:label col) :class (:class col)} (:label col)]))]))

(defn phase-row
  "Render a responsive row that displays as a card on mobile and as a table row on wide screens.
  This is a display-only component — it does not render interactive inputs.

  Each column map can have:
  - :label          — column header / field label
  - :value          — string, number, or hiccup to display
  - :class          — additional CSS classes for the wide-screen column cell
  - :highlight?     — when true, renders negative numbers in red
  - :hide-on-mobile? — when true, omits this field from the mobile card view

  [columns vector] -> hiccup"
  [columns]
  [:div.border-b.border-green-400.last:border-b-0

   ;; Mobile/Tablet: vertical card layout
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

   ;; Wide screen: horizontal spreadsheet-style row
   [:div.hidden.lg:grid.lg:gap-4.lg:items-center.lg:px-4.lg:py-3
    {:style {:grid-template-columns (str "repeat(" (count columns) ", minmax(0, 1fr))")}}
    (for [{:keys [label value class highlight?]} columns]
      [:div {:key (str "d-" label) :class class}
       (if (vector? value)
         value
         [:span.font-mono
          {:class (when (and highlight? (number? value) (neg? value)) "text-red-400")}
          value])])]])
