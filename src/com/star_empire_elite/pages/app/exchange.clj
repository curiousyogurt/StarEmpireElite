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
            [com.star-empire-elite.utils :as utils]
            [com.star-empire-elite.pages.app.expenses :as expenses]))

;;;;
;;;; Calculations
;;;;

(def sell-asset-row-specs
  [{:label "Soldiers"          :abbrev "Soldiers"         :field "soldiers-sold"     
    :qty-key :soldiers-sold    :rate-key :soldier-sell    :player-key :player/soldiers}
   {:label "Transports"        :abbrev "Transports"       :field "transports-sold"   
    :qty-key :transports-sold  :rate-key :transport-sell  :player-key :player/transports}
   {:label "Generals"          :abbrev "Generals"         :field "generals-sold"     
    :qty-key :generals-sold    :rate-key :general-sell    :player-key :player/generals}
   {:label "Fighters"          :abbrev "Fighters"         :field "fighters-sold"     
    :qty-key :fighters-sold    :rate-key :fighter-sell    :player-key :player/fighters}
   {:label "Carriers"          :abbrev "Carriers"         :field "carriers-sold"     
    :qty-key :carriers-sold    :rate-key :carrier-sell    :player-key :player/carriers}
   {:label "Admirals"          :abbrev "Admirals"         :field "admirals-sold"     
    :qty-key :admirals-sold    :rate-key :admiral-sell    :player-key :player/admirals}
   {:label "Defence Stations"  :abbrev "Def Stns"         :field "stations-sold"     
    :qty-key :stations-sold    :rate-key :station-sell    :player-key :player/stations}
   {:label "Command Ships"     :abbrev "Cmd Ships"        :field "cmd-ships-sold"    
    :qty-key :cmd-ships-sold   :rate-key :cmd-ship-sell   :player-key :player/cmd-ships}
   {:label "Agents"            :abbrev "Agents"           :field "agents-sold"       
    :qty-key :agents-sold      :rate-key :agent-sell      :player-key :player/agents}
   {:label "Military Planets"  :abbrev "Mil Plts"         :field "mil-planets-sold"  
    :qty-key :mil-planets-sold :rate-key :mil-planet-sell :player-key :player/mil-planets}
   {:label "Energy Planets"    :abbrev "Erg Plts"         :field "erg-planets-sold"  
    :qty-key :erg-planets-sold :rate-key :erg-planet-sell :player-key :player/erg-planets}
   {:label "Ore Planets"       :abbrev "Ore Plts"         :field "ore-planets-sold"  
    :qty-key :ore-planets-sold :rate-key :ore-planet-sell :player-key :player/ore-planets}])

(def sell-resource-row-specs
  [{:label "Food"        :abbrev "Food"           :field "food-sold"  :qty-key :food-sold 
    :rate-key :food-sell :player-key :player/food :resource-key :food 
    :ex-tooltip "Sell food excess"}
   {:label "Fuel"        :abbrev "Fuel"           :field "fuel-sold"  :qty-key :fuel-sold  
    :rate-key :fuel-sell :player-key :player/fuel :resource-key :fuel  
    :ex-tooltip "Sell fuel excess"}])

(def sell-row-specs
  (concat sell-asset-row-specs sell-resource-row-specs))

(def buy-row-specs
  [{:label "Food"       :abbrev "Food"     :field "food-bought" :qty-key :food-bought 
    :rate-key :food-buy :max-key :max-food :resource-key :food  :player-key :player/food 
    :sf-tooltip "Buy food shortfall"}
   {:label "Fuel"       :abbrev "Fuel"     :field "fuel-bought" :qty-key :fuel-bought 
    :rate-key :fuel-buy :max-key :max-fuel :resource-key :fuel  :player-key :player/fuel 
    :sf-tooltip "Buy fuel shortfall"}])

(def exchange-hx-include
  (str/join ","
            (for [spec (concat sell-row-specs buy-row-specs)]
              (str "[name='" (:field spec) "']"))))

