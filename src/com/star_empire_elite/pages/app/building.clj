;;;;;
;;;;; Building Phase - Purchasing Units and Planets
;;;;;
;;;;; The building phase is the third phase of each turn, where players spend credits to purchase
;;;;; military units, support units, and planets. Unlike expenses (which cost multiple resources),
;;;;; building only costs credits.
;;;;;
;;;;; This phase uses htmx for dynamic validation, showing players in real-time whether they can
;;;;; afford their selected purchases before submitting.
;;;;;

(ns com.star-empire-elite.pages.app.building
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

;;;;
;;;; Calculations
;;;;

;;; Calculate total cost of purchases using game constants
(defn calculate-purchase-cost
  "Calculate total credits needed for all purchases based on game constants.
   Returns map with total cost."
  [quantities game]
  (let [total-cost (+ (* (:soldiers quantities)     (:game/soldier-cost game))
                      (* (:transports quantities)   (:game/transport-cost game))
                      (* (:generals quantities)     (:game/general-cost game))
                      (* (:carriers quantities)     (:game/carrier-cost game))
                      (* (:fighters quantities)     (:game/fighter-cost game))
                      (* (:admirals quantities)     (:game/admiral-cost game))
                      (* (:stations quantities)     (:game/station-cost game))
                      (* (:cmd-ships quantities)    (:game/cmd-ship-cost game))
                      (* (:mil-planets quantities)  (:game/mil-planet-cost game))
                      (* (:food-planets quantities) (:game/food-planet-cost game))
                      (* (:ore-planets quantities)  (:game/ore-planet-cost game)))]
    {:total-cost total-cost}))

;;; Calculate all resources after purchases
(defn calculate-resources-after-purchases
  "Calculate player resources after executing purchases.
   Returns map of all resource values after purchases."
  [player quantities cost-info]
  {:credits      (- (:player/credits player)      (:total-cost cost-info))
   :soldiers     (+ (:player/soldiers player)     (:soldiers quantities))
   :transports   (+ (:player/transports player)   (:transports quantities))
   :generals     (+ (:player/generals player)     (:generals quantities))
   :carriers     (+ (:player/carriers player)     (:carriers quantities))
   :fighters     (+ (:player/fighters player)     (:fighters quantities))
   :admirals     (+ (:player/admirals player)     (:admirals quantities))
   :stations     (+ (:player/stations player)     (:stations quantities))
   :cmd-ships    (+ (:player/cmd-ships player)    (:cmd-ships quantities))
   :mil-planets  (+ (:player/mil-planets player)       (:mil-planets quantities))
   :food-planets (+ (:player/food-planets player) (:food-planets quantities))
   :ore-planets  (+ (:player/ore-planets player)  (:ore-planets quantities))})

;;; Validate purchase is affordable
(defn can-afford-purchases?
  "Returns true if player has enough credits for all purchases"
  [resources-after]
  (>= (:credits resources-after) 0))

;;;;
;;;; Actions
;;;;
;;;; There are three parts to the actions for this phase: (i) building-page, which shows the purchase 
;;;; options and input fields, (ii) calculate-building which provides htmx dynamic updates as the 
;;;; user changes values, and (iii) apply-building which commits the purchases to the database and 
;;;; advances to phase 4.
;;;;

;;; Applies purchases and advances to the next phase. Uses pure calculation functions to
;;; determine resource changes, then commits them in a single transaction.
(defn apply-building [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Phase validation - only allow building if player is in phase 3
    (if-let [redirect (utils/validate-phase player 3 player-id)]
      redirect
      (let [;; Parse all purchase inputs using shared utility
            quantities {:soldiers    (utils/parse-numeric-input (:soldiers params))
                       :transports   (utils/parse-numeric-input (:transports params))
                       :generals     (utils/parse-numeric-input (:generals params))
                       :carriers     (utils/parse-numeric-input (:carriers params))
                       :fighters     (utils/parse-numeric-input (:fighters params))
                       :admirals     (utils/parse-numeric-input (:admirals params))
                       :stations     (utils/parse-numeric-input (:stations params))
                       :cmd-ships    (utils/parse-numeric-input (:cmd-ships params))
                       :mil-planets  (utils/parse-numeric-input (:mil-planets params))
                       :food-planets (utils/parse-numeric-input (:food-planets params))
                       :ore-planets  (utils/parse-numeric-input (:ore-planets params))}
            
            ;; Use pure calculation functions to determine final resource values
            cost-info (calculate-purchase-cost quantities game)
            resources-after (calculate-resources-after-purchases player quantities cost-info)]
        
        ;; Validate player can afford purchases
        (if (not (can-afford-purchases? resources-after))
          {:status 400
           :body "Insufficient credits for purchase"}
          ;; Single atomic transaction updates resources and advances phase together
          ;; This prevents partial updates if something fails midway through
          (do
            (biff/submit-tx ctx
              [{:db/doc-type :player
                :db/op :update
                :xt/id player-id
                :player/credits      (:credits resources-after)
                :player/soldiers     (:soldiers resources-after)
                :player/transports   (:transports resources-after)
                :player/generals     (:generals resources-after)
                :player/carriers     (:carriers resources-after)
                :player/fighters     (:fighters resources-after)
                :player/admirals     (:admirals resources-after)
                :player/stations     (:stations resources-after)
                :player/cmd-ships    (:cmd-ships resources-after)
                :player/mil-planets  (:mil-planets resources-after)
                :player/food-planets (:food-planets resources-after)
                :player/ore-planets  (:ore-planets resources-after)
                :player/current-phase 4}])
            {:status 303
             :headers {"location" (str "/app/game/" player-id "/action")}}))))))

;;; Provides HTMX dynamic updates showing resources after purchases as user changes input values.
;;; This gives immediate feedback on whether the player can afford their selected purchases.
(defn calculate-building [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [;; Parse all purchase inputs using shared utility
          quantities {:soldiers    (utils/parse-numeric-input (:soldiers params))
                     :transports   (utils/parse-numeric-input (:transports params))
                     :generals     (utils/parse-numeric-input (:generals params))
                     :carriers     (utils/parse-numeric-input (:carriers params))
                     :fighters     (utils/parse-numeric-input (:fighters params))
                     :admirals     (utils/parse-numeric-input (:admirals params))
                     :stations     (utils/parse-numeric-input (:stations params))
                     :cmd-ships    (utils/parse-numeric-input (:cmd-ships params))
                     :mil-planets  (utils/parse-numeric-input (:mil-planets params))
                     :food-planets (utils/parse-numeric-input (:food-planets params))
                     :ore-planets  (utils/parse-numeric-input (:ore-planets params))}
        
          ;; Use pure calculation functions
          cost-info (calculate-purchase-cost quantities game)
          resources-after (calculate-resources-after-purchases player quantities cost-info)
          affordable? (can-afford-purchases? resources-after)]
    
      ;; Render HTMX response fragments that replace specific page elements
      (biff/render
        [:div
         ;; Resources display with red highlighting for insufficient credits - use shared component
         [:div#resources-after
          (ui/extended-resource-display-grid 
            (assoc resources-after
                   :food (:player/food player)
                   :fuel (:player/fuel player)
                   :galaxars (:player/galaxars player))
            "Resources After Purchases"
            true)]  ; Enable negative highlighting
         
         ;; Cost summary
         [:div#cost-summary.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
          {:hx-swap-oob "true"}
          [:h3.font-bold.mb-2 "Total Cost"]
          [:p.text-2xl.font-mono (:total-cost cost-info) " credits"]]
         
         ;; Warning message if purchases exceed available credits
         [:div#building-warning.h-8.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.font-bold "WARNING: Insufficient credits for purchases!"])]
         
         ;; Submit button - disabled if player can't afford purchases
         [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
          {:type "submit"
           :disabled (not affordable?)
           :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"
           :hx-swap-oob "true"}
          "Complete Purchases"]]))))


;;; Shows building options and input fields for player to purchase units and planets
(defn building-page [{:keys [player game]}]
  (let [player-id (:xt/id player)
        hx-include "[name='soldiers'],[name='transports'],[name='generals'],[name='carriers'],[name='fighters'],[name='admirals'],[name='stations'],[name='cmd-ships'],[name='mil-planets'],[name='food-planets'],[name='ore-planets']"]
    (ui/page
      {}
      [:div.text-green-400.font-mono
       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "BUILDING")

       ;;; Current Resources Display - using shared extended component
       (ui/extended-resource-display-grid player "Resources Before Purchases")

       ;;; Purchase Input Form:
       ;;; Player chooses what to buy. HTMX provides real-time feedback on total cost and
       ;;; whether they can afford the purchases.
       (biff/form
         {:action (str "/app/game/" player-id "/apply-building")
          :method "post"}

         [:h3.font-bold.mb-4 "Purchases This Round"]
         [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8

          ;; Purchase soldiers
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Soldiers"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/soldier-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "soldiers" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase transports
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Transports"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/transport-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "transports" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase generals
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Generals"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/general-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "generals" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase carriers
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Carriers"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/carrier-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "carriers" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase fighters
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Fighters"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/fighter-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "fighters" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase admirals
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Admirals"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/admiral-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "admirals" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase defence stations
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Defence Stations"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/station-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "stations" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase command ships
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Command Ships"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/cmd-ship-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "cmd-ships" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase military planets
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Military Planets"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/mil-planet-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "mil-planets" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase food planets
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Food Planets"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/food-planet-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "food-planets" 0 player-id "/calculate-building" hx-include)]]]

          ;; Purchase ore planets
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Ore Planets"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Cost per Unit"]
             [:p.font-mono (str (:game/ore-planet-cost game) " credits")]]
            [:div
             [:label.text-xs "Purchase Quantity"]
             (ui/numeric-input "ore-planets" 0 player-id "/calculate-building" hx-include)]]]]

         ;;; Resources After Purchases - using shared extended component
         ;;; This section is dynamically updated by HTMX as the user changes input values.
         [:div#resources-after
          (ui/extended-resource-display-grid player "Resources After Purchases")]

         ;; Warning message area - populated by HTMX if player can't afford purchases
         [:div#building-warning.h-8.flex.items-center]
         
         ;; Navigation and submit buttons
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id)} "Back to Game"]
          [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
           {:type "submit"
            :disabled true
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           "Complete Purchases"]])
       ])))
