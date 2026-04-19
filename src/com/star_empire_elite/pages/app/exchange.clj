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
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Calculations
;;;;

(def sell-row-specs
  [{:label "Soldiers"         :abbrev "Soldiers"   :field "soldiers-sold"     :qty-key :soldiers-sold     :rate-key :soldier-sell    :player-key :player/soldiers}
   {:label "Transports"       :abbrev "Transports" :field "transports-sold"   :qty-key :transports-sold   :rate-key :transport-sell  :player-key :player/transports}
   {:label "Generals"         :abbrev "Generals"   :field "generals-sold"     :qty-key :generals-sold     :rate-key :general-sell    :player-key :player/generals}
   {:label "Fighters"         :abbrev "Fighters"   :field "fighters-sold"     :qty-key :fighters-sold     :rate-key :fighter-sell    :player-key :player/fighters}
   {:label "Carriers"         :abbrev "Carriers"   :field "carriers-sold"     :qty-key :carriers-sold     :rate-key :carrier-sell    :player-key :player/carriers}
   {:label "Admirals"         :abbrev "Admirals"   :field "admirals-sold"     :qty-key :admirals-sold     :rate-key :admiral-sell    :player-key :player/admirals}
   {:label "Defence Stations" :abbrev "Def Stns"   :field "stations-sold"     :qty-key :stations-sold     :rate-key :station-sell    :player-key :player/stations}
   {:label "Command Ships"    :abbrev "Cmd Ships"  :field "cmd-ships-sold"    :qty-key :cmd-ships-sold    :rate-key :cmd-ship-sell   :player-key :player/cmd-ships}
   {:label "Agents"           :abbrev "Agents"     :field "agents-sold"       :qty-key :agents-sold       :rate-key :agent-sell      :player-key :player/agents}
   {:label "Military Planets" :abbrev "Mil Plts"   :field "mil-planets-sold"  :qty-key :mil-planets-sold  :rate-key :mil-planet-sell :player-key :player/mil-planets}
   {:label "Food Planets"     :abbrev "Food Plts"  :field "food-planets-sold" :qty-key :food-planets-sold :rate-key :food-planet-sell :player-key :player/food-planets}
   {:label "Ore Planets"      :abbrev "Ore Plts"   :field "ore-planets-sold"  :qty-key :ore-planets-sold  :rate-key :ore-planet-sell :player-key :player/ore-planets}
   {:label "Food"             :abbrev "Food"       :field "food-sold"         :qty-key :food-sold         :rate-key :food-sell       :player-key :player/food}
   {:label "Fuel"             :abbrev "Fuel"       :field "fuel-sold"         :qty-key :fuel-sold         :rate-key :fuel-sell       :player-key :player/fuel}])

(def buy-row-specs
  [{:label "Food" :abbrev "Food" :field "food-bought" :qty-key :food-bought :rate-key :food-buy :max-key :max-food}
   {:label "Fuel" :abbrev "Fuel" :field "fuel-bought" :qty-key :fuel-bought :rate-key :fuel-buy :max-key :max-fuel}])

(def exchange-hx-include
  (str/join ","
    (for [spec (concat sell-row-specs buy-row-specs)]
      (str "[name='" (:field spec) "']"))))

(defn get-exchange-rates
  "Extract exchange rates from the game entity.

  [game game-map] -> {:soldier-sell int, :transport-sell int, ...}"
  [game]
  {:soldier-sell     (:game/soldier-sell     game)
   :transport-sell   (:game/transport-sell   game)
   :general-sell     (:game/general-sell     game)
   :fighter-sell     (:game/fighter-sell     game)
   :carrier-sell     (:game/carrier-sell     game)
   :admiral-sell     (:game/admiral-sell     game)
   :station-sell     (:game/station-sell     game)
   :cmd-ship-sell    (:game/cmd-ship-sell    game)
   :agent-sell       (:game/agent-sell       game)
   :mil-planet-sell  (:game/mil-planet-sell  game)
   :food-planet-sell (:game/food-planet-sell game)
   :ore-planet-sell  (:game/ore-planet-sell  game)
   :food-buy         (:game/food-buy         game)
   :food-sell        (:game/food-sell        game)
   :fuel-buy         (:game/fuel-buy         game)
   :fuel-sell        (:game/fuel-sell        game)})

