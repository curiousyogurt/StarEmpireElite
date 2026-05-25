;;;;;
;;;;; Expenses Phase - Empire Upkeep
;;;;;
;;;;; The expenses phase is the second phase of each round where players pay upkeep costs for their
;;;;; planets, military units, and population. Unlike the income phase, expenses require player
;;;;; decisions, and they can choose how much to pay for each category, with underpayment resulting
;;;;; in various penalties.  As a result this page (and those in other phases) need to be able to 
;;;;; accept input, and should be interactive.
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

  [player player-map, game game-map] -> {:planets-credits int,   :planets-food int,
                                         :soldiers-credits int,  :soldiers-food int,
                                         :fighters-credits int,  :fighters-fuel int,
                                         :stations-credits int,  :stations-fuel int,
                                         :agents-food int,       :agents-fuel int,
                                         :population-food int,   :population-fuel int}"
  [player game]
  (let [planet-count (+ (:player/mil-planets player)
                        (:player/erg-planets player)
                        (:player/ore-planets player))]
    ;; Keys such as :player/soldiers are fully qualified keys in the player map.
    ;; That is, there is a key in the player map named :player/soldiers, etc.
    ;;                   (* asset-count-for-player     upkeep-cost-per-asset-in-game)
    {:planets-credits  (* planet-count                (:game/planet-upkeep-credits  game))
     :planets-food     (* planet-count                (:game/planet-upkeep-food     game))
     :soldiers-credits (* (:player/soldiers   player) (:game/soldier-upkeep-credits game))
     :soldiers-food    (* (:player/soldiers   player) (:game/soldier-upkeep-food    game))
     :fighters-credits (* (:player/fighters   player) (:game/fighter-upkeep-credits game))
     :fighters-fuel    (* (:player/fighters   player) (:game/fighter-upkeep-fuel    game))
     :stations-credits (* (:player/stations   player) (:game/station-upkeep-credits game))
     :stations-fuel    (* (:player/stations   player) (:game/station-upkeep-fuel    game))
     :agents-food      (* (:player/agents     player) (:game/agent-upkeep-food      game))
     :agents-fuel      (* (:player/agents     player) (:game/agent-upkeep-fuel      game))
     :population-food  (* (:player/population player) (:game/population-upkeep-food game))
     :population-fuel  (* (:player/population player) (:game/population-upkeep-fuel game))}))

(defn calculate-required-expense-totals
  "Aggregate per-category required expenses into per-resource totals.
  These are the minimum payments needed to avoid stability penalties.

  [required expenses-map] -> {:credits int, :food int, :fuel int}"
  [required]
  {:credits (+ (:planets-credits  required) (:soldiers-credits required)
               (:fighters-credits required) (:stations-credits required))
   :food    (+ (:planets-food     required) (:soldiers-food    required)
               (:agents-food      required) (:population-food  required))
   :fuel    (+ (:fighters-fuel    required) (:stations-fuel    required)
               (:agents-fuel      required) (:population-fuel  required))})

(defn calculate-resources-after-expenses
  "Calculate player resources after deducting total expense payments.

  [player player-map, payments payments-map] -> {:credits int, :food int, :fuel int}"
  [player payments]
  (-> (utils/player-snapshot player)
      (update :credits - (:credits-pay payments))
      (update :food    - (:food-pay    payments))
      (update :fuel    - (:fuel-pay    payments))))

(defn calculate-expense-stability-penalty
  "Calculate stability penalty for underpaying expenses.
  Compares per-resource payments against required totals; sums fractional shortfalls
  across the three resources (so the total fraction can range from 0 to 3, not 0 to 1).
  Returns 0 when all resources are fully covered or the game constant is 0.

  [required-totals totals-map, payments payments-map, game game-map] -> int"
  [required-totals payments game]
  (let [pairs          [[:credits :credits-pay]
                        [:food    :food-pay]
                        [:fuel    :fuel-pay]]
        total-fraction (reduce + (for [[required-key pay-key] pairs
                                       :let [required  (get required-totals required-key 0)
                                             paid      (get payments pay-key 0)
                                             shortfall (max 0 (- required paid))]]
                                   (double (/ shortfall (max 1 required)))))]
    (long (* total-fraction (:game/expense-stability-penalty game))))) ; Round down

