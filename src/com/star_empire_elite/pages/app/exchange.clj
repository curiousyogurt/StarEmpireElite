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
   :transport-sell const/transport-sell
   :general-sell const/general-sell
   :fighter-sell const/fighter-sell
   :carrier-sell const/carrier-sell
   :admiral-sell const/admiral-sell
   :station-sell const/station-sell
   :cmd-ship-sell const/cmd-ship-sell
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
   :transports-sold (utils/parse-numeric-input (:transports-sold params))
   :generals-sold (utils/parse-numeric-input (:generals-sold params))
   :fighters-sold (utils/parse-numeric-input (:fighters-sold params))
   :carriers-sold (utils/parse-numeric-input (:carriers-sold params))
   :admirals-sold (utils/parse-numeric-input (:admirals-sold params))
   :stations-sold (utils/parse-numeric-input (:stations-sold params))
   :cmd-ships-sold (utils/parse-numeric-input (:cmd-ships-sold params))
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
  (let [credits-from-sales        (+ (* (:soldiers-sold quantities)     (:soldier-sell rates))
                                     (* (:transports-sold quantities)   (:transport-sell rates))
                                     (* (:generals-sold quantities)     (:general-sell rates))
                                     (* (:fighters-sold quantities)     (:fighter-sell rates))
                                     (* (:carriers-sold quantities)     (:carrier-sell rates))
                                     (* (:admirals-sold quantities)     (:admiral-sell rates))
                                     (* (:stations-sold quantities)     (:station-sell rates))
                                     (* (:cmd-ships-sold quantities)    (:cmd-ship-sell rates))
                                     (* (:mil-planets-sold quantities)  (:mil-planet-sell rates))
                                     (* (:food-planets-sold quantities) (:food-planet-sell rates))
                                     (* (:ore-planets-sold quantities)  (:ore-planet-sell rates)))
        credits-from-resources (- (+ (* (:food-sold quantities)         (:food-sell rates))
                                     (* (:fuel-sold quantities)         (:fuel-sell rates)))
                                  (+ (* (:food-bought quantities)       (:food-buy rates))
                                     (* (:fuel-bought quantities)       (:fuel-buy rates))))]
    {:credits-from-sales credits-from-sales
     :credits-from-resources credits-from-resources
     :total-credits (+ credits-from-sales credits-from-resources)}))

;;; Calculate maximum quantities that can be bought based on available credits after selling
(defn calculate-max-buy-quantities
  "Calculate maximum quantities that can be purchased with available credits after all sales.
  Returns map with max quantities for each buyable item."
  [player sell-quantities rates]
  (let [sell-credit-changes (calculate-exchange-credits
                              (assoc sell-quantities
                                     :food-bought 0 :fuel-bought 0)
                              rates)
        total-available-credits (+ (:player/credits player) (:total-credits sell-credit-changes))]
    {:max-food (max 0 (quot total-available-credits (:food-buy rates)))
     :max-fuel (max 0 (quot total-available-credits (:fuel-buy rates)))}))

;;; Calculate all resources after exchange
(defn calculate-resources-after-exchange
  "Calculate all player resources after executing exchanges.
  Returns map of all resource values after exchange."
  [player quantities credit-changes]
  {:credits      (+ (:player/credits player)      (:total-credits credit-changes))
   :soldiers     (- (:player/soldiers player)     (:soldiers-sold quantities))
   :transports   (- (:player/transports player)   (:transports-sold quantities))
   :generals     (- (:player/generals player)     (:generals-sold quantities))
   :carriers     (- (:player/carriers player)     (:carriers-sold quantities))
   :fighters     (- (:player/fighters player)     (:fighters-sold quantities))
   :admirals     (- (:player/admirals player)     (:admirals-sold quantities))
   :stations     (- (:player/stations player)     (:stations-sold quantities))
   :cmd-ships    (- (:player/cmd-ships player)    (:cmd-ships-sold quantities))
   :mil-planets  (- (:player/mil-planets player)  (:mil-planets-sold quantities))
   :food-planets (- (:player/food-planets player) (:food-planets-sold quantities))
   :ore-planets  (- (:player/ore-planets player)  (:ore-planets-sold quantities))
   :food (+ (:player/food player) (:food-bought quantities) (- (:food-sold quantities)))
   :fuel (+ (:player/fuel player) (:fuel-bought quantities) (- (:fuel-sold quantities)))
   :galaxars (:player/galaxars player)})

