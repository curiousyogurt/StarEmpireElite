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
            [com.star-empire-elite.utils :as utils]
            [com.star-empire-elite.pages.app.income :as income-calc]
            [com.star-empire-elite.pages.app.expenses :as expenses-calc]))

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
   {:label "Energy Planets"   :abbrev "Erg Plts"  :qty-key :erg-planets  :cost-key :game/erg-planet-cost}
   {:label "Military Planets" :abbrev "Mil Plts"  :qty-key :mil-planets  :cost-key :game/mil-planet-cost}])

(def building-hx-include
  (str/join ","
    (for [spec purchase-row-specs]
      (str "[name='" (name (:qty-key spec)) "']"))))

(defn parse-purchase-quantities
  "Parse all purchase quantity inputs from request params.

  [params ring-params] -> {:soldiers int, :transports int, ...}"
  [params]
  (into {} (for [spec purchase-row-specs]
             [(:qty-key spec) (utils/parse-numeric-input (get params (:qty-key spec)))])))

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
  (-> (utils/player-snapshot player)
      (update :credits     - (:total-cost cost-info))
      (update :soldiers    + (:soldiers   quantities))
      (update :transports  + (:transports quantities))
      (update :generals    + (:generals   quantities))
      (update :carriers    + (:carriers   quantities))
      (update :fighters    + (:fighters   quantities))
      (update :admirals    + (:admirals   quantities))
      (update :stations    + (:stations   quantities))
      (update :cmd-ships   + (:cmd-ships  quantities))
      (update :agents      + (:agents     quantities))
      (update :ore-planets + (:ore-planets quantities))
      (update :erg-planets + (:erg-planets quantities))
      (update :mil-planets + (:mil-planets quantities))))

(defn can-afford-purchases?
  "Returns true if player has enough credits for all purchases.

  [resources-after purchase-resources-map] -> boolean"
  [resources-after]
  (>= (:credits resources-after) 0))

(defn calculate-max-quantities
  "Calculate the maximum total quantity of each item that can be selected.

  For each item, the max is computed after accounting for credits committed
  to all other items, but not the current item's own selected quantity. This
  keeps an item's max stable when the player enters that item's current max,
  while still reducing it when other purchases consume credits.

  [player player-map, quantities purchase-quantities, game game-map] -> {:soldiers int, :transports int, ...}"
  [player quantities game]
  (let [credits (:player/credits player)]
    (into {}
          (for [spec purchase-row-specs
                :let [qty-key       (:qty-key spec)
                      cost-per-unit (get game (:cost-key spec))
                      other-cost    (reduce
                                      +
                                      (for [other-spec purchase-row-specs
                                            :when (not= (:qty-key other-spec) qty-key)]
                                        (* (get quantities (:qty-key other-spec) 0)
                                           (get game (:cost-key other-spec)))))
                      remaining-for-this (- credits other-cost)
                      max-qty            (quot remaining-for-this cost-per-unit)]]
            [qty-key max-qty]))))

;;;;
;;;; SVG Deduction Bar
;;;;

(defn- deduction-bar
  "Render an SVG indicator bar showing a left-pointing resource deduction arrow.

  [before int, payment int, filter-id str] -> hiccup"
  [before payment filter-id]
  (ui/svg-indicator-bar :loss before payment filter-id))

;;;;
;;;; UI Components
;;;;

