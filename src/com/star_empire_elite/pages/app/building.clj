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

;;; Parse purchase quantities from request parameters
(defn parse-purchase-quantities
  "Parse all purchase quantity inputs from request params.
   Returns map of quantities for all purchasable items."
  [params]
  {:soldiers     (utils/parse-numeric-input (:soldiers params))
   :transports   (utils/parse-numeric-input (:transports params))
   :generals     (utils/parse-numeric-input (:generals params))
   :carriers     (utils/parse-numeric-input (:carriers params))
   :fighters     (utils/parse-numeric-input (:fighters params))
   :admirals     (utils/parse-numeric-input (:admirals params))
   :stations     (utils/parse-numeric-input (:stations params))
   :cmd-ships    (utils/parse-numeric-input (:cmd-ships params))
   :ore-planets  (utils/parse-numeric-input (:ore-planets params))
   :food-planets (utils/parse-numeric-input (:food-planets params))
   :mil-planets  (utils/parse-numeric-input (:mil-planets params))})

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
                      (* (:ore-planets quantities)  (:game/ore-planet-cost game))
                      (* (:food-planets quantities) (:game/food-planet-cost game))
                      (* (:mil-planets quantities)  (:game/mil-planet-cost game)))]
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
   :ore-planets  (+ (:player/ore-planets player)  (:ore-planets quantities))
   :food-planets (+ (:player/food-planets player) (:food-planets quantities))
   :mil-planets  (+ (:player/mil-planets player)  (:mil-planets quantities))})

;;; Validate purchase is affordable
(defn can-afford-purchases?
  "Returns true if player has enough credits for all purchases"
  [resources-after]
  (>= (:credits resources-after) 0))

;;; Calculate maximum affordable quantity for each item type
(defn calculate-max-quantities
  "Calculate the maximum quantity of each item that can be purchased with remaining credits.
   Takes into account credits already spent on other items in current selections.

   Parameters:
   - player: Player entity with current credits
   - quantities: Map of current purchase quantities
   - game: Game entity with cost constants

   Returns: Map of item-key to max affordable quantity"
  [player quantities game]
  (let [total-cost-so-far (+ (* (:soldiers quantities)     (:game/soldier-cost game))
                             (* (:transports quantities)   (:game/transport-cost game))
                             (* (:generals quantities)     (:game/general-cost game))
                             (* (:carriers quantities)     (:game/carrier-cost game))
                             (* (:fighters quantities)     (:game/fighter-cost game))
                             (* (:admirals quantities)     (:game/admiral-cost game))
                             (* (:stations quantities)     (:game/station-cost game))
                             (* (:cmd-ships quantities)    (:game/cmd-ship-cost game))
                             (* (:ore-planets quantities)  (:game/ore-planet-cost game))
                             (* (:food-planets quantities) (:game/food-planet-cost game))
                             (* (:mil-planets quantities)  (:game/mil-planet-cost game)))
        remaining-credits (- (:player/credits player) total-cost-so-far)]
    {:soldiers     (quot remaining-credits (:game/soldier-cost game))
     :transports   (quot remaining-credits (:game/transport-cost game))
     :generals     (quot remaining-credits (:game/general-cost game))
     :carriers     (quot remaining-credits (:game/carrier-cost game))
     :fighters     (quot remaining-credits (:game/fighter-cost game))
     :admirals     (quot remaining-credits (:game/admiral-cost game))
     :stations     (quot remaining-credits (:game/station-cost game))
     :cmd-ships    (quot remaining-credits (:game/cmd-ship-cost game))
     :ore-planets  (quot remaining-credits (:game/ore-planet-cost game))
     :food-planets (quot remaining-credits (:game/food-planet-cost game))
     :mil-planets  (quot remaining-credits (:game/mil-planet-cost game))}))

;;;;
;;;; UI Components
;;;;