;;; Validate exchange is legal
(defn valid-exchange?
  "Returns true if player can execute the exchange (all resources non-negative)"
  [resources-after]
  (and (>= (:credits resources-after) 0)
       (>= (:soldiers resources-after) 0)
       (>= (:transports resources-after) 0)
       (>= (:generals resources-after) 0)
       (>= (:carriers resources-after) 0)
       (>= (:fighters resources-after) 0)
       (>= (:admirals resources-after) 0)
       (>= (:stations resources-after) 0)
       (>= (:cmd-ships resources-after) 0)
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
   :invalid-transport-sale? (< (:transports resources-after) 0)
   :invalid-general-sale? (< (:generals resources-after) 0)
   :invalid-fighter-sale? (< (:fighters resources-after) 0)
   :invalid-carrier-sale? (< (:carriers resources-after) 0)
   :invalid-admiral-sale? (< (:admirals resources-after) 0)
   :invalid-station-sale? (< (:stations resources-after) 0)
   :invalid-cmd-ship-sale? (< (:cmd-ships resources-after) 0)
   :invalid-mil-planet-sale? (< (:mil-planets resources-after) 0)
   :invalid-food-planet-sale? (< (:food-planets resources-after) 0)
   :invalid-ore-planet-sale? (< (:ore-planets resources-after) 0)
   :invalid-food-sale? (and (> (:food-sold quantities) 0) (< (:food resources-after) 0))
   :invalid-fuel-sale? (and (> (:fuel-sold quantities) 0) (< (:fuel resources-after) 0))
   :invalid-food-purchase? (and (> (:food-bought quantities) 0) (< (:credits resources-after) 0))
   :invalid-fuel-purchase? (and (> (:fuel-bought quantities) 0) (< (:credits resources-after) 0))})

;;;;
;;;; UI Components for Table-Based Layout
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

;;; Sell row with single input field, CSS-only responsive styling (like building page)
(defn sell-row
  "Renders a sell row with ONE input field that's just restyled for mobile vs desktop.
  Uses responsive CSS grid - no duplicate inputs, no JavaScript sync needed.

  Parameters:
  - item-name: Full display name (e.g. 'Soldiers', 'Defence Stations')
  - item-name-mobile: Abbreviated name for mobile (e.g. 'Soldiers', 'Def Stns')
  - field-key: Form field key (e.g. 'soldiers-sold')
  - price-per-unit: Credits received per unit sold
  - current-quantity: Current input value
  - max-quantity: Maximum quantity available to sell
  - player-id: Player UUID
  - hx-include: HTMX include selector string"
  [item-name item-name-mobile field-key price-per-unit current-quantity max-quantity player-id hx-include]
  (let [credit-gain (* price-per-unit current-quantity)
        credit-id (str "credit-" field-key)
        max-qty-id (str "max-qty-" field-key)]
    ;; Single grid that changes layout responsively - one input, just restyled
    [:div.border-b.border-green-400.last:border-b-0.grid.items-center.gap-1.px-2.py-2.text-xs.leading-tight.lg:gap-3.lg:px-4.lg:py-2.lg:text-base.exchange-row-grid

     ;; Col 1: Item name (abbreviated on mobile, full on desktop)
     [:div.font-mono.lg:pr-4
      [:span.lg:hidden item-name-mobile]
      [:span.hidden.lg:inline.whitespace-nowrap item-name]]

     ;; Col 2: Credits per unit
     [:div.text-right.font-mono.lg:pr-4
      (ui/format-number price-per-unit)]

     ;; Col 3: Maximum quantity available
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id max-qty-id
              :class (when (zero? max-quantity) "opacity-20")}
       (ui/format-number (if (neg? max-quantity) 0 max-quantity))]]

     ;; Col 4: Sell Quantity - SINGLE input field with responsive sizing
     [:div.px-1.lg:pr-4
      (ui/numeric-input field-key current-quantity player-id "/calculate-exchange" hx-include
                        {:input-class "py-0.5 text-xs lg:py-1 lg:text-sm"})]

     ;; Col 5: Credits gained
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id credit-id
              :class (when (zero? credit-gain) "opacity-20")}
       [:<> "+" (ui/format-number credit-gain)]]]]))

