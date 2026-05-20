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

  [required expenses-map] -> {:credits int, :food int, :fuel int}"
  [required]
  {:credits (+ (:planets-credits required)  (:soldiers-credits required)
               (:fighters-credits required) (:stations-credits required))
   :food    (+ (:planets-food required)     (:soldiers-food required)
               (:agents-food required)      (:population-food required))
   :fuel    (+ (:fighters-fuel required)    (:stations-fuel required)
               (:agents-fuel required)      (:population-fuel required))})

(defn calculate-resources-after-expenses
  "Calculate player resources after deducting total expense payments.

  [player payments] -> {:credits int, :food int, :fuel int}"
  [player payments]
  (-> (utils/player-snapshot player)
      (update :credits - (:credits-pay payments))
      (update :food    - (:food-pay    payments))
      (update :fuel    - (:fuel-pay    payments))))

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
                                       :let [req        (get required-totals req-key 0)
                                             paid       (get payments pay-key 0)
                                             shortfall  (max 0 (- req paid))]]
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
;;;; UI Components
;;;;

(defn build-expense-projections-data
  "Compute the projections data structure for snapshot-section's :projections opt.
  Shows required expense breakdown for Credits, Food, and Fuel.
  All rows and totals are negative, showing what is owed per category.

  [player player-map, required required-expenses-map, required-totals map] -> projection-cards"
  [required required-totals]
  [{:name "Credits"
    :total (- (:credits required-totals))
    :rows [{:label "Planets"  :value (- (:planets-credits required))}
           {:label "Military" :value (- (+ (:soldiers-credits required)
                                            (:fighters-credits required)
                                            (:stations-credits required)))}]}
   {:name "Food"
    :total (- (:food required-totals))
    :rows [{:label "Planets"    :value (- (:planets-food required))}
           {:label "Military"   :value (- (+ (:soldiers-food required)
                                              (:agents-food required)))}
           {:label "Population" :value (- (:population-food required))}]}
   {:name "Fuel"
    :total (- (:fuel required-totals))
    :rows [{:label "Military"   :value (- (+ (:fighters-fuel required)
                                              (:stations-fuel required)
                                              (:agents-fuel required)))}
           {:label "Population" :value (- (:population-fuel required))}]}])

(defn- resource-row
  "Render one resource row in two variants: mobile (no bar) and desktop (with bar).
  The bar shows a left-pointing deduction arrow from before to after.
  The after-cell contains an id'd span for HTMX OOB updates.

  [name str, before int, payment int, filter-id str, field-name str,
   player-id uuid, hx-include str] -> hiccup"
  [name before payment filter-id field-name player-id hx-include]
  (let [after        (- before payment)
        row-class    "items-center gap-2 py-1 px-3 border-b border-game-divider bg-game-row"
        name-cell    [:div.text-base.font-bold.text-green-400 name]
        before-cell  [:div.text-base.text-right.text-game-green-muted
                      (ui/format-number before)]
        after-cls     (fn [v] (str "font-bold " (if (neg? v) "text-red-400" "text-green-400")))
        slug          (str/lower-case name)
        after-cell-m  [:div.text-base.text-right
                       [:span {:id (str "after-" slug "-m") :class (after-cls after)}
                        (ui/format-number after)]]
        after-cell-d  [:div.text-base.text-right
                       [:span {:id (str "after-" slug "-d") :class (after-cls after)}
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
      {:class (str "md:hidden " row-class)}
      name-cell
      before-cell
      change-cell-m
      after-cell-m]

     [:div.expense-row-desktop
      {:class (str "hidden md:grid " row-class)}
      name-cell
      [:div {:id (str "bar-" slug)} (ui/svg-indicator-bar :loss before payment filter-id)]
      before-cell
      change-cell-d
      after-cell-d]]))

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
                                    :class (str "font-bold " (if (neg? v) "text-red-400" "text-green-400"))}
                             (ui/format-number v)])
          oob-bar         (fn [id before payment filter-id]
                            [:div {:id id :hx-swap-oob "true"}
                             (ui/svg-indicator-bar :loss before payment filter-id)])]
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
             {:class "tracking-[0.03em]"}
             "⚠ Insufficient resources to pay expenses."])]
         ;; OOB: submit button
         (ui/submit-button affordable? "Continue to Building" {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn expenses-page
  "Show expense requirements and input fields for the player to choose payment amounts.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [required         (calculate-required-expenses player game)
        required-totals  (calculate-required-expense-totals required)
        player-id        (:xt/id player)
        projections-data (build-expense-projections-data required required-totals)
        ;; Compute initial affordable state using default payments (= required totals)
        initial-after    (calculate-resources-after-expenses player {:credits-pay (:credits required-totals)
                                                                     :food-pay    (:food    required-totals)
                                                                     :fuel-pay    (:fuel    required-totals)})
        affordable?      (can-afford-expenses? initial-after)
        ;; hx-include covers all three payment inputs so every change re-evaluates all resources
        hx-include       "[name='credits-pay'],[name='food-pay'],[name='fuel-pay']"]
    (ui/page
      {}
      ;; Terminal shell
      [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative.bg-game-bg.rounded.text-green-400.font-mono
       {:class "border-[1.5px] border-game-green-border"}

       ;; Scanline overlay
       (ui/scanline-overlay)

       ;; Topbar
       (ui/phase-topbar player game "EXPENSES PHASE")

       ;; Form wraps body + action bar so submit button works
       (biff/form
         {:action (str "/app/game/" player-id "/apply-expenses") :method "post"
          :class  "m-0"}

         ;; Body
         [:div.flex.flex-col.gap-2
          {:class "py-2.5 px-3.5"}

          ;; Snapshot with expense breakdown folded in as projections
          (ui/snapshot-section player {:projections projections-data})

          ;; Resource table: Credits, Food, Fuel with deduction bars and inputs
          [:div.overflow-hidden.rounded-game.bg-game-surface
           {:class "border border-game-border"}
           (ui/deduction-table-header)
           (resource-row "Credits" (:player/credits player) (:credits required-totals)
                         "glow-credits" "credits-pay" player-id hx-include)
           (resource-row "Food"    (:player/food    player) (:food    required-totals)
                         "glow-food"    "food-pay"    player-id hx-include)
           (resource-row "Fuel"    (:player/fuel    player) (:fuel    required-totals)
                         "glow-fuel"    "fuel-pay"    player-id hx-include)]

          ;; HTMX swap-target placeholder (invisible; OOB updates go to the after-column spans)
          [:div#resources-after.hidden]

          ;; Warning message — populated by HTMX when player can't afford expenses
          [:div#expense-warning.flex.items-center]

          (ui/incoming-alert player)]

         ;; Action bar
         [:div.flex.gap-2
          {:class "py-2 px-3.5 border-t border-game-border"}
          (ui/action-bar-link (str "/app/game/" player-id) "Pause")
          (ui/action-bar-link (str "/app/game/" player-id "/exchange") "Go to Exchange")
          (ui/submit-button affordable? "Continue to Building")])])))
