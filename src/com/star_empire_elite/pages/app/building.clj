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
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Calculations
;;;;

(def purchase-row-specs
  [{:label "Soldiers"         :abbrev "Soldiers"  :qty-key :soldiers     :cost-key :game/soldier-cost}
   {:label "Transports"       :abbrev "Transport" :qty-key :transports   :cost-key :game/transport-cost}
   {:label "Generals"         :abbrev "Generals"  :qty-key :generals     :cost-key :game/general-cost}
   {:label "Carriers"         :abbrev "Carriers"  :qty-key :carriers     :cost-key :game/carrier-cost}
   {:label "Fighters"         :abbrev "Fighters"  :qty-key :fighters     :cost-key :game/fighter-cost}
   {:label "Admirals"         :abbrev "Admirals"  :qty-key :admirals     :cost-key :game/admiral-cost}
   {:label "Defence Stations" :abbrev "Def Stns"  :qty-key :stations     :cost-key :game/station-cost}
   {:label "Command Ships"    :abbrev "Cmd Ships" :qty-key :cmd-ships    :cost-key :game/cmd-ship-cost}
   {:label "Agents"           :abbrev "Agents"    :qty-key :agents       :cost-key :game/agent-cost}
   {:label "Ore Planets"      :abbrev "Ore Plts"  :qty-key :ore-planets  :cost-key :game/ore-planet-cost}
   {:label "Food Planets"     :abbrev "Food Plts" :qty-key :food-planets :cost-key :game/food-planet-cost}
   {:label "Military Planets" :abbrev "Mil Plts"  :qty-key :mil-planets  :cost-key :game/mil-planet-cost}])

(def building-hx-include
  (str/join ","
    (for [spec purchase-row-specs]
      (str "[name='" (name (:qty-key spec)) "']"))))

(defn parse-purchase-quantities
  "Parse all purchase quantity inputs from request params.

  [params ring-params] -> {:soldiers int, :transports int, ...}"
  [params]
  {:soldiers     (utils/parse-numeric-input (:soldiers params))
   :transports   (utils/parse-numeric-input (:transports params))
   :generals     (utils/parse-numeric-input (:generals params))
   :carriers     (utils/parse-numeric-input (:carriers params))
   :fighters     (utils/parse-numeric-input (:fighters params))
   :admirals     (utils/parse-numeric-input (:admirals params))
   :stations     (utils/parse-numeric-input (:stations params))
   :cmd-ships    (utils/parse-numeric-input (:cmd-ships params))
   :agents       (utils/parse-numeric-input (:agents params))
   :ore-planets  (utils/parse-numeric-input (:ore-planets params))
   :food-planets (utils/parse-numeric-input (:food-planets params))
   :mil-planets  (utils/parse-numeric-input (:mil-planets params))})

(defn- total-cost
  "Sum of quantity × cost-per-unit across all purchasable items.

  [quantities purchase-quantities, game game-map] -> int"
  [quantities game]
  (reduce + (for [spec purchase-row-specs]
              (* (get quantities (:qty-key spec))
                 (get game (:cost-key spec))))))

(defn calculate-purchase-cost
  "Calculate total credits needed for all purchases based on game constants.

  [quantities purchase-quantities, game game-map] -> {:total-cost int}"
  [quantities game]
  {:total-cost (total-cost quantities game)})

(defn calculate-resources-after-purchases
  "Calculate player resources after executing purchases.

  [player player-map, quantities purchase-quantities, cost-info {:total-cost int}] -> {:credits int, :soldiers int, ...}"
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
   :agents       (+ (:player/agents player)       (:agents quantities))
   :ore-planets  (+ (:player/ore-planets player)  (:ore-planets quantities))
   :food-planets (+ (:player/food-planets player) (:food-planets quantities))
   :mil-planets  (+ (:player/mil-planets player)  (:mil-planets quantities))})

(defn can-afford-purchases?
  "Returns true if player has enough credits for all purchases.

  [resources-after purchase-resources-map] -> boolean"
  [resources-after]
  (>= (:credits resources-after) 0))