;;; Submit button component - extracted to avoid duplication
(defn submit-button
  "Renders the submit button with dynamic disabled state based on affordability.
   Used in both initial page render and HTMX updates.
   Accepts optional extra-attrs map for additional HTML attributes."
  ([affordable?] (submit-button affordable? {}))
  ([affordable? extra-attrs]
   [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
    (merge {:type "submit"
            :disabled (not affordable?)
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           extra-attrs)
    "Continue to Action"]))

;;; Purchase row component using phase-row for responsive layout with dynamic cost
(defn purchase-row
  "Renders a purchase row that displays as a card on mobile and as a table row on wide screens.
   Includes dynamic Max Quantity and Cost columns.

   Parameters:
   - unit-name: Display name (e.g. 'Soldiers', 'Defence Stations')
   - unit-key: Keyword for form field (e.g. :soldiers, :stations)
   - cost-key: Keyword for game cost constant (e.g. :game/soldier-cost)
   - current-quantity: Current quantity value from params (defaults to 0)
   - max-quantity: Maximum affordable quantity (defaults to 0, updated by HTMX)
   - game: Game configuration map
   - player-id: Player identifier for HTMX routing
   - hx-include: HTMX include string for related fields"
  [unit-name unit-key cost-key current-quantity max-quantity game player-id hx-include]
  (let [cost-per-unit (get game cost-key)
        item-cost (* cost-per-unit current-quantity)
        cost-id (str "cost-" (name unit-key))
        max-qty-id (str "max-qty-" (name unit-key))]  ; ID for max quantity element
    (ui/phase-row
      [{:label "Item"
        :value unit-name
        :class "pr-4"}  ; Right padding for spacing
       {:label "Credits per Unit"
        :value cost-per-unit
        :class "text-right pr-4"}  ; Right-justified with padding
       {:label "Maximum Quantity"
        :value [:span {:id max-qty-id 
                       :class (cond
                                (< max-quantity 0) "text-red-400"
                                (zero? max-quantity) "opacity-20"
                                :else "")} 
                (ui/format-number max-quantity)]
        :class "text-right pr-4"  ; Right-justified with padding
        :hide-on-mobile? false}  ; Show on mobile since it's useful info
       {:label "Purchase Quantity"
        :component (ui/numeric-input (name unit-key) 0 player-id "/calculate-building" hx-include)}
       {:label "Cost in Credits"
        :value [:span {:id cost-id
                       :class (when (zero? item-cost) "opacity-20")} 
                "+" (ui/format-number item-cost)]
        :class "text-right pr-4"  ; Right-justified with padding
        :hide-on-mobile? false}])))  ; Show cost on mobile too since it's important info

;;; Total cost row component - displayed as the last row in the table
(defn total-cost-row
  "Renders the total cost as a row at the bottom of the table.
   On mobile, shows as a card. On wide screens, shows as a table row with the total in the last column."
  [total-cost]
  [:div#cost-summary.border-t.border-green-400
   
   ;; Mobile/Tablet: Card layout
   [:div.lg:hidden.p-4.bg-green-400.bg-opacity-10
    [:div.flex.justify-between.items-center
     [:span.font-bold.text-lg "Total Cost:"]
     [:span.font-mono.text-xl (ui/format-number total-cost) " credits"]]]
   
   ;; Wide screen: Table row layout aligned with columns above
   [:div.hidden.lg:grid.lg:gap-4.lg:items-center.lg:px-4.lg:py-3.lg:bg-green-400.lg:bg-opacity-10.font-bold
    {:style {:grid-template-columns "repeat(5, minmax(0, 1fr))"}}
    [:div.pr-4 ""]  ; Empty Item column
    [:div.text-right.pr-4 ""]  ; Empty Credits per Unit column
    [:div.text-right.pr-4 ""]  ; Empty Max Quantity column
    [:div "Total Cost in Credits:"]  ; Label in Purchase Quantity column
    [:div.text-right.pr-4 (ui/format-number total-cost)]]])  ; Total in Cost column

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
      (let [;; Parse all purchase inputs using extracted function
            quantities (parse-purchase-quantities params)

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
                              :player/ore-planets  (:ore-planets resources-after)
                              :player/food-planets (:food-planets resources-after)
                              :player/mil-planets  (:mil-planets resources-after)
                              :player/current-phase 4}])
            {:status 303
             :headers {"location" (str "/app/game/" player-id "/action")}}))))))

