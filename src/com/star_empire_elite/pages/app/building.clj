(ns com.star-empire-elite.pages.app.building
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: apply building - save purchases to database and advance to phase 4
(defn apply-building [{:keys [path-params params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; only allow building if player is in phase 3
      (if (not= (:player/current-phase player) 3)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (let [;; parse purchase input values, default to 0 if not provided
              soldiers (max 0 (parse-long (or (:soldiers params) "0")))
              transports (max 0 (parse-long (or (:transports params) "0")))
              generals (max 0 (parse-long (or (:generals params) "0")))
              carriers (max 0 (parse-long (or (:carriers params) "0")))
              fighters (max 0 (parse-long (or (:fighters params) "0")))
              admirals (max 0 (parse-long (or (:admirals params) "0")))
              defence-stations (max 0 (parse-long (or (:defence-stations params) "0")))
              command-ships (max 0 (parse-long (or (:command-ships params) "0")))
              military-planets (max 0 (parse-long (or (:military-planets params) "0")))
              food-planets (max 0 (parse-long (or (:food-planets params) "0")))
              ore-planets (max 0 (parse-long (or (:ore-planets params) "0")))
              
              ;; get game constants for costs
              game (xt/entity db (:player/game player))
              
              ;; calculate total cost with null safety
              total-cost (+ (* soldiers (or (:game/soldier-cost game) 0))
                           (* transports (or (:game/transport-cost game) 0))
                           (* generals (or (:game/general-cost game) 0))
                           (* carriers (or (:game/carrier-cost game) 0))
                           (* fighters (or (:game/fighter-cost game) 0))
                           (* admirals (or (:game/admiral-cost game) 0))
                           (* defence-stations (or (:game/station-cost game) 0))
                           (* command-ships (or (:game/command-ship-cost game) 0))
                           (* military-planets (or (:game/military-planet-cost game) 0))
                           (* food-planets (or (:game/food-planet-cost game) 0))
                           (* ore-planets (or (:game/ore-planet-cost game) 0)))
              
              ;; calculate new totals
              new-credits (- (:player/credits player) total-cost)
              new-soldiers (+ (:player/soldiers player) soldiers)
              new-transports (+ (:player/transports player) transports)
              new-generals (+ (:player/generals player) generals)
              new-carriers (+ (:player/carriers player) carriers)
              new-fighters (+ (:player/fighters player) fighters)
              new-admirals (+ (:player/admirals player) admirals)
              new-defence-stations (+ (:player/defence-stations player) defence-stations)
              new-command-ships (+ (:player/command-ships player) command-ships)
              new-military-planets (+ (:player/military-planets player) military-planets)
              new-food-planets (+ (:player/food-planets player) food-planets)
              new-ore-planets (+ (:player/ore-planets player) ore-planets)]
          
          ;; only allow purchase if they have enough credits
          (if (< new-credits 0)
            {:status 400
             :body "Insufficient credits for purchase"}
            ;; submit transaction to update player resources and advance phase
            (do
              (biff/submit-tx ctx
                [{:db/doc-type :player
                  :db/op :update
                  :xt/id player-id
                  :player/credits new-credits
                  :player/soldiers new-soldiers
                  :player/transports new-transports
                  :player/generals new-generals
                  :player/carriers new-carriers
                  :player/fighters new-fighters
                  :player/admirals new-admirals
                  :player/defence-stations new-defence-stations
                  :player/command-ships new-command-ships
                  :player/military-planets new-military-planets
                  :player/food-planets new-food-planets
                  :player/ore-planets new-ore-planets
                  :player/current-phase 4}])
              
              ;; redirect to action page
              {:status 303
               :headers {"location" (str "/app/game/" player-id "/action")}})))))))

;; :: calculate building costs dynamically for HTMX updates
(defn calculate-building [{:keys [path-params params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))
        
        ;; parse purchase quantities, default to 0, ensure non-negative
        soldiers (max 0 (parse-long (or (:soldiers params) "0")))
        transports (max 0 (parse-long (or (:transports params) "0")))
        generals (max 0 (parse-long (or (:generals params) "0")))
        carriers (max 0 (parse-long (or (:carriers params) "0")))
        fighters (max 0 (parse-long (or (:fighters params) "0")))
        admirals (max 0 (parse-long (or (:admirals params) "0")))
        defence-stations (max 0 (parse-long (or (:defence-stations params) "0")))
        command-ships (max 0 (parse-long (or (:command-ships params) "0")))
        military-planets (max 0 (parse-long (or (:military-planets params) "0")))
        food-planets (max 0 (parse-long (or (:food-planets params) "0")))
        ore-planets (max 0 (parse-long (or (:ore-planets params) "0")))
        
        ;; calculate total cost with null safety
        total-cost (+ (* soldiers (or (:game/soldier-cost game) 0))
                     (* transports (or (:game/transport-cost game) 0))
                     (* generals (or (:game/general-cost game) 0))
                     (* carriers (or (:game/carrier-cost game) 0))
                     (* fighters (or (:game/fighter-cost game) 0))
                     (* admirals (or (:game/admiral-cost game) 0))
                     (* defence-stations (or (:game/station-cost game) 0))
                     (* command-ships (or (:game/command-ship-cost game) 0))
                     (* military-planets (or (:game/military-planet-cost game) 0))
                     (* food-planets (or (:game/food-planet-cost game) 0))
                     (* ore-planets (or (:game/ore-planet-cost game) 0)))
        
        ;; calculate remaining resources and new totals
        credits-after (- (:player/credits player) total-cost)
        soldiers-after (+ (:player/soldiers player) soldiers)
        transports-after (+ (:player/transports player) transports)
        generals-after (+ (:player/generals player) generals)
        carriers-after (+ (:player/carriers player) carriers)
        fighters-after (+ (:player/fighters player) fighters)
        admirals-after (+ (:player/admirals player) admirals)
        defence-stations-after (+ (:player/defence-stations player) defence-stations)
        command-ships-after (+ (:player/command-ships player) command-ships)
        military-planets-after (+ (:player/military-planets player) military-planets)
        food-planets-after (+ (:player/food-planets player) food-planets)
        ore-planets-after (+ (:player/ore-planets player) ore-planets)]
    
    (biff/render
     [:div#resources-after.border.border-green-400.p-4.mb-8.bg-green-100.bg-opacity-5
      [:h3.font-bold.mb-4 "Resources After Building"]
      [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
       [:div
        [:p.text-xs "Credits"]
        [:p.font-mono {:class (when (< credits-after 0) "text-red-400")} credits-after]]
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
        [:p.font-mono soldiers-after]]
       [:div
        [:p.text-xs "Transports"]
        [:p.font-mono transports-after]]
       [:div
        [:p.text-xs "Generals"]
        [:p.font-mono generals-after]]
       [:div
        [:p.text-xs "Carriers"]
        [:p.font-mono carriers-after]]
       [:div
        [:p.text-xs "Fighters"]
        [:p.font-mono fighters-after]]
       [:div
        [:p.text-xs "Admirals"]
        [:p.font-mono admirals-after]]
       [:div
        [:p.text-xs "Stations"]
        [:p.font-mono defence-stations-after]]
       [:div
        [:p.text-xs "Command Ships"]
        [:p.font-mono command-ships-after]]
       [:div
        [:p.text-xs "Military Planets"]
        [:p.font-mono military-planets-after]]
       [:div
        [:p.text-xs "Food Planets"]
        [:p.font-mono food-planets-after]]
       [:div
        [:p.text-xs "Ore Planets"]
        [:p.font-mono ore-planets-after]]
       [:div
        [:p.text-xs "Agents"]
        [:p.font-mono (:player/agents player)]]]])))