;;; Buy row with single input field, CSS-only responsive styling (like building page)
(defn buy-row
  "Renders a buy row with ONE input field that's just restyled for mobile vs desktop.
  Uses responsive CSS grid - no duplicate inputs, no JavaScript sync needed.

  Parameters:
  - item-name: Full display name (e.g. 'Food', 'Fuel')
  - item-name-mobile: Abbreviated name for mobile (e.g. 'Food', 'Fuel')
  - field-key: Form field key (e.g. 'food-bought')
  - price-per-unit: Credits per unit cost
  - current-quantity: Current input value
  - max-quantity: Maximum quantity affordable
  - player-id: Player UUID
  - hx-include: HTMX include selector string"
  [item-name item-name-mobile field-key price-per-unit current-quantity max-quantity player-id hx-include]
  (let [credit-cost (* price-per-unit current-quantity)
        credit-id (str "credit-" field-key)
        max-qty-id (str "max-qty-" field-key)]
    ;; Single grid that changes layout responsively - one input, just restyled
    [:div.border-b.border-green-400.last:border-b-0.grid.items-center.gap-1.px-2.py-2.text-xs.leading-tight.lg:gap-3.lg:px-4.lg:py-2.lg:text-base.exchange-row-grid

     ;; Col 1: Item name (abbreviated on mobile, full on desktop)
     [:div.font-mono.lg:pr-4
      [:span.lg:hidden item-name-mobile]
      [:span.hidden.lg:inline.whitespace-nowrap item-name]]

     ;; Col 2: Credits per unit
     [:div.text-right.font-mono.lg:pr-4
      (ui/format-number price-per-unit)]

     ;; Col 3: Maximum quantity affordable
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id max-qty-id
              :class (when (zero? max-quantity) "opacity-20")}
       (ui/format-number (if (neg? max-quantity) 0 max-quantity))]]

     ;; Col 4: Buy Quantity - SINGLE input field with responsive sizing
     [:div.px-1.lg:pr-4
      (ui/numeric-input field-key current-quantity player-id "/calculate-exchange" hx-include
                        {:input-class "py-0.5 text-xs lg:py-1 lg:text-sm"})]

     ;; Col 5: Credits cost
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id credit-id
              :class (when (zero? credit-cost) "opacity-20")}
       [:<> "+" (ui/format-number credit-cost)]]]]))

;;; Table-level total row for sell/buy tables
(defn table-total-row
  "Renders a total row at the bottom of a sell or buy table.
  Shows the total in the Credits column aligned with the Item label."
  [row-id label total]
  [:div {:id row-id :class "border-t-2 border-green-400 bg-green-400 bg-opacity-10"}
   ;; Mobile
   [:div.grid.gap-1.px-2.py-3.font-bold.text-sm.lg:hidden
    {:style {:grid-template-columns "0.9fr 0.8fr 0.7fr 1.1fr 0.9fr"}}
    [:div label]
    [:div] [:div] [:div]
    [:div.text-right.font-mono.text-base
     {:class (when (zero? total) "opacity-20")}
     (ui/format-number total)]]
   ;; Desktop
   [:div.hidden.lg:grid.lg:gap-3.lg:px-4.lg:py-2.lg:font-bold
    {:style {:grid-template-columns "1.5fr 1fr 1fr 1fr 1fr"}}
    [:div.text-lg label]
    [:div] [:div] [:div]
    [:div.text-right.font-mono.text-xl.pr-4
     {:class (when (zero? total) "opacity-20")}
     (ui/format-number total)]]])