(defn parse-exchange-quantities
  "Parse all exchange quantity inputs from request params.

  [params ring-params] -> {:soldiers-sold int, :transports-sold int, ...}"
  [params]
  {:soldiers-sold     (utils/parse-numeric-input (:soldiers-sold params))
   :transports-sold   (utils/parse-numeric-input (:transports-sold params))
   :generals-sold     (utils/parse-numeric-input (:generals-sold params))
   :fighters-sold     (utils/parse-numeric-input (:fighters-sold params))
   :carriers-sold     (utils/parse-numeric-input (:carriers-sold params))
   :admirals-sold     (utils/parse-numeric-input (:admirals-sold params))
   :stations-sold     (utils/parse-numeric-input (:stations-sold params))
   :cmd-ships-sold    (utils/parse-numeric-input (:cmd-ships-sold params))
   :agents-sold       (utils/parse-numeric-input (:agents-sold params))
   :mil-planets-sold  (utils/parse-numeric-input (:mil-planets-sold params))
   :food-planets-sold (utils/parse-numeric-input (:food-planets-sold params))
   :ore-planets-sold  (utils/parse-numeric-input (:ore-planets-sold params))
   :food-bought       (utils/parse-numeric-input (:food-bought params))
   :food-sold         (utils/parse-numeric-input (:food-sold params))
   :fuel-bought       (utils/parse-numeric-input (:fuel-bought params))
   :fuel-sold         (utils/parse-numeric-input (:fuel-sold params))})

