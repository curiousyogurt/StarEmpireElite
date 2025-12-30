;;;;;
;;;;; Exchange (Extension of Phase 2) - Buying and Selling Resources and Assets
;;;;;
;;;;; The exchange page allows players to buy resources, and sell military units and planets. Unlike 
;;;;; income (which is automatic) and expenses (which are required), exchange is entirely optional; 
;;;;; players can choose to make no exchanges at all. The game stays in phase 2, allowing players to 
;;;;; return to expenses or proceed to building.
;;;;;
;;;;; This page uses htmx for dynamic validation, showing players in real-time whether they can afford 
;;;;; their selected exchanges before submitting.
;;;;;

(ns com.star-empire-elite.pages.app.exchange
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [com.star-empire-elite.constants :as const]
            [xtdb.api :as xt]))

;;;;
;;;; Calculations
;;;;

;;; Get exchange rates from constants
(defn get-exchange-rates
  "Returns map of all exchange rates from game constants"
  []
  {:soldier-sell const/soldier-sell
   :fighter-sell const/fighter-sell
   :station-sell const/station-sell
   :mil-planet-sell const/mil-planet-sell
   :food-planet-sell const/food-planet-sell
   :ore-planet-sell const/ore-planet-sell
   :food-buy const/food-buy
   :food-sell const/food-sell
   :fuel-buy const/fuel-buy
   :fuel-sell const/fuel-sell})

;;; Parse exchange quantities from request parameters
(defn parse-exchange-quantities
  "Parse all exchange quantity inputs from request params.
   Returns map of quantities for all exchange types."
  [params]
  {:soldiers-sold (utils/parse-numeric-input (:soldiers-sold params))
   :fighters-sold (utils/parse-numeric-input (:fighters-sold params))
   :stations-sold (utils/parse-numeric-input (:stations-sold params))
   :mil-planets-sold (utils/parse-numeric-input (:mil-planets-sold params))
   :food-planets-sold (utils/parse-numeric-input (:food-planets-sold params))
   :ore-planets-sold (utils/parse-numeric-input (:ore-planets-sold params))
   :food-bought (utils/parse-numeric-input (:food-bought params))
   :food-sold (utils/parse-numeric-input (:food-sold params))
   :fuel-bought (utils/parse-numeric-input (:fuel-bought params))
   :fuel-sold (utils/parse-numeric-input (:fuel-sold params))})

;;; Calculate credit changes from all exchanges
(defn calculate-exchange-credits
  "Calculate net credit change from selling units/planets and buying/selling resources.
   Returns map with breakdown of credit sources."
  [quantities rates]
  (let [credits-from-sales (+ (* (:soldiers-sold quantities)     (:soldier-sell rates))
                              (* (:fighters-sold quantities)     (:fighter-sell rates))
                              (* (:stations-sold quantities)     (:station-sell rates))
                              (* (:mil-planets-sold quantities)  (:mil-planet-sell rates))
                              (* (:food-planets-sold quantities) (:food-planet-sell rates))
                              (* (:ore-planets-sold quantities)  (:ore-planet-sell rates)))
        credits-from-resources (- (+ (* (:food-sold quantities)   (:food-sell rates))
                                     (* (:fuel-sold quantities)   (:fuel-sell rates)))
                                  (+ (* (:food-bought quantities) (:food-buy rates))
                                     (* (:fuel-bought quantities) (:fuel-buy rates))))]
    {:credits-from-sales credits-from-sales
     :credits-from-resources credits-from-resources
     :total-credits (+ credits-from-sales credits-from-resources)}))

;;; Calculate all resources after exchange
(defn calculate-resources-after-exchange
  "Calculate all player resources after executing exchanges.
   Returns map of all resource values after exchange."
  [player quantities credit-changes]
  {:credits      (+ (:player/credits player)      (:total-credits credit-changes))
   :soldiers     (- (:player/soldiers player)     (:soldiers-sold quantities))
   :fighters     (- (:player/fighters player)     (:fighters-sold quantities))
   :stations     (- (:player/stations player)     (:stations-sold quantities))
   :mil-planets  (- (:player/mil-planets player)  (:mil-planets-sold quantities))
   :food-planets (- (:player/food-planets player) (:food-planets-sold quantities))
   :ore-planets  (- (:player/ore-planets player)  (:ore-planets-sold quantities))
   :food (+ (:player/food player) (:food-bought quantities) (- (:food-sold quantities)))
   :fuel (+ (:player/fuel player) (:fuel-bought quantities) (- (:fuel-sold quantities)))})