(defn can-afford-expenses?
  "Returns true if all spendable resources after expenses are non-negative.

  [resources-after resources-map] -> boolean"
  [resources-after]
  (and (>= (:credits resources-after) 0)
       (>= (:food    resources-after) 0)
       (>= (:fuel    resources-after) 0)))

(defn- expenses-affordable-by-default?
  "Returns true if the player can afford their required expenses when paying the full required
  amount on each resource. Used by expenses-page to set the initial state of the submit button
  before the player has made any input adjustments.

  [player player-map, required-totals totals-map] -> boolean"
  [player required-totals]
  (can-afford-expenses?
    (calculate-resources-after-expenses
      player
      {:credits-pay (:credits required-totals)
       :food-pay    (:food    required-totals)
       :fuel-pay    (:fuel    required-totals)})))

(defn parse-expense-payments
  "Parse the three total expense payment inputs from request params.
  Invalid or missing values default to 0.

  [params ring-params] -> {:credits-pay int, :food-pay int, :fuel-pay int}"
  [params]
  {:credits-pay (utils/parse-numeric-input (:credits-pay params))
   :food-pay    (utils/parse-numeric-input (:food-pay    params))
   :fuel-pay    (utils/parse-numeric-input (:fuel-pay    params))})


;;;;
;;;; View Models
;;;;

(defn build-expense-obligations-data
  "Compute the obligations data structure consumed by obligations-grid.
  Shows the required expense breakdown for Credits, Food, and Fuel.
  All rows and totals are negative, showing what the empire owes per category.

  [required expenses-map, required-totals totals-map] -> seq of obligation-card-maps"
  [required required-totals]
  [{:name "Credits"    :total (- (:credits required-totals))
    :rows [{:label "Planets"  :value (- (:planets-credits required))}
           {:label "Military" :value (- (+ (:soldiers-credits required)
                                            (:fighters-credits required)
                                            (:stations-credits required)))}]}
   {:name "Food"       :total (- (:food required-totals))
    :rows [{:label "Planets"    :value (- (:planets-food required))}
           {:label "Military"   :value (- (+ (:soldiers-food required)
                                              (:agents-food required)))}
           {:label "Population" :value (- (:population-food required))}]}
   {:name "Fuel"       :total (- (:fuel required-totals))
    :rows [{:label "Military"   :value (- (+ (:fighters-fuel required)
                                              (:stations-fuel required)
                                              (:agents-fuel required)))}
           {:label "Population" :value (- (:population-fuel required))}]}])

;;;;
;;;; UI Components
;;;;

;;;
;;; Expense table definitions:
;;; - expense-table-row: a single editable row with HTMX input
;;; - expense-table-header: the column-label row
;;; - expense-table: put it all together
;;;

(defn- expense-table-row
  "Render one expense row for the expense table.
  Emits two variants: a 4-column mobile row (no bar column) and a 5-column
  desktop row (with the SVG indicator bar), aligned with expense-table-header.
  The change column contains a numeric input with HTMX live-update integration.
  The after column contains id'd spans for HTMX OOB updates.

  [name str, before int, payment int, filter-id str, field-name str,
   player-id uuid, hx-include str] -> hiccup"
  [name before payment filter-id field-name player-id hx-include]
  (let [after       (- before payment)
        slug        (str/lower-case name)
        row-class   "items-center gap-2 py-1 px-3 border-b border-game-divider bg-game-row"
        name-cell   [:div.text-base.font-bold.text-green-400 name]
        before-cell [:div.text-base.text-right.text-game-green-muted
                     (ui/format-number before)]
        after-class (str "font-bold " (if (neg? after) "text-red-400" "text-green-400"))
        after-cell-m [:div.text-base.text-right
                      [:span {:id (str "after-" slug "-m") :class after-class}
                       (ui/format-number after)]]
        after-cell-d [:div.text-base.text-right
                      [:span {:id (str "after-" slug "-d") :class after-class}
                       (ui/format-number after)]]
        ;; Mobile row uses display-only input (no name, no HTMX) to avoid duplicate
        ;; field submission — both rows are always in the DOM, only CSS hides one.
        input-style  {:color "#7ab88a" :border-color "#2d6644"
                      :padding-top "1px" :padding-bottom "1px"}
        change-cell-m [:div.justify-self-end
                       {:class "w-[min(140px,100%)]"}
                       (ui/numeric-input field-name payment player-id
                                         "/calculate-expenses" hx-include
                                         {:input-class   "text-xs text-right"
                                          :input-style   input-style
                                          :display-only? true
                                          :mirror-of     field-name
                                          :prefix        "-"})]
        change-cell-d [:div.justify-self-end
                       {:class "w-[min(140px,100%)]"}
                       (ui/numeric-input field-name payment player-id
                                         "/calculate-expenses" hx-include
                                         {:input-class "text-xs lg:text-sm text-right"
                                          :input-style input-style
                                          :prefix      "-"})]]
    [:<>

     ;; Mobile
     [:div.expense-row-mobile
      {:class (str "md:hidden " row-class)}
      name-cell before-cell change-cell-m after-cell-m]

     ;; Desktop
     [:div.expense-row-desktop
      {:class (str "hidden md:grid " row-class)}
      name-cell
      [:div {:id (str "bar-" slug)} (ui/svg-indicator-bar :loss before payment filter-id)]
      before-cell change-cell-d after-cell-d]]))

