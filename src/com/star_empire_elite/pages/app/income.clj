;;;;;
;;;;; Income Phase - Resource Generation
;;;;;
;;;;; The income phase is the first phase of each round where players collect resources from their
;;;;; planets and any other sources. Unlike later phases (expenses, exchange, building), the income
;;;;; phase requires no player decisions; it's purely informational, showing what resources will be
;;;;; generated before the player advances to the next phase.
;;;;;

(ns com.star-empire-elite.pages.app.income
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;;;;
;;;; Calculations
;;;;

;;; Calculate income from all planet types using game constants. Returns a map of resource deltas,
;;; connecting each planet to the resources that is generating.
(defn calculate-income 
  "Calculate income from all planet types. Returns map of resource changes."
  [player game]
  {:ore-credits  (* (:player/ore-planets player)      (:game/ore-planet-credits game))
   :ore-fuel     (* (:player/ore-planets player)      (:game/ore-planet-fuel game))
   :ore-galaxars (* (:player/ore-planets player)      (:game/ore-planet-galaxars game))
   :food-food    (* (:player/food-planets player)     (:game/food-planet-food game))
   :mil-soldiers (* (:player/military-planets player) (:game/military-planet-soldiers game))
   :mil-fighters (* (:player/military-planets player) (:game/military-planet-fighters game))
   :mil-stations (* (:player/military-planets player) (:game/military-planet-stations game))
   :mil-agents   (* (:player/military-planets player) (:game/military-planet-agents game))})

;;;;
;;;; Actions
;;;;
;;;; There are two parts to the actions for this phase: (i) income-page, which shows the income due 
;;;; the player in the current round, and (ii) apply-income which shows the income and then applies it
;;;; when the user advances to the next page.  Both functions call (calculate-income player game) in
;;;; order to determine income from all the planet types, making reference to the game constants.
;;;;

;;; Applies income from all planet types and advances to the next phase. Uses calculate-income to
;;; determine resource changes, and then commits them in a single transaction.
(defn apply-income [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; Phase validation prevents players from applying income multiple times or out of order
      (if (not= (:player/current-phase player) 1)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (let [income (calculate-income player game)]
          ;; Single atomic transaction updates resources and advances the phase all at once. This
          ;; prevents partial updates if something fails midway through.
          (biff/submit-tx ctx
            [{:db/doc-type :player
              :db/op :update
              :xt/id player-id
              :player/credits          (+ (:player/credits player)          (:ore-credits income))
              :player/food             (+ (:player/food player)             (:food-food income))
              :player/fuel             (+ (:player/fuel player)             (:ore-fuel income))
              :player/galaxars         (+ (:player/galaxars player)         (:ore-galaxars income))
              :player/soldiers         (+ (:player/soldiers player)         (:mil-soldiers income))
              :player/fighters         (+ (:player/fighters player)         (:mil-fighters income))
              :player/defence-stations (+ (:player/defence-stations player) (:mil-stations income))
              :player/agents           (+ (:player/agents player)           (:mil-agents income))
              :player/current-phase 2}])
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/expenses")}})))))

;;; Shows preview of income generation before committing. Unlike expenses/exchange/building phases,
;;; no htmx dynamic updates are needed, since there are no player choices to validate.
(defn income-page [{:keys [player game]}]
  (let [income (calculate-income player game)]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
 
      (ui/phase-header (:player/current-phase player) "INCOME")
 
      ;;; Current Resources Display:
      ;;; Shows starting position before income is applied, helping players understand what they have
      ;;; available for the upcoming expenses phase.
      [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
       [:h3.font-bold.mb-4 "Resources Before Income"]
       [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
        [:div
         [:p.text-xs "Credits"]
         [:p.font-mono (:player/credits player)]]
        [:div
         [:p.text-xs "Food"]
         [:p.font-mono (:player/food player)]]
        [:div
         [:p.text-xs "Fuel"]
         [:p.font-mono (:player/fuel player)]]
        [:div
         [:p.text-xs "Galaxars"]
         [:p.font-mono (:player/galaxars player)]]
        [:div
         [:p.text-xs "Soldiers"]
         [:p.font-mono (:player/soldiers player)]]
        [:div
         [:p.text-xs "Fighters"]
         [:p.font-mono (:player/fighters player)]]
        [:div
         [:p.text-xs "Stations"]
         [:p.font-mono (:player/defence-stations player)]]
        [:div
         [:p.text-xs "Agents"]
         [:p.font-mono (:player/agents player)]]]]

      ;;; Income Breakdown Cards:
      ;;; Three-column grid visually separates the three planet types and what they produce. This
      ;;; makes it immediately clear which planets generate which resources.
      [:h3.font-bold.mb-4 "Income This Round"]
      [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8

       ;; Ore planets generate economic resources: credits, fuel, and galaxars (the premium currency)
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 (str "Ore Planets (" (:player/ore-planets player) ")")]
        [:div.space-y-2
         [:div
          [:p.text-xs "Credits"]
          [:p.text-lg.font-mono (str "+ " (:ore-credits income))]]
         [:div
          [:p.text-xs "Fuel"]
          [:p.text-lg.font-mono (str "+ " (:ore-fuel income))]]
         [:div
          [:p.text-xs "Galaxars"]
          [:p.text-lg.font-mono (str "+ " (:ore-galaxars income))]]]]

       ;; Food planets have a single output: food for feeding the population and the military
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 (str "Food Planets (" (:player/food-planets player) ")")]
        [:div.space-y-2
         [:div
          [:p.text-xs "Food"]
          [:p.text-lg.font-mono (str "+ " (:food-food income))]]]]
 
       ;; Military planets generate military units: soldiers, fighters, stations, and agents
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 (str "Military Planets (" (:player/military-planets player) ")")]
        [:div.space-y-2
         [:div
          [:p.text-xs "Soldiers"]
          [:p.text-lg.font-mono (str "+ " (:mil-soldiers income))]]
         [:div
          [:p.text-xs "Fighters"]
          [:p.text-lg.font-mono (str "+ " (:mil-fighters income))]]
         [:div
          [:p.text-xs "Stations"]
          [:p.text-lg.font-mono (str "+ " (:mil-stations income))]]
         [:div
          [:p.text-xs "Agents"]
          [:p.text-lg.font-mono (str "+ " (:mil-agents income))]]]]]

      ;;; Final Resource Totals:
      ;;; Shows what the player will have after income is applied. This preview helps players plan
      ;;; expenses and future building choices.
      [:div.border.border-green-400.p-4.mb-8.bg-green-100.bg-opacity-5
       [:h3.font-bold.mb-4 "Resources After Income"]
       [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
        [:div
         [:p.text-xs "Credits"]
         [:p.font-mono (+ (:player/credits player) (:ore-credits income))]]
        [:div
         [:p.text-xs "Food"]
         [:p.font-mono (+ (:player/food player) (:food-food income))]]
        [:div
         [:p.text-xs "Fuel"]
         [:p.font-mono (+ (:player/fuel player) (:ore-fuel income))]]
        [:div
         [:p.text-xs "Galaxars"]
         [:p.font-mono (+ (:player/galaxars player) (:ore-galaxars income))]]
        [:div
         [:p.text-xs "Soldiers"]
         [:p.font-mono (+ (:player/soldiers player) (:mil-soldiers income))]]
        [:div
         [:p.text-xs "Fighters"]
         [:p.font-mono (+ (:player/fighters player) (:mil-fighters income))]]
        [:div
         [:p.text-xs "Stations"]
         [:p.font-mono (+ (:player/defence-stations player) (:mil-stations income))]]
        [:div
         [:p.text-xs "Agents"]
         [:p.font-mono (+ (:player/agents player) (:mil-agents income))]]]]

      [:.h-6]
 
      ;;; Phase Advance Form:
      ;;; Simple form submission with no validation needed. Income is always valid and beneficial.
      ;;; No htmx dynamic updates unlike later phases since there are no player choices to validate.
      (biff/form
       {:action (str "/app/game/" (:xt/id player) "/apply-income")
        :method "post"}
       [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
        {:type "submit"}
        "Continue to Expenses"])])))