;;; Total credits summary row
(defn total-credits-row
  "Renders the total credits gained summary at the bottom of the table.
  Shows credits in red if player tries to sell more than they have."
  [total-credits can-execute?]
  (let [abs-credits (Math/abs total-credits)
        formatted-credits (ui/format-number abs-credits)
        sign (if (>= total-credits 0) "+" "-")]
    [:div#cost-summary.border-green-400.bg-green-400.bg-opacity-10

     ;; Mobile: Compact layout
     [:div.grid.gap-1.px-2.py-3.font-bold.text-sm.lg:hidden
      {:style {:grid-template-columns "1.2fr 0.8fr 0.6fr 1fr 0.8fr"}}
      [:div.col-span-4.text-right.pr-4 "Credits:"]
      [:div.text-right.font-mono.text-base
       {:class (when (and (not can-execute?) (< total-credits 0)) "text-red-400")}
       [:<> sign formatted-credits]]]

     ;; Desktop: Full width layout
     [:div.hidden.lg:grid.lg:gap-3.lg:px-4.lg:py-2.lg:font-bold
      {:style {:grid-template-columns "1.5fr 1fr 1fr 1fr 1fr"}}
      [:div.col-span-4.text-right.text-lg.pr-4 "Credits:"]
      [:div.text-right.font-mono.text-xl
       {:class (when (and (not can-execute?) (< total-credits 0)) "text-red-400")}
       [:<> sign formatted-credits]]]]))

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
                          :player/transports (:transports resources-after)
                          :player/generals (:generals resources-after)
                          :player/carriers (:carriers resources-after)
                          :player/fighters (:fighters resources-after)
                          :player/admirals (:admirals resources-after)
                          :player/stations (:stations resources-after)
                          :player/cmd-ships (:cmd-ships resources-after)
                          :player/mil-planets (:mil-planets resources-after)
                          :player/food-planets (:food-planets resources-after)
                          :player/ore-planets (:ore-planets resources-after)
                          :player/food (:food resources-after)
                          :player/fuel (:fuel resources-after)}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/expenses")}}))))

