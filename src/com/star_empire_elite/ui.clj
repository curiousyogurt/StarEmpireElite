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
            [com.star-empire-elite.utils :as utils]
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

(defn format-signed-number
  "Format a number with an explicit sign. Uses a typographic minus for negatives.
  Returns a string for small numbers, or a hiccup fragment for abbreviated numbers."
  [n]
  (cond
    (zero? n) "0"
    :else (let [abs-val          (Math/abs (long n))
                sign             (if (neg? n) "–" "+")
                [s abbreviated?] (format-number-core abs-val)]
            (if abbreviated?
              [:<> sign [:span {:title (str abs-val)} s]]
              (str sign s)))))

(defn format-number-str
  "Like format-number but always returns a plain string. Use where hiccup is not appropriate,
  e.g. when building slash-separated compound display values.

  [n number] -> string"
  [n]
  (when n (first (format-number-core n))))

(defn format-population
  "Format a population value stored in millions for display.
  Multiplies by 1,000,000 before abbreviating so that 6 → '6M', 1500 → '1.5B', etc.
  Trailing '.0' before the suffix letter is stripped (e.g. '6.0M' → '6M').

  [n number] -> string"
  [n]
  (when n
    (str/replace (format-number-str (* n 1000000)) #"\.0([A-Za-z])" "$1")))

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
      (for [x [0 25 50 75 100]]
        [:line {:x1 x :y1 (+ ly 1) :x2 x :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}])]
     [:div.text-xs.text-gray-400.relative.mb-2
      {:class "h-[1em]"}
      [:span.absolute.left-0 "0"]
      [:span {:class "absolute left-1/2 -translate-x-1/2"} (fmt-tick (* 0.5 scale-max))]
      [:span.absolute.right-0 (fmt-tick scale-max)]]]))

;;;;
;;;; Phase Navigation
;;;;

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
         {:class (str "w-[22px] h-[22px] border-[1.5px] "
                        (cond
                          active? "border-green-400 text-green-400 bg-game-green-deep"
                          done?   "border-game-green-border bg-game-green-done text-green-400"
                          :else   "border-game-green-border text-game-green-muted"))}
         (if done? "✓" label)]
        (when (< phase 6)
          [:span.text-xs.text-game-green-muted "›"])])]))

;;;;
;;;; Terminal Shell Components
;;;;

(defn scanline-overlay
  "Render the absolute-positioned scanline overlay used in all terminal-shell pages.

  [] -> hiccup"
  []
  [:div.absolute.inset-0.pointer-events-none.z-10
   {:class "bg-[repeating-linear-gradient(to_bottom,transparent_0px,transparent_2px,rgba(0,0,0,0.07)_2px,rgba(0,0,0,0.07)_3px)]"}])