(defn- projections-section
  "Render the three resource projection pills: Credits, Food, Fuel.
  Pulls from income and expense calculations to show net flows by category.

  [player player-map, game game-map] -> hiccup"
  [player game]
  (let [income            (income-calc/calculate-income player game)
        required          (expenses-calc/calculate-required-expenses player game)
        credits-current   (:player/credits player)
        food-current      (:player/food player)
        fuel-current      (:player/fuel player)
        credits-changes   [{:label "Planets"
                            :value (- (:ore-credits income) (:planets-credits required))
                            :suffix "cr"
                            :id "credits-pill-planets"}
                           {:label "Military"
                            :value (- (+ (:soldiers-credits required)
                                         (:fighters-credits required)
                                         (:stations-credits required)))
                            :suffix "cr"
                            :id "credits-pill-military"}
                           {:label "Taxes"
                            :value (:tax-credits income)
                            :suffix "cr"}]
        food-changes      [{:label "Planets"
                            :value (:erg-food income)
                            :suffix "food"
                            :id "food-pill-planets"}
                           {:label "Military"
                            :value (- (+ (:soldiers-food required) (:agents-food required)))
                            :suffix "food"
                            :id "food-pill-military"}
                           {:label "Population"
                            :value (- (:population-food required))
                            :suffix "food"}]
        fuel-changes      [{:label "Planets"
                            :value (:erg-fuel income)
                            :suffix "fuel"
                            :id "fuel-pill-planets"}
                           {:label "Military"
                            :value (- (+ (:fighters-fuel required)
                                         (:stations-fuel required)
                                         (:agents-fuel required)))
                            :suffix "fuel"
                            :id "fuel-pill-military"}
                           {:label "Population"
                            :value (- (:population-fuel required))
                            :suffix "fuel"}]
        sum-changes       (fn [rows] (reduce + (map :value rows)))
        credits-total     (+ credits-current (sum-changes credits-changes))
        food-total        (+ food-current (sum-changes food-changes))
        fuel-total        (+ fuel-current (sum-changes fuel-changes))
        credits-rows      (concat [{:label "Current" :value credits-current :suffix "cr"
                                    :id "credits-pill-current"}]
                                  credits-changes)
        food-rows         (concat [{:label "Current" :value food-current :suffix "food"}]
                                  food-changes)
        fuel-rows         (concat [{:label "Current" :value fuel-current :suffix "fuel"}]
                                  fuel-changes)]
    [:div
     (ui/section-label "Resource Projections for Next Turn")
     [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-1.5"}
      (ui/projection-pill "Credits" credits-total credits-rows {:total-id "projection-credits-total"})
      (ui/projection-pill "Food"    food-total    food-rows {:total-id "projection-food-total"})
      (ui/projection-pill "Fuel"    fuel-total    fuel-rows {:total-id "projection-fuel-total"})]]))

(defn- build-table-header
  "Render the column-label row for the build orders table.

  [] -> hiccup"
  []
  (let [header-style {:background "#151f1a" :border-bottom "1px solid #253530"}
        col-style    {:letter-spacing "0.08em" :color "#4ade80"}
        item-style   (assoc col-style :letter-spacing "0.1em")
        label        (fn [text style extra-class]
                       [:span.text-xs.uppercase {:class extra-class :style style} text])
        r            (fn [text] (label text col-style "text-right justify-self-end"))
        c            (fn [text] (label text col-style "text-center justify-self-center"))]
    [:div.building-purchase-grid
     {:class "building-purchase-grid gap-2 py-1 px-3 items-center"
      :style header-style}
     (label "Item" item-style nil)
     (r "Each")
     (r "Max")
     (c "Build")
     (r "Cost")]))

(defn- build-purchase-row
  "Render one purchase row with item name, unit cost, max affordable, build input, and line cost.

  [spec map, purchase-qty int, max-qty int, game game-map, player-id uuid] -> hiccup"
  [spec purchase-qty max-qty game player-id]
  (let [cost-per-unit (get game (:cost-key spec))
        item-cost     (* cost-per-unit purchase-qty)
        cost-id       (str "cost-" (name (:qty-key spec)))
        max-qty-id    (str "max-qty-" (name (:qty-key spec)))
        row-style     {:border-bottom "1px solid #1a2820" :background "#121a18"}
        can-afford?   (not (neg? max-qty))]
    [:div.building-purchase-grid
     {:class "building-purchase-grid items-center gap-2 py-1 px-3"
      :style row-style}

     ;; Item name: abbreviated on mobile, full on desktop
     [:div.text-base.font-bold.text-green-400
      [:span.lg:hidden (:abbrev spec)]
      [:span.hidden.lg:inline (:label spec)]]

     ;; Each (cost per unit)
     [:div.text-base.text-right
      {:style {:color "#7ab88a"}}
      (ui/format-number cost-per-unit)]

     ;; Max affordable
     [:div.text-base.text-right
      {:style {:color (if can-afford? "#9adaaa" "#3a5040")}}
      [:span {:id max-qty-id
              :data-value (str (if (neg? max-qty) 0 max-qty))}
       (ui/format-number (if (neg? max-qty) 0 max-qty))]]

     ;; Build input + max button
     [:div.flex.items-center.justify-center
      {:style {:min-width "0"}}

      [:div.flex.items-center.gap-1
       {:style {:min-width "0"
                :transform "translateX(16px)"}}

       [:div
        {:style {:width "min(120px, 100%)"
                 :min-width "0"}}
        (ui/numeric-input (name (:qty-key spec)) purchase-qty player-id
                          "/calculate-building" building-hx-include
                          {:input-class "text-xs lg:text-sm text-right min-w-0"
                           :input-style {:color "#7ab88a"
                                         :border-color "#2d6644"
                                         :padding-top "1px"
                                         :padding-bottom "1px"}})]

       [:button.text-xs
        {:type "button"
         :style {:flex "0 0 auto"
                 :border "1px solid #2d6644"
                 :background "transparent"
                 :color "#7ab88a"
                 :padding "1px 4px"
                 :border-radius "2px"
                 :cursor "pointer"
                 :font-family "inherit"
                 :white-space "nowrap"}
         :onclick (str "var s=document.getElementById('" max-qty-id "');"
                       "var i=document.querySelector('[name=\"" (name (:qty-key spec)) "\"]');"
                       "if(s&&i){i.value=s.dataset.value||'0';"
                                 "i.dispatchEvent(new Event('input',{bubbles:true}));}")}
        "max"]]]

     ;; Line cost
     [:div.text-base.text-right
      {:style {:color (if (pos? item-cost) "#4ade80" "#3a5040")}}
      [:span {:id cost-id}
       (if (pos? item-cost)
         (ui/format-number item-cost)
         "—")]]]))

