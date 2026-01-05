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

;;; Unified responsive purchase row (no phase-row, exactly one input per item)
(defn purchase-row
  "Renders a purchase row in a single responsive structure:
   - On mobile: a vertical card, similar to income cards.
   - On desktop: fields align under the 5-column header:
       Item | Credits per Unit | Maximum Quantity | Purchase Quantity | Cost in Credits"
  [unit-name unit-key cost-key current-quantity max-quantity game player-id hx-include]
  (let [cost-per-unit (get game cost-key)
        item-cost (* cost-per-unit current-quantity)
        cost-id (str "cost-" (name unit-key))
        max-qty-id (str "max-qty-" (name unit-key))]
    [:div.border-b.border-green-400.last:border-b-0.py-3.px-4
     ;; One grid for both layouts; desktop uses 5 columns, mobile stacks.
     [:div.grid.grid-cols-1.lg:grid-cols-5.gap-3.lg:items-center
      ;; Make first column a bit wider on desktop so long labels don't wrap
      {:style {:grid-template-columns "1.5fr 1fr 1fr 1fr 1fr"}}

      ;; Col 1: Item
      ;; - Mobile: big label at top (like "Ore", "Food", "Mil" in income).
      ;; - Desktop: regular text in first column.
      [:div.pr-4
       [:div.lg:hidden.mb-1
        [:span.text-xs.text-green-300 "Item"]]
       [:span.font-mono.whitespace-nowrap.text-lg.lg:text-base.font-bold.lg:font-normal
        unit-name]]

      ;; Col 2: Credits per Unit
      ;; - Mobile: label on left, value on right in a small row.
      ;; - Desktop: value right-aligned under header.
      [:div.lg:text-right.lg:pr-4
       [:div.flex.justify-between.items-center.lg:block
        [:span.text-xs.text-green-300.lg:hidden "Credits per Unit"]
        [:span.font-mono (ui/format-number cost-per-unit)]]]

      ;; Col 3: Maximum Quantity
      [:div.lg:text-right.lg:pr-4
       [:div.flex.justify-between.items-center.lg:block
        [:span.text-xs.text-green-300.lg:hidden "Maximum Quantity"]
        [:span.font-mono
         [:span {:id max-qty-id
                 :class (cond
                          (< max-quantity 0) "text-red-400"
                          (zero? max-quantity) "opacity-20"
                          :else "")}
          (ui/format-number max-quantity)]]]]

      ;; Col 4: Purchase Quantity (single numeric input)
      ;; - Mobile: label on left, input on right.
      ;; - Desktop: input under its header column.
      [:div.lg:pr-4
       [:div.flex.justify-between.items-center.lg:block
        [:span.text-xs.text-green-300.lg:hidden "Purchase Quantity"]
        [:div.w-24.lg:w-auto
         (ui/numeric-input (name unit-key) current-quantity player-id "/calculate-building" hx-include)]]]

      ;; Col 5: Cost in Credits
      [:div.lg:text-right.lg:pr-4
       [:div.flex.justify-between.items-center.lg:block
        [:span.text-xs.text-green-300.lg:hidden "Cost in Credits"]
        [:span.font-mono
         [:span {:id cost-id
                 :class (when (zero? item-cost) "opacity-20")}
          "+" (ui/format-number item-cost)]]]]]]))

(defn total-cost-row
  "Renders the total cost as a row at the bottom of the table."
  [total-cost]
  [:div#cost-summary.border-t.border-green-400
   [:div.lg:hidden.p-4.bg-green-400.bg-opacity-10
    [:div.flex.justify-between.items-center
     [:span.font-bold.text-lg "Total Credits:"]
     [:span.font-mono.text-xl (ui/format-number total-cost) " credits"]]]
   [:div.hidden.lg:grid.lg:gap-4.lg:items-center.lg:px-4.lg:py-3.lg:bg-green-400.lg:bg-opacity-10.font-bold
    {:style {:grid-template-columns "repeat(5, minmax(0, 1fr))"}}
    [:div.pr-4 ""]
    [:div.text-right.pr-4 ""]
    [:div.text-right.pr-4 ""]
    [:div "Total Credits:"]
    [:div.text-right.pr-4 (ui/format-number total-cost)]]])

