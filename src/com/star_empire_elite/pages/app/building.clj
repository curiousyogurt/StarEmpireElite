;;;;;
;;;;; Building Phase - Purchasing Units and Planets
;;;;;
;;;;; The building phase is the third phase of each turn, where players spend credits to purchase
;;;;; military units, support units, and planets. Unlike expenses (which cost multiple resources),
;;;;; building only costs credits; though projections need to track other classes of resources.
;;;;;
;;;;; This phase uses htmx for dynamic validation, showing players in real-time whether they can
;;;;; afford their selected purchases before submitting, as well as making projections.
;;;;;

(ns com.star-empire-elite.pages.app.building
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [com.star-empire-elite.pages.app.expenses :as expenses]))

;;;;
;;;; Calculations
;;;;

(def purchase-row-specs
  [{:label "Soldiers"         :abbrev "Soldiers"  :qty-key :soldiers     :cost-key :game/soldier-cost}
   {:label "Transports"       :abbrev "Transport" :qty-key :transports   :cost-key :game/transport-cost}
   {:label "Generals"         :abbrev "Generals"  :qty-key :generals     :cost-key :game/general-cost}
   {:label "Fighters"         :abbrev "Fighters"  :qty-key :fighters     :cost-key :game/fighter-cost}
   {:label "Carriers"         :abbrev "Carriers"  :qty-key :carriers     :cost-key :game/carrier-cost}
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
  (reduce (fn [m spec]
            (update m (:qty-key spec) + (get quantities (:qty-key spec) 0)))
          (update (utils/player-snapshot player) :credits - (:total-cost cost-info))
          purchase-row-specs))

(defn can-afford-purchases?
  "Returns true if player has enough credits for all purchases.

  [resources-after snapshot-map] -> boolean"
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
                :let [qty-key            (:qty-key spec)
                      cost-per-unit      (get game (:cost-key spec))
                      other-cost         (reduce
                                           +
                                           (for [other-spec purchase-row-specs
                                                 :when (not= (:qty-key other-spec) qty-key)]
                                             (* (get quantities (:qty-key other-spec) 0)
                                                (get game (:cost-key other-spec)))))
                      remaining-for-this (- credits other-cost)
                      max-qty            (quot remaining-for-this cost-per-unit)]]
            [qty-key max-qty]))))

;;;;
;;;; UI Components
;;;;