(defn calculate-max-quantities
  "Calculate the maximum quantity of each item that can be purchased with remaining credits,
  taking into account credits already committed to other items in the current selection.

  [player player-map, quantities purchase-quantities, game game-map] -> {:soldiers int, :transports int, ...}"
  [player quantities game]
  (let [remaining-credits (- (:player/credits player) (total-cost quantities game))]
    (into {} (for [spec purchase-row-specs]
               [(:qty-key spec) (quot remaining-credits (get game (:cost-key spec)))]))))

;;;;
;;;; UI Components
;;;;

(defn submit-button
  "Renders the submit button with dynamic disabled state based on affordability.
  Used in both initial page render and HTMX updates.

  ([affordable? bool]) ([affordable? bool, extra-attrs map]) -> hiccup"
  ([affordable?] (submit-button affordable? {}))
  ([affordable? extra-attrs]
   [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
    (merge {:type "submit"
            :disabled (not affordable?)
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           extra-attrs)
    "Continue to Action"]))

(defn purchase-row
  "Renders a purchase row with a single responsive input field for mobile and desktop.

  [unit-name str, unit-name-mobile str, unit-key keyword, cost-key keyword,
   current-quantity int, max-quantity int, game game-map, player-id uuid, hx-include str] -> hiccup"
  [unit-name unit-name-mobile unit-key cost-key current-quantity max-quantity game player-id hx-include]
  (let [cost-per-unit (get game cost-key)
        item-cost     (* cost-per-unit current-quantity)
        cost-id       (str "cost-" (name unit-key))
        max-qty-id    (str "max-qty-" (name unit-key))]
    [:div.border-b.border-green-400.last:border-b-0.grid.items-center.gap-1.px-2.py-2.text-xs.leading-tight.lg:gap-3.lg:px-4.lg:py-2.lg:text-base.phase-row-grid

     ;; Col 1: Item name (abbreviated on mobile, full on desktop)
     [:div.font-mono.lg:pr-4
      [:span.lg:hidden unit-name-mobile]
      [:span.hidden.lg:inline.whitespace-nowrap unit-name]]

     ;; Col 2: Cost per unit
     [:div.text-right.font-mono.lg:pr-4
      (ui/format-number cost-per-unit)]

     ;; Col 3: Maximum quantity
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id max-qty-id
              :class (when (zero? max-quantity) "opacity-20")}
       (ui/format-number (if (neg? max-quantity) 0 max-quantity))]]

     ;; Col 4: Purchase Quantity - SINGLE input field with responsive sizing
     [:div.px-1.lg:pr-4
      (ui/numeric-input (name unit-key) current-quantity player-id "/calculate-building" hx-include
                        {:input-class "py-0.5 text-xs lg:py-1 lg:text-sm"})]

     ;; Col 5: Cost in credits
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id cost-id
              :class (when (zero? item-cost) "opacity-20")}
       "+" (ui/format-number item-cost)]]]))

(defn total-cost-row
  "Renders the total cost summary at the bottom of the table.
  Shows cost in red if it exceeds available credits.

  [total-cost int, affordable? bool] -> hiccup"
  [total-cost affordable?]
  [:div#cost-summary.border-t-2.border-green-400.bg-green-400.bg-opacity-10

   ;; Mobile: Compact layout
   [:div.grid.gap-1.px-2.py-3.font-bold.text-sm.phase-row-grid.lg:hidden
    [:div "Credits (Expense)"]
    [:div] [:div] [:div]
    [:div.text-right.font-mono.text-base
     {:class (when (not affordable?) "text-red-400")}
     (ui/format-number total-cost)]]

   ;; Desktop: Full width layout
   [:div.hidden.lg:grid.lg:gap-3.lg:px-4.lg:py-2.lg:font-bold.phase-row-grid
    [:div.text-lg "Credits (Expense)"]
    [:div] [:div] [:div]
    [:div.text-right.font-mono.text-xl.pr-4
     {:class (when (not affordable?) "text-red-400")}
     (ui/format-number total-cost)]]])

;;;;
;;;; Actions
;;;;

(defn apply-building
  "Commit purchases to the database, advance to action phase, and redirect.

  [ctx ring-ctx] -> ring-response (303 redirect to action, or 400 if unaffordable)"
  [{:keys [path-params params biff/db] :as ctx}]
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
                              :player/agents       (:agents resources-after)
                              :player/ore-planets  (:ore-planets resources-after)
                              :player/food-planets (:food-planets resources-after)
                              :player/mil-planets  (:mil-planets resources-after)
                              :player/current-phase 4}])
            {:status 303
             :headers {"location" (str "/app/game/" player-id "/action")}}))))))