(defn calculate-exchange-credits
  "Calculate net credit change from selling units/planets and buying/selling resources.

  [quantities exchange-quantities, rates exchange-rates] -> {:credits-from-sales int, :credits-from-resources int, :total-credits int}"
  [quantities rates]
  (let [credits-from-sales        (+ (* (:soldiers-sold quantities)     (:soldier-sell rates))
                                     (* (:transports-sold quantities)   (:transport-sell rates))
                                     (* (:generals-sold quantities)     (:general-sell rates))
                                     (* (:fighters-sold quantities)     (:fighter-sell rates))
                                     (* (:carriers-sold quantities)     (:carrier-sell rates))
                                     (* (:admirals-sold quantities)     (:admiral-sell rates))
                                     (* (:stations-sold quantities)     (:station-sell rates))
                                     (* (:cmd-ships-sold quantities)    (:cmd-ship-sell rates))
                                     (* (:agents-sold quantities)       (:agent-sell rates))
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

(defn calculate-max-buy-quantities
  "Calculate maximum quantities that can be purchased with available credits after all sales.

  [player player-map, sell-quantities exchange-quantities, rates exchange-rates] -> {:max-food int, :max-fuel int}"
  [player sell-quantities rates]
  (let [sell-credit-changes (calculate-exchange-credits
                              (assoc sell-quantities :food-bought 0 :fuel-bought 0) rates)
        total-available-credits (+ (:player/credits player) (:total-credits sell-credit-changes))]
    {:max-food (max 0 (quot total-available-credits (:food-buy rates)))
     :max-fuel (max 0 (quot total-available-credits (:fuel-buy rates)))}))

(defn calculate-resources-after-exchange
  "Calculate all player resources after executing exchanges.

  [player player-map, quantities exchange-quantities, credit-changes exchange-credit-changes] -> {:credits int, :soldiers int, ...}"
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
   :agents       (- (:player/agents player)       (:agents-sold quantities))
   :mil-planets  (- (:player/mil-planets player)  (:mil-planets-sold quantities))
   :food-planets (- (:player/food-planets player) (:food-planets-sold quantities))
   :ore-planets  (- (:player/ore-planets player)  (:ore-planets-sold quantities))
   :food (+ (:player/food player) (:food-bought quantities) (- (:food-sold quantities)))
   :fuel (+ (:player/fuel player) (:fuel-bought quantities) (- (:fuel-sold quantities)))
   :galaxars (:player/galaxars player)})

(defn valid-exchange?
  "Returns true if player can execute the exchange (all resources non-negative).

  [resources-after exchange-resources-map] -> boolean"
  [resources-after]
  (every? #(>= (get resources-after %) 0)
          [:credits :soldiers :transports :generals :carriers :fighters
           :admirals :stations :cmd-ships :agents :mil-planets :food-planets
           :ore-planets :food :fuel]))

(defn identify-invalid-exchanges
  "Returns map indicating which specific exchange types are invalid.

  [resources-after exchange-resources-map, quantities exchange-quantities] -> {:invalid-soldier-sale? bool, ...}"
  [resources-after quantities]
  {:invalid-soldier-sale?     (< (:soldiers resources-after) 0)
   :invalid-transport-sale?   (< (:transports resources-after) 0)
   :invalid-general-sale?     (< (:generals resources-after) 0)
   :invalid-fighter-sale?     (< (:fighters resources-after) 0)
   :invalid-carrier-sale?     (< (:carriers resources-after) 0)
   :invalid-admiral-sale?     (< (:admirals resources-after) 0)
   :invalid-station-sale?     (< (:stations resources-after) 0)
   :invalid-cmd-ship-sale?    (< (:cmd-ships resources-after) 0)
   :invalid-agent-sale?       (< (:agents resources-after) 0)
   :invalid-mil-planet-sale?  (< (:mil-planets resources-after) 0)
   :invalid-food-planet-sale? (< (:food-planets resources-after) 0)
   :invalid-ore-planet-sale?  (< (:ore-planets resources-after) 0)
   :invalid-food-sale?        (and (> (:food-sold quantities) 0) (< (:food resources-after) 0))
   :invalid-fuel-sale?        (and (> (:fuel-sold quantities) 0) (< (:fuel resources-after) 0))
   :invalid-food-purchase?    (and (> (:food-bought quantities) 0) (< (:credits resources-after) 0))
   :invalid-fuel-purchase?    (and (> (:fuel-bought quantities) 0) (< (:credits resources-after) 0))})

;;;;
;;;; UI Components
;;;;

(defn submit-button
  "Renders the submit button with dynamic disabled state based on validity.
  Used in both initial page render and HTMX updates.

  ([can-execute? bool]) ([can-execute? bool, extra-attrs map]) -> hiccup"
  ([can-execute?] (submit-button can-execute? {}))
  ([can-execute? extra-attrs]
   [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
    (merge {:type "submit"
            :disabled (not can-execute?)
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           extra-attrs)
    "Make Exchange"]))

(defn exchange-row
  "Renders a buy or sell row with a single responsive input field for mobile and desktop.

  [item-name str, item-name-mobile str, field-key str, price-per-unit int,
  current-quantity int, max-quantity int, player-id uuid, hx-include str] -> hiccup"
  [item-name item-name-mobile field-key price-per-unit current-quantity max-quantity player-id hx-include]
  (let [credit-value (* price-per-unit current-quantity)
        credit-id    (str "credit-" field-key)
        max-qty-id   (str "max-qty-" field-key)]
    [:div.border-b.border-green-400.last:border-b-0.grid.items-center.gap-1.px-2.py-2.text-xs.leading-tight.lg:gap-3.lg:px-4.lg:py-2.lg:text-base.phase-row-grid

     ;; Col 1: Item name (abbreviated on mobile, full on desktop)
     [:div.font-mono.lg:pr-4
      [:span.lg:hidden item-name-mobile]
      [:span.hidden.lg:inline.whitespace-nowrap item-name]]

     ;; Col 2: Credits per unit
     [:div.text-right.font-mono.lg:pr-4
      (ui/format-number price-per-unit)]

     ;; Col 3: Maximum quantity available/affordable
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id max-qty-id
              :class (when (zero? max-quantity) "opacity-20")}
       (ui/format-number (if (neg? max-quantity) 0 max-quantity))]]

     ;; Col 4: Quantity input with responsive sizing
     [:div.px-1.lg:pr-4
      (ui/numeric-input field-key current-quantity player-id "/calculate-exchange" hx-include
                        {:input-class "py-0.5 text-xs lg:py-1 lg:text-sm"})]

     ;; Col 5: Credits value
     [:div.text-right.font-mono.lg:pr-4
      [:span {:id credit-id
              :class (when (zero? credit-value) "opacity-20")}
       [:<> "+" (ui/format-number credit-value)]]]]))

(defn table-total-row
  "Renders a total row at the bottom of a sell or buy table.

  [row-id str, label str, total int] -> hiccup"
  [row-id label total]
  [:div {:id row-id :class "border-t-2 border-green-400 bg-green-400 bg-opacity-10"}
   ;; Mobile
   [:div.grid.gap-1.px-2.py-3.font-bold.text-sm.phase-row-grid.lg:hidden
    [:div label]
    [:div] [:div] [:div]
    [:div.text-right.font-mono.text-base
     {:class (when (zero? total) "opacity-20")}
     (ui/format-number total)]]
   ;; Desktop
   [:div.hidden.lg:grid.lg:gap-3.lg:px-4.lg:py-2.lg:font-bold.phase-row-grid
    [:div.text-lg label]
    [:div] [:div] [:div]
    [:div.text-right.font-mono.text-xl.pr-4
     {:class (when (zero? total) "opacity-20")}
     (ui/format-number total)]]])

(defn total-credits-row
  "Renders the total credits gained summary at the bottom of the table.
  Shows credits in red if player tries to buy more than they can afford.

  [total-credits int, can-execute? bool] -> hiccup"
  [total-credits can-execute?]
  (let [abs-credits       (Math/abs total-credits)
        formatted-credits (ui/format-number abs-credits)
        sign              (if (>= total-credits 0) "+" "-")]
    [:div#cost-summary.border-green-400.bg-green-400.bg-opacity-10

     ;; Mobile: Compact layout
     [:div.grid.gap-1.px-2.py-3.font-bold.text-sm.phase-row-grid.lg:hidden
      [:div.col-span-4.text-right.pr-4 "Credits:"]
      [:div.text-right.font-mono.text-base
       {:class (when (and (not can-execute?) (< total-credits 0)) "text-red-400")}
       [:<> sign formatted-credits]]]

     ;; Desktop: Full width layout
     [:div.hidden.lg:grid.lg:gap-3.lg:px-4.lg:py-2.lg:font-bold.phase-row-grid
      [:div.col-span-4.text-right.text-lg.pr-4 "Credits:"]
      [:div.text-right.font-mono.text-xl
       {:class (when (and (not can-execute?) (< total-credits 0)) "text-red-400")}
       [:<> sign formatted-credits]]]]))

;;;;
;;;; Actions
;;;;

(defn apply-exchange
  "Commit exchanges to the database and redirect back to expenses. Stays in phase 2 to allow
  additional exchanges. Uses pure calculation functions to determine resource changes.

  [ctx ring-ctx] -> ring-response (303 redirect to expenses)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Phase validation - only allow exchange if player is in phase 2
    (if-let [redirect (utils/validate-phase player 2 player-id)]
      redirect
      (let [quantities      (parse-exchange-quantities params)
            rates           (get-exchange-rates game)
            credit-changes  (calculate-exchange-credits quantities rates)
            resources-after (calculate-resources-after-exchange player quantities credit-changes)]
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
                          :player/mil-planets  (:mil-planets resources-after)
                          :player/food-planets (:food-planets resources-after)
                          :player/ore-planets  (:ore-planets resources-after)
                          :player/food         (:food resources-after)
                          :player/fuel         (:fuel resources-after)}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/expenses")}}))))

(defn calculate-exchange
  "Provide HTMX out-of-band updates showing resources after exchange as user changes input values.

  [ctx ring-ctx] -> hiccup (via biff/render)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [quantities         (parse-exchange-quantities params)
          rates              (get-exchange-rates game)
          credit-changes     (calculate-exchange-credits quantities rates)
          resources-after    (calculate-resources-after-exchange player quantities credit-changes)
          can-execute?       (valid-exchange? resources-after)
          max-buy-quantities (calculate-max-buy-quantities player
                               (assoc quantities :food-bought 0 :fuel-bought 0) rates)
          sell-total         (+ (:credits-from-sales credit-changes)
                                (* (:food-sold quantities) (:food-sell rates))
                                (* (:fuel-sold quantities) (:fuel-sell rates)))
          buy-total          (+ (* (:food-bought quantities) (:food-buy rates))
                                (* (:fuel-bought quantities) (:fuel-buy rates)))]

      (biff/render
        [:div
         ;; Update credit value spans for all sell and buy rows
         (for [spec (concat sell-row-specs buy-row-specs)
               :let [qty (get quantities (:qty-key spec))
                     v   (* qty (get rates (:rate-key spec)))]]
           [:span {:id (str "credit-" (:field spec)) :hx-swap-oob "true"
                   :class (when (zero? v) "opacity-20")}
            [:<> "+" (ui/format-number v)]])

         ;; Update maximum quantities for buy rows
         (for [spec buy-row-specs
               :let [max-qty (get max-buy-quantities (:max-key spec))]]
           [:span {:id (str "max-qty-" (:field spec)) :hx-swap-oob "true"
                   :class (when (zero? max-qty) "opacity-20")}
            (ui/format-number max-qty)])

         ;; Resources display with red highlighting for negative values
         [:div#resources-after
          (ui/extended-resource-display-grid resources-after "Resources After Exchange" true)]

         ;; Warning message if exchange is invalid
         [:div#exchange-warning.flex.items-center
          {:hx-swap-oob "true"}
          (when (not can-execute?)
            (let [invalid-exchanges (identify-invalid-exchanges resources-after quantities)
                  selling-too-much? (some true? (vals (dissoc invalid-exchanges
                                                              :invalid-food-purchase?
                                                              :invalid-fuel-purchase?)))
                  buying-too-much?  (or (:invalid-food-purchase? invalid-exchanges)
                                        (:invalid-fuel-purchase? invalid-exchanges))]
              (cond
                (and selling-too-much? buying-too-much?)
                [:p.text-yellow-400.font-bold "WARNING: Cannot sell more than you have or buy more than you can afford!"]

                selling-too-much?
                [:p.text-yellow-400.font-bold "WARNING: Cannot sell more than you have!"]

                buying-too-much?
                [:p.text-yellow-400.font-bold "WARNING: Cannot buy more than you can afford!"])))]

         ;; Total credits summary
         [:div#cost-summary
          {:hx-swap-oob "true"}
          (total-credits-row (:total-credits credit-changes) can-execute?)]

         ;; Sell and buy table total rows
         [:div {:id "sell-total" :hx-swap-oob "true"}
          (table-total-row "sell-total" "Credits (Income)" sell-total)]
         [:div {:id "buy-total" :hx-swap-oob "true"}
          (table-total-row "buy-total" "Credits (Expense)" buy-total)]

         ;; Submit button - disabled if player can't execute exchanges
         (submit-button can-execute? {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn exchange-page
  "Show exchange options and input fields for buying/selling resources and assets.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [player-id          (:xt/id player)
        rates              (get-exchange-rates game)
        zero-quantities    (into {} (for [spec (concat sell-row-specs buy-row-specs)]
                                      [(:qty-key spec) 0]))
        max-buy-quantities (calculate-max-buy-quantities player zero-quantities rates)]
    (ui/page
      {}
      [:div.mx-auto.max-w-4xl.w-full.text-green-400.font-mono

       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "EXCHANGE"
                        (str "Turn " (:player/current-turn player) " | Round " (:player/current-round player)))

       ;; Current resources before exchange
       (ui/extended-resource-display-grid player "Resources Before Exchange" false)

       (biff/form
         {:action (str "/app/game/" player-id "/apply-exchange")
          :method "post"}

         ;; Selling This Round Table
         [:h3.font-bold.mb-4 "Selling This Round"]
         [:div.w-full.mb-8
          [:div.border.border-green-400.overflow-x-auto
           [:div

            ;; Mobile header - abbreviated
            [:div.grid.gap-1.px-2.py-2.bg-green-400.bg-opacity-10.font-bold.border-b.border-green-400.text-xs.phase-row-grid.lg:hidden
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
            (for [spec sell-row-specs]
              (exchange-row (:label spec) (:abbrev spec) (:field spec)
                            (get rates (:rate-key spec)) 0
                            (get player (:player-key spec)) player-id exchange-hx-include))

            (table-total-row "sell-total" "Total Credits" 0)]]]

         ;; Buying This Round Table
         [:h3.font-bold.mb-4 "Buying This Round"]
         [:div.w-full.mb-8
          [:div.border.border-green-400.overflow-x-auto
           [:div

            ;; Mobile header - abbreviated
            [:div.grid.gap-1.px-2.py-2.bg-green-400.bg-opacity-10.font-bold.border-b.border-green-400.text-xs.phase-row-grid.lg:hidden
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
            (for [spec buy-row-specs]
              (exchange-row (:label spec) (:abbrev spec) (:field spec)
                            (get rates (:rate-key spec)) 0
                            (get max-buy-quantities (:max-key spec)) player-id exchange-hx-include))

            (table-total-row "buy-total" "Credits" 0)]]]

         ;; Resources After Exchange (initially shows current resources)
         [:div#resources-after
          (ui/extended-resource-display-grid player "Resources After Exchange" true)]

         ;; Warning message area - populated by HTMX if player tries invalid exchanges
         [:div#exchange-warning.flex.items-center]

         (ui/incoming-alert player)

         ;; Navigation and submit buttons
         [:div.flex.gap-4.mt-2
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id "/expenses")} "Cancel Exchange"]
          ;; Initial button starts disabled - HTMX updates will enable it when valid
          (submit-button false)])])))