;;; Provides HTMX dynamic updates showing resources after purchases as user changes input values.
;;; This gives immediate feedback on whether the player can afford their selected purchases.
(defn calculate-building [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [;; Parse all purchase inputs using extracted function
          quantities (parse-purchase-quantities params)

          ;; Use pure calculation functions
          cost-info (calculate-purchase-cost quantities game)
          resources-after (calculate-resources-after-purchases player quantities cost-info)
          affordable? (can-afford-purchases? resources-after)

          ;; Calculate maximum affordable quantities for each item
          max-quantities (calculate-max-quantities player quantities game)

          ;; Calculate individual item costs for display
          item-costs {:soldiers     (* (:soldiers quantities)     (:game/soldier-cost game))
                      :transports   (* (:transports quantities)   (:game/transport-cost game))
                      :generals     (* (:generals quantities)     (:game/general-cost game))
                      :carriers     (* (:carriers quantities)     (:game/carrier-cost game))
                      :fighters     (* (:fighters quantities)     (:game/fighter-cost game))
                      :admirals     (* (:admirals quantities)     (:game/admiral-cost game))
                      :stations     (* (:stations quantities)     (:game/station-cost game))
                      :cmd-ships    (* (:cmd-ships quantities)    (:game/cmd-ship-cost game))
                      :ore-planets  (* (:ore-planets quantities)  (:game/ore-planet-cost game))
                      :food-planets (* (:food-planets quantities) (:game/food-planet-cost game))
                      :mil-planets  (* (:mil-planets quantities)  (:game/mil-planet-cost game))}]

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
            "Resources After Building"
            true)]  ; Enable negative highlighting

         ;; Total cost row update (out-of-band)
         [:div#cost-summary
          {:hx-swap-oob "true"}
          (total-cost-row (:total-cost cost-info))]

         ;; Individual max quantity updates (out-of-band)
         (for [[item-key max-qty] max-quantities]
           [:span {:id (str "max-qty-" (name item-key)) 
                   :hx-swap-oob "true" 
                   :key (str "max-" item-key)
                   :class (cond
                            (< max-qty 0) "text-red-400"
                            (zero? max-qty) "opacity-20"
                            :else "")}
            (ui/format-number max-qty)])

         ;; Individual item cost updates (out-of-band)
         (for [[item-key cost] item-costs]
           [:span {:id (str "cost-" (name item-key)) 
                   :hx-swap-oob "true" 
                   :key item-key
                   :class (when (zero? cost) "opacity-20")}
            "+" (ui/format-number cost)])

         ;; Warning message if purchases exceed available credits
         [:div#building-warning.h-8.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.font-bold "WARNING: Insufficient credits for purchases!"])]

         ;; Submit button - dynamically enabled/disabled based on affordability
         (submit-button affordable? {:hx-swap-oob "true"})]))))



;;; Shows building options and input fields for player to purchase units and planets
(defn building-page [{:keys [player game]}]
  (let [player-id (:xt/id player)
        hx-include "[name='soldiers'],[name='transports'],[name='generals'],[name='carriers'],[name='fighters'],[name='admirals'],[name='stations'],[name='cmd-ships'],[name='ore-planets'],[name='food-planets'],[name='mil-planets']"]
    (ui/page
      {}
      [:div.text-green-400.font-mono
       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "BUILDING")

       ;;; Current Resources Display - using shared extended component
       (ui/extended-resource-display-grid player "Resources Before Building")

       ;;; Purchase Input Form:
       ;;; Player chooses what to buy. HTMX provides real-time feedback on total cost and
       ;;; whether they can afford the purchases.
       (biff/form
         {:action (str "/app/game/" player-id "/apply-building")
          :method "post"}

         [:h3.font-bold.mb-4 "Building This Round"]

         ;; Table container with header and rows
         [:div.border.border-green-400.mb-8
          ;; Header row (only visible on wide screens)
          (ui/phase-table-header 
            [{:label "Item" :class "pr-4"}
             {:label "Credits per Unit" :class "text-right pr-4"}
             {:label "Maximum Quantity" :class "text-right pr-4"}
             {:label "Purchase Quantity" :class "pr-4"}
             {:label "Cost in Credits" :class "text-right pr-4"}])

          ;; All purchase rows using extracted component - pass 0 for initial quantity and max quantity
          (purchase-row "Soldiers" :soldiers :game/soldier-cost 0 0 game player-id hx-include)
          (purchase-row "Transports" :transports :game/transport-cost 0 0 game player-id hx-include)
          (purchase-row "Generals" :generals :game/general-cost 0 0 game player-id hx-include)
          (purchase-row "Carriers" :carriers :game/carrier-cost 0 0 game player-id hx-include)
          (purchase-row "Fighters" :fighters :game/fighter-cost 0 0 game player-id hx-include)
          (purchase-row "Admirals" :admirals :game/admiral-cost 0 0 game player-id hx-include)
          (purchase-row "Defence Stations" :stations :game/station-cost 0 0 game player-id hx-include)
          (purchase-row "Command Ships" :cmd-ships :game/cmd-ship-cost 0 0 game player-id hx-include)
          (purchase-row "Ore Planets" :ore-planets :game/ore-planet-cost 0 0 game player-id hx-include)
          (purchase-row "Food Planets" :food-planets :game/food-planet-cost 0 0 game player-id hx-include)
          (purchase-row "Military Planets" :mil-planets :game/mil-planet-cost 0 0 game player-id hx-include)
          
          ;; Total cost row as last row in table - updated by HTMX
          (total-cost-row 0)]

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
          ;; Initial button starts disabled - HTMX updates will enable it when affordable
          (submit-button false)])
       ])))