(defn calculate-building
  "Provide HTMX out-of-band updates showing resources after purchases as user changes input values.

  [ctx ring-ctx] -> hiccup (via biff/render)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [quantities      (parse-purchase-quantities params)
          cost-info       (calculate-purchase-cost quantities game)
          resources-after (calculate-resources-after-purchases player quantities cost-info)
          affordable?     (can-afford-purchases? resources-after)
          max-quantities  (calculate-max-quantities player quantities game)
          item-costs      (into {} (for [spec purchase-row-specs]
                                     [(:qty-key spec) (* (get quantities (:qty-key spec))
                                                         (get game (:cost-key spec)))]))]
      (biff/render
        [:div
         [:div#resources-after
          (ui/extended-resource-display-grid
            (assoc resources-after
                   :food     (:player/food player)
                   :fuel     (:player/fuel player)
                   :galaxars (:player/galaxars player))
            "Resources After Building" true)]
         [:div#cost-summary
          {:hx-swap-oob "true"}
          (total-cost-row (:total-cost cost-info) affordable?)]
         (for [[item-key max-qty] max-quantities]
           [:span {:id (str "max-qty-" (name item-key))
                   :hx-swap-oob "true"
                   :key (str "max-" item-key)
                   :class (when (or (zero? max-qty) (neg? max-qty)) "opacity-20")}
            (ui/format-number (if (neg? max-qty) 0 max-qty))])
         (for [[item-key cost] item-costs]
           [:span {:id (str "cost-" (name item-key))
                   :hx-swap-oob "true"
                   :key item-key
                   :class (when (zero? cost) "opacity-20")}
            "+" (ui/format-number cost)])
         [:div#building-warning.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.font-bold "WARNING: Insufficient credits for purchases!"])]
         (submit-button affordable? {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn building-page
  "Show purchase options and input fields for buying units and planets.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [player-id (:xt/id player)]
    (ui/page
      {}
      [:div.mx-auto.max-w-4xl.w-full.text-green-400.font-mono

       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "BUILDING"
                        (str "Turn " (:player/current-turn player) " | Round " (:player/current-round player)))

       ;; Current resources before building
       (ui/extended-resource-display-grid player "Resources Before Building" false)

       (biff/form
         {:action (str "/app/game/" player-id "/apply-building")
          :method "post"}

         [:h3.font-bold.mb-4 "Building This Round"]

         [:div.w-full.mb-8
          [:div.border.border-green-400.overflow-x-auto
           [:div

            ;; Mobile header - abbreviated
            [:div.grid.gap-1.px-2.py-2.bg-green-400.bg-opacity-10.font-bold.border-b.border-green-400.text-xs.phase-row-grid.lg:hidden
             [:div "Item"]
             [:div.text-right "Cost"]
             [:div.text-right "Max"]
             [:div.text-center "Buy"]
             [:div.text-right "Cred"]]

            ;; Desktop header - full text
            (ui/phase-table-header
              [{:label "Item" :class "pr-4"}
               {:label "Credits/Unit" :class "text-right pr-4"}
               {:label "Maximum" :class "text-right pr-4"}
               {:label "Purchase" :class "pr-4"}
               {:label "Credits" :class "text-right pr-4"}])

            ;; Purchase rows
            (for [spec purchase-row-specs]
              (purchase-row (:label spec) (:abbrev spec) (:qty-key spec) (:cost-key spec)
                            0 0 game player-id building-hx-include))

            (total-cost-row 0 true)]]]

         ;; Resources after purchases - initial copy, updated via HTMX
         [:div#resources-after
          (ui/extended-resource-display-grid player "Resources After Purchases" false)]

         ;; Warning message area - populated by HTMX if player can't afford purchases
         [:div#building-warning.flex.items-center]

         (ui/incoming-alert player)

         ;; Navigation and submit buttons
         [:div.flex.gap-4.mt-2
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id)} "Pause"]
          (submit-button false)])])))
