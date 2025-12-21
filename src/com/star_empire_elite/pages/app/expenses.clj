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
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

;;;;
;;;; Calculations
;;;;

;;; Calculate required upkeep costs for all empire assets using game constants
(defn calculate-required-expenses 
  "Calculate total required upkeep costs based on player's assets and game constants.
  Returns map with expense categories and their costs."
  [player game]
  (let [planet-count (+ (:player/military-planets player)
                        (:player/food-planets player)
                        (:player/ore-planets player))]
    {:planets-credits   (* planet-count (:game/planet-upkeep-credits game))
     :planets-food      planet-count
     :soldiers-credits  (Math/round (/ (* (:player/soldiers player) 
                                          (:game/soldier-upkeep-credits game)) 
                                       1000.0))
     :soldiers-food     (Math/round (/ (* (:player/soldiers player) 
                                          (:game/soldier-upkeep-food game)) 
                                       1000.0))
     :fighters-credits  (* (:player/fighters player) 
                           (:game/fighter-upkeep-credits game))
     :fighters-fuel     (* (:player/fighters player) 
                           (:game/fighter-upkeep-fuel game))
     :stations-credits  (* (:player/stations player) 
                           (:game/station-upkeep-credits game))
     :stations-fuel     (* (:player/stations player) 
                           (:game/station-upkeep-fuel game))
     :agents-credits    (* (:player/agents player) 
                           (:game/agent-upkeep-credits game))
     :agents-food       (Math/round (/ (* (:player/agents player) 
                                          (:game/agent-upkeep-food game)) 
                                       1000.0))
     :population-credit (Math/round (/ (* (:player/population player) 
                                          (:game/population-upkeep-credits game)) 
                                       1000.0))
     :population-food   (Math/round (/ (* (:player/population player) 
                                          (:game/population-upkeep-food game)) 
                                       1000.0))}))

;;; Calculate resources after paying specified expenses
(defn calculate-resources-after-expenses 
  "Calculate player resources after deducting expense payments.
  Returns map of {:credits :food :fuel} after expenses."
  [player payments]
  {:credits (- (:player/credits player)
               (:planets-pay payments)
               (:soldiers-credits payments)
               (:fighters-credits payments)
               (:stations-credits payments)
               (:agents-credits payments)
               (:population-credits payments))
   :food    (- (:player/food player)
               (:planets-food payments)
               (:soldiers-food payments)
               (:agents-food payments)
               (:population-food payments))
   :fuel    (- (:player/fuel player)
               (:fighters-fuel payments)
               (:stations-fuel payments))})

;;; Check if player can afford the expenses
(defn can-afford-expenses? 
  "Returns true if all resource values are non-negative"
  [resources-after]
  (and (>= (:credits resources-after) 0)
       (>= (:food resources-after) 0)
       (>= (:fuel resources-after) 0)))

;;;;
;;;; Actions
;;;;
;;;; There are three parts to the actions for this phase: (i) expenses-page, which shows the
;;;; costs and input fields, (ii) calculate-expenses which provides htmx dynamic updates as the user 
;;;; changes values, and (iii) apply-expenses which commits the payments to the database and advances 
;;;; to the next phase.
;;;;