;; :: helper function for purchase input fields with reset button
(defn purchase-input [name value player-id hx-include]
  [:div.relative.mt-1
   [:input.w-full.bg-black.border.border-green-400.text-green-400.p-2.pr-6.font-mono
    {:type "text" :name name :value value
     :hx-post (str "/app/game/" player-id "/calculate-building")
     :hx-target "#resources-after" :hx-swap "outerHTML"
     :hx-trigger "load, change"
     :hx-include hx-include}]
   [:button
    {:type "button"
     :tabindex "-1"
     :class "absolute right-2 top-1/2 -translate-y-1/2 text-green-400 hover:text-green-300 transition-colors text-2xl font-bold p-0 bg-none border-none cursor-pointer"
     :onclick (str "document.querySelector('[name=\"" name "\"]').value = 0; "
                   "document.querySelector('[name=\"" name "\"]').dispatchEvent(new Event('change', {bubbles: true}))")
     :title "Reset"}
    "\u2022"]])

;; :: building page - players purchase units, ships, and planets
(defn building-page [{:keys [player game]}]
  (let [player-id (:xt/id player)
        hx-include "[name='soldiers'],[name='transports'],[name='generals'],[name='carriers'],[name='fighters'],[name='admirals'],[name='defence-stations'],[name='command-ships'],[name='military-planets'],[name='food-planets'],[name='ore-planets']"]
    
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
      
      [:h2.text-xl.font-bold.mb-6 "PHASE 3: BUILDING"]
      
      ;; :: resources before building
      [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
       [:h3.font-bold.mb-4 "Resources Before Building"]
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
      
      ;; :: building form
      (biff/form
       {:action (str "/app/game/" player-id "/apply-building")
        :method "post"}
       
       [:h3.font-bold.mb-4 "Building This Round"]
       [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8
       
       ;; :: soldiers purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Soldiers"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/soldier-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "soldiers" 0 player-id hx-include)]]]
       
       ;; :: transports purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Transports"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/transport-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "transports" 0 player-id hx-include)]]]
       
       ;; :: generals purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Generals"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/general-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "generals" 0 player-id hx-include)]]]
       
       ;; :: carriers purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Carriers"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/carrier-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "carriers" 0 player-id hx-include)]]]
       
       ;; :: fighters purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Fighters"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/fighter-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "fighters" 0 player-id hx-include)]]]
       
       ;; :: admirals purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Admirals"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/admiral-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "admirals" 0 player-id hx-include)]]]
       
       ;; :: defence stations purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Defence Stations"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/station-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "defence-stations" 0 player-id hx-include)]]]
       
       ;; :: command ships purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Command Ships"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/command-ship-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "command-ships" 0 player-id hx-include)]]]
       
       ;; :: military planets purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Military Planets"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/military-planet-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "military-planets" 0 player-id hx-include)]]]
       
       ;; :: food planets purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Food Planets"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/food-planet-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "food-planets" 0 player-id hx-include)]]]
       
       ;; :: ore planets purchase
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 "Ore Planets"]
        [:div.space-y-2
         [:div
          [:p.text-xs "Cost per Unit"]
          [:p.font-mono (str (or (:game/ore-planet-cost game) 0) " credits")]]
         [:div
          [:label.text-xs "Purchase Quantity"]
          (purchase-input "ore-planets" 0 player-id hx-include)]]]
       ]
      
       ;; :: resources after building
       [:div#resources-after.border.border-green-400.p-4.mb-8.bg-green-100.bg-opacity-5
        [:h3.font-bold.mb-4 "Resources After Building"]
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
         "Continue to Action"]])
      ]))) ;; end of biff/form