;;; Validate exchange is legal
(defn valid-exchange?
  "Returns true if player can execute the exchange (all resources non-negative)"
  [resources-after]
  (and (>= (:credits resources-after) 0)
       (>= (:soldiers resources-after) 0)
       (>= (:fighters resources-after) 0)
       (>= (:stations resources-after) 0)
       (>= (:mil-planets resources-after) 0)
       (>= (:food-planets resources-after) 0)
       (>= (:ore-planets resources-after) 0)
       (>= (:food resources-after) 0)
       (>= (:fuel resources-after) 0)))

;;; Identify which specific exchanges are invalid (for UI highlighting)
(defn identify-invalid-exchanges
  "Returns map indicating which specific exchange types are invalid"
  [resources-after quantities]
  {:invalid-soldier-sale? (< (:soldiers resources-after) 0)
   :invalid-fighter-sale? (< (:fighters resources-after) 0)
   :invalid-station-sale? (< (:stations resources-after) 0)
   :invalid-mil-planet-sale? (< (:mil-planets resources-after) 0)
   :invalid-food-planet-sale? (< (:food-planets resources-after) 0)
   :invalid-ore-planet-sale? (< (:ore-planets resources-after) 0)
   :invalid-food-sale? (and (> (:food-sold quantities) 0) (< (:food resources-after) 0))
   :invalid-fuel-sale? (and (> (:fuel-sold quantities) 0) (< (:fuel resources-after) 0))
   :invalid-food-purchase? (and (> (:food-bought quantities) 0) (< (:credits resources-after) 0))
   :invalid-fuel-purchase? (and (> (:fuel-bought quantities) 0) (< (:credits resources-after) 0))})

;;;;
;;;; UI Components
;;;;