(def ^:private resource-rate-keys
  "Rate keys for food and fuel that are handled separately from unit/planet credit sales."
  #{:food-sell :fuel-sell})

(defn get-exchange-rates
  "Extract exchange rates from the game entity.

  [game game-map] -> {:soldier-sell int, :transport-sell int, ...}"
  [game]
  {:soldier-sell     (:game/soldier-sell    game)
   :transport-sell   (:game/transport-sell  game)
   :general-sell     (:game/general-sell    game)
   :fighter-sell     (:game/fighter-sell    game)
   :carrier-sell     (:game/carrier-sell    game)
   :admiral-sell     (:game/admiral-sell    game)
   :station-sell     (:game/station-sell    game)
   :cmd-ship-sell    (:game/cmd-ship-sell   game)
   :agent-sell       (:game/agent-sell      game)
   :mil-planet-sell  (:game/mil-planet-sell game)
   :erg-planet-sell  (:game/erg-planet-sell game)
   :ore-planet-sell  (:game/ore-planet-sell game)
   :food-buy         (:game/food-buy        game)
   :food-sell        (:game/food-sell       game)
   :fuel-buy         (:game/fuel-buy        game)
   :fuel-sell        (:game/fuel-sell       game)})

(defn parse-exchange-quantities
  "Parse all exchange quantity inputs from request params.

  [params ring-params] -> {:soldiers-sold int, :transports-sold int, ...}"
  [params]
  (into {} (for [spec (concat sell-row-specs buy-row-specs)]
             [(:qty-key spec) (utils/parse-numeric-input (get params (:qty-key spec)))])))

(defn calculate-exchange-credits
  "Calculate net credit change from selling units/planets and buying/selling resources.

  [quantities exchange-quantities, rates exchange-rates] ->
  {:credits-from-sales int, :credits-from-resources int, :total-credits int}"
  [quantities rates]
  (let [credits-from-sales    (reduce + (for [spec sell-row-specs
                                              :when (not (resource-rate-keys (:rate-key spec)))]
                                          (* (get quantities (:qty-key spec)) (get rates (:rate-key spec)))))
        credits-from-resources (- (+ (* (:food-sold quantities)   (:food-sell rates))
                                     (* (:fuel-sold quantities)   (:fuel-sell rates)))
                                  (+ (* (:food-bought quantities) (:food-buy rates))
                                     (* (:fuel-bought quantities) (:fuel-buy rates))))]
    {:credits-from-sales     credits-from-sales
     :credits-from-resources credits-from-resources
     :total-credits          (+ credits-from-sales credits-from-resources)}))

(defn calculate-max-buy-quantities
  "Calculate maximum quantities that can be purchased with available credits after all sales.

  [player player-map, sell-quantities exchange-quantities, rates exchange-rates] -> {:max-food int, :max-fuel int}"
  [player sell-quantities rates]
  (let [sell-credit-changes     (calculate-exchange-credits
                                  (assoc sell-quantities :food-bought 0 :fuel-bought 0) rates)
        total-available-credits (+ (:player/credits player) (:total-credits sell-credit-changes))]
    {:max-food (max 0 (quot total-available-credits (:food-buy rates)))
     :max-fuel (max 0 (quot total-available-credits (:fuel-buy rates)))}))

(defn calculate-resources-after-exchange
  "Calculate all player resources after executing exchanges.

  [player player-map, quantities exchange-quantities, credit-changes exchange-credit-changes] -> {:credits int, :soldiers int, ...}"
  [player quantities credit-changes]
  (-> (utils/player-snapshot player)
      (update :credits     + (:total-credits credit-changes))
      (update :soldiers    - (:soldiers-sold    quantities))
      (update :transports  - (:transports-sold  quantities))
      (update :generals    - (:generals-sold    quantities))
      (update :carriers    - (:carriers-sold    quantities))
      (update :fighters    - (:fighters-sold    quantities))
      (update :admirals    - (:admirals-sold    quantities))
      (update :stations    - (:stations-sold    quantities))
      (update :cmd-ships   - (:cmd-ships-sold   quantities))
      (update :agents      - (:agents-sold      quantities))
      (update :mil-planets - (:mil-planets-sold quantities))
      (update :erg-planets - (:erg-planets-sold quantities))
      (update :ore-planets - (:ore-planets-sold quantities))
      (update :food        + (:food-bought quantities) (- (:food-sold quantities)))
      (update :fuel        + (:fuel-bought quantities) (- (:fuel-sold quantities)))))

(defn valid-exchange?
  "Returns true if player can execute the exchange (all resources non-negative).

  [resources-after exchange-resources-map] -> boolean"
  [resources-after]
  (every? #(>= (get resources-after %) 0)
          [:credits :soldiers :transports :generals :carriers :fighters
           :admirals :stations :cmd-ships :agents :mil-planets :erg-planets
           :ore-planets :food :fuel]))

(defn identify-invalid-exchanges
  "Returns map indicating which specific exchange types are invalid.

  [resources-after exchange-resources-map, quantities exchange-quantities] -> {:invalid-soldier-sale? bool, ...}"
  [resources-after quantities]
  {:invalid-soldier-sale?       (< (:soldiers resources-after) 0)
   :invalid-transport-sale?     (< (:transports resources-after) 0)
   :invalid-general-sale?       (< (:generals resources-after) 0)
   :invalid-fighter-sale?       (< (:fighters resources-after) 0)
   :invalid-carrier-sale?       (< (:carriers resources-after) 0)
   :invalid-admiral-sale?       (< (:admirals resources-after) 0)
   :invalid-station-sale?       (< (:stations resources-after) 0)
   :invalid-cmd-ship-sale?      (< (:cmd-ships resources-after) 0)
   :invalid-agent-sale?         (< (:agents resources-after) 0)
   :invalid-mil-planet-sale?    (< (:mil-planets resources-after) 0)
   :invalid-energy-planet-sale? (< (:erg-planets resources-after) 0)
   :invalid-ore-planet-sale?    (< (:ore-planets resources-after) 0)
   :invalid-food-sale?          (and (> (:food-sold quantities) 0)   (< (:food resources-after) 0))
   :invalid-fuel-sale?          (and (> (:fuel-sold quantities) 0)   (< (:fuel resources-after) 0))
   :invalid-food-purchase?      (and (> (:food-bought quantities) 0) (< (:credits resources-after) 0))
   :invalid-fuel-purchase?      (and (> (:fuel-bought quantities) 0) (< (:credits resources-after) 0))})

(defn calculate-required-expense-reduction
  "Calculate how much each required-expense total decreases when the player sells units or planets.
  Selling units/planets reduces their upkeep, so the Required rows in the expense-coverage pills
  should drop accordingly.

  Returns the per-resource reduction amounts (not the new totals).

  [quantities exchange-quantities, game game-map] -> {:credits int, :food int, :fuel int}"
  [quantities game]
  (let [planets-sold (+ (:mil-planets-sold quantities)
                        (:erg-planets-sold quantities)
                        (:ore-planets-sold quantities))]
    {:credits (+ (* (:soldiers-sold  quantities) (:game/soldier-upkeep-credits  game))
                 (* (:fighters-sold  quantities) (:game/fighter-upkeep-credits  game))
                 (* (:stations-sold  quantities) (:game/station-upkeep-credits  game))
                 (* planets-sold                 (:game/planet-upkeep-credits   game)))
     :food    (+ (* (:soldiers-sold  quantities) (:game/soldier-upkeep-food     game))
                 (* (:agents-sold    quantities) (:game/agent-upkeep-food       game))
                 (* planets-sold                 (:game/planet-upkeep-food      game)))
     :fuel    (+ (* (:fighters-sold  quantities) (:game/fighter-upkeep-fuel     game))
                 (* (:stations-sold  quantities) (:game/station-upkeep-fuel     game))
                 (* (:agents-sold    quantities) (:game/agent-upkeep-fuel       game)))}))

;;;;
;;;; View Models
;;;;

(defn build-exchange-projections-data
  "Compute the projections data structure consumed by projection-grid.
  Shows current holdings, required, and exchange impact for Credits, Food, and Fuel.
  Expense rows are negative; Exchange rows start at 0 and are updated via HTMX OOB.

  [player player-map, required-totals {:credits int, :food int, :fuel int}] -> projection-cards"
  [player required-totals]
  (let [cr-current   (:player/credits player)
        food-current (:player/food    player)
        fuel-current (:player/fuel    player)]
    [{:name "Credits" :total (- cr-current   (:credits required-totals)) :total-id "projection-credits-total"
      :rows [{:label "Current"  :value cr-current}
             {:label "Required" :value (- (:credits    required-totals)) :id "credits-pill-required"}
             {:label "Exchange" :value 0                                   :id "credits-pill-exchange"}]}
     {:name "Food"    :total (- food-current (:food    required-totals)) :total-id "projection-food-total"
      :rows [{:label "Current"  :value food-current}
             {:label "Required" :value (- (:food       required-totals)) :id "food-pill-required"}
             {:label "Exchange" :value 0                                   :id "food-pill-exchange"}]}
     {:name "Fuel"    :total (- fuel-current (:fuel    required-totals)) :total-id "projection-fuel-total"
      :rows [{:label "Current"  :value fuel-current}
             {:label "Required" :value (- (:fuel       required-totals)) :id "fuel-pill-required"}
             {:label "Exchange" :value 0                                   :id "fuel-pill-exchange"}]}]))


;;;;
;;;; UI Components
;;;;

(defn- exchange-impact-table
  "Render the Credits/Food/Fuel impact table showing net resource changes from exchange.

  [player player-map, resources-after {:credits int, :food int, :fuel int}] -> hiccup"
  [player resources-after]
  [:div.overflow-hidden.rounded-game.bg-game-surface
   {:class "border border-game-border"}
   (ui/deduction-table-header)
   (ui/impact-row "Credits" (:player/credits player) (:credits resources-after) "exchange" "glow-ex-credits")
   (ui/impact-row "Food"    (:player/food    player) (:food    resources-after) "exchange" "glow-ex-food")
   (ui/impact-row "Fuel"    (:player/fuel    player) (:fuel    resources-after) "exchange" "glow-ex-fuel")])

(defn exchange-row
  "Render one exchange row (sell or buy) with building-page styling.

  [item-name str, item-name-mobile str, field-key str, price-per-unit int,
   current-quantity int, max-quantity int, player-id uuid, hx-include str,
   opts (optional map):
   :ex-value   — when provided, adds an 'Ex' button that fills the input with this amount
   :ex-tooltip — tooltip text for the 'Ex' button
   :sf-value   — when provided, adds an 'Sf' button that fills the input with this amount
   :sf-tooltip — tooltip text for the 'Sf' button] -> hiccup"
  [item-name item-name-mobile field-key price-per-unit current-quantity max-quantity player-id hx-include
   & [{:keys [ex-value ex-tooltip sf-value sf-tooltip]}]]
  (let [credit-value (* price-per-unit current-quantity)
        credit-id    (str "credit-" field-key)
        max-qty-id   (str "max-qty-" field-key)
        has-max?     (pos? max-quantity)
        fill-onclick (fn [v]
                       (str "var i=document.querySelector('[name=\"" field-key "\"]');"
                            "if(i){i.value='" (long v) "';"
                                   "i.dispatchEvent(new Event('input',{bubbles:true}));}"))
        btn-cls      "absolute top-1/2 -translate-y-1/2 border border-game-green-border bg-game-green-done text-game-green-muted font-mono text-[11px] py-px px-1 rounded-sm cursor-pointer whitespace-nowrap"
        input-node   (ui/numeric-input field-key current-quantity player-id "/calculate-exchange" hx-include
                                       {:input-class "text-xs lg:text-sm text-right min-w-0"
                                        :input-style {:color "#7ab88a"
                                                      :border-color "#2d6644"
                                                      :padding-top "1px"
                                                      :padding-bottom "1px"}})]
    [:div.building-purchase-grid
     {:class "items-center gap-2 py-1 px-3 border-b border-game-divider bg-game-row"}

     ;; Item name: abbreviated on mobile, full on desktop
     [:div.text-base.font-bold.text-green-400
      [:span.lg:hidden item-name-mobile]
      [:span.hidden.lg:inline item-name]]

     ;; Rate per unit
     [:div.text-base.text-right.text-game-green-muted
      (ui/format-number price-per-unit)]

     ;; Max available/affordable
     [:div.text-base.text-right
      {:class (if has-max? "text-game-green-soft" "text-game-green-dark")}
      [:span {:id max-qty-id}
       (ui/format-number (if (neg? max-quantity) 0 max-quantity))]]

     ;; Quantity input.
     ;; The input box itself is always centered in the same 120px slot.
     ;; The optional Ex button hangs off the right side, so rows with and without
     ;; Ex buttons keep their text fields aligned.
     [:div.flex.items-center.justify-center.min-w-0

      [:div.relative.min-w-0
       {:class "w-[min(120px,100%)] translate-x-4"}

       input-node

       (when (some? ex-value)
         [:button
          {:type "button" :title ex-tooltip :onclick (fill-onclick ex-value)
           :class (str btn-cls " left-[calc(100%+4px)]")}
          "ex"])

       (when (some? sf-value)
         [:button
          {:type "button" :title sf-tooltip :onclick (fill-onclick sf-value)
           :class (str btn-cls " left-[calc(100%+4px)]")}
          "sf"])]]

     ;; Credits value
     [:div.text-base.text-right
      {:class (if (pos? credit-value) "text-green-400" "text-game-green-dark")}
      [:span {:id credit-id}
       (if (pos? credit-value)
         [:<> "+" (ui/format-number credit-value)]
         "—")]]]))

;;;;
;;;; Actions
;;;;

(defn apply-exchange
  "Commit exchanges to the database and redirect back to expenses. Stays in phase 2 to allow
  additional exchanges. Uses pure calculation functions to determine resource changes.
  If the exchange is no longer valid against current player state (e.g. units destroyed by an
  incoming attack between page load and submit), redirects back to the exchange page without writing.

  [ctx ring-ctx] -> ring-response (303 redirect to expenses or exchange)"
  [{:keys [path-params params biff/db session] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 2 player-id)]
      redirect
      (let [quantities     (parse-exchange-quantities params)
            rates          (get-exchange-rates game)
            credit-changes (calculate-exchange-credits quantities rates)
            resources-after (calculate-resources-after-exchange player quantities credit-changes)]
        (if-not (valid-exchange? resources-after)
          {:status  303
           :headers {"location" (str "/app/game/" player-id "/exchange")}
           :session (utils/flash session :warn
                      "Submission rejected due to a change in your empire (enemy attack or espionage). Please review and resubmit.")}
          (do
            (biff/submit-tx ctx
                            [{:db/doc-type :player
                              :db/op :update
                              :xt/id player-id
                              :player/credits     (:credits resources-after)
                              :player/soldiers    (:soldiers resources-after)
                              :player/transports  (:transports resources-after)
                              :player/generals    (:generals resources-after)
                              :player/carriers    (:carriers resources-after)
                              :player/fighters    (:fighters resources-after)
                              :player/admirals    (:admirals resources-after)
                              :player/stations    (:stations resources-after)
                              :player/cmd-ships   (:cmd-ships resources-after)
                              :player/agents      (:agents resources-after)
                              :player/mil-planets (:mil-planets resources-after)
                              :player/erg-planets (:erg-planets resources-after)
                              :player/ore-planets (:ore-planets resources-after)
                              :player/food        (:food resources-after)
                              :player/fuel        (:fuel resources-after)}])
            {:status 303
             :headers {"location" (str "/app/game/" player-id "/expenses")}}))))))

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
          ;; Pill exchange values
          required        (expenses/calculate-required-expenses player game)
          required-totals  (expenses/calculate-required-expense-totals required)
          expense-reduction  (calculate-required-expense-reduction quantities game)
          exchange-credits   (:total-credits credit-changes)
          exchange-food      (- (:food-bought quantities) (:food-sold quantities))
          exchange-fuel      (- (:fuel-bought quantities) (:fuel-sold quantities))
          adj-cr-required   (- (:credits required-totals) (:credits expense-reduction))
          adj-food-required (- (:food    required-totals) (:food    expense-reduction))
          adj-fuel-required (- (:fuel    required-totals) (:fuel    expense-reduction))
          cr-total      (+ (- (:player/credits player) adj-cr-required)   exchange-credits)
          food-total    (+ (- (:player/food    player) adj-food-required) exchange-food)
          fuel-total    (+ (- (:player/fuel    player) adj-fuel-required) exchange-fuel)]
      (biff/render
        [:div
         ;; Renew HTMX swap-target so subsequent requests can find it
         [:div#resources-after]
         ;; OOB: credit value spans for all sell and buy rows
         (for [spec (concat sell-row-specs buy-row-specs)
               :let [qty (get quantities (:qty-key spec))
                     v   (* qty (get rates (:rate-key spec)))]]
           [:span {:id (str "credit-" (:field spec)) :hx-swap-oob "true"
                   :class (if (pos? v) "text-green-400" "text-game-green-dark")}
            (if (pos? v) [:<> "+" (ui/format-number v)] "—")])

         ;; OOB: maximum quantities for buy rows
         (for [spec buy-row-specs
               :let [max-qty (get max-buy-quantities (:max-key spec))]]
           [:span {:id (str "max-qty-" (:field spec)) :hx-swap-oob "true"
                   :class (if (pos? max-qty) "text-game-green-soft" "text-game-green-dark")}
            (ui/format-number max-qty)])

         ;; OOB: pill Required rows (adjusted for proposed unit/planet sales)
         (ui/oob-proj-pill "credits-pill-required" "Required" (- adj-cr-required))
         (ui/oob-proj-pill "food-pill-required"    "Required" (- adj-food-required))
         (ui/oob-proj-pill "fuel-pill-required"    "Required" (- adj-fuel-required))

         ;; OOB: pill Exchange rows
         (ui/oob-proj-pill "credits-pill-exchange" "Exchange" exchange-credits)
         (ui/oob-proj-pill "food-pill-exchange"    "Exchange" exchange-food)
         (ui/oob-proj-pill "fuel-pill-exchange"    "Exchange" exchange-fuel)

         ;; OOB: projection card totals
         [:span.font-bold
          {:id "projection-credits-total" :hx-swap-oob "true"
           :class (str "text-[14px] " (if (neg? cr-total) "text-amber-400" "text-green-400"))
           :style {:text-shadow "0 0 10px rgba(61,220,132,0.25)"}}
          (ui/format-number cr-total)]
         [:span.font-bold
          {:id "projection-food-total" :hx-swap-oob "true"
           :class (str "text-[14px] " (if (neg? food-total) "text-amber-400" "text-green-400"))
           :style {:text-shadow "0 0 10px rgba(61,220,132,0.25)"}}
          (ui/format-number food-total)]
         [:span.font-bold
          {:id "projection-fuel-total" :hx-swap-oob "true"
           :class (str "text-[14px] " (if (neg? fuel-total) "text-amber-400" "text-green-400"))
           :style {:text-shadow "0 0 10px rgba(61,220,132,0.25)"}}
          (ui/format-number fuel-total)]

         ;; OOB: exchange summary bar table — bars, change spans, after spans
         (let [cr-before   (:player/credits player)
               food-before (:player/food    player)
               fuel-before (:player/fuel    player)
               cr-after    (:credits resources-after)
               food-after  (:food    resources-after)
               fuel-after  (:fuel    resources-after)
               cr-delta    (- cr-after   cr-before)
               food-delta  (- food-after food-before)
               fuel-delta  (- fuel-after fuel-before)
               oob-bar     (fn [id before after filter-id]
                             [:div {:id id :hx-swap-oob "true"}
                              (if (>= after before)
                                (ui/svg-indicator-bar :gain before after filter-id)
                                (ui/svg-indicator-bar :loss before (- before after) filter-id))])
               oob-after   (fn [id v]
                             [:span {:id id :hx-swap-oob "true"
                                     :class (str "font-bold " (if (neg? v) "text-red-400" "text-green-400"))}
                              (ui/format-number v)])
               oob-change  (fn [id delta]
                             [:span {:id id :hx-swap-oob "true"
                                     :class (str "tracking-[0.03em] "
                                                 (if (zero? delta) "text-game-green-muted" "text-green-400"))}
                              (if (zero? delta)
                                "0"
                                [:<> (if (pos? delta) "+" "−")
                                 (ui/format-number (Math/abs (long delta)))])])]
           (list
             (oob-bar    "bar-exchange-credits"    cr-before   cr-after   "glow-ex-credits")
             (oob-bar    "bar-exchange-food"       food-before food-after "glow-ex-food")
             (oob-bar    "bar-exchange-fuel"       fuel-before fuel-after "glow-ex-fuel")
             (oob-change "change-exchange-credits-m" cr-delta)
             (oob-change "change-exchange-credits-d" cr-delta)
             (oob-change "change-exchange-food-m"    food-delta)
             (oob-change "change-exchange-food-d"    food-delta)
             (oob-change "change-exchange-fuel-m"    fuel-delta)
             (oob-change "change-exchange-fuel-d"    fuel-delta)
             (oob-after  "after-exchange-credits-m"  cr-after)
             (oob-after  "after-exchange-credits-d"  cr-after)
             (oob-after  "after-exchange-food-m"     food-after)
             (oob-after  "after-exchange-food-d"     food-after)
             (oob-after  "after-exchange-fuel-m"     fuel-after)
             (oob-after  "after-exchange-fuel-d"     fuel-after)))

         ;; OOB: warning message
         (ui/phase-warning-div "exchange-warning"
                               (when (not can-execute?)
                                 (let [sell-overrun? (some #(neg? (get resources-after %))
                                                           [:soldiers :transports :generals :carriers :fighters
                                                            :admirals :stations :cmd-ships :agents
                                                            :mil-planets :erg-planets :ore-planets :food :fuel])
                                       buy-overrun?  (neg? (:credits resources-after))]
                                   (cond
                                     (and sell-overrun? buy-overrun?) "⚠ Cannot sell more than you have or buy more than you can afford."
                                     sell-overrun?                    "⚠ Cannot sell more than you have."
                                     buy-overrun?                     "⚠ Cannot buy more than you can afford.")))
                               {:oob? true})

         ;; OOB: submit button
         (ui/submit-button can-execute? "Make Exchange" {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn exchange-page
  "Show exchange options and input fields for buying/selling resources and assets.

  [{:keys [player game flash]}] -> hiccup"
  [{:keys [player game flash]}]
  (let [player-id          (:xt/id player)
        rates              (get-exchange-rates game)
        zero-quantities    (into {} (for [spec (concat sell-row-specs buy-row-specs)]
                                      [(:qty-key spec) 0]))
        max-buy-quantities (calculate-max-buy-quantities player zero-quantities rates)
        required           (expenses/calculate-required-expenses player game)
        required-totals    (expenses/calculate-required-expense-totals required)
        projections-data   (build-exchange-projections-data player required-totals)]
    (ui/phase-shell player game "Exchange"
                    (biff/form
                      {:action (str "/app/game/" player-id "/apply-exchange") :method "post"
                       :class  "m-0"}
                      (ui/phase-body
                        player
                        ;; Flash if necessary
                        (ui/flash-notice flash)
                        ;; Empire status
                        (ui/snapshot-section player)
                        ;; Projections
                        (ui/section-label "Expenses" "‣ Current Turn + Exchange")
                        (ui/projection-grid projections-data {:projection-turn "THIS TURN"})
                        ;; Assets to sell
                        (ui/section-label "Sell Assets")
                        [:div
                         [:div.overflow-hidden.rounded-game.bg-game-surface
                          {:class "border border-game-border"}
                          (ui/purchase-table-header "Rate" "Sell" "Credits")
                          (for [spec sell-asset-row-specs]
                            (exchange-row (:label spec) (:abbrev spec) (:field spec)
                                          (get rates (:rate-key spec)) 0
                                          (get player (:player-key spec)) player-id exchange-hx-include))]]
                        ;; Resources to sell
                        (ui/section-label "Sell Resources")
                        [:div
                         [:div.overflow-hidden.rounded-game.bg-game-surface
                          {:class "border border-game-border"}
                          (ui/purchase-table-header "Rate" "Sell" "Credits")
                          (for [spec sell-resource-row-specs
                                :let [ex-val (max 0 (- (get player (:player-key spec))
                                                       (get required-totals (:resource-key spec) 0)))]]
                            (exchange-row (:label spec) (:abbrev spec) (:field spec)
                                          (get rates (:rate-key spec)) 0
                                          (get player (:player-key spec)) player-id exchange-hx-include
                                          {:ex-value ex-val :ex-tooltip (:ex-tooltip spec)}))]]
                        ;; Resources to buy
                        (ui/section-label "Buy Resources")
                        [:div
                         [:div.overflow-hidden.rounded-game.bg-game-surface
                          {:class "border border-game-border"}
                          (ui/purchase-table-header "Rate" "Buy" "Credits")
                          (for [spec buy-row-specs
                                :let [sf-val (max 0 (- (get required-totals (:resource-key spec) 0)
                                                       (get player (:player-key spec))))]]
                            (exchange-row (:label spec) (:abbrev spec) (:field spec)
                                          (get rates (:rate-key spec)) 0
                                          (get max-buy-quantities (:max-key spec)) player-id exchange-hx-include
                                          {:sf-value sf-val :sf-tooltip (:sf-tooltip spec)}))]]
                        (ui/section-label "Impact")
                        [:div
                         (exchange-impact-table player {:credits (:player/credits player)
                                                        :food    (:player/food    player)
                                                        :fuel    (:player/fuel    player)})]
                        [:div#resources-after.hidden])
                      (ui/phase-warning "exchange-warning")
                      (ui/phase-action-bar
                        (ui/action-bar-link (str "/app/game/" player-id "/expenses") "Cancel Exchange")
                        (ui/submit-button false "Make Exchange"))))))