(defn phase-topbar
  "Render the terminal-shell topbar with empire name, turn/round subtitle, and phase stepper.

  [player player-map, game game-map, phase-label str] -> hiccup"
  [player game phase-label]
  (let [{:keys [turn round]} (utils/display-turn-round player game)]
    [:div.flex.items-center.justify-between
     {:class "bg-game-surface border-b border-game-green-border py-[7px] px-3.5"}
     [:div
      [:div.text-3xl.font-bold.text-green-400
       {:class "tracking-wider"}
       (:player/empire-name player)]
      [:div.text-sm.mt-px
       {:class "text-game-green-soft"}
       (str phase-label " · Turn " turn "/" (:game/turns-per-round game)
            " · Round " round "/" (:game/rounds-per-day game))]]
     (phase-stepper (:player/current-phase player))]))

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
                      :description "Shape destiny. Write history. Become legend."
                      :image "https://clojure.org/images/clojure-logo-120b.png"})
        (update :base/head (fn [head]
                             (concat [[:link {:rel "stylesheet" :href (static-path "/css/main.css")}]
                                      [:script {:src (static-path "/js/main.js")}]
                                      [:script {:src "https://unpkg.com/htmx.org@2.0.7"}]
                                      [:script {:src "https://unpkg.com/htmx-ext-ws@2.0.2/ws.js"}]
                                      [:script {:src "https://unpkg.com/hyperscript.org@0.9.14"}]
                                      [:link {:rel "icon" :type "image/x-icon" :href "/fav/favicon.ico"}]
                                      [:link {:rel "icon" :type "image/png" :sizes "16x16" :href "/fav/favicon-16x16.png"}]
                                      [:link {:rel "icon" :type "image/png" :sizes "32x32" :href "/fav/favicon-32x32.png"}]
                                      [:link {:rel "icon" :type "image/png" :sizes "48x48" :href "/fav/favicon-48x48.png"}]
                                      [:link {:rel "apple-touch-icon" :sizes "180x180" :href "/fav/apple-touch-icon.png"}]
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
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.mx-auto.text-green-400.font-mono.p-4.rounded-lg.bg-black.bg-opacity-10
     (merge
       {:class "m-2 w-full sm:m-4 md:m-10 md:w-11/12 border border-game-green-border"}
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
   [:button#submit-button.font-mono.text-sm.tracking-wider.rounded-sm
    (merge {:type     "submit"
            :disabled (not affordable?)
            :class    (str "py-[5px] px-3.5 "
                           (if affordable?
                             "border border-green-400 bg-game-green-deep text-green-400"
                             "border border-game-border bg-transparent text-game-green-muted opacity-50 cursor-not-allowed"))}
           extra-attrs)
    label]))

(defn section-label
  "Render a small uppercase section-label div used above every phase section. An optional
  subtitle string can be appended in normal-case dim text, useful for adding clarifying
  hints (e.g. \"minimum required to avoid stability penalty\").

  [text str, subtitle? str] -> hiccup"
  [text & [subtitle]]
  [:div.text-xs.uppercase.my-1
   {:class "tracking-[0.12em] text-game-green-muted"}
   text
   (when subtitle
     [:span.normal-case.opacity-70
      {:class "ml-[10px] text-[10px] tracking-[0.04em]"}
      subtitle])])

(defn mode-badge
  "Render a colored badge pill: ACTION (red), ESPIONAGE (amber), GROWTH (green),
  STABILITY (yellow), or EVENT (gray). Used across outcomes, alerts, and news.

  [mode keyword] -> hiccup"
  [mode]
  (let [[text cls] (case mode
                     (:bomb :spy :incite :defect) ["ESPIONAGE" "border-[#fbbf24] text-[#fbbf24]"]
                     :stability                   ["STABILITY" "border-[#facc15] text-[#facc15]"]
                     :growth                      ["GROWTH"    "border-[#4ade80] text-[#4ade80]"]
                     (:breakaway :elimination
                      :event)                     ["EVENT"     "border-[#9ca3af] text-[#9ca3af]"]
                                                  ["ACTION"    "border-[#f87171] text-[#f87171]"])]
    [:span {:class (str "border px-[7px] py-[2px] text-[11px] tracking-[0.12em] " cls)}
     text]))

(defn resource-pills
  "Render resource pills for a phase summary card: one pill per non-zero value in value-map.
  sign is prepended to each value (e.g. \"+\" for income, \"-\" for expenses).
  resources is a vector of [key suffix] pairs defining which resources to check and their labels.

  [sign str, resources [[key suffix]], value-map map] -> seq of hiccup"
  [sign resources value-map]
  (for [[k suffix] resources
        :let [v (or (k value-map) 0)]
        :when (pos? v)]
    [:span.text-xs.inline-block.rounded-sm.text-green-400
     {:key k :class "py-px px-[5px] bg-game-green-deep"}
     sign (format-number v) " " suffix]))

(defn phase-info-card
  "Render a phase summary card: name header with optional count, and pills content below.
  pills is pre-rendered content, typically from resource-pills.

  [{:keys [name count]} map, pills hiccup] -> hiccup"
  [{:keys [name count]} pills]
  [:div.flex.flex-col.gap-1.rounded-game.bg-game-card
   {:class "border border-game-border py-1.5 px-2"}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 name]
    (when count [:span.text-xs.text-game-green-muted (str "x" count)])]
   [:div {:class "flex flex-col gap-0.5"}
    pills]])

(defn action-bar-link
  "Render a navigation anchor styled to match the terminal-shell action bar (secondary style).

  [href str, label str] -> hiccup"
  [href label]
  [:a.inline-block.no-underline.text-game-green-soft.font-mono.text-sm.rounded-sm.tracking-wider.border.border-game-green-border.bg-transparent
   {:href href :class "py-[5px] px-3.5"}
   label])

(defn action-bar-primary-link
  "Render a navigation anchor styled as the primary action (green fill), for use in action bars.

  [href str, label str] -> hiccup"
  [href label]
  [:a.inline-block.no-underline.text-green-400.font-mono.text-sm.rounded-sm.tracking-wider.border.border-green-400.bg-game-green-deep
   {:href href :class "py-[5px] px-3.5"}
   label])

(defn action-bar-button
  "Render a <button type='button'> styled to match the terminal-shell action bar (secondary style).
  extra-attrs is merged into the button element — used for onclick, CSS classes, etc.

  [label str, extra-attrs map] -> hiccup"
  [label extra-attrs]
  (let [base  "inline-block text-game-green-soft font-mono text-sm rounded-sm tracking-wider border border-game-green-border bg-transparent cursor-pointer py-[5px] px-3.5"
        extra (dissoc extra-attrs :class)]
    [:button
     (merge {:type "button" :class (str base (when-let [c (:class extra-attrs)] (str " " c)))}
            extra)
     label]))


(defn- snapshot-hero
  "Render the 4-cell hero strip: Credits, Ore/Erg/Mil Planets in oversized stat cells.

  [player player-map] -> hiccup"
  [player]
  [:div.grid.grid-cols-4
   {:class "border-b border-game-border bg-[linear-gradient(180deg,rgba(20,42,28,0.5),rgba(20,42,28,0.15))]"}
   (for [[label value] [["SCORE"       (format-number (:player/score       player))]
                        ["ORE PLANETS" (format-number-str (:player/ore-planets player))]
                        ["ERG PLANETS" (format-number-str (:player/erg-planets player))]
                        ["MIL PLANETS" (format-number-str (:player/mil-planets player))]]]
     [:div.flex.flex-col.border-r.border-game-divider.last:border-r-0.min-w-0
      {:class "px-[18px] pt-[14px] pb-[16px] gap-[5px]"}
      [:div.uppercase {:class "text-game-green-dim text-[9px] tracking-[0.18em]"} label]
      [:div.font-bold.leading-none.text-green-400
       {:class "text-[28px]" :style {:text-shadow "0 0 14px rgba(61,220,132,0.25)"}}
       value]])])

(defn- snapshot-manifest-item
  "Render a single label/value pair inside a manifest row.
  Zero values render dim and non-bold.

  [label str, display str|hiccup, zero? bool] -> hiccup"
  [label display zero?]
  [:span.inline-flex.items-baseline
   {:class "gap-[7px]"}
   [:span {:class "text-game-green-dim text-[10px] tracking-[0.04em]"} label]
   [:span.font-bold
    {:class (str "text-[13px] " (if zero? "text-game-green-dim font-normal" "text-green-400"))}
    display]])

(defn- snapshot-manifest-row
  "Render one manifest row: a role tag on the left and a wrapped item list on the right.
  last? suppresses the bottom dashed divider.

  [tag str, items seq, last? bool] -> hiccup"
  [tag items last?]
  [:div.flex.items-baseline
   {:class (str "gap-4 py-[5px]" (when-not last? " border-b border-dashed border-game-divider"))}
   [:div.shrink-0.uppercase
    {:class "text-[9px] tracking-[0.18em] text-game-green-mid w-[92px]"}
    (str "› " tag)]
   (into [:div.flex.flex-wrap {:class "gap-x-7 gap-y-1"}] items)])

(defn- snapshot-manifest
  "Render the manifest section: EMPIRE, GROUND, and FLEET rows.

  [player player-map] -> hiccup"
  [player]
  (let [z? (fn [k] (zero? (or (k player) 0)))]
    [:div {:class "px-4 pt-[10px] pb-3"}
     (snapshot-manifest-row "EMPIRE"
       [(snapshot-manifest-item "credits"    (format-number (:player/credits    player))      (z? :player/credits))
        (snapshot-manifest-item "food"       (format-number (:player/food       player))      (z? :player/food))
        (snapshot-manifest-item "fuel"       (format-number (:player/fuel       player))      (z? :player/fuel))
        (snapshot-manifest-item "population" (format-population (:player/population player))  false)
        (snapshot-manifest-item "stability"  (str (:player/stability player) "%")             false)]
       false)
     (snapshot-manifest-row "GROUND"
       [(snapshot-manifest-item "soldiers"   (format-number (:player/soldiers   player)) (z? :player/soldiers))
        (snapshot-manifest-item "transports" (format-number (:player/transports player)) (z? :player/transports))
        (snapshot-manifest-item "generals"   (format-number (:player/generals   player)) (z? :player/generals))
        (snapshot-manifest-item "agents"     (format-number (:player/agents     player)) (z? :player/agents))]
       false)
     (snapshot-manifest-row "FLEET"
       [(snapshot-manifest-item "fighters"  (format-number (:player/fighters  player)) (z? :player/fighters))
        (snapshot-manifest-item "carriers"  (format-number (:player/carriers  player)) (z? :player/carriers))
        (snapshot-manifest-item "admirals"  (format-number (:player/admirals  player)) (z? :player/admirals))
        (snapshot-manifest-item "stations"  (format-number (:player/stations  player)) (z? :player/stations))
        (snapshot-manifest-item "cmd ships" (format-number (:player/cmd-ships player)) (z? :player/cmd-ships))]
       true)]))

(defn snapshot-section
  "Render the empire status card: 4-cell hero and full manifest.

  opts map keys (all optional):
    :extra — pre-rendered hiccup appended inside the card after the manifest,
             wrapped in a border-top separator style.

  [player player-map, opts? map] -> hiccup"
  [player & [{:keys [extra]}]]
  [:div.relative.overflow-hidden.rounded.bg-game-bg.w-full.font-mono
   {:class "border-[1.5px] border-game-green-border"}
   (snapshot-hero player)
   (snapshot-manifest player)
   (when extra
     [:div {:class "px-4 pt-[10px] pb-[14px] border-t border-game-border bg-[rgba(20,42,28,0.10)]"}
      extra])])

(defn projection-pill
  "Render one projection pill card with a title, right-aligned total, and breakdown rows.

  opts may contain:
  - :total-id — element id for HTMX OOB updates on the total span
  - :signed?  — when true, prefix total with a sign and color it red when negative (default false)

  [title str, total number, rows [{:keys [label value suffix id]}], opts map] -> hiccup"
  [title total rows & [{:keys [total-id signed?]}]]
  [:div.flex.flex-col.gap-1.rounded-game.bg-game-card
   {:class "border border-game-border py-1.5 px-2"}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 title]
    [:span.text-xs
     (cond-> {:class (if (and signed? (neg? total)) "text-red-400" "text-game-green-muted")}
       total-id (assoc :id total-id))
     (if signed?
       (list (if (neg? total) "-" "+") (format-number (Math/abs (long total))))
       (format-number total))]]
   [:div {:class "flex flex-col gap-0.5"}
    (for [{:keys [label value suffix id]} rows]
      [:span.text-xs.inline-block.rounded-sm.text-green-400
       (cond-> {:class "py-px px-[5px] bg-game-green-deep"}
         id (assoc :id id))
       label " "
       (if (neg? value) "-" "+")
       (format-number (Math/abs (long value)))
       (when suffix (list " " suffix))])]])

(defn stat-pill
  "Render a pill showing a set of plain label/value statistics.
  Rows are maps with :label, :value, optional :display (string override for value),
  optional :highlight? (bright green), and optional :warn? (yellow).

  [title str, rows [{:keys [label value display highlight? warn?]}]] -> hiccup"
  [title rows]
  [:div.flex.flex-col.gap-1.rounded-game.bg-game-card
   {:class "border border-game-border py-1.5 px-2"}
   [:span.text-base.font-bold.text-green-400 title]
   [:div {:class "flex flex-col gap-0.5"}
    (for [{:keys [label value display highlight? warn?]} rows]
      [:div.flex.justify-between.items-baseline.rounded-sm.bg-game-green-deep.py-px
       {:class "px-[6px]"}
       [:span {:class "text-[9px] tracking-[0.04em] text-game-green-muted"} label]
       [:span.font-bold
        {:class (str "text-[10px] " (cond warn? "text-yellow-400" highlight? "text-green-400" :else "text-game-green-soft"))}
        (or display (format-number value))]])]])

(def ^:private th-col-cls  "tracking-[0.08em] text-green-400")
(def ^:private th-item-cls "tracking-widest text-green-400")

(defn- th-span
  "Render an uppercase text-xs table header span. align is an optional extra CSS class."
  [text cls align]
  [:span.text-xs.uppercase {:class (str cls (when align (str " " align)))} text])

(defn- th-r [text] (th-span text th-col-cls "text-right justify-self-end"))
(defn- th-c [text] (th-span text th-col-cls "text-center justify-self-center"))

(defn deduction-table-header
  "Render the Item/Before/Change/After column-label row for impact tables (exchange, building).
  Emits two variants: mobile (4-col, no bar) and desktop (5-col, with bar placeholder).
  Uses impact-row-* CSS classes.

  [] -> hiccup"
  []
  (let [header-class "gap-2 py-1 px-3 items-center bg-game-header border-b border-game-border"]
    [:<>
     [:div.impact-row-mobile {:class (str "md:hidden " header-class)}
      (th-span "Item" th-item-cls nil) (th-r "Before") (th-r "Change") (th-r "After")]
     [:div.impact-row-desktop {:class (str "hidden md:grid " header-class)}
      (th-span "Item" th-item-cls nil) [:span] (th-r "Before") (th-r "Change") (th-r "After")]]))

(defn impact-row
  "Render one before/change/after row for an OOB-updatable impact table.
  Emits mobile (4-col, no bar) and desktop (5-col, with bidirectional SVG bar) variants.
  Bar direction is inferred: gain when after >= before, loss otherwise.

  id-prefix is used for OOB element IDs: change-{prefix}-{slug}-{m|d},
  after-{prefix}-{slug}-{m|d}, bar-{prefix}-{slug}.

  [name str, before int, after int, id-prefix str, filter-id str] -> hiccup"
  [name before after id-prefix filter-id]
  (let [delta      (- after before)
        slug       (str/lower-case name)
        row-class  "items-center gap-2 py-1 px-3 border-b border-game-divider bg-game-row"
        name-cell  [:div.text-base.font-bold.text-green-400 name]
        before-cell [:div.text-base.text-right.text-game-green-muted
                     (format-number before)]
        bar        (if (>= after before)
                     (svg-indicator-bar :gain before after filter-id)
                     (svg-indicator-bar :loss before (- before after) filter-id))
        change-display (cond
                         (zero? delta) "0"
                         (pos? delta)  [:<> "+" (format-number delta)]
                         :else         [:<> "−" (format-number (Math/abs (long delta)))])
        change-class (str "tracking-[0.03em] "
                          (if (zero? delta) "text-game-green-muted" "text-green-400"))
        after-class  (str "font-bold " (if (neg? after) "text-red-400" "text-green-400"))
        change-cell (fn [id]
                      [:div.text-base.text-right.whitespace-nowrap
                       {:class change-class}
                       [:span {:id id} change-display]])
        after-cell  (fn [id]
                      [:div.text-base.text-right
                       [:span {:id id :class after-class}
                        (format-number after)]])]
    [:<>
     [:div.impact-row-mobile
      {:class (str "md:hidden " row-class)}
      name-cell before-cell
      (change-cell (str "change-" id-prefix "-" slug "-m"))
      (after-cell  (str "after-"  id-prefix "-" slug "-m"))]
     [:div.impact-row-desktop
      {:class (str "hidden md:grid " row-class)}
      name-cell
      [:div {:id (str "bar-" id-prefix "-" slug)} bar]
      before-cell
      (change-cell (str "change-" id-prefix "-" slug "-d"))
      (after-cell  (str "after-"  id-prefix "-" slug "-d"))]]))

(defn impact-table-header
  "Render the Item/Before/Change/After column-label row for impact tables.
  When :single-row? true, emits one row with the bar column hidden on mobile.
  Otherwise emits dual mobile/desktop variants using {prefix}-row-mobile/-desktop CSS classes.

  [row-prefix str, opts? {:single-row? bool}] -> hiccup"
  [row-prefix & [{:keys [single-row?]}]]
  (let [header-class "gap-2 py-1 px-3 items-center bg-game-header border-b border-game-border"]
    (if single-row?
      [:div {:class (str row-prefix "-row " header-class)}
       (th-span "Item" th-item-cls nil) [:span.hidden.md:block] (th-r "Before") (th-r "Change") (th-r "After")]
      [:<>
       [:div {:class (str row-prefix "-row-mobile md:hidden " header-class)}
        (th-span "Item" th-item-cls nil) (th-r "Before") (th-r "Change") (th-r "After")]
       [:div {:class (str "hidden md:grid " row-prefix "-row-desktop " header-class)}
        (th-span "Item" th-item-cls nil) [:span] (th-r "Before") (th-r "Change") (th-r "After")]])))

(defn table-group-header
  "Render a full-width subheading row inside a purchase/sell table to label a group of items.
  Spans all columns naturally (no grid layout).

  [label str] -> hiccup"
  [label]
  [:div {:class "px-3 py-[3px] bg-game-surface border-b border-game-divider"}
   [:span {:class "text-[10px] uppercase tracking-[0.14em] text-game-green-muted opacity-60"}
    label]])

(defn purchase-table-header
  "Render the column-label row for exchange and building purchase/sell tables.
  Uses the building-purchase-grid CSS class for a 5-column layout.

  opts map keys (all optional):
    :action-btn-placeholder — when truthy, adds an invisible spacer matching the width of the
                              companion button (e.g. \"max\") so the header aligns with the input's
                              right edge rather than the input+button group's right edge.

  [price-label str, action-label str, total-label str, opts? map] -> hiccup"
  [price-label action-label total-label & [{:keys [action-btn-placeholder]}]]
  [:div.building-purchase-grid
   {:class "gap-2 py-1 px-3 items-center bg-game-header border-b border-game-border"}
   (th-span "Item" th-item-cls nil)
   (th-r price-label)
   (th-r "Max")
   ;; Mimic the input column layout so the header right-aligns with the right
   ;; edge of the input box below. Uses the same centering + translate-x-4 as rows.
   ;; When action-btn-placeholder is set, an invisible button occupies the same
   ;; space as the row's companion button (e.g. "max") to match centering.
   (let [;; Invisible input that sizes the div identically to the row's input wrapper.
         ;; Uses the same classes as numeric-input's <input> element.
         sizing-input [:input.invisible.w-full.bg-black.border.border-green-400.p-2.font-mono
                       {:type "text" :tabindex "-1" :aria-hidden "true"
                        :class "text-xs lg:text-sm"
                        :style {:padding-top "1px" :padding-bottom "1px"}}]
         label-overlay [:div.absolute.inset-0.flex.items-center.justify-end
                        (th-span action-label th-col-cls nil)]]
     [:div.flex.items-center.justify-center.min-w-0
      (if action-btn-placeholder
        ;; Building: input + visible "max" button in a flex group.
        [:div.flex.items-center.gap-1.translate-x-4.min-w-0
         [:div.relative.min-w-0 {:class "w-[min(120px,100%)]"}
          sizing-input label-overlay]
         [:button.text-xs.shrink-0.border.border-game-green-border.bg-transparent.py-px.px-1.rounded-sm.cursor-pointer.whitespace-nowrap
          {:type "button" :tabindex "-1" :aria-hidden "true"
           :style {:visibility "hidden"}}
          action-btn-placeholder]]
        ;; Exchange: input alone; buttons are absolutely positioned in the row.
        [:div.relative.min-w-0 {:class "w-[min(120px,100%)] translate-x-4"}
         sizing-input label-overlay])])
   (th-r total-label)])

(defn pill-oob
  "Render a signed OOB pill span for HTMX out-of-band updates.
  Prefixes value with + or - sign and appends suffix.

  [id str, label str, value number, suffix str] -> hiccup"
  [id label value suffix]
  [:span.text-xs.inline-block.rounded-sm.text-green-400
   {:id id :hx-swap-oob "true" :class "py-px px-[5px] bg-game-green-deep"}
   label " "
   (if (neg? value) "-" "+")
   (format-number (Math/abs (long value)))
   (when suffix (list " " suffix))])

;;;;
;;;; Flash Notice
;;;;

(defn flash-notice
  "Render a one-shot notice strip when a flash message is present. The flash itself is
  cleared by take-flash in the GET handler; this component only renders. Pass nil to
  render nothing.

  Styling follows the level: warn = yellow, error = red, info = green.

  [flash {:level keyword, :message string} | nil] -> hiccup | nil"
  [flash]
  (when flash
    (let [{:keys [level message]} flash
          color (case level
                  :error "#f87171"
                  :warn  "#facc15"
                  :info  "#4ade80"
                  "#facc15")]
      [:div.mb-2.pt-3.px-3.rounded-game
       {:style {:border (str "1px solid " color)
                :background "rgba(250, 204, 21, 0.05)"}}
       [:p.text-sm.font-bold
        {:style {:color color :letter-spacing "0.03em"}}
        message]])))

;;;;
;;;; Incoming Alerts
;;;;

(defn- alert-line
  "Render one alert row: badge + text, matching the news feed's minimal style.

  [badge-mode keyword, & body hiccup] -> hiccup"
  [badge-mode & body]
  [:div.flex.items-center.gap-2.text-sm
   {:class "py-[3px] px-2 bg-game-row rounded-sm"}
   (mode-badge badge-mode)
   (into [:span.text-game-green-soft.flex-1] body)])

(defn- attack-info-line
  "Render one alert row summarizing a single incoming attack record (pr-str'd map).

  [record-str string] -> hiccup"
  [record-str]
  (let [r             (read-string record-str)
        mode          (:mode r)
        attacker      (:attacker-name r)
        attacker-wins? (:attacker-wins? r)
        verb          (case mode
                        :invade "invaded"
                        :raid   "raided"
                        :strike "launched a strike against"
                        "attacked")
        outcome       (when (not= mode :strike)
                        (if attacker-wins? " (SUCCESS)" " (FAILURE)"))]
    (alert-line mode
      [:span.text-green-300.font-bold attacker]
      (str " " verb " you" outcome))))

(defn incoming-alert-content
  "Render the alert banner summarizing incoming activity with badges matching outcomes page style.
  Returns nil when there are no alerts.

  [player player-map] -> hiccup | nil"
  [player]
  (let [attacks            (seq (:player/incoming-attacks player))
        esp-fails          (seq (:player/incoming-espionage-fails player))
        esp-agents         (or (:player/incoming-espionage-agents-gained player) 0)
        incite-stab-lost   (or (:player/incoming-incite-stability-lost player) 0)
        bomb-result        (some-> (:player/incoming-bomb-result player) read-string)
        defect-agents-lost (or (:player/incoming-defect-agents-lost player) 0)]
    (when (or attacks esp-fails (pos? incite-stab-lost) bomb-result (pos? defect-agents-lost))
      [:div.mb-4.rounded-game.border.border-game-green-border.relative
       {:class "px-3.5 pt-2.5"}
       ;; Refresh link — top-right corner
       [:a.absolute.text-xs.text-game-green-dim.underline
        {:class "top-2.5 right-3.5"
         :href  "javascript:window.location.reload()"
         :title "Reload the page to update empire stats. Any in-progress form input will be lost."}
        "\u21bb refresh"]
       ;; Heading
       [:p.font-bold.text-yellow-400.mb-2.text-sm "\u26a0 Activity since the end of your last turn"]
       ;; Event lines — minimal badge + text, matching news feed style
       [:div.flex.flex-col.gap-1
        (map attack-info-line attacks)
        (when bomb-result
          (let [total (+ (or (:soldiers bomb-result) 0)
                         (or (:transports bomb-result) 0)
                         (or (:fighters bomb-result) 0)
                         (or (:carriers bomb-result) 0))]
            (alert-line :bomb
              (str "An unknown agent bombed you " (if (pos? total) "(SUCCESS)" "(FAILURE)")))))
        (when (pos? incite-stab-lost)
          (alert-line :incite "An unknown agent incited unrest in your empire (SUCCESS)"))
        (when (pos? defect-agents-lost)
          (alert-line :defect "An unknown agent turned agents from you (SUCCESS)"))
        (when esp-fails
          (for [op esp-fails]
            (let [verb (case op
                         "spy"    "spied on"
                         "incite" "incited unrest in"
                         "bomb"   "bombed"
                         "defect" "turned agents from"
                         "attacked")]
              (alert-line (keyword op)
                (str "An unknown agent " verb " you (FAILED)")))))]
       ;; Footer
       [:p.text-game-green-dim.italic.mt-2 {:class "text-[11px]"}
        "Full details available in Outcomes phase."]])))

(defn incoming-alert
  "Render the HTMX polling container that updates the alert banner in place every 10 seconds.
  The player uses the inline refresh link to reload the page when ready.

  [player player-map] -> hiccup"
  [player]
  (let [player-id (:xt/id player)]
    [:div#incoming-alert
     {:hx-get     (str "/app/game/" player-id "/alerts")
      :hx-trigger "every 10s"
      :hx-swap    "innerHTML"}
     (incoming-alert-content player)]))

;;;;
;;;; Phase Page Layout Helpers
;;;;

(defn phase-shell
  "Render the standard phase page: outer page wrapper + terminal card div + scanline overlay +
  phase topbar. All children are rendered inside the terminal card.

  [player player-map, game game-map, phase-name str & children hiccup] -> hiccup"
  [player game phase-name & children]
  (page
    {}
    (into [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative.bg-game-bg.rounded.text-green-400.font-mono
           {:class "border-[1.5px] border-game-green-border"}
           (scanline-overlay)
           (phase-topbar player game (str/upper-case phase-name))]
          children)))

(defn phase-body
  "Render the padded body area for a phase page.
  (incoming-alert player) is always appended as the last child.

  [player player-map & children hiccup] -> hiccup"
  [player & children]
  (into [:div.flex.flex-col.gap-2
         {:class "py-2.5 px-3.5"}]
        (concat children [(incoming-alert player)])))

(defn phase-warning
  "Render a fixed-height empty placeholder between phase-body and phase-action-bar.
  Use phase-warning-div in the HTMX handler to fill it.

  [id str] -> hiccup"
  [id]
  [:div {:id id :class "flex items-center px-6 pt-2 h-5 mb-3"}])

(defn phase-warning-div
  "Render a phase-warning div for HTMX handler responses.
  Pass nil message to render an empty placeholder (clears any existing message).
  opts:
    :color — text color class, default 'text-red-400'
    :oob?  — true to add hx-swap-oob='true' (for piggybacking on a larger response)

  [id str, message str|nil, opts? map] -> hiccup"
  [id message & [{:keys [color oob?]}]]
  (let [color (or color "text-red-400")]
    (cond-> [:div {:id id :class "flex items-center px-6 pt-2 h-5 mb-3"}]
      oob?    (assoc-in [1 :hx-swap-oob] "true")
      message (conj [:p.text-sm {:class (str color " tracking-[0.03em]")} message]))))

(defn phase-action-bar
  "Render the bottom action bar for a phase page.

  [& children hiccup] -> hiccup"
  [& children]
  (into [:div.flex.gap-2
         {:class "py-2 px-3.5 border-t border-game-border"}]
        children))

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
      :input-style   — inline style map applied to the <input>
      :sync-key      — JS key for syncing value across views
      :prefix        — text displayed as an overlaid prefix inside the input (e.g. \"x\")

  [name value player-id hx-post-path hx-include & [opts]] -> hiccup"
  [name value player-id hx-post-path hx-include & [{:keys [display-only? mirror-of input-class input-style sync-key prefix]}]]
  [:div.relative
   (when prefix
     [:span {:class "absolute top-1/2 -translate-y-1/2 pointer-events-none select-none text-game-green-muted text-inherit left-[6px]"}
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

;;;
;;; Info cards
;;;
(defn info-pill
  "Render one small value pill.

  Row shape:
  {:key          optional hiccup key
   :label        optional label before the value
   :value        number
   :display      optional string — rendered as-is instead of formatting :value
   :unit         optional unit after the value
   :sign         optional literal sign, e.g. \"+\"
   :signed?      true to render +/−/0 automatically
   :warn?        true to render value in amber instead of green
   :id           optional element id (for HTMX OOB swaps)
   :hx-swap-oob  optional HTMX out-of-band swap attribute}

  Examples:
  {:value 1000 :sign \"+\" :unit \"cr\"}          -> +1000 cr
  {:label \"Planets\" :value -2000 :signed? true} -> Planets −2000
  {:label \"Limited\" :display \"Transports\"}    -> Limited Transports"
  [{:keys [key label value display unit sign signed? warn? id hx-swap-oob]}]
  [:span
   (cond-> {:key   (or key label unit)
            :class (str "text-xs inline-block rounded-sm bg-game-green-deep "
                        (if warn? "text-yellow-400" "text-green-400"))
            :style {:padding "1px 5px"}}
     id          (assoc :id id)
     hx-swap-oob (assoc :hx-swap-oob hx-swap-oob))
   (when label
     (str label " "))
   (cond
     display  display
     signed? (format-signed-number value)
     sign    [:<> sign (format-number value)]
     :else   (format-number value))
   (when unit
     (str " " unit))])

(defn proj-pill-oob
  "Render a projection pill span for HTMX out-of-band swap. Matches the style of pills
  rendered inside projection-grid: signed value with label.

  [id str, label str, value number] -> hiccup"
  [id label value]
  (info-pill {:label label :value value :signed? true :id id :hx-swap-oob "true"}))

(defn info-card
  "Render one compact card with title, right-side header value, and stacked pills.

  Card shape:
  {:key         optional hiccup key
   :title       string
   :aside       right-side header value
   :aside-class optional full class string
   :aside-id    optional element id on the aside span (for HTMX OOB swaps)
   :rows        seq of info-pill row maps}"
  [{:keys [key title aside aside-class aside-id rows]}]
  [:div
   {:key   (or key title)
    :class "flex flex-col gap-1 py-1.5 px-2 border border-game-border rounded-game bg-game-card"}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 title]
    (when aside
      [:span
       (cond-> {:class (or aside-class "text-xs text-game-green-muted")}
         aside-id (assoc :id aside-id))
       aside])]
   [:div {:class "flex flex-col gap-0.5"}
    (map info-pill rows)]])

(defn info-grid
  "Render a grid of summary cards.

  [{:keys [grid-class cards]}] -> hiccup"
  [{:keys [grid-class cards]}]
  [:div {:class grid-class}
   (map info-card cards)])

(defn projection-grid
  "Render the resource projections grid: pill cards for each resource,
  each showing a signed total and breakdown rows. Callers provide section-label separately.

  [projections seq] -> hiccup"
  [projections & _opts]
  (info-grid
    {:grid-class "grid grid-cols-3 gap-2"
     :cards
     (for [{:keys [name total total-id rows]} projections]
       {:key       name
        :title     name
        :aside     (format-signed-number total)
        :aside-class (str "text-xs "
                          (if (neg? total) "text-amber-400" "text-green-400"))
        :aside-id  total-id
        :rows      (for [{:keys [label value id]} rows]
                     {:label label :value value :signed? true :id id})})}))

;;;;
;;;; Target Table Components
;;;; Shared by action.clj (combat) and espionage.clj (covert ops) target-selection tables.
;;;;

(defn op-radio-btn
  "Render a radio button for selecting a target operation. Supports toggle-on-reclick
  (clicking an already-selected radio deselects it) and fires an HTMX warning update.

  [radio-name str, value str, label str, warning-endpoint str, warning-id str] -> hiccup"
  [radio-name value label warning-endpoint warning-id]
  [:label.block.cursor-pointer
   [:input.peer.sr-only
    {:type       "radio"
     :name       radio-name
     :value      value
     :hx-post    warning-endpoint
     :hx-trigger "click"
     :hx-target  (str "#" warning-id)
     :hx-swap    "outerHTML"
     :onclick    (str "var p=this.dataset.was==='true';"
                      "document.querySelectorAll('[name=" radio-name "]').forEach(function(r){r.dataset.was='false';});"
                      "if(p){this.checked=false;}else{this.dataset.was='true';}")}]
   [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border.transition-colors
    {:class "text-green-400 border-green-400 hover:text-yellow-400 hover:border-yellow-400 peer-checked:text-yellow-400 peer-checked:border-yellow-400 peer-checked:bg-yellow-400 peer-checked:bg-opacity-10"}
    label]])

(defn disabled-radio-btn
  "Render a disabled radio button for an unavailable operation.

  [radio-name str, label str] -> hiccup"
  [radio-name label]
  [:label
   [:input.sr-only {:type "radio" :name radio-name :disabled true}]
   [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border.text-game-green-dim.border-game-border.cursor-not-allowed
    label]])

(defn selection-warning-div
  "Render a queued-operation warning based on a composite selection value.

  [warning-id str, selected-value str, disabled-hint str|nil,
   segment-idx int, labels map, noun str] -> hiccup"
  [warning-id selected-value disabled-hint segment-idx labels noun]
  (let [label (some-> selected-value
                      utils/parse-choice-value
                      (nth segment-idx nil)
                      labels)]
    (phase-warning-div
      warning-id
      (if label
        (str "\u24d8 " label " " noun " queued for Outcomes phase.")
        disabled-hint)
      {:color (if label "text-yellow-400" "text-game-green-soft")})))

(defn target-info-tds
  "Render the Empire name, Planets, and Score cells for a target table row.
  Returns a vector of 3 [:td ...] elements.

  [player player-map] -> [[:td ...] [:td ...] [:td ...]]"
  [player]
  (let [td-cls       "border-r border-game-border py-1 px-3 text-game-green-soft"
        td-right-cls "border-r border-game-border py-1 px-3 text-game-green-soft text-right"]
    [[:td {:class td-cls}       (:player/empire-name player)]
     [:td {:class td-right-cls} (format-number (utils/total-planets player))]
     [:td {:class td-right-cls} (format-number (:player/score player))]]))

(defn target-operation-row
  "Render a target table row using shared empire/planet/score cells plus operation cells.

  [player player-map, operations seq-of-hiccup, cell-class str] -> hiccup"
  [player operations cell-class]
  (into [:tr.bg-game-row.border-b.border-game-divider]
        (concat
          (target-info-tds player)
          (for [op operations]
            [:td {:class cell-class} op]))))