(defn- build-table
  "Render the full build orders table with header and all purchase rows.

  [player player-map, game game-map, quantities purchase-quantities, max-quantities map] -> hiccup"
  [player game quantities max-quantities]
  (let [player-id (:xt/id player)]
    [:div.overflow-hidden
     {:style {:border "1px solid #253530" :border-radius "3px" :background "#161616"}}
     (build-table-header)
     (for [spec purchase-row-specs
           :let [qty-key      (:qty-key spec)
                 purchase-qty (get quantities qty-key 0)
                 max-qty      (get max-quantities qty-key 0)]]
       (build-purchase-row spec purchase-qty max-qty game player-id))]))

(defn- build-credits-row
  "Render the credits row in the post-building expense summary.
  Shows a deduction bar (desktop) and OOB-updatable spans for cost and after-credits.

  [player player-map, cost int] -> hiccup"
  [player cost]
  (let [before      (:player/credits player)
        after       (- before cost)
        row-class   "items-center gap-2 py-1 px-3"
        row-style   {:border-bottom "1px solid #1a2820" :background "#121a18"}
        after-style (fn [v] {:color (if (neg? v) "#f87171" "#4ade80") :font-weight "bold"})
        cost-display (if (pos? cost) [:<> "-" (ui/format-number cost)] "—")]
    [:<>
     ;; Mobile row
     [:div.expense-row-mobile
      {:class (str "md:hidden " row-class) :style row-style}
      [:div.text-base.font-bold.text-green-400 "Credits"]
      [:div.text-base.text-right {:style {:color "#7ab88a"}} (ui/format-number before)]
      [:div.justify-self-center.text-base.whitespace-nowrap
       {:style {:color "#f87171"}}
       [:span#build-cost-display-m cost-display]]
      [:div.text-base.text-right
       [:span#after-credits-m {:style (after-style after)} (ui/format-number after)]]]

     ;; Desktop row
     [:div.expense-row-desktop
      {:class (str "hidden md:grid " row-class) :style row-style}
      [:div.text-base.font-bold.text-green-400 "Credits"]
      [:div {:id "bar-build-credits"} (deduction-bar before cost "glow-build-credits")]
      [:div.text-base.text-right {:style {:color "#7ab88a"}} (ui/format-number before)]
      [:div.justify-self-center.text-base.whitespace-nowrap
       {:style {:color "#f87171"}}
       [:span#build-cost-display-d cost-display]]
      [:div.text-base.text-right
       [:span#after-credits-d {:style (after-style after)} (ui/format-number after)]]]]))

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
                              :player/erg-planets  (:erg-planets resources-after)
                              :player/mil-planets  (:mil-planets resources-after)
                              :player/current-phase 4}])
            {:status 303
             :headers {"location" (str "/app/game/" player-id "/action")}}))))))

(defn calculate-building
  "Provide HTMX out-of-band updates as user changes purchase inputs.
  Updates the max quantities, item costs, credit deduction bar, after-credits, and submit button.

  [ctx ring-ctx] -> hiccup (via biff/render)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [quantities      (parse-purchase-quantities params)
          cost-info       (calculate-purchase-cost quantities game)
          resources-after (calculate-resources-after-purchases player quantities cost-info)
          affordable?     (can-afford-purchases? resources-after)
          max-quantities  (calculate-max-quantities player quantities game)
          total           (:total-cost cost-info)
          credits-after   (:credits resources-after)
          after-style     {:color (if (neg? credits-after) "#f87171" "#4ade80") :font-weight "bold"}
          cost-display    (if (pos? total) [:<> "-" (ui/format-number total)] "—")
          item-costs      (into {} (for [spec purchase-row-specs]
                                     [(:qty-key spec) (* (get quantities (:qty-key spec))
                                                         (get game (:cost-key spec)))]))
          ;; Credits projection: Planets row reflects proposed ore planet purchases
          income              (income-calc/calculate-income player game)
          required            (expenses-calc/calculate-required-expenses player game)
          new-ore-planets      (+ (:player/ore-planets player) (:ore-planets quantities))
          new-erg-planets      (+ (:player/erg-planets player) (:erg-planets quantities))
          new-mil-planets      (+ (:player/mil-planets player) (:mil-planets quantities))
          new-total-planets    (+ new-ore-planets new-erg-planets new-mil-planets)
          new-ore-income       (* new-ore-planets (:game/ore-planet-credits game))
          new-planet-expense   (* new-total-planets (:game/planet-upkeep-credits game))
          new-planets-value    (- new-ore-income new-planet-expense)
          ;; Military planet income adds to unit counts that must be maintained
          mil-income-soldiers  (* new-mil-planets (:game/mil-planet-soldiers game))
          mil-income-fighters  (* new-mil-planets (:game/mil-planet-fighters game))
          mil-income-stations  (* new-mil-planets (:game/mil-planet-stations game))
          proj-soldiers        (+ (:player/soldiers player) (:soldiers quantities) mil-income-soldiers)
          proj-fighters        (+ (:player/fighters player) (:fighters quantities) mil-income-fighters)
          proj-stations        (+ (:player/stations player) (:stations quantities) mil-income-stations)
          new-military-value   (- (+ (* proj-soldiers (:game/soldier-upkeep-credits game))
                                     (* proj-fighters (:game/fighter-upkeep-credits game))
                                     (* proj-stations (:game/station-upkeep-credits game))))
          proj-total           (+ credits-after new-planets-value
                                  new-military-value
                                  (:tax-credits income))
          ;; Food projection: Planets and Military rows update dynamically
          new-erg-food-income  (* new-erg-planets (:game/erg-planet-food game))
          new-planet-food-exp  (* new-total-planets (:game/planet-upkeep-food game))
          new-planets-food     (- new-erg-food-income new-planet-food-exp)
          proj-agents          (+ (:player/agents player) (:agents quantities))
          new-military-food    (- (+ (* proj-soldiers (:game/soldier-upkeep-food game))
                                     (* proj-agents (:game/agent-upkeep-food game))))
          food-current         (:player/food player)
          pop-food             (- (:population-food required))
          food-proj-total      (+ food-current new-planets-food new-military-food pop-food)
          ;; Fuel projection: Planets and Military rows update dynamically
          new-erg-fuel-income  (* new-erg-planets (:game/erg-planet-fuel game))
          new-military-fuel    (- (+ (* proj-fighters (:game/fighter-upkeep-fuel game))
                                     (* proj-stations (:game/station-upkeep-fuel game))
                                     (* proj-agents   (:game/agent-upkeep-fuel   game))))
          fuel-current         (:player/fuel player)
          pop-fuel             (- (:population-fuel required))
          fuel-proj-total      (+ fuel-current new-erg-fuel-income new-military-fuel pop-fuel)]
      (biff/render
        [:div
         ;; Renew HTMX swap-target placeholder for the next request
         [:div#resources-after]
         ;; OOB: credits pill "Current" row — decreases as build cost rises
         (ui/oob-pill "credits-pill-current" "Current" credits-after "cr")
         ;; OOB: credits pill "Planets" row — updates as ore/erg/mil planet purchases change
         (ui/oob-pill "credits-pill-planets" "Planets" new-planets-value "cr")
         ;; OOB: credits pill "Military" row — updates as soldier/fighter/station purchases change
         (ui/oob-pill "credits-pill-military" "Military" new-military-value "cr")
         ;; OOB: credits pill total — sum of all rows (current + planets + military + taxes)
         [:span#projection-credits-total {:hx-swap-oob "true" :style {:color "#7ab88a"}}
          (ui/format-number proj-total)]
         ;; OOB: food pill "Planets" row — updates as erg/ore/mil planet purchases change
         (ui/oob-pill "food-pill-planets" "Planets" new-planets-food "food")
         ;; OOB: food pill "Military" row — updates as soldier/agent purchases change
         (ui/oob-pill "food-pill-military" "Military" new-military-food "food")
         ;; OOB: food pill total
         [:span#projection-food-total {:hx-swap-oob "true" :style {:color "#7ab88a"}}
          (ui/format-number food-proj-total)]
         ;; OOB: fuel pill "Planets" row — updates as erg planet purchases change
         (ui/oob-pill "fuel-pill-planets" "Planets" new-erg-fuel-income "fuel")
         ;; OOB: fuel pill "Military" row — updates as fighter/station/agent purchases change
         (ui/oob-pill "fuel-pill-military" "Military" new-military-fuel "fuel")
         ;; OOB: fuel pill total
         [:span#projection-fuel-total {:hx-swap-oob "true" :style {:color "#7ab88a"}}
          (ui/format-number fuel-proj-total)]
         ;; OOB: after-credits spans (mobile + desktop)
         [:span#after-credits-m {:hx-swap-oob "true" :style after-style} (ui/format-number credits-after)]
         [:span#after-credits-d {:hx-swap-oob "true" :style after-style} (ui/format-number credits-after)]
         ;; OOB: deduction bar (desktop)
         [:div#bar-build-credits {:hx-swap-oob "true"} (deduction-bar (:player/credits player) total "glow-build-credits")]
         ;; OOB: cost change display (mobile + desktop)
         [:span#build-cost-display-m {:hx-swap-oob "true" :style {:color "#f87171"}} cost-display]
         [:span#build-cost-display-d {:hx-swap-oob "true" :style {:color "#f87171"}} cost-display]
         ;; OOB: max quantities per item
         (for [[item-key max-qty] max-quantities]
           [:span {:id (str "max-qty-" (name item-key))
                   :hx-swap-oob "true"
                   :key (str "max-" item-key)
                   :data-value (str (if (neg? max-qty) 0 max-qty))
                   :style {:color (if (or (zero? max-qty) (neg? max-qty)) "#3a5040" "#9adaaa")}}
            (ui/format-number (if (neg? max-qty) 0 max-qty))])
         ;; OOB: item line costs
         (for [[item-key cost] item-costs]
           [:span {:id (str "cost-" (name item-key))
                   :hx-swap-oob "true"
                   :key item-key
                   :style {:color (if (pos? cost) "#4ade80" "#3a5040")}}
            (if (pos? cost) (ui/format-number cost) "—")])
         ;; OOB: insufficient-credits warning
         [:div#building-warning.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.text-sm
             {:style {:letter-spacing "0.03em"}}
             "⚠ Insufficient credits for purchases."])]
         ;; OOB: submit button
         (ui/submit-button affordable? "Continue to Action" {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn building-page
  "Show purchase options for buying units and planets, with snapshot, projections, and credit impact.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [player-id      (:xt/id player)
        zero-quantities (into {} (map (fn [s] [(:qty-key s) 0]) purchase-row-specs))
        max-quantities  (calculate-max-quantities player zero-quantities game)]
    (ui/page
      {}
      [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
       {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
                :border-radius "4px" :color "#4ade80"
                :font-family "'Courier New', monospace"}}

       ;; Scanline overlay
       (ui/scanline-overlay)

       ;; Topbar
       (ui/phase-topbar player "BUILDING PHASE")

       ;; Form wraps body + action bar so the submit button works
       (biff/form
         {:action (str "/app/game/" player-id "/apply-building") :method "post"
          :style  {:margin 0}}

         ;; Body
         [:div.flex.flex-col.gap-2
          {:style {:padding "10px 14px"}}

          ;; 1. Snapshot — full empire state at T-0
          (ui/snapshot-section player)

          ;; 2. Projections — income/expense flows for Credits, Food, Fuel
          (projections-section player game)

          ;; 3. Build orders table
          [:div
           (ui/section-label "Build Orders")
           (build-table player game zero-quantities max-quantities)]

          ;; 4. Credit impact — deduction bar showing before/change/after for credits
          [:div
           (ui/section-label "Credit Impact")
           [:div.overflow-hidden
            {:style {:border "1px solid #253530" :border-radius "3px" :background "#161616"}}
            (ui/deduction-table-header)
            (build-credits-row player 0)]]

          ;; Hidden HTMX swap-target placeholder
          [:div#resources-after {:style {:display "none"}}]

          ;; Warning message area — populated by HTMX if player can't afford purchases
          [:div#building-warning.flex.items-center]

          (ui/incoming-alert player)]

         ;; Action bar
         [:div.flex.gap-2
          {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
          (ui/action-bar-link (str "/app/game/" player-id) "Pause")
          (ui/submit-button true "Continue to Action")])])))