;;; Applies expense payments and advances to the next phase. Uses calculations to determine resource 
;;; changes, then commits them in a single transaction.
(defn apply-expenses [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Phase validation prevents players from applying expenses multiple times or out of order
    (if-let [redirect (utils/validate-phase player 2 player-id)]
      redirect
      ;; Parse all expense payment inputs using shared utility
      (let [payments {:planets-pay         (utils/parse-numeric-input (:planets-pay params))
                      :planets-food        (utils/parse-numeric-input (:planets-food params))
                      :soldiers-credits    (utils/parse-numeric-input (:soldiers-credits params))
                      :soldiers-food       (utils/parse-numeric-input (:soldiers-food params))
                      :fighters-credits    (utils/parse-numeric-input (:fighters-credits params))
                      :fighters-fuel       (utils/parse-numeric-input (:fighters-fuel params))
                      :stations-credits    (utils/parse-numeric-input (:stations-credits params))
                      :stations-fuel       (utils/parse-numeric-input (:stations-fuel params))
                      :agents-credits      (utils/parse-numeric-input (:agents-credits params))
                      :agents-food         (utils/parse-numeric-input (:agents-food params))
                      :population-credits  (utils/parse-numeric-input (:population-credits params))
                      :population-food     (utils/parse-numeric-input (:population-food params))}

            ;; Calculate the final resource values
            resources-after (calculate-resources-after-expenses player payments)]

        ;; Single atomic transaction updates resources and advances phase together
        ;; This prevents partial updates if something fails midway through
        (biff/submit-tx ctx
                        [{:db/doc-type :player
                          :db/op :update
                          :xt/id player-id
                          :player/credits (:credits resources-after)
                          :player/food (:food resources-after)
                          :player/fuel (:fuel resources-after)
                          :player/current-phase 3}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/building")}}))))

;;; Provides htmx dynamic updates showing resources after expenses as user changes input values.
;;; This gives immediate feedback on whether the player can afford their selected expenses.
(defn calculate-expenses [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Parse expense payment inputs using shared utility
    (let [payments {:planets-pay         (utils/parse-numeric-input (:planets-pay params))
                    :planets-food        (utils/parse-numeric-input (:planets-food params))
                    :soldiers-credits    (utils/parse-numeric-input (:soldiers-credits params))
                    :soldiers-food       (utils/parse-numeric-input (:soldiers-food params))
                    :fighters-credits    (utils/parse-numeric-input (:fighters-credits params))
                    :fighters-fuel       (utils/parse-numeric-input (:fighters-fuel params))
                    :stations-credits    (utils/parse-numeric-input (:stations-credits params))
                    :stations-fuel       (utils/parse-numeric-input (:stations-fuel params))
                    :agents-credits      (utils/parse-numeric-input (:agents-credits params))
                    :agents-food         (utils/parse-numeric-input (:agents-food params))
                    :population-credits  (utils/parse-numeric-input (:population-credits params))
                    :population-food     (utils/parse-numeric-input (:population-food params))}

          ;; Calculate resulting resources and whether affordable
          resources-after (calculate-resources-after-expenses player payments)
          affordable? (can-afford-expenses? resources-after)]

      ;; Render htmx response fragments that replace specific page elements
      (biff/render
        [:div
         ;; Resources display with red highlighting for negative values; use shared component
         [:div#resources-after
          (ui/resource-display-grid 
            (assoc resources-after 
                   :galaxars (:player/galaxars player)
                   :soldiers (:player/soldiers player)
                   :fighters (:player/fighters player)
                   :stations (:player/stations player)
                   :agents (:player/agents player))
            "Resources After Expenses"
            true)]  ; Enable negative highlighting

         ;; Warning message if expenses exceed available resources
         [:div#expense-warning.h-8.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.font-bold "WARNING: Insufficient resources to pay expenses!"])]

         ;; Submit button - disabled if player can't afford expenses
         [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
          {:type "submit"
           :disabled (not affordable?)
           :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"
           :hx-swap-oob "true"}
          "Continue to Building"]]))))

;;; Helper function for expense input fields - delegates to shared numeric-input component
(defn expense-input [name value player-id hx-include]
  (ui/numeric-input name value player-id "/calculate-expenses" hx-include))

;;; Shows expense requirements and input fields for player to choose how much to pay
(defn expenses-page [{:keys [player game]}]
  (let [required (calculate-required-expenses player game)

        player-id (:xt/id player)
        hx-include "[name='planets-pay'],[name='planets-food'],[name='soldiers-credits'],[name='soldiers-food'],[name='fighters-credits'],[name='fighters-fuel'],[name='stations-credits'],[name='stations-fuel'],[name='agents-credits'],[name='agents-food'],[name='population-credits'],[name='population-food']"]
    (ui/page
      {}
      [:div.text-green-400.font-mono
       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "EXPENSES")

       ;;; Current Resources Display - using shared component
       (ui/resource-display-grid player "Resources Before Expenses")

       ;;; Expense Input Form:
       ;;; Player chooses how much to pay for each category. Using htmx provides real-time feedback on 
       ;;; whether they can afford the total expenses.
       (biff/form
         {:action (str "/app/game/" player-id "/apply-expenses")
          :method "post"}

         [:h3.font-bold.mb-4 "Expenses This Round"]
         [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8

          ;; Planets upkeep - costs credits and food
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Planets Upkeep"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Planets: " (+ (:player/military-planets player)
                                        (:player/food-planets player)
                                        (:player/ore-planets player))]]
            [:div
             [:p.text-xs "Cost per Planet"]
             [:p.font-mono (str (:game/planet-upkeep-credits game) " credits, " 
                                (:game/planet-upkeep-food game) " food")]]
            [:div
             [:p.text-xs "Total Required"]
             [:p.font-mono (str (:planets-credits required) " credits, " 
                                (:planets-food required) " food")]]
            [:div
             [:label.text-xs "Pay Credits"]
             (expense-input "planets-pay" (:planets-credits required) player-id hx-include)]
            [:div
             [:label.text-xs "Pay Food"]
             (expense-input "planets-food" (:planets-food required) player-id hx-include)]]]

          ;; Soldiers upkeep - costs credits and food per 1000 soldiers
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Soldiers Upkeep"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Soldiers: " (:player/soldiers player)]]
            [:div
             [:p.text-xs "Cost per 1000"]
             [:p.font-mono (str (:game/soldier-upkeep-credits game) " credit, " 
                                (:game/soldier-upkeep-food game) " food")]]
            [:div
             [:p.text-xs "Total Required"]
             [:p.font-mono (str (:soldiers-credits required) " credits, " 
                                (:soldiers-food required) " food")]]
            [:div
             [:label.text-xs "Pay Credits"]
             (expense-input "soldiers-credits" (:soldiers-credits required) player-id hx-include)]
            [:div
             [:label.text-xs "Pay Food"]
             (expense-input "soldiers-food" (:soldiers-food required) player-id hx-include)]]]

          ;; Fighters upkeep - costs credits and fuel per fighter
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Fighters Upkeep"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Fighters: " (:player/fighters player)]]
            [:div
             [:p.text-xs "Cost per Fighter"]
             [:p.font-mono (str (:game/fighter-upkeep-credits game) " credits, " 
                                (:game/fighter-upkeep-fuel game) " fuel")]]
            [:div
             [:p.text-xs "Total Required"]
             [:p.font-mono (str (:fighters-credits required) " credits, " 
                                (:fighters-fuel required) " fuel")]]
            [:div
             [:label.text-xs "Pay Credits"]
             (expense-input "fighters-credits" (:fighters-credits required) player-id hx-include)]
            [:div
             [:label.text-xs "Pay Fuel"]
             (expense-input "fighters-fuel" (:fighters-fuel required) player-id hx-include)]]]

          ;; Defence stations upkeep - costs credits and fuel per station
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Defence Stations Upkeep"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Stations: " (:player/stations player)]]
            [:div
             [:p.text-xs "Cost per Station"]
             [:p.font-mono (str (:game/station-upkeep-credits game) " credits, " 
                                (:game/station-upkeep-fuel game) " fuel")]]
            [:div
             [:p.text-xs "Total Required"]
             [:p.font-mono (str (:stations-credits required) " credits, " 
                                (:stations-fuel required) " fuel")]]
            [:div
             [:label.text-xs "Pay Credits"]
             (expense-input "stations-credits" (:stations-credits required) player-id hx-include)]
            [:div
             [:label.text-xs "Pay Fuel"]
             (expense-input "stations-fuel" (:stations-fuel required) player-id hx-include)]]]

          ;; Agents upkeep - costs credits and food per 1000 agents
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Agents Upkeep"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Agents: " (:player/agents player)]]
            [:div
             [:p.text-xs "Cost per 1000 Agents"]
             [:p.font-mono (str (:game/agent-upkeep-credits game) " credits, " 
                                (:game/agent-upkeep-food game) " food")]]
            [:div
             [:p.text-xs "Total Required"]
             [:p.font-mono (str (:agents-credits required) " credits, " 
                                (:agents-food required) " food")]]
            [:div
             [:label.text-xs "Pay Credits"]
             (expense-input "agents-credits" (:agents-credits required) player-id hx-include)]
            [:div
             [:label.text-xs "Pay Food"]
             (expense-input "agents-food" (:agents-food required) player-id hx-include)]]]

          ;; Population upkeep - costs credits and food per 1000 population
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Population Upkeep"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Population: " (:player/population player)]]
            [:div
             [:p.text-xs "Cost per 1000"]
             [:p.font-mono (str (:game/population-upkeep-credits game) " credit, " 
                                (:game/population-upkeep-food game) " food")]]
            [:div
             [:p.text-xs "Total Required"]
             [:p.font-mono (str (:population-credits required) " credits, " 
                                (:population-food required) " food")]]
            [:div
             [:label.text-xs "Pay Credits"]
             (expense-input "population-credits" (:population-credits required) player-id hx-include)]
            [:div
             [:label.text-xs "Pay Food"]
             (expense-input "population-food" (:population-food required) player-id hx-include)]]]]

         ;;; Resources after expenses - using shared component
         ;;; This section is dynamically updated by htmx as the user changes input values.
         [:div#resources-after
          (ui/resource-display-grid player "Resources After Expenses")]

         ;; Warning message area - populated by htmx if player can't afford expenses
         [:div#expense-warning.h-8.flex.items-center]

         ;; Navigation and submit buttons
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id)} "Back to Game"]
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id "/exchange")} "Continue to Exchange"]
          [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
           {:type "submit"
            :disabled true
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           "Continue to Building"]])
       ])))