;;; Provides HTMX dynamic updates showing resources after exchange as user changes input values.
(defn calculate-exchange [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [;; Parse all exchange inputs using extracted function
          quantities (parse-exchange-quantities params)

          ;; Use pure calculation functions
          rates (get-exchange-rates)
          credit-changes (calculate-exchange-credits quantities rates)
          resources-after (calculate-resources-after-exchange player quantities credit-changes)
          can-execute? (valid-exchange? resources-after)

          ;; Calculate maximums for buy quantities based on sell proceeds
          sell-quantities {:soldiers-sold (:soldiers-sold quantities)
                           :transports-sold (:transports-sold quantities)
                           :generals-sold (:generals-sold quantities)
                           :fighters-sold (:fighters-sold quantities)
                           :carriers-sold (:carriers-sold quantities)
                           :admirals-sold (:admirals-sold quantities)
                           :stations-sold (:stations-sold quantities)
                           :cmd-ships-sold (:cmd-ships-sold quantities)
                           :mil-planets-sold (:mil-planets-sold quantities)
                           :food-planets-sold (:food-planets-sold quantities)
                           :ore-planets-sold (:ore-planets-sold quantities)
                           :food-sold (:food-sold quantities)
                           :fuel-sold (:fuel-sold quantities)
                           :food-bought 0 :fuel-bought 0}
          max-buy-quantities (calculate-max-buy-quantities player sell-quantities rates)
          sell-total (+ (:credits-from-sales credit-changes)
                        (* (:food-sold quantities) (:food-sell rates))
                        (* (:fuel-sold quantities) (:fuel-sell rates)))
          buy-total (+ (* (:food-bought quantities) (:food-buy rates))
                       (* (:fuel-bought quantities) (:fuel-buy rates)))]

      ;; Render HTMX response fragments that replace specific page elements
      (biff/render
        [:div
         ;; Update individual credits for sell rows
         [:span {:id "credit-soldiers-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:soldiers-sold quantities) (:soldier-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:soldiers-sold quantities) (:soldier-sell rates)))]]
         [:span {:id "credit-transports-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:transports-sold quantities) (:transport-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:transports-sold quantities) (:transport-sell rates)))]]
         [:span {:id "credit-generals-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:generals-sold quantities) (:general-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:generals-sold quantities) (:general-sell rates)))]]
         [:span {:id "credit-fighters-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:fighters-sold quantities) (:fighter-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:fighters-sold quantities) (:fighter-sell rates)))]]
         [:span {:id "credit-carriers-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:carriers-sold quantities) (:carrier-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:carriers-sold quantities) (:carrier-sell rates)))]]
         [:span {:id "credit-admirals-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:admirals-sold quantities) (:admiral-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:admirals-sold quantities) (:admiral-sell rates)))]]
         [:span {:id "credit-stations-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:stations-sold quantities) (:station-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:stations-sold quantities) (:station-sell rates)))]]
         [:span {:id "credit-cmd-ships-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:cmd-ships-sold quantities) (:cmd-ship-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:cmd-ships-sold quantities) (:cmd-ship-sell rates)))]]
         [:span {:id "credit-mil-planets-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:mil-planets-sold quantities) (:mil-planet-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:mil-planets-sold quantities) (:mil-planet-sell rates)))]]
         [:span {:id "credit-food-planets-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:food-planets-sold quantities) (:food-planet-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:food-planets-sold quantities) (:food-planet-sell rates)))]]
         [:span {:id "credit-ore-planets-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:ore-planets-sold quantities) (:ore-planet-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:ore-planets-sold quantities) (:ore-planet-sell rates)))]]
         [:span {:id "credit-food-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:food-sold quantities) (:food-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:food-sold quantities) (:food-sell rates)))]]
         [:span {:id "credit-fuel-sold" :hx-swap-oob "true"
                 :class (when (zero? (* (:fuel-sold quantities) (:fuel-sell rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:fuel-sold quantities) (:fuel-sell rates)))]]

         ;; Update individual credits for buy rows
         [:span {:id "credit-food-bought" :hx-swap-oob "true"
                 :class (when (zero? (* (:food-bought quantities) (:food-buy rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:food-bought quantities) (:food-buy rates)))]]
         [:span {:id "credit-fuel-bought" :hx-swap-oob "true"
                 :class (when (zero? (* (:fuel-bought quantities) (:fuel-buy rates))) "opacity-20")}
          [:<> "+" (ui/format-number (* (:fuel-bought quantities) (:fuel-buy rates)))]]

         ;; Update buy-row maximum quantities
         [:span {:id "max-qty-food-bought" :hx-swap-oob "true"
                 :class (when (zero? (:max-food max-buy-quantities)) "opacity-20")}
          (ui/format-number (:max-food max-buy-quantities))]
         [:span {:id "max-qty-fuel-bought" :hx-swap-oob "true"
                 :class (when (zero? (:max-fuel max-buy-quantities)) "opacity-20")}
          (ui/format-number (:max-fuel max-buy-quantities))]

         ;; Resources display with red highlighting for negative values
         [:div#resources-after
          (ui/extended-resource-display-grid
            (assoc resources-after
                   :player/current-turn  (:player/current-turn player)
                   :player/current-round (:player/current-round player))
            "Resources After Exchange" true game)]

         ;; Warning message if exchange is invalid
         [:div#exchange-warning.flex.items-center
          {:hx-swap-oob "true"}
          (when (not can-execute?)
            (let [invalid-exchanges (identify-invalid-exchanges resources-after quantities)
                  selling-too-much? (or (:invalid-soldier-sale? invalid-exchanges)
                                        (:invalid-transport-sale? invalid-exchanges)
                                        (:invalid-general-sale? invalid-exchanges)
                                        (:invalid-fighter-sale? invalid-exchanges)
                                        (:invalid-carrier-sale? invalid-exchanges)
                                        (:invalid-admiral-sale? invalid-exchanges)
                                        (:invalid-station-sale? invalid-exchanges)
                                        (:invalid-cmd-ship-sale? invalid-exchanges)
                                        (:invalid-mil-planet-sale? invalid-exchanges)
                                        (:invalid-food-planet-sale? invalid-exchanges)
                                        (:invalid-ore-planet-sale? invalid-exchanges)
                                        (:invalid-food-sale? invalid-exchanges)
                                        (:invalid-fuel-sale? invalid-exchanges))
                  buying-too-much? (or (:invalid-food-purchase? invalid-exchanges)
                                       (:invalid-fuel-purchase? invalid-exchanges))]
              (cond
                (and selling-too-much? buying-too-much?)
                [:p.text-yellow-400.font-bold "WARNING: Cannot sell more than you have or buy more than you can afford!"]

                selling-too-much?
                [:p.text-yellow-400.font-bold "WARNING: Cannot sell more than you have!"]

                buying-too-much?
                [:p.text-yellow-400.font-bold "WARNING: Cannot afford to buy that much!"])))]

         ;; Total credits summary
         [:div#cost-summary
          {:hx-swap-oob "true"}
          (total-credits-row (:total-credits credit-changes) can-execute?)]

         ;; Sell table total row
         [:div {:id "sell-total" :hx-swap-oob "true"}
          (table-total-row "sell-total" "Credits (Income)" sell-total)]

         ;; Buy table total row
         [:div {:id "buy-total" :hx-swap-oob "true"}
          (table-total-row "buy-total" "Credits (Expense)" buy-total)]

         ;; Submit button - disabled if player can't execute exchanges
         (submit-button can-execute? {:hx-swap-oob "true"})]))))

;;; Shows exchange options and input fields for player to buy/sell resources and assets
(defn exchange-page [{:keys [player game]}]
  (let [player-id (:xt/id player)
        hx-include "[name='soldiers-sold'],[name='transports-sold'],[name='generals-sold'],[name='fighters-sold'],[name='carriers-sold'],[name='admirals-sold'],[name='stations-sold'],[name='cmd-ships-sold'],[name='mil-planets-sold'],[name='food-planets-sold'],[name='ore-planets-sold'],[name='food-bought'],[name='food-sold'],[name='fuel-bought'],[name='fuel-sold']"
        rates (get-exchange-rates)
        max-buy-quantities (calculate-max-buy-quantities player {:soldiers-sold 0 :transports-sold 0 :generals-sold 0 :fighters-sold 0 :carriers-sold 0 :admirals-sold 0 :stations-sold 0 :cmd-ships-sold 0 :mil-planets-sold 0 :food-planets-sold 0 :ore-planets-sold 0 :food-sold 0 :fuel-sold 0 :food-bought 0 :fuel-bought 0} rates)]
    (ui/page
      {}
      [:div.mx-auto.max-w-4xl.w-full.text-green-400.font-mono
       ;; CSS for responsive grid layout
       [:style "
        .exchange-row-grid {
                            grid-template-columns: 0.9fr 0.8fr 0.7fr 1.1fr 0.9fr;
                            }
        @media (min-width: 1024px) {
                                    .exchange-row-grid {
                                                        grid-template-columns: 1.5fr 1fr 1fr 1fr 1fr;
                                                        }
                                    }
        "]

       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "EXCHANGE")

       ;; Current resources before exchange
       (ui/extended-resource-display-grid player "Resources Before Exchange" false game)

       ;; Exchange Input Form
       (biff/form
         {:action (str "/app/game/" player-id "/apply-exchange")
          :method "post"}

         ;; Selling This Round Table
         [:h3.font-bold.mb-4 "Selling This Round"]
         [:div.w-full.mb-8
          [:div.border.border-green-400.overflow-x-auto
           [:div

            ;; Mobile header - abbreviated
            [:div.grid.gap-1.px-2.py-2.bg-green-400.bg-opacity-10.font-bold.border-b.border-green-400.text-xs.lg:hidden
             {:style {:grid-template-columns "0.9fr 0.8fr 0.7fr 1.1fr 0.9fr"}}
             [:div "Item"]
             [:div.text-right "Credits/Unit"]
             [:div.text-right "Max"]
             [:div.text-center "Sell"]
             [:div.text-right "Cred"]]

            ;; Desktop header - full text
            (ui/phase-table-header
              [{:label "Item" :class "pr-4"}
               {:label "Credits/Unit" :class "text-right pr-4"}
               {:label "Maximum" :class "text-right pr-4"}
               {:label "Sell" :class "pr-4"}
               {:label "Credits" :class "text-right pr-4"}])

            ;; Sell rows
            (sell-row "Soldiers" "Soldiers" "soldiers-sold" (:soldier-sell rates) 0 (:player/soldiers player) player-id hx-include)
            (sell-row "Transports" "Transports" "transports-sold" (:transport-sell rates) 0 (:player/transports player) player-id hx-include)
            (sell-row "Generals" "Generals" "generals-sold" (:general-sell rates) 0 (:player/generals player) player-id hx-include)
            (sell-row "Fighters" "Fighters" "fighters-sold" (:fighter-sell rates) 0 (:player/fighters player) player-id hx-include)
            (sell-row "Carriers" "Carriers" "carriers-sold" (:carrier-sell rates) 0 (:player/carriers player) player-id hx-include)
            (sell-row "Admirals" "Admirals" "admirals-sold" (:admiral-sell rates) 0 (:player/admirals player) player-id hx-include)
            (sell-row "Defence Stations" "Def Stns" "stations-sold" (:station-sell rates) 0 (:player/stations player) player-id hx-include)
            (sell-row "Command Ships" "Cmd Ships" "cmd-ships-sold" (:cmd-ship-sell rates) 0 (:player/cmd-ships player) player-id hx-include)
            (sell-row "Military Planets" "Mil Plts" "mil-planets-sold" (:mil-planet-sell rates) 0 (:player/mil-planets player) player-id hx-include)
            (sell-row "Food Planets" "Food Plts" "food-planets-sold" (:food-planet-sell rates) 0 (:player/food-planets player) player-id hx-include)
            (sell-row "Ore Planets" "Ore Plts" "ore-planets-sold" (:ore-planet-sell rates) 0 (:player/ore-planets player) player-id hx-include)
            (sell-row "Food" "Food" "food-sold" (:food-sell rates) 0 (:player/food player) player-id hx-include)
            (sell-row "Fuel" "Fuel" "fuel-sold" (:fuel-sell rates) 0 (:player/fuel player) player-id hx-include)
            (table-total-row "sell-total" "Total Credits" 0)]]]

         ;; Buying This Round Table
         [:h3.font-bold.mb-4 "Buying This Round"]
         [:div.w-full.mb-8
          [:div.border.border-green-400.overflow-x-auto
           [:div

            ;; Mobile header - abbreviated
            [:div.grid.gap-1.px-2.py-2.bg-green-400.bg-opacity-10.font-bold.border-b.border-green-400.text-xs.lg:hidden
             {:style {:grid-template-columns "0.9fr 0.8fr 0.7fr 1.1fr 0.9fr"}}
             [:div "Item"]
             [:div.text-right "Credits/Unit"]
             [:div.text-right "Max"]
             [:div.text-center "Buy"]
             [:div.text-right "Cred"]]

            ;; Desktop header - full text
            (ui/phase-table-header
              [{:label "Item" :class "pr-4"}
               {:label "Credits/Unit" :class "text-right pr-4"}
               {:label "Maximum" :class "text-right pr-4"}
               {:label "Buy" :class "pr-4"}
               {:label "Credits" :class "text-right pr-4"}])

            ;; Buy rows
            (buy-row "Food" "Food" "food-bought" (:food-buy rates) 0 (:max-food max-buy-quantities) player-id hx-include)
            (buy-row "Fuel" "Fuel" "fuel-bought" (:fuel-buy rates) 0 (:max-fuel max-buy-quantities) player-id hx-include)
            (table-total-row "buy-total" "Credits" 0)]]]

         ;; Resources After Exchange (initially shows current resources)
         [:div#resources-after
          (ui/extended-resource-display-grid player "Resources After Exchange" true game)]

         ;; Warning message area - populated by HTMX if player tries invalid exchanges
         [:div#exchange-warning.flex.items-center]

         (ui/incoming-alert player)

         ;; Navigation and submit buttons
         [:div.flex.gap-4.mt-2
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id "/expenses")} "Cancel Exchange"]
          ;; Initial button starts disabled - HTMX updates will enable it when valid
          (submit-button false)])
       ])))