;;;;
;;;; Actions
;;;;

(defn apply-building [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 3 player-id)]
      redirect
      (let [quantities      (parse-purchase-quantities params)
            cost-info       (calculate-purchase-cost quantities game)
            resources-after (calculate-resources-after-purchases player quantities cost-info)]
        (if (not (can-afford-purchases? resources-after))
          {:status 400
           :body "Insufficient credits for purchase"}
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

(defn calculate-building [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [quantities      (parse-purchase-quantities params)
          cost-info       (calculate-purchase-cost quantities game)
          resources-after (calculate-resources-after-purchases player quantities cost-info)
          affordable?     (can-afford-purchases? resources-after)
          max-quantities  (calculate-max-quantities player quantities game)
          item-costs      {:soldiers     (* (:soldiers quantities)     (:game/soldier-cost game))
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
      (biff/render
        [:div
         [:div#resources-after
          (ui/extended-resource-display-grid
            (assoc resources-after
                   :food (:player/food player)
                   :fuel (:player/fuel player)
                   :galaxars (:player/galaxars player))
            "Resources After Building"
            true)]
         [:div#cost-summary
          {:hx-swap-oob "true"}
          (total-cost-row (:total-cost cost-info))]
         (for [[item-key max-qty] max-quantities]
           [:span {:id (str "max-qty-" (name item-key))
                   :hx-swap-oob "true"
                   :key (str "max-" item-key)
                   :class (cond
                            (< max-qty 0) "text-red-400"
                            (zero? max-qty) "opacity-20"
                            :else "")}
            (ui/format-number max-qty)])
         (for [[item-key cost] item-costs]
           [:span {:id (str "cost-" (name item-key))
                   :hx-swap-oob "true"
                   :key item-key
                   :class (when (zero? cost) "opacity-20")}
            "+" (ui/format-number cost)])
         [:div#building-warning.h-8.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.font-bold "WARNING: Insufficient credits for purchases!"])]
         (submit-button affordable? {:hx-swap-oob "true"})]))))

(defn building-page [{:keys [player game]}]
  (let [player-id  (:xt/id player)
        hx-include "[name='soldiers'],[name='transports'],[name='generals'],[name='carriers'],[name='fighters'],[name='admirals'],[name='stations'],[name='cmd-ships'],[name='ore-planets'],[name='food-planets'],[name='mil-planets']"]
    (ui/page
      {}
      [:div.mx-auto.max-w-4xl.w-full.text-green-400.font-mono
       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "BUILDING")

       ;; Current resources before building
       (ui/extended-resource-display-grid player "Resources Before Building")

       (biff/form
         {:action (str "/app/game/" player-id "/apply-building")
          :method "post"}

         [:h3.font-bold.mb-4 "Building This Round"]

         ;; Table container with header and rows, horizontally scrollable on very narrow screens
         [:div.border.border-green-400.mb-8.overflow-x-auto
          [:div.min-w-full
           ;; Header row (wide screens)
           (ui/phase-table-header
             [{:label "Item" :class "pr-4"}
              {:label "Credits/Unit" :class "text-right pr-4"}
              {:label "Maximum" :class "text-right pr-4"}
              {:label "Purchase" :class "pr-4"}
              {:label "Credits" :class "text-right pr-4"}])

           ;; All purchase rows using unified component
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
           (total-cost-row 0)]]

         ;; Resources after purchases - initial copy, updated via HTMX
         [:div#resources-after
          (ui/extended-resource-display-grid player "Resources After Purchases")]

         ;; Warning message area - populated by HTMX if player can't afford purchases
         [:div#building-warning.h-8.flex.items-center]

         ;; Navigation and submit buttons
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id)} "Back to Game"]
          (submit-button false)])])))
