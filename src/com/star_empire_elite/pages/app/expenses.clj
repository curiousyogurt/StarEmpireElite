(ns com.star-empire-elite.pages.app.expenses
  (:require [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: helper function for expense input fields with reset button
(defn expense-input [name value player-id hx-include]
  [:div.relative.mt-1
   [:input.w-full.bg-black.border.border-green-400.text-green-400.p-2.pr-6.font-mono
    {:type "text" :name name :value value
     :hx-post (str "/app/game/" player-id "/calculate-expenses")
     :hx-target "#resources-after" :hx-swap "outerHTML"
     :hx-trigger "load, change"
     :hx-include hx-include}]
   [:button
    {:type "button"
     :tabindex "-1"
     :class "absolute right-2 top-1/2 -translate-y-1/2 text-green-400 hover:text-green-300 transition-colors text-2xl font-bold p-0 bg-none border-none cursor-pointer"
     :onclick (str "document.querySelector('[name=\"" name "\"]').value = " value "; "
                   "document.querySelector('[name=\"" name "\"]').dispatchEvent(new Event('change', {bubbles: true}))")
     :title "Reset"}
    "↻"]])

;; :: expenses page - players pay for upkeep of their empire
(defn expenses-page [{:keys [player]}]
  (let [;; calculate costs
        planet-cost-per (* (+ (:player/military-planets player)
                              (:player/food-planets player)
                              (:player/ore-planets player)) 10)
        soldiers-credit-cost (Math/round (/ (:player/soldiers player) 1000.0))
        soldiers-food-cost (Math/round (/ (:player/soldiers player) 1000.0))
        fighters-credit-cost (* (:player/fighters player) 2)
        fighters-fuel-cost (* (:player/fighters player) 2)
        stations-credit-cost (* (:player/defence-stations player) 3)
        stations-fuel-cost (* (:player/defence-stations player) 3)
        agents-credit-cost (* (:player/agents player) 2)
        agents-food-cost (Math/round (/ (:player/agents player) 1000.0))
        population-credit-cost (Math/round (/ (:player/population player) 1000.0))
        population-food-cost (Math/round (/ (:player/population player) 1000.0))
        
        player-id (:xt/id player)
        hx-include "[name='planets-pay'],[name='planets-food'],[name='soldiers-credits'],[name='soldiers-food'],[name='fighters-credits'],[name='fighters-fuel'],[name='stations-credits'],[name='stations-fuel'],[name='agents-credits'],[name='agents-food'],[name='population-credits'],[name='population-food']"]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
      
      [:h2.text-xl.font-bold.mb-6 "PHASE 2: EXPENSES"]
      
      ;; :: resources before expenses
      [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
       [:h3.font-bold.mb-4 "Resources Before Expenses"]
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
      
      ;; :: expenses form
      [:h3.font-bold.mb-4 "Expenses This Round"]
      [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8
       
       ;; :: planets expense
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Planets Upkeep"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Planets: " (+ (:player/military-planets player)
                                      (:player/food-planets player)
                                      (:player/ore-planets player))]]
         [:div
          [:p.text-xs "Cost per Planet"]
          [:p.font-mono "10 credits, 1 food"]]
         [:div
          [:p.text-xs "Total Required"]
          [:p.font-mono (str planet-cost-per " credits, " (+ (:player/military-planets player)
                                                                    (:player/food-planets player)
                                                                    (:player/ore-planets player)) " food")]]
         [:div
          [:label.text-xs "Pay Credits"]
          (expense-input "planets-pay" planet-cost-per player-id hx-include)]
         [:div
          [:label.text-xs "Pay Food"]
          (expense-input "planets-food" (+ (:player/military-planets player)
                                           (:player/food-planets player)
                                           (:player/ore-planets player)) player-id hx-include)]]]
       
       ;; :: soldiers expense
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Soldiers Upkeep"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Soldiers: " (:player/soldiers player)]]
         [:div
          [:p.text-xs "Cost per 1000"]
          [:p.font-mono "1 credit, 1 food"]]
         [:div
          [:p.text-xs "Total Required"]
          [:p.font-mono (str soldiers-credit-cost " credits, " soldiers-food-cost " food")]]
         [:div
          [:label.text-xs "Pay Credits"]
          (expense-input "soldiers-credits" soldiers-credit-cost player-id hx-include)]
         [:div
          [:label.text-xs "Pay Food"]
          (expense-input "soldiers-food" soldiers-food-cost player-id hx-include)]]]
       
       ;; :: fighters expense
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Fighters Upkeep"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Fighters: " (:player/fighters player)]]
         [:div
          [:p.text-xs "Cost per Fighter"]
          [:p.font-mono "2 credits, 2 fuel"]]
         [:div
          [:p.text-xs "Total Required"]
          [:p.font-mono (str fighters-credit-cost " credits, " fighters-fuel-cost " fuel")]]
         [:div
          [:label.text-xs "Pay Credits"]
          (expense-input "fighters-credits" fighters-credit-cost player-id hx-include)]
         [:div
          [:label.text-xs "Pay Fuel"]
          (expense-input "fighters-fuel" fighters-fuel-cost player-id hx-include)]]]
       
       ;; :: stations expense
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Defence Stations Upkeep"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Stations: " (:player/defence-stations player)]]
         [:div
          [:p.text-xs "Cost per Station"]
          [:p.font-mono "3 credits, 3 fuel"]]
         [:div
          [:p.text-xs "Total Required"]
          [:p.font-mono (str stations-credit-cost " credits, " stations-fuel-cost " fuel")]]
         [:div
          [:label.text-xs "Pay Credits"]
          (expense-input "stations-credits" stations-credit-cost player-id hx-include)]
         [:div
          [:label.text-xs "Pay Fuel"]
          (expense-input "stations-fuel" stations-fuel-cost player-id hx-include)]]]
       
       ;; :: agents expense
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Agents Upkeep"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Agents: " (:player/agents player)]]
         [:div
          [:p.text-xs "Cost per Agent"]
          [:p.font-mono "2 credits, 0.001 food"]]
         [:div
          [:p.text-xs "Total Required"]
          [:p.font-mono (str agents-credit-cost " credits, " agents-food-cost " food")]]
         [:div
          [:label.text-xs "Pay Credits"]
          (expense-input "agents-credits" agents-credit-cost player-id hx-include)]
         [:div
          [:label.text-xs "Pay Food"]
          (expense-input "agents-food" agents-food-cost player-id hx-include)]]]
       
       ;; :: population expense
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Population Upkeep"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Population: " (:player/population player)]]
         [:div
          [:p.text-xs "Cost per 1000"]
          [:p.font-mono "1 credit, 1 food"]]
         [:div
          [:p.text-xs "Total Required"]
          [:p.font-mono (str population-credit-cost " credits, " population-food-cost " food")]]
         [:div
          [:label.text-xs "Pay Credits"]
          (expense-input "population-credits" population-credit-cost player-id hx-include)]
         [:div
          [:label.text-xs "Pay Food"]
          (expense-input "population-food" population-food-cost player-id hx-include)]]]
       ]
      
      ;; :: resources after expenses
      [:div#resources-after.border.border-green-400.p-4.mb-8.bg-green-100.bg-opacity-5
       [:h3.font-bold.mb-4 "Resources After Expenses"]
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

      [:.h-6]
      [:div.flex.gap-4
       [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
        {:href (str "/app/game/" player-id)} "Back to Game"]
       [:a.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
        {:href (str "/app/game/" player-id "/building")} "Continue to Building"]]])))
