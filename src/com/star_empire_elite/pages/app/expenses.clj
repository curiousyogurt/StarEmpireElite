;;;;;
;;;;; Expenses Phase - Empire Upkeep
;;;;;
;;;;; The expenses phase is the second phase of each round where players pay upkeep costs for their
;;;;; planets, military units, and population. Unlike the income phase, expenses require player
;;;;; decisions, and they can choose how much to pay for each category, with underpayment resulting
;;;;; in various penalties.
;;;;;
;;;;; This webpage uses htmx for dynamic validation, showing players in real-time whether they can
;;;;; afford their selected expense amounts before submitting.
;;;;;

(ns com.star-empire-elite.pages.app.expenses
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Calculations
;;;;

(defn calculate-required-expenses
  "Calculate required upkeep costs for all empire assets using game constants.

  [player game] -> {:planets-credits int, :planets-food int,
                    :soldiers-credits int, :soldiers-food int,
                    :fighters-credits int, :fighters-fuel int,
                    :stations-credits int, :stations-fuel int,
                    :agents-food int, :agents-fuel int,
                    :population-food int, :population-fuel int}"
  [player game]
  (let [planet-count (+ (:player/mil-planets player)
                        (:player/erg-planets player)
                        (:player/ore-planets player))]
    {:planets-credits   (* planet-count                (:game/planet-upkeep-credits  game))
     :planets-food      (* planet-count                (:game/planet-upkeep-food     game))
     :soldiers-credits  (* (:player/soldiers   player) (:game/soldier-upkeep-credits game))
     :soldiers-food     (* (:player/soldiers   player) (:game/soldier-upkeep-food    game))
     :fighters-credits  (* (:player/fighters   player) (:game/fighter-upkeep-credits game))
     :fighters-fuel     (* (:player/fighters   player) (:game/fighter-upkeep-fuel    game))
     :stations-credits  (* (:player/stations   player) (:game/station-upkeep-credits game))
     :stations-fuel     (* (:player/stations   player) (:game/station-upkeep-fuel    game))
     :agents-food       (* (:player/agents     player) (:game/agent-upkeep-food      game))
     :agents-fuel       (* (:player/agents     player) (:game/agent-upkeep-fuel      game))
     :population-food   (* (:player/population player) (:game/population-upkeep-food game))
     :population-fuel   (* (:player/population player) (:game/population-upkeep-fuel game))}))

(defn calculate-required-expense-totals
  "Aggregate per-category required expenses into per-resource totals.
  These are the minimum payments needed to avoid stability penalties.

  [required required-map] -> {:credits int, :food int, :fuel int}"
  [required]
  {:credits (+ (:planets-credits required) (:soldiers-credits required)
               (:fighters-credits required) (:stations-credits required))
   :food    (+ (:planets-food required) (:soldiers-food required)
               (:agents-food required)   (:population-food required))
   :fuel    (+ (:fighters-fuel required) (:stations-fuel required)
               (:agents-fuel required)   (:population-fuel required))})

(defn calculate-resources-after-expenses
  "Calculate player resources after deducting total expense payments.

  [player payments {:credits-pay int, :food-pay int, :fuel-pay int}] -> {:credits int, ...}"
  [player payments]
  {:credits     (- (:player/credits   player) (:credits-pay payments))
   :food        (- (:player/food      player) (:food-pay    payments))
   :fuel        (- (:player/fuel      player) (:fuel-pay    payments))
   :population  (:player/population  player)
   :stability   (:player/stability   player)
   :galaxars    (:player/galaxars    player)
   :soldiers    (:player/soldiers    player)
   :transports  (:player/transports  player)
   :generals    (:player/generals    player)
   :fighters    (:player/fighters    player)
   :carriers    (:player/carriers    player)
   :admirals    (:player/admirals    player)
   :stations    (:player/stations    player)
   :cmd-ships   (:player/cmd-ships   player)
   :agents      (:player/agents      player)
   :ore-planets (:player/ore-planets player)
   :erg-planets (:player/erg-planets player)
   :mil-planets (:player/mil-planets player)})

(defn calculate-expense-stability-penalty
  "Calculate stability penalty for underpaying expenses.
  Compares per-resource payments against required totals; sums fractional shortfalls.
  Returns 0 when all resources are fully covered or the game constant is 0.

  [required-totals {:credits int, :food int, :fuel int},
   payments {:credits-pay int, :food-pay int, :fuel-pay int},
   game game-map] -> int"
  [required-totals payments game]
  (let [pairs [[:credits :credits-pay]
               [:food    :food-pay]
               [:fuel    :fuel-pay]]
        total-fraction (reduce + (for [[req-key pay-key] pairs
                                       :let [req      (get required-totals req-key 0)
                                             paid     (get payments pay-key 0)
                                             shortfall (max 0 (- req paid))]]
                                   (double (/ shortfall (max 1 req)))))]
    (long (* total-fraction (:game/expense-stability-penalty game)))))

(defn can-afford-expenses?
  "Returns true if all spendable resources after expenses are non-negative.

  [resources-after {:credits int, :food int, :fuel int}] -> boolean"
  [resources-after]
  (and (>= (:credits resources-after) 0)
       (>= (:food resources-after)    0)
       (>= (:fuel resources-after)    0)))

(defn parse-expense-payments
  "Parse the three total expense payment inputs from request params.
  Invalid or missing values default to 0.

  [params ring-params] -> {:credits-pay int, :food-pay int, :fuel-pay int}"
  [params]
  {:credits-pay (utils/parse-numeric-input (:credits-pay params))
   :food-pay    (utils/parse-numeric-input (:food-pay    params))
   :fuel-pay    (utils/parse-numeric-input (:fuel-pay    params))})

;;;;
;;;; SVG Indicator Bar
;;;;

(def ^:private nice-scale-multipliers [1 2.5 5 7.5])

(def ^:private nice-scales
  "Nice scale ceilings for resource bars."
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
  "Format a number for a scale-bar tick label.

  [v number] -> string"
  [v]
  (let [abs-v (Math/abs (double v))
        sign  (if (neg? (double v)) "-" "")]
    (cond
      (< abs-v 1000)    (format "%.0f" (double v))
      (< abs-v 1000000) (str sign (str/replace (format "%.1f" (/ abs-v 1000.0)) #"\.0$" "") "K")
      :else             (ui/format-number-str v))))

(defn- deduction-bar
  "Render an SVG indicator bar with a left-pointing arrow showing a resource deduction.
  The arrow runs from x-before (right) toward x-after (left), tip pointing left.

  [before int, payment int, filter-id str] -> hiccup"
  [before payment filter-id]
  (let [after      (max 0.0 (- (double before) (double payment)))
        scale-max  (choose-scale-max before after)
        x-before   (min (* (/ (double before) scale-max) 100.0) 100.0)
        x-after    (min (* (/ after            scale-max) 100.0) 100.0)
        has-arrow? (pos? payment)
        ly         5
        arrow-w    3.5
        arrow-h    2.5]
    [:div.flex.flex-col.justify-center.h-full.px-8
     [:svg.block.w-full.overflow-visible.mt-1.mb-1
      {:viewBox "0 0 100 10" :preserveAspectRatio "none"
       :style {:height "8px"}}
      [:defs
       [:filter {:id filter-id :x "-50%" :y "-50%" :width "200%" :height "200%"}
        [:feGaussianBlur {:stdDeviation "0.8" :result "blur"}]
        [:feMerge
         [:feMergeNode {:in "blur"}]
         [:feMergeNode {:in "SourceGraphic"}]]]]
      ;; Track line
      [:line {:x1 0 :y1 ly :x2 100 :y2 ly :stroke "#152a1e" :stroke-width "0.8"}]
      ;; Left-pointing arrow: tip at x-after, base to the right
      (when has-arrow?
        (let [tip-x  x-after
              base-x (+ x-after (* 2 arrow-w))]
          (list
            [:line {:x1 x-before :y1 ly
                    :x2 (+ base-x (* arrow-w 0.55)) :y2 ly
                    :stroke "#4ade80" :stroke-width "1.2"
                    :filter (str "url(#" filter-id ")")}]
            [:polygon {:points (str tip-x  "," ly " "
                                    base-x "," (- ly arrow-h) " "
                                    base-x "," (+ ly arrow-h))
                       :fill "#4ade80"
                       :filter (str "url(#" filter-id ")")}])))
      ;; Tick marks
      [:line {:x1 0   :y1 (+ ly 1) :x2 0   :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 25  :y1 (+ ly 1) :x2 25  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 50  :y1 (+ ly 1) :x2 50  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 75  :y1 (+ ly 1) :x2 75  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 100 :y1 (+ ly 1) :x2 100 :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]]
     ;; Tick labels
     [:div.text-xs.text-gray-400.relative.mb-2
      {:style {:height "1em"}}
      [:span.absolute.left-0 "0"]
      [:span.absolute {:style {:left "50%" :transform "translateX(-50%)"}} (fmt-tick (* 0.5 scale-max))]
      [:span.absolute.right-0 (fmt-tick scale-max)]]]))

;;;;
;;;; UI Components
;;;;

(defn- expense-pills
  "Render expense pills for a summary card: one pill per non-zero cost.

  [expense-map {:credits? int, :food? int, :fuel? int}] -> seq of hiccup"
  [expense-map]
  (for [[k suffix] [[:credits "cr"] [:food "food"] [:fuel "fuel"]]
        :let [v (or (k expense-map) 0)]
        :when (pos? v)]
    [:span.text-xs.inline-block.rounded-sm.text-green-400
     {:key k :style {:padding "1px 5px" :background "#1a3a28"}}
     "-" (ui/format-number v) " " suffix]))

(defn- expense-summary-card
  "Render one expense summary card with category name, optional asset count, and cost pills.

  [{:keys [name count expense-map]}] -> hiccup"
  [{:keys [name count expense-map]}]
  [:div.flex.flex-col.gap-1
   {:style {:border "1px solid #253530" :border-radius "3px"
            :padding "6px 8px" :background "#1e1e1e"}}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 name]
    (when count
      [:span.text-xs {:style {:color "#7ab88a"}} (str "x" count)])]
   [:div {:class "flex flex-col gap-0.5"}
    (expense-pills expense-map)]])

(defn- expense-summary-grid
  "Render the 4-column expense summary grid: Planets, Military, Population, Total.
  Shows total costs per category as pills, mirroring income's source-grid.

  [player player-map, required required-expenses-map, required-totals map] -> hiccup"
  [player required required-totals]
  (let [planet-count (+ (:player/ore-planets player)
                        (:player/erg-planets player)
                        (:player/mil-planets player))
        mil-count    (+ (:player/soldiers player)
                        (:player/fighters player)
                        (:player/stations player)
                        (:player/agents   player))
        pop-count    (-> (format "%.1f" (double (:player/population player)))
                         (str/replace #"\.0$" "")
                         (str "M"))
        cards [{:name "Planets"
                :count planet-count
                :expense-map {:credits (:planets-credits required)
                              :food    (:planets-food required)}}
               {:name "Military"
                :count mil-count
                :expense-map {:credits (+ (:soldiers-credits required)
                                          (:fighters-credits required)
                                          (:stations-credits required))
                              :food    (+ (:soldiers-food required)
                                          (:agents-food required))
                              :fuel    (+ (:fighters-fuel required)
                                          (:stations-fuel required)
                                          (:agents-fuel required))}}
               {:name "Population"
                :count pop-count
                :expense-map {:food (:population-food required)
                              :fuel (:population-fuel required)}}
               {:name "Totals"
                :expense-map {:credits (:credits required-totals)
                              :food    (:food    required-totals)
                              :fuel    (:fuel    required-totals)}}]]
    [:div
     [:div.text-xs.uppercase.mb-1
      {:style {:letter-spacing "0.12em" :color "#7ab88a"}}
      "Expenses"]
     [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-1.5"}
      (for [card cards]
        (expense-summary-card card))]]))

(defn- expense-resource-table-header
  "Render the column-label row for the resource table.
  Emits two variants: mobile/tablet (4-col, no bar) and desktop (5-col, with bar).

  [] -> hiccup"
  []
  (let [header-style {:background "#151f1a" :border-bottom "1px solid #253530"}
        header-class "gap-4 py-1 px-2 items-center"
        col-style    {:letter-spacing "0.08em" :color "#4ade80"}
        item-style   (assoc col-style :letter-spacing "0.1em")
        col-label    (fn [text style extra-class]
                       [:span.text-xs.uppercase {:class extra-class :style style} text])
        value-label   (fn [text]
                        (col-label text col-style "text-right justify-self-end"))
        change-label  (fn []
                        (col-label "Change" col-style "text-center justify-self-center"))]
    [:<>
     [:div.expense-row-mobile {:class (str "md:hidden " header-class) :style header-style}
      (col-label "Item" item-style nil)
      (value-label "Before") (change-label) (value-label "After")]
     [:div.expense-row-desktop {:class (str "hidden md:grid " header-class) :style header-style}
      (col-label "Item" item-style nil)
      [:span]
      (value-label "Before") (change-label) (value-label "After")]]))

(defn- expense-resource-row
  "Render one resource row in two variants: mobile (no bar) and desktop (with bar).
  The bar shows a left-pointing deduction arrow from before to after.
  The after-cell contains an id'd span for HTMX OOB updates.

  [name str, before int, payment int, filter-id str, field-name str,
   player-id uuid, hx-include str] -> hiccup"
  [name before payment filter-id field-name player-id hx-include]
  (let [after        (- before payment)
        row-class    "items-center gap-2 py-1 px-3"
        row-style    {:border-bottom "1px solid #1a2820" :background "#121a18"}
        name-cell    [:div.text-base.font-bold.text-green-400 name]
        before-cell  [:div.text-base.text-right
                      {:style {:color "#7ab88a"}}
                      (ui/format-number before)]
        after-style   (fn [v] {:color (if (neg? v) "#f87171" "#4ade80") :font-weight "bold"})
        slug          (str/lower-case name)
        after-cell-m  [:div.text-base.text-right
                       [:span {:id (str "after-" slug "-m") :style (after-style after)}
                        (ui/format-number after)]]
        after-cell-d  [:div.text-base.text-right
                       [:span {:id (str "after-" slug "-d") :style (after-style after)}
                        (ui/format-number after)]]
        ;; Mobile row uses display-only input (no name, no HTMX) to avoid duplicate
        ;; field submission — both rows are always in the DOM, only CSS hides one.
        input-style   {:color "#7ab88a" :border-color "#2d6644" :padding-top "1px" :padding-bottom "1px"}
        change-cell-m [:div.justify-self-center
                       {:style {:width "min(140px, 100%)"}}
                       (ui/numeric-input field-name payment player-id
                                         "/calculate-expenses" hx-include
                                         {:input-class   "text-xs text-right"
                                          :input-style   input-style
                                          :display-only? true
                                          :mirror-of     field-name
                                          :prefix        "-"})]
        change-cell-d [:div.justify-self-center
                       {:style {:width "min(140px, 100%)"}}
                       (ui/numeric-input field-name payment player-id
                                         "/calculate-expenses" hx-include
                                         {:input-class "text-xs lg:text-sm text-right"
                                          :input-style input-style
                                          :prefix      "-"})]]
    [:<>
     [:div.expense-row-mobile
      {:class (str "md:hidden " row-class) :style row-style}
      name-cell
      before-cell
      change-cell-m
      after-cell-m]

     [:div.expense-row-desktop
      {:class (str "hidden md:grid " row-class) :style row-style}
      name-cell
      [:div {:id (str "bar-" slug)} (deduction-bar before payment filter-id)]
      before-cell
      change-cell-d
      after-cell-d]]))

(defn- submit-button
  "Render the submit button with terminal-shell styling.
  Disabled state shows muted appearance when the player cannot afford expenses.
  extra-attrs is merged into the button element — used for hx-swap-oob in HTMX responses.

  ([affordable?]) ([affordable? extra-attrs map]) -> hiccup"
  ([affordable?] (submit-button affordable? {}))
  ([affordable? extra-attrs]
   [:button#submit-button.text-sm.tracking-wider.rounded-sm
    (merge {:type "submit"
            :disabled (not affordable?)
            :style (merge {:padding "5px 14px"}
                          (if affordable?
                            {:border "1px solid #4ade80" :background "#1a3a28" :color "#4ade80"}
                            {:border "1px solid #253530" :background "transparent"
                             :color "#7ab88a" :opacity "0.5" :cursor "not-allowed"}))}
           extra-attrs)
    "Continue to Building"]))

;;;;
;;;; Actions
;;;;

(defn apply-expenses
  "Commit expense payments to the database and advance to the building phase.

  [ctx ring-ctx] -> ring-response (303 redirect to building)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 2 player-id)]
      redirect
      (let [payments        (parse-expense-payments params)
            required        (calculate-required-expenses player game)
            required-totals (calculate-required-expense-totals required)
            resources-after (calculate-resources-after-expenses player payments)
            penalty         (calculate-expense-stability-penalty required-totals payments game)]
        (biff/submit-tx ctx
                        [{:db/doc-type                       :player
                          :db/op                             :update
                          :xt/id                             player-id
                          :player/credits                    (:credits resources-after)
                          :player/food                       (:food resources-after)
                          :player/fuel                       (:fuel resources-after)
                          :player/expense-stability-penalty  penalty
                          :player/current-phase              3}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/building")}}))))

(defn calculate-expenses
  "Return HTMX out-of-band fragments updating the after-column cells, warning, and submit button
  in real time as the player adjusts expense inputs.

  [ctx ring-ctx] -> hiccup (HTMX oob fragments)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [payments        (parse-expense-payments params)
          resources-after (calculate-resources-after-expenses player payments)
          affordable?     (can-afford-expenses? resources-after)
          oob-span        (fn [id v]
                            [:span {:id id :hx-swap-oob "true"
                                    :style {:color (if (neg? v) "#f87171" "#4ade80")
                                            :font-weight "bold"}}
                             (ui/format-number v)])
          oob-bar         (fn [id before payment filter-id]
                            [:div {:id id :hx-swap-oob "true"}
                             (deduction-bar before payment filter-id)])]
      (biff/render
        [:div
         ;; Renew the HTMX swap-target placeholder for the next request
         [:div#resources-after]
         ;; OOB: after-column spans in the resource table (mobile + desktop variants)
         (oob-span "after-credits-m" (:credits resources-after 0))
         (oob-span "after-credits-d" (:credits resources-after 0))
         (oob-span "after-food-m"    (:food    resources-after 0))
         (oob-span "after-food-d"    (:food    resources-after 0))
         (oob-span "after-fuel-m"    (:fuel    resources-after 0))
         (oob-span "after-fuel-d"    (:fuel    resources-after 0))
         ;; OOB: deduction bars (desktop only)
         (oob-bar "bar-credits" (:player/credits player) (:credits-pay payments) "glow-credits")
         (oob-bar "bar-food"    (:player/food    player) (:food-pay    payments) "glow-food")
         (oob-bar "bar-fuel"    (:player/fuel    player) (:fuel-pay    payments) "glow-fuel")
         ;; OOB: insufficient-resources warning
         [:div#expense-warning.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.text-sm
             {:style {:letter-spacing "0.03em"}}
             "⚠ Insufficient resources to pay expenses."])]
         ;; OOB: submit button
         (submit-button affordable? {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn expenses-page
  "Show expense requirements and input fields for the player to choose payment amounts.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [required        (calculate-required-expenses player game)
        required-totals (calculate-required-expense-totals required)
        player-id       (:xt/id player)
        ;; Compute initial affordable state using default payments (= required totals)
        initial-after   (calculate-resources-after-expenses player {:credits-pay (:credits required-totals)
                                                                    :food-pay    (:food    required-totals)
                                                                    :fuel-pay    (:fuel    required-totals)})
        affordable?     (can-afford-expenses? initial-after)
        ;; hx-include covers all three payment inputs so every change re-evaluates all resources
        hx-include      "[name='credits-pay'],[name='food-pay'],[name='fuel-pay']"]
    (ui/page
      {}
      ;; Terminal shell
      [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
       {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
                :border-radius "4px" :color "#4ade80"
                :font-family "'Courier New', monospace"}}

       ;; Scanline overlay
       [:div.absolute.inset-0.pointer-events-none.z-10
        {:style {:background "repeating-linear-gradient(to bottom, transparent 0px, transparent 2px, rgba(0,0,0,0.07) 2px, rgba(0,0,0,0.07) 3px)"}}]

       ;; Topbar
       [:div.flex.items-center.justify-between
        {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "7px 14px"}}
        [:div
         [:div.text-3xl.font-bold.text-green-400
          {:style {:letter-spacing "0.05em"}}
          (:player/empire-name player)]
         [:div.text-sm.mt-px
          {:style {:color "#9adaaa"}}
          (str "EXPENSES PHASE · Turn " (:player/current-turn player)
               " · Round " (:player/current-round player))]]
        (ui/phase-stepper (:player/current-phase player))]

       ;; Form wraps body + action bar so submit button works
       (biff/form
         {:action (str "/app/game/" player-id "/apply-expenses") :method "post"
          :style  {:margin 0}}

         ;; Body
         [:div.flex.flex-col.gap-2
          {:style {:padding "10px 14px"}}

          ;; Expense summary: totals by category (Planets, Military, Population, Total)
          (expense-summary-grid player required required-totals)

          ;; Resource table: Credits, Food, Fuel with deduction bars and inputs
          [:div.overflow-hidden
           {:style {:border "1px solid #253530" :border-radius "3px" :background "#161616"}}
           (expense-resource-table-header)
           (expense-resource-row "Credits" (:player/credits player) (:credits required-totals)
                                 "glow-credits" "credits-pay" player-id hx-include)
           (expense-resource-row "Food"    (:player/food    player) (:food    required-totals)
                                 "glow-food"    "food-pay"    player-id hx-include)
           (expense-resource-row "Fuel"    (:player/fuel    player) (:fuel    required-totals)
                                 "glow-fuel"    "fuel-pay"    player-id hx-include)]

          ;; HTMX swap-target placeholder (invisible; OOB updates go to the after-column spans)
          [:div#resources-after {:style {:display "none"}}]

          ;; Warning message — populated by HTMX when player can't afford expenses
          [:div#expense-warning.flex.items-center]

          (ui/incoming-alert player)]

         ;; Action bar
         [:div.flex.gap-2
          {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
          [:a.text-sm.no-underline
           {:href  (str "/app/game/" player-id)
            :style {:padding "5px 14px" :border "1px solid #1e6e44" :background "transparent"
                    :color "#9adaaa" :border-radius "2px" :letter-spacing "0.05em"
                    :font-family "'Courier New', monospace"}}
           "Pause"]
          (submit-button affordable?)])])))