;;; Submit button component - extracted to avoid duplication
(defn submit-button
  "Renders the submit button with dynamic disabled state based on validity.
   Used in both initial page render and HTMX updates.
   Accepts optional extra-attrs map for additional HTML attributes."
  ([can-execute?] (submit-button can-execute? {}))
  ([can-execute? extra-attrs]
   [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
    (merge {:type "submit"
            :disabled (not can-execute?)
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           extra-attrs)
    "Make Exchange"]))

;;; Sell exchange card component
(defn sell-exchange-card
  "Renders a sell exchange card for units, planets, or resources.
   Parameters:
   - item-name: Display name (e.g. 'Soldiers', 'Ore Planets', 'Food')
   - field-name: Form field name (e.g. 'soldiers-sold', 'ore-planets-sold')
   - price: Price per unit in credits
   - max-available: Current quantity player owns
   - player-id: Player identifier for HTMX routing
   - hx-include: HTMX include string for related fields"
  [item-name field-name price max-available player-id hx-include]
  [:div.border.border-green-400.p-4
   [:h4.font-bold.mb-3 (str "Sell " item-name)]
   [:div.space-y-2
    [:div
     [:p.text-xs "Price per Unit"]
     [:p.font-mono (str price " credits")]]
    [:div
     [:p.text-xs "Max Available"]
     [:p.font-mono max-available]]
    [:div
     [:label.text-xs "Sell Quantity"]
     (ui/numeric-input field-name 0 player-id "/calculate-exchange" hx-include)]]])

;;; Buy exchange card component
(defn buy-exchange-card
  "Renders a buy exchange card for resources.
   Parameters:
   - item-name: Display name (e.g. 'Food', 'Fuel')
   - field-name: Form field name (e.g. 'food-bought', 'fuel-bought')
   - price: Price per unit in credits
   - player-id: Player identifier for HTMX routing
   - hx-include: HTMX include string for related fields"
  [item-name field-name price player-id hx-include]
  [:div.border.border-green-400.p-4
   [:h4.font-bold.mb-3 (str "Buy " item-name)]
   [:div.space-y-2
    [:div
     [:p.text-xs "Price per Unit"]
     [:p.font-mono (str price " credits")]]
    [:div
     [:label.text-xs "Buy Quantity"]
     (ui/numeric-input field-name 0 player-id "/calculate-exchange" hx-include)]]])

;;;;
;;;; Actions
;;;;
;;;; There are three parts to the actions for this phase: (i) exchange-page, which shows the
;;;; exchange options and input fields, (ii) calculate-exchange which provides HTMX dynamic updates
;;;; as the user changes values, and (iii) apply-exchange which commits the exchanges to the database
;;;; (staying in phase 2 to allow additional exchanges).
;;;;

;;; Applies exchanges and redirects back to expenses page. Uses pure calculation functions to
;;; determine resource changes, then commits them in a single transaction.
(defn apply-exchange [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Phase validation - only allow exchange if player is in phase 2
    (if-let [redirect (utils/validate-phase player 2 player-id)]
      redirect
      ;; Parse all exchange inputs using extracted function
      (let [quantities (parse-exchange-quantities params)
            
            ;; Use pure calculation functions to determine final resource values
            rates (get-exchange-rates)
            credit-changes (calculate-exchange-credits quantities rates)
            resources-after (calculate-resources-after-exchange player quantities credit-changes)]
        
        ;; Single atomic transaction updates resources (stays in phase 2)
        ;; This prevents partial updates if something fails midway through
        (biff/submit-tx ctx
          [{:db/doc-type :player
            :db/op :update
            :xt/id player-id
            :player/credits (:credits resources-after)
            :player/soldiers (:soldiers resources-after)
            :player/fighters (:fighters resources-after)
            :player/stations (:stations resources-after)
            :player/mil-planets (:mil-planets resources-after)
            :player/food-planets (:food-planets resources-after)
            :player/ore-planets (:ore-planets resources-after)
            :player/food (:food resources-after)
            :player/fuel (:fuel resources-after)}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/expenses")}}))))

;;; Provides HTMX dynamic updates showing resources after exchange as user changes input values.
;;; This gives immediate feedback on whether the player can afford their selected exchanges.
(defn calculate-exchange [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [;; Parse all exchange inputs using extracted function
          quantities (parse-exchange-quantities params)
        
          ;; Use pure calculation functions
          rates (get-exchange-rates)
          credit-changes (calculate-exchange-credits quantities rates)
          resources-after (calculate-resources-after-exchange player quantities credit-changes)
          can-execute? (valid-exchange? resources-after)
          invalid-exchanges (identify-invalid-exchanges resources-after quantities)
          
          ;; Determine if any credit-related transaction is invalid
          invalid-credits-transaction? (or (:invalid-soldier-sale? invalid-exchanges)
                                           (:invalid-fighter-sale? invalid-exchanges)
                                           (:invalid-station-sale? invalid-exchanges)
                                           (:invalid-mil-planet-sale? invalid-exchanges)
                                           (:invalid-food-planet-sale? invalid-exchanges)
                                           (:invalid-ore-planet-sale? invalid-exchanges)
                                           (:invalid-food-sale? invalid-exchanges)
                                           (:invalid-fuel-sale? invalid-exchanges)
                                           (:invalid-food-purchase? invalid-exchanges)
                                           (:invalid-fuel-purchase? invalid-exchanges))]
    
      ;; Render HTMX response fragments that replace specific page elements
      (biff/render
        [:div
         ;; Resources display with red highlighting for invalid values
         [:div#resources-after.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
          [:h3.font-bold.mb-4 "Resources After Exchange"]
          [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
           [:div
            [:p.text-xs "Credits"]
            [:p.font-mono {:class (when invalid-credits-transaction? "text-red-400")} 
             (:credits resources-after)]]
           [:div
            [:p.text-xs "Food"]
            [:p.font-mono {:class (when (or (:invalid-food-sale? invalid-exchanges) 
                                            (:invalid-food-purchase? invalid-exchanges)) 
                                     "text-red-400")} 
             (:food resources-after)]]
           [:div
            [:p.text-xs "Fuel"]
            [:p.font-mono {:class (when (or (:invalid-fuel-sale? invalid-exchanges) 
                                            (:invalid-fuel-purchase? invalid-exchanges)) 
                                     "text-red-400")} 
             (:fuel resources-after)]]
           [:div
            [:p.text-xs "Galaxars"]
            [:p.font-mono (:player/galaxars player)]]
           [:div
            [:p.text-xs "Soldiers"]
            [:p.font-mono {:class (when (:invalid-soldier-sale? invalid-exchanges) "text-red-400")} 
             (:soldiers resources-after)]]
           [:div
            [:p.text-xs "Fighters"]
            [:p.font-mono {:class (when (:invalid-fighter-sale? invalid-exchanges) "text-red-400")} 
             (:fighters resources-after)]]
           [:div
            [:p.text-xs "Stations"]
            [:p.font-mono {:class (when (:invalid-station-sale? invalid-exchanges) "text-red-400")} 
             (:stations resources-after)]]
           [:div
            [:p.text-xs "Mil Plts"]
            [:p.font-mono {:class (when (:invalid-mil-planet-sale? invalid-exchanges) "text-red-400")} 
             (:mil-planets resources-after)]]
           [:div
            [:p.text-xs "Food Plts"]
            [:p.font-mono {:class (when (:invalid-food-planet-sale? invalid-exchanges) "text-red-400")} 
             (:food-planets resources-after)]]
           [:div
            [:p.text-xs "Ore Plts"]
            [:p.font-mono {:class (when (:invalid-ore-planet-sale? invalid-exchanges) "text-red-400")} 
             (:ore-planets resources-after)]]]]
         
         ;; Warning message if exchanges exceed available resources
         [:div#exchange-warning.h-8.flex.items-center
          {:hx-swap-oob "true"}
          (when (not can-execute?)
            [:p.text-yellow-400.font-bold "WARNING: Invalid exchanges! You cannot sell more than you own."])]
         
         ;; Submit button - disabled if player can't execute exchanges
         (submit-button can-execute? {:hx-swap-oob "true"})]))))

;;; Shows exchange options and input fields for player to buy/sell resources and assets
(defn exchange-page [{:keys [player game]}]
  (let [player-id (:xt/id player)
        hx-include "[name='soldiers-sold'],[name='fighters-sold'],[name='stations-sold'],[name='mil-planets-sold'],[name='food-planets-sold'],[name='ore-planets-sold'],[name='food-bought'],[name='food-sold'],[name='fuel-bought'],[name='fuel-sold']"
        rates (get-exchange-rates)]
    (ui/page
      {}
      [:div.text-green-400.font-mono
       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "EXCHANGE")

       ;;;  Current Resources Display:
       ;;; Shows starting position before exchanges, helping players understand what they have
       ;;; available to sell or spend.
       [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
        [:h3.font-bold.mb-4 "Resources Before Exchange"]
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
          [:p.font-mono (:player/stations player)]]
         [:div
          [:p.text-xs "Mil Plts"]
          [:p.font-mono (:player/mil-planets player)]]
         [:div
          [:p.text-xs "Food Plts"]
          [:p.font-mono (:player/food-planets player)]]
         [:div
          [:p.text-xs "Ore Plts"]
          [:p.font-mono (:player/ore-planets player)]]]]

       ;;; Exchange Input Form:
       ;;; Player chooses what to buy and sell. HTMX provides real-time feedback on whether
       ;;; they can afford the total exchanges.
       (biff/form
         {:action (str "/app/game/" player-id "/apply-exchange")
          :method "post"}

         [:h3.font-bold.mb-4 "Exchanges This Round"]
         [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8

          ;; All sell cards using extracted component
          (sell-exchange-card "Soldiers" "soldiers-sold" (:soldier-sell rates) 
                              (:player/soldiers player) player-id hx-include)
          (sell-exchange-card "Fighters" "fighters-sold" (:fighter-sell rates) 
                              (:player/fighters player) player-id hx-include)
          (sell-exchange-card "Stations" "stations-sold" (:station-sell rates) 
                              (:player/stations player) player-id hx-include)
          (sell-exchange-card "Ore Planets" "ore-planets-sold" (:ore-planet-sell rates) 
                              (:player/ore-planets player) player-id hx-include)
          (sell-exchange-card "Food Planets" "food-planets-sold" (:food-planet-sell rates) 
                              (:player/food-planets player) player-id hx-include)
          (sell-exchange-card "Military Planets" "mil-planets-sold" (:mil-planet-sell rates) 
                              (:player/mil-planets player) player-id hx-include)
          (sell-exchange-card "Food" "food-sold" (:food-sell rates) 
                              (:player/food player) player-id hx-include)
          (sell-exchange-card "Fuel" "fuel-sold" (:fuel-sell rates) 
                              (:player/fuel player) player-id hx-include)

          ;; Invisible placeholder for grid alignment (only visible on lg screens)
          [:div.hidden.lg:block.invisible.border.border-green-400.p-4]

          ;; All buy cards using extracted component
          (buy-exchange-card "Food" "food-bought" (:food-buy rates) player-id hx-include)
          (buy-exchange-card "Fuel" "fuel-bought" (:fuel-buy rates) player-id hx-include)

          ;; Invisible placeholder for grid alignment (only visible on lg screens)
          [:div.hidden.lg:block.invisible.border.border-green-400.p-4]]

         ;;; Resources After Exchange:
         ;;; This section is dynamically updated by HTMX as the user changes input values.
         ;;; Initially shows current resources (no exchanges applied yet).
         [:div#resources-after.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
          [:h3.font-bold.mb-4 "Resources After Exchange"]
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
            [:p.font-mono (:player/stations player)]]
           [:div
            [:p.text-xs "Mil Plts"]
            [:p.font-mono (:player/mil-planets player)]]
           [:div
            [:p.text-xs "Food Plts"]
            [:p.font-mono (:player/food-planets player)]]
           [:div
            [:p.text-xs "Ore Plts"]
            [:p.font-mono (:player/ore-planets player)]]]]

         ;; Warning message area - populated by HTMX if player can't afford exchanges
         [:div#exchange-warning.h-8.flex.items-center]
         
         ;; Navigation and submit buttons
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id "/expenses")} "Cancel Exchange"]
          ;; Initial button starts disabled - HTMX updates will enable it when valid
          (submit-button false)])
       ])))
