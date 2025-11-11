(ns com.star-empire-elite.pages.app.expenses
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: apply expenses - save expenses to database and advance to phase 3
(defn apply-expenses [{:keys [path-params params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; only allow expense application if player is in phase 2
      (if (not= (:player/current-phase player) 2)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (let [;; parse expense input values, default to 0 if not provided
              planets-pay (parse-long (or (:planets-pay params) "0"))
              planets-food (parse-long (or (:planets-food params) "0"))
              soldiers-credits (parse-long (or (:soldiers-credits params) "0"))
              soldiers-food (parse-long (or (:soldiers-food params) "0"))
              fighters-credits (parse-long (or (:fighters-credits params) "0"))
              fighters-fuel (parse-long (or (:fighters-fuel params) "0"))
              stations-credits (parse-long (or (:stations-credits params) "0"))
              stations-fuel (parse-long (or (:stations-fuel params) "0"))
              agents-credits (parse-long (or (:agents-credits params) "0"))
              agents-food (parse-long (or (:agents-food params) "0"))
              population-credits (parse-long (or (:population-credits params) "0"))
              population-food (parse-long (or (:population-food params) "0"))
              
              ;; calculate new resource totals after expenses
              new-credits (- (:player/credits player) planets-pay soldiers-credits 
                             fighters-credits stations-credits agents-credits population-credits)
              new-food (- (:player/food player) planets-food soldiers-food agents-food population-food)
              new-fuel (- (:player/fuel player) fighters-fuel stations-fuel)]
          ;; submit transaction to update player resources and advance phase
          (biff/submit-tx ctx
            [{:db/doc-type :player
              :db/op :update
              :xt/id player-id
              :player/credits new-credits
              :player/food new-food
              :player/fuel new-fuel
              :player/current-phase 3}])
          ;; redirect to building page (when it exists)
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/building")}})))))

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
    "\u21bb"]])

;; :: expenses page - players pay for upkeep of their empire
(defn expenses-page [{:keys [player game]}]
  (let [;; calculate costs using game constants
        planet-count (+ (:player/military-planets player)
                        (:player/food-planets player)
                        (:player/ore-planets player))
        planet-cost-per (* planet-count (:game/planet-upkeep-credits game))
        planet-food-cost planet-count ; since food cost is 1 per planet
        
        soldiers-credit-cost (Math/round (/ (* (:player/soldiers player) (:game/soldier-upkeep-credits game)) 1000.0))
        soldiers-food-cost (Math/round (/ (* (:player/soldiers player) (:game/soldier-upkeep-food game)) 1000.0))
        
        fighters-credit-cost (* (:player/fighters player) (:game/fighter-upkeep-credits game))
        fighters-fuel-cost (* (:player/fighters player) (:game/fighter-upkeep-fuel game))
        
        stations-credit-cost (* (:player/defence-stations player) (:game/station-upkeep-credits game))
        stations-fuel-cost (* (:player/defence-stations player) (:game/station-upkeep-fuel game))
        
        agents-credit-cost (* (:player/agents player) (:game/agent-upkeep-credits game))
        agents-food-cost (Math/round (/ (* (:player/agents player) (:game/agent-upkeep-food game)) 1000.0))
        
        population-credit-cost (Math/round (/ (* (:player/population player) (:game/population-upkeep-credits game)) 1000.0))
        population-food-cost (Math/round (/ (* (:player/population player) (:game/population-upkeep-food game)) 1000.0))
        
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
      (biff/form
       {:action (str "/app/game/" player-id "/apply-expenses")
        :method "post"}
       
       [:h3.font-bold.mb-4 "Expenses This Round"]
       [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8
       
       ;; :: planets expense
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Planets Upkeep"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Planets: " planet-count]]
         [:div
          [:p.text-xs "Cost per Planet"]
          [:p.font-mono (str (:game/planet-upkeep-credits game) " credits, " 
                             (:game/planet-upkeep-food game) " food")]]
         [:div
          [:p.text-xs "Total Required"]
          [:p.font-mono (str planet-cost-per " credits, " planet-food-cost " food")]]
         [:div
          [:label.text-xs "Pay Credits"]
          (expense-input "planets-pay" planet-cost-per player-id hx-include)]
         [:div
          [:label.text-xs "Pay Food"]
          (expense-input "planets-food" planet-food-cost player-id hx-include)]]]
       
       ;; :: soldiers expense
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
          [:p.font-mono (str (:game/fighter-upkeep-credits game) " credits, " 
                             (:game/fighter-upkeep-fuel game) " fuel")]]
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
          [:p.font-mono (str (:game/station-upkeep-credits game) " credits, " 
                             (:game/station-upkeep-fuel game) " fuel")]]
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
          [:p.text-xs "Cost per 1000 Agents"]
          [:p.font-mono (str (:game/agent-upkeep-credits game) " credits, " (:game/agent-upkeep-food game) " food")]]
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
          [:p.font-mono (str (:game/population-upkeep-credits game) " credit, " (:game/population-upkeep-food game) " food")]]
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
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         "Continue to Building"]])
      ]))) ;; end of biff/form