(defn build-projections-data
  "Compute the projections data structure for snapshot-section's :projections opt.
  Accounts for proposed purchases in quantities; pass zero-quantities for the initial page render.
  Credits 'Current' row reflects credits after the proposed build cost is deducted.

  [player player-map, game game-map, quantities purchase-quantities] -> projection-cards"
  [player game quantities]
  (let [required            (expenses/calculate-required-expenses player game)
        ;; Planet counts including proposed purchases
        new-ore-planets     (+ (:player/ore-planets player) (:ore-planets quantities))
        new-erg-planets     (+ (:player/erg-planets player) (:erg-planets quantities))
        new-mil-planets     (+ (:player/mil-planets player) (:mil-planets quantities))
        new-total-planets   (+ new-ore-planets new-erg-planets new-mil-planets)
        ;; Military counts including proposed purchases + new mil planet production
        mil-soldiers        (* new-mil-planets (:game/mil-planet-soldiers game))
        mil-fighters        (* new-mil-planets (:game/mil-planet-fighters game))
        mil-stations        (* new-mil-planets (:game/mil-planet-stations game))
        proj-soldiers       (+ (:player/soldiers player) (:soldiers quantities) mil-soldiers)
        proj-fighters       (+ (:player/fighters player) (:fighters quantities) mil-fighters)
        proj-stations       (+ (:player/stations player) (:stations quantities) mil-stations)
        proj-agents         (+ (:player/agents player) (:agents quantities))
        ;; Credits
        credits-current     (- (:player/credits player)
                               (:total-cost (calculate-purchase-cost quantities game)))
        planets-cr          (- (* new-ore-planets (:game/ore-planet-credits game))
                               (* new-total-planets (:game/planet-upkeep-credits game)))
        military-cr         (- (+ (* proj-soldiers (:game/soldier-upkeep-credits game))
                                  (* proj-fighters (:game/fighter-upkeep-credits game))
                                  (* proj-stations (:game/station-upkeep-credits game))))
        tax-cr              (* (:player/population player) (:game/population-tax-credits game))
        credits-total       (+ credits-current planets-cr military-cr tax-cr)
        ;; Food
        food-current        (:player/food player)
        planets-food        (- (* new-erg-planets (:game/erg-planet-food game))
                               (* new-total-planets (:game/planet-upkeep-food game)))
        military-food       (- (+ (* proj-soldiers (:game/soldier-upkeep-food game))
                                  (* proj-agents (:game/agent-upkeep-food game))))
        pop-food            (- (:population-food required))
        food-total          (+ food-current planets-food military-food pop-food)
        ;; Fuel
        fuel-current        (:player/fuel player)
        planets-fuel        (* new-erg-planets (:game/erg-planet-fuel game))
        military-fuel       (- (+ (* proj-fighters (:game/fighter-upkeep-fuel game))
                                  (* proj-stations (:game/station-upkeep-fuel game))
                                  (* proj-agents (:game/agent-upkeep-fuel game))))
        pop-fuel            (- (:population-fuel required))
        fuel-total          (+ fuel-current planets-fuel military-fuel pop-fuel)]
    [{:name "Credits"
      :total credits-total
      :total-id "projection-credits-total"
      :rows [{:label "Current"  :value credits-current :id "credits-pill-current"}
             {:label "Planets"  :value planets-cr      :id "credits-pill-planets"}
             {:label "Military" :value military-cr     :id "credits-pill-military"}
             {:label "Taxes"    :value tax-cr}]}
     {:name "Food"
      :total food-total
      :total-id "projection-food-total"
      :rows [{:label "Current"    :value food-current}
             {:label "Planets"    :value planets-food  :id "food-pill-planets"}
             {:label "Military"   :value military-food :id "food-pill-military"}
             {:label "Population" :value pop-food}]}
     {:name "Fuel"
      :total fuel-total
      :total-id "projection-fuel-total"
      :rows [{:label "Current"    :value fuel-current}
             {:label "Planets"    :value planets-fuel  :id "fuel-pill-planets"}
             {:label "Military"   :value military-fuel :id "fuel-pill-military"}
             {:label "Population" :value pop-fuel}]}]))

(defn- build-purchase-row
  "Render one purchase row with item name, unit cost, max affordable, build input, and line cost.

  [spec map, purchase-qty int, max-qty int, game game-map, player-id uuid] -> hiccup"
  [spec purchase-qty max-qty game player-id]
  (let [cost-per-unit (get game (:cost-key spec))
        item-cost     (* cost-per-unit purchase-qty)
        cost-id       (str "cost-" (name (:qty-key spec)))
        max-qty-id    (str "max-qty-" (name (:qty-key spec)))
        can-afford?   (not (neg? max-qty))]
    [:div.building-purchase-grid
     {:class "items-center gap-2 py-1 px-3 border-b border-game-divider bg-game-row"}

     ;; Item name: abbreviated on mobile, full on desktop
     [:div.text-base.font-bold.text-green-400
      [:span.lg:hidden (:abbrev spec)]
      [:span.hidden.lg:inline (:label spec)]]

     ;; Each (cost per unit)
     [:div.text-base.text-right.text-game-green-muted
      (ui/format-number cost-per-unit)]

     ;; Max affordable
     [:div.text-base.text-right
      {:class (if can-afford? "text-game-green-soft" "text-game-green-dark")}
      [:span {:id max-qty-id
              :data-value (str (if (neg? max-qty) 0 max-qty))}
       (ui/format-number (if (neg? max-qty) 0 max-qty))]]

     ;; Build input + max button
     [:div.flex.items-center.justify-center.min-w-0

      [:div.flex.items-center.gap-1.translate-x-4.min-w-0

       [:div.min-w-0
        {:style {:width "min(120px, 100%)"}}
        (ui/numeric-input (name (:qty-key spec)) purchase-qty player-id
                          "/calculate-building" building-hx-include
                          {:input-class "text-xs lg:text-sm text-right min-w-0"
                           :input-style {:color "#7ab88a"
                                         :border-color "#2d6644"
                                         :padding-top "1px"
                                         :padding-bottom "1px"}})]

       [:button.text-xs.shrink-0.border.border-game-green-border.bg-transparent.text-game-green-muted.py-px.px-1.rounded-sm.cursor-pointer.whitespace-nowrap
        {:type "button"
         :onclick (str "var s=document.getElementById('" max-qty-id "');"
                       "var i=document.querySelector('[name=\"" (name (:qty-key spec)) "\"]');"
                       "if(s&&i){i.value=s.dataset.value||'0';"
                                 "i.dispatchEvent(new Event('input',{bubbles:true}));}")}
        "max"]]]

     ;; Line cost
     [:div.text-base.text-right
      {:class (if (pos? item-cost) "text-green-400" "text-game-green-dark")}
      [:span {:id cost-id}
       (if (pos? item-cost)
         (ui/format-number item-cost)
         "—")]]]))

