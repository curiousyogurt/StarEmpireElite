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
        (let [;; helper function to safely parse numbers, treating empty/nil as 0
              safe-parse (fn [val] (or (parse-long (if (empty? (str val)) "0" (str val))) 0))

              ;; parse expense input values, default to 0 if not provided or empty
              planets-pay (safe-parse (:planets-pay params))
              planets-food (safe-parse (:planets-food params))
              soldiers-credits (safe-parse (:soldiers-credits params))
              soldiers-food (safe-parse (:soldiers-food params))
              fighters-credits (safe-parse (:fighters-credits params))
              fighters-fuel (safe-parse (:fighters-fuel params))
              stations-credits (safe-parse (:stations-credits params))
              stations-fuel (safe-parse (:stations-fuel params))
              agents-credits (safe-parse (:agents-credits params))
              agents-food (safe-parse (:agents-food params))
              population-credits (safe-parse (:population-credits params))
              population-food (safe-parse (:population-food params))              
 
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

(defn calculate-expenses [{:keys [path-params params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        ;; helper function to parse values, stripping non-numeric chars before parsing, treating empty/nil as 0
        parse-value (fn [v] 
                      (try
                        (let [s (str v)
                              cleaned (clojure.string/replace s #"[^0-9]" "")]
                          (if (empty? cleaned)
                            0
                            (parse-long cleaned)))
                        (catch Exception e
                          0)))
        
        ;; parse input values, default to 0 if not provided or empty
        planets-pay (parse-value (:planets-pay params))
        planets-food (parse-value (:planets-food params))
        soldiers-credits (parse-value (:soldiers-credits params))
        soldiers-food (parse-value (:soldiers-food params))
        fighters-credits (parse-value (:fighters-credits params))
        fighters-fuel (parse-value (:fighters-fuel params))
        stations-credits (parse-value (:stations-credits params))
        stations-fuel (parse-value (:stations-fuel params))
        agents-credits (parse-value (:agents-credits params))
        agents-food (parse-value (:agents-food params))
        population-credits (parse-value (:population-credits params))
        population-food (parse-value (:population-food params))
        
        ;; calculate remaining resources
        credits-after (- (:player/credits player) planets-pay soldiers-credits 
                         fighters-credits stations-credits agents-credits population-credits)
        food-after (- (:player/food player) planets-food soldiers-food agents-food population-food)
        fuel-after (- (:player/fuel player) fighters-fuel stations-fuel)
        
        ;; check if player can afford all expenses
        can-afford? (and (>= credits-after 0) (>= food-after 0) (>= fuel-after 0))]
    (biff/render
     [:div
      [:div#resources-after.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
      [:h3.font-bold.mb-4 "Resources After Expenses"]
      [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
       [:div
        [:p.text-xs "Credits"]
        [:p.font-mono {:class (when (< credits-after 0) "text-red-400")} credits-after]]
       [:div
        [:p.text-xs "Food"]
        [:p.font-mono {:class (when (< food-after 0) "text-red-400")} food-after]]
       [:div
        [:p.text-xs "Fuel"]
        [:p.font-mono {:class (when (< fuel-after 0) "text-red-400")} fuel-after]]
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
      [:div#expense-warning.h-8.flex.items-center
       (when (not can-afford?)
         [:p.text-yellow-400.font-bold "⚠ Insufficient resources to pay expenses!"])]
      ;; CRITICAL FIX: Remove hx-swap-oob to prevent button replacement
      [:div#submit-container
       [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
        {:type "submit"
         :disabled (not can-afford?)
         :class (if can-afford?
                  "hover:bg-green-300"
                  "opacity-50 cursor-not-allowed bg-gray-600 hover:bg-gray-600")}
        "Continue to Building"]]])))

;; :: helper function for expense input fields - REMOVED ALL JAVASCRIPT
(defn expense-input [name value player-id hx-include]
  [:div.relative.mt-1
   [:input.w-full.bg-black.border.border-green-400.text-green-400.p-2.pr-8.font-mono
    {:type "number" 
     :name name 
     :value value
     :min "0"
     :autocomplete "off"
     :autocapitalize "off"
     :autocorrect "off" 
     :spellcheck "false"
     :data-lpignore "true"
     :data-form-type "other"
     :hx-post (str "/app/game/" player-id "/calculate-expenses")
     :hx-target "#resources-after" 
     :hx-swap "outerHTML"
     ;; CRITICAL FIX: Use debounced input trigger only
     :hx-trigger "input delay:500ms, change"
     :hx-include hx-include}]
   [:button.absolute.right-2.top-1/2.-translate-y-1/2.text-green-400.hover:text-green-300.transition-colors.text-xl.font-bold.p-1
    {:type "button"
     :tabindex "-1"
     ;; CRITICAL FIX: Use HTMX to reset field instead of JavaScript
     :hx-get (str "/app/game/" player-id "/expenses")
     :hx-target "closest form"
     :hx-swap "outerHTML"
     :title "Reset"}
    "↻"]])

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
      
      (ui/phase-header (:player/current-phase player) "EXPENSES")
      
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
       [:div#resources-after.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
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

       [:div#expense-warning.h-8.flex.items-center]
       [:div.flex.gap-4
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" player-id)} "Back to Game"]
        ;; CRITICAL FIX: Static button that doesn't get replaced
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors.hover:bg-green-300
         {:type "submit"}
         "Continue to Building"]])
      ])))