(defn- expense-table-header
  "Render the column-label row for the expense table.
  Emits two variants: a 4-column mobile row (no bar column) and a 5-column
  desktop row with an empty cell aligning the header grid to the data-row grid,
  which uses that column for the SVG indicator bar.
  Before, Change, and After headers are all right-justified.

  [] -> hiccup"
  []
  (let [header-class    "gap-2 py-1 px-3 items-center bg-game-header border-b border-game-border"
        col-class       "tracking-[0.08em] text-green-400"
        item-class      "tracking-[0.1em] text-green-400"
        col-label       (fn [text base-cls extra-cls]
                          [:span.text-xs.uppercase
                           {:class (str base-cls (when extra-cls (str " " extra-cls)))}
                           text])
        value-label     (fn [text]
                          (col-label text col-class "text-right justify-self-end"))
        ;; Empty cell aligning the header grid with the data-row grid,
        ;; which uses this column for the SVG indicator bar (see expense-table-row).
        bar-placeholder [:span]]
    [:<>

     ;; Mobile
     [:div.expense-row-mobile
      {:class (str "md:hidden " header-class)}
      (col-label "Item" item-class nil)
      (value-label "before")
      (value-label "change")
      (value-label "after")]

     ;; Desktop
     [:div.expense-row-desktop
      {:class (str "hidden md:grid " header-class)}
      (col-label "Item" item-class nil)
      bar-placeholder
      (value-label "before")
      (value-label "change")
      (value-label "after")]]))

(defn- expense-table
  "Render the Impact table: three editable rows where the player chooses credits/food/fuel
  payments against required totals.

  [player player-map, required-totals totals-map, player-id uuid, hx-include str] -> hiccup"
  [player required-totals player-id hx-include]
  [:div
   (ui/section-label "Impact")
   [:div.overflow-hidden.border.border-game-border.rounded-game.bg-game-surface
    (expense-table-header)
    (expense-table-row "Credits" (:player/credits player) (:credits required-totals)
                       "glow-credits" "credits-pay" player-id hx-include)
    (expense-table-row "Food"    (:player/food    player) (:food    required-totals)
                       "glow-food"    "food-pay"    player-id hx-include)
    (expense-table-row "Fuel"    (:player/fuel    player) (:fuel    required-totals)
                       "glow-fuel"    "fuel-pay"    player-id hx-include)]])

;;;
;;; Obligations grid
;;;

(defn- obligation-pill
  "Render one obligation pill: a small rounded badge with label and signed value.
  Styled to match income's resource-pill (same size, padding, colours).

  [{:keys [label value]}] -> hiccup"
  [{:keys [label value]}]
  [:span.text-xs.inline-block.rounded-sm.text-green-400.bg-game-green-deep
   {:key   label
    :style {:padding "1px 5px"}}
   label " "
   (cond
     (neg? value)  (list "−" (ui/format-number (Math/abs (long value))))
     (zero? value) "0"
     :else         (list "+" (ui/format-number value)))])