(defn- build-table
  "Render the full build orders table with header and all purchase rows.

  [player player-map, game game-map, quantities purchase-quantities, max-quantities map] -> hiccup"
  [player game quantities max-quantities]
  (let [player-id (:xt/id player)]
    [:div.overflow-hidden.rounded-game.bg-game-surface
     {:class "border border-game-border"}
     (ui/purchase-table-header "Each" "Build" "Cost")
     (for [spec purchase-row-specs
           :let [qty-key      (:qty-key spec)
                 purchase-qty (get quantities qty-key 0)
                 max-qty      (get max-quantities qty-key 0)]]
       (build-purchase-row spec purchase-qty max-qty game player-id))]))

(defn- build-credits-row
  "Render the credits row in the Credit Impact section.
  Shows a deduction bar (desktop) and OOB-updatable spans for cost and after-credits.

  [player player-map, cost int] -> hiccup"
  [player cost]
  (let [before      (:player/credits player)
        after       (- before cost)
        row-class    "items-center gap-2 py-1 px-3 border-b border-game-divider bg-game-row"
        after-cls    (fn [v] (str "font-bold " (if (neg? v) "text-red-400" "text-green-400")))
        cost-display (if (pos? cost) [:<> "-" (ui/format-number cost)] "0")]
    [:<>
     ;; Mobile row
     [:div.expense-row-mobile
      {:class (str "md:hidden " row-class)}
      [:div.text-base.font-bold.text-green-400 "Credits"]
      [:div.text-base.text-right.text-game-green-muted (ui/format-number before)]
      [:div.justify-self-center.text-base.whitespace-nowrap.text-red-400
       [:span#build-cost-display-m cost-display]]
      [:div.text-base.text-right
       [:span#after-credits-m {:class (after-cls after)} (ui/format-number after)]]]

     ;; Desktop row
     [:div.expense-row-desktop
      {:class (str "hidden md:grid " row-class)}
      [:div.text-base.font-bold.text-green-400 "Credits"]
      [:div {:id "bar-build-credits"} (ui/svg-indicator-bar :loss before cost "glow-build-credits")]
      [:div.text-base.text-right.text-game-green-muted (ui/format-number before)]
      [:div.justify-self-center.text-base.whitespace-nowrap.text-red-400
       [:span#build-cost-display-d cost-display]]
      [:div.text-base.text-right
       [:span#after-credits-d {:class (after-cls after)} (ui/format-number after)]]]]))

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
                              :player/fighters     (:fighters resources-after)
                              :player/carriers     (:carriers resources-after)
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
          after-cls       (str "font-bold " (if (neg? credits-after) "text-red-400" "text-green-400"))
          cost-display    (if (pos? total) [:<> "-" (ui/format-number total)] "0")
          item-costs      (into {} (for [spec purchase-row-specs]
                                     [(:qty-key spec) (* (get quantities (:qty-key spec))
                                                         (get game (:cost-key spec)))]))
          proj-data       (build-projections-data player game quantities)]
      (biff/render
        [:div
         ;; Renew HTMX swap-target placeholder for the next request
         [:div#resources-after]
         ;; OOB: projection pills — all dynamic rows (those with :id)
         (for [{:keys [rows]} proj-data
               {:keys [label value id]} rows
               :when id]
           (ui/oob-proj-pill id label value))
         ;; OOB: projection totals
         (for [{:keys [total total-id]} proj-data]
           [:span.font-bold
            {:id total-id :hx-swap-oob "true"
             :class (str "text-[14px] " (if (neg? total) "text-amber-400" "text-green-400"))
             :style {:text-shadow "0 0 10px rgba(61,220,132,0.25)"}}
            (ui/format-number total)])
         ;; OOB: after-credits spans (mobile + desktop)
         [:span#after-credits-m {:hx-swap-oob "true" :class after-cls} (ui/format-number credits-after)]
         [:span#after-credits-d {:hx-swap-oob "true" :class after-cls} (ui/format-number credits-after)]
         ;; OOB: deduction bar (desktop)
         [:div#bar-build-credits {:hx-swap-oob "true"} (ui/svg-indicator-bar :loss (:player/credits player) total "glow-build-credits")]
         ;; OOB: cost change display (mobile + desktop)
         [:span#build-cost-display-m {:hx-swap-oob "true" :class "text-green-400"} cost-display]
         [:span#build-cost-display-d {:hx-swap-oob "true" :class "text-green-400"} cost-display]
         ;; OOB: max quantities per item
         (for [[item-key max-qty] max-quantities]
           [:span {:id (str "max-qty-" (name item-key))
                   :hx-swap-oob "true"
                   :key (str "max-" item-key)
                   :data-value (str (if (neg? max-qty) 0 max-qty))
                   :class (if (or (zero? max-qty) (neg? max-qty)) "text-game-green-dark" "text-game-green-soft")}
            (ui/format-number (if (neg? max-qty) 0 max-qty))])
         ;; OOB: item line costs
         (for [[item-key cost] item-costs]
           [:span {:id (str "cost-" (name item-key))
                   :hx-swap-oob "true"
                   :key item-key
                   :class (if (pos? cost) "text-green-400" "text-game-green-dark")}
            (if (pos? cost) (ui/format-number cost) "—")])
         ;; OOB: insufficient-credits warning
         (ui/phase-warning-div "building-warning"
                               (when (not affordable?) "⚠ Insufficient credits to pay expenses.")
                               {:oob? true})
         ;; OOB: submit button
         (ui/submit-button affordable? "Continue to Action" {:hx-swap-oob "true"})]))))

;;;;
;;;; Page
;;;;

(defn building-page
  "Show purchase options for buying units and planets, with snapshot, projections, and credit impact.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [player-id        (:xt/id player)
        zero-quantities  (into {} (map (fn [s] [(:qty-key s) 0]) purchase-row-specs))
        max-quantities   (calculate-max-quantities player zero-quantities game)
        projections-data (build-projections-data player game zero-quantities)]
    (ui/phase-shell player game "BUILDING PHASE"
                    (biff/form
                      {:action (str "/app/game/" player-id "/apply-building") :method "post"
                       :class  "m-0"}
                      (ui/phase-body player
                                     (ui/snapshot-section player {:projections projections-data})
                                     (ui/section-label "Build Orders")
                                     [:div
                                      (build-table player game zero-quantities max-quantities)]
                                     (ui/section-label "Impact")
                                     [:div
                                      [:div.overflow-hidden.rounded-game.bg-game-surface
                                       {:class "border border-game-border"}
                                       (ui/deduction-table-header)
                                       (build-credits-row player 0)]]
                                     [:div#resources-after.hidden])
                      (ui/phase-warning "building-warning")
                      (ui/phase-action-bar
                        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
                        (ui/submit-button true "Continue to Action"))))))