(defn- obligation-card
  "Render one obligation card: bordered container with title + signed total header and a
  stack of obligation pills showing the breakdown by source. Styled to match income's
  resource-card (same shell, title, header layout, body gap); negative totals display
  in amber, everything else in the same muted green as income's count.

  [{:keys [name total rows]}] -> hiccup"
  [{:keys [name total rows]}]
  [:div.flex.flex-col.gap-1.border.border-game-border.rounded-game.bg-game-card
   {:key   name
    :style {:padding "6px 8px"}}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 name]
    [:span.text-xs {:class (if (neg? total) "text-amber-400" "text-game-green-muted")}
     (ui/format-number total)]]
   [:div {:class "flex flex-col gap-0.5"}
    (map obligation-pill rows)]])

(defn- obligations-grid
  "Render the empire obligations grid: one obligation card per resource (Credits, Food,
  Fuel), showing the minimum payments required this turn and the breakdown by source.
  Underpayment triggers a stability penalty (see calculate-expense-stability-penalty).

  Standalone body element; renders its own section label.

  [obligations seq of obligation-card-maps] -> hiccup"
  [obligations]
  [:div
   (ui/section-label "Obligations" "minimum required to avoid stability penalty")
   [:div.grid.grid-cols-3
    {:class "gap-2"}
    (map obligation-card obligations)]])

;;;;
;;;; Actions
;;;;

(defn apply-expenses
  "Commit expense payments to the database and advance to the building phase.
  If expenses are no longer affordable against current player state (e.g. resources lost to an
  incoming attack between page load and submit), redirects back to the expenses page without writing.

  [ctx ring-ctx] -> ring-response (303 redirect to building or expenses)"
  [{:keys [path-params params biff/db session] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 2 player-id)]
      redirect
      (let [payments        (parse-expense-payments params)
            required        (calculate-required-expenses player game)
            required-totals (calculate-required-expense-totals required)
            resources-after (calculate-resources-after-expenses player payments)
            penalty         (calculate-expense-stability-penalty required-totals payments game)]
        (if-not (can-afford-expenses? resources-after)
          {:status  303
           :headers {"location" (str "/app/game/" player-id "/expenses")}
           :session (utils/flash session :warn
                      "Submission rejected due to a change in your empire (enemy attack or espionage). Please review and resubmit.")}
          (do
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
             :headers {"location" (str "/app/game/" player-id "/building")}}))))))

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
         (ui/phase-warning-div "expense-warning"
           (when (not affordable?) "⚠ Insufficient resources to pay expenses.")
           {:oob? true})
         ;; OOB: submit button
         (ui/submit-button affordable? "Continue to Building" {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn expenses-page
  "Show expense requirements and input fields for the player to choose payment amounts.

  [{:keys [player game flash]}] -> hiccup"
  [{:keys [player game flash]}]
  (let [required         (calculate-required-expenses player game)
        required-totals  (calculate-required-expense-totals required)
        player-id        (:xt/id player)
        obligations-data (build-expense-obligations-data required required-totals)
        affordable?      (expenses-affordable-by-default? player required-totals)
        ;; hx-include covers all three payment inputs so every change re-evaluates all resources
        hx-include       "[name='credits-pay'],[name='food-pay'],[name='fuel-pay']"]
    (ui/phase-shell player game "EXPENSES PHASE"
      (biff/form
        {:action (str "/app/game/" player-id "/apply-expenses") :method "post"
         :class  "m-0"}
        (ui/phase-body player
          (ui/flash-notice flash)
          (ui/snapshot-section player)
          (obligations-grid obligations-data)
          (expense-table player required-totals player-id hx-include)
          ;; HTMX OOB swap target: renewed each request by calculate-expenses
          [:div#resources-after.hidden])
        (ui/phase-warning "expense-warning")
        (ui/phase-action-bar
          (ui/action-bar-link (str "/app/game/" player-id) "Pause")
          (ui/action-bar-link (str "/app/game/" player-id "/exchange") "Go to Exchange")
          (ui/submit-button affordable? "Continue to Building"))))))

