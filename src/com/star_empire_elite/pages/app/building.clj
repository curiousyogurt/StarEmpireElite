;;;;;
;;;;; Building Phase - Purchasing Units and Planets
;;;;;
;;;;; The building phase is the third phase of each turn, where players spend credits to purchase
;;;;; military units, support units, and planets. Unlike expenses (which cost multiple resources),
;;;;; building only costs credits (though projections need to track other classes of resources).
;;;;;
;;;;; This phase uses htmx for dynamic validation, showing players in real-time whether they can
;;;;; afford their selected purchases before submitting, as well as making projections into the next 
;;;;; turn, since purchases made in this phase affect income and expenses in the following phase.
;;;;;

;;;;; Logical Structure:
;;;;;
;;;;; 1)  building-page ← calculate-max-quantities
;;;;;     └─ ui/phase-shell
;;;;;        ├─ biff/form
;;;;;        │  ├─ ui/phase-body
;;;;;        │  │  ├─ ui/flash-notice
;;;;;        │  │  ├─ ui/snapshot-section
;;;;;        │  │  ├─ ui/section-label "Projected Resources"
;;;;;        │  │  ├─ projection-grid
;;;;;        │  │  │  └─ projection-card  (×3: Credits, Food, Fuel)
;;;;;        │  │  │     └─ projection-row  (per resource value)
;;;;;        │  │  ├─ ui/section-label "Build Orders"
;;;;;        │  │  ├─ build-table
;;;;;        │  │  │  └─ build-row  (per purchase-rows)
;;;;;        │  │  ├─ ui/section-label "Impact"
;;;;;        │  │  └─ build-credits-row
;;;;;        │  └─ ui/phase-warning
;;;;;        └─ ui/phase-action-bar
;;;;;           ├─ ui/action-bar-link "Pause"
;;;;;           └─ ui/submit-button "Continue to Action"
;;;;;
;;;;; 2)  calculate-building-oob ← parse-purchase-quantities, calculate-purchase-cost,
;;;;;                               calculate-resources-after-purchases, valid-purchase?,
;;;;;                               calculate-max-quantities  (HTMX OOB handler)
;;;;;
;;;;; 3)  apply-building ← parse-purchase-quantities, calculate-purchase-cost,
;;;;;                       calculate-resources-after-purchases, valid-purchase?

(ns com.star-empire-elite.pages.app.building
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [com.star-empire-elite.pages.app.expenses :as expenses]))


;;;;
;;;; Calculations
;;;;

(def purchase-rows
  [{:label "Soldiers"         :abbrev "Soldiers"  
    :qty-key :soldiers        :cost-key :game/soldier-cost}
   {:label "Transports"       :abbrev "Transport" 
    :qty-key :transports      :cost-key :game/transport-cost}
   {:label "Generals"         :abbrev "Generals"  
    :qty-key :generals        :cost-key :game/general-cost}
   {:label "Fighters"         :abbrev "Fighters" 
    :qty-key :fighters        :cost-key :game/fighter-cost}
   {:label "Carriers"         :abbrev "Carriers" 
    :qty-key :carriers        :cost-key :game/carrier-cost}
   {:label "Admirals"         :abbrev "Admirals" 
    :qty-key :admirals        :cost-key :game/admiral-cost}
   {:label "Defence Stations" :abbrev "Def Stns" 
    :qty-key :stations        :cost-key :game/station-cost}
   {:label "Command Ships"    :abbrev "Cmd Ships"
    :qty-key :cmd-ships       :cost-key :game/cmd-ship-cost}
   {:label "Agents"           :abbrev "Agents"   
    :qty-key :agents          :cost-key :game/agent-cost}
   {:label "Ore Planets"      :abbrev "Ore Plts" 
    :qty-key :ore-planets     :cost-key :game/ore-planet-cost}
   {:label "Energy Planets"   :abbrev "Erg Plts" 
    :qty-key :erg-planets     :cost-key :game/erg-planet-cost}
   {:label "Military Planets" :abbrev "Mil Plts" 
    :qty-key :mil-planets     :cost-key :game/mil-planet-cost}])

(def building-hx-include
  (str/join ","
            (for [row purchase-rows]
              (str "[name='" (name (:qty-key row)) "']"))))

(defn- total-cost
  "Sum of quantity × cost-per-unit across all purchasable items.

  [quantities purchase-quantities, game game-map] -> int"
  [quantities game]
  (reduce + (for [row purchase-rows]
              (* (get quantities (:qty-key row))
                 (get game (:cost-key row))))))

(defn calculate-purchase-cost
  "Calculate total credits needed for all purchases based on game constants.

  [quantities purchase-quantities, game game-map] -> {:total-cost int}"
  [quantities game]
  {:total-cost (total-cost quantities game)})

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
          (for [row purchase-rows
                :let [qty-key            (:qty-key row)
                      cost-per-unit      (get game (:cost-key row))
                      other-cost         (reduce
                                           +
                                           (for [other-row purchase-rows
                                                 :when (not= (:qty-key other-row) qty-key)]
                                             (* (get quantities (:qty-key other-row) 0)
                                                (get game (:cost-key other-row)))))
                      remaining-for-this (- credits other-cost)
                      max-qty            (quot remaining-for-this cost-per-unit)]]
            [qty-key max-qty]))))

(defn calculate-resources-after-purchases
  "Calculate player resources after executing purchases.

  [player player-map, quantities purchase-quantities, cost-info {:total-cost int}] -> {:credits int, :soldiers int, ...}"
  [player quantities cost-info]
  (reduce (fn [m row]
            (update m (:qty-key row) + (get quantities (:qty-key row) 0)))
          (update (utils/player-snapshot player) :credits - (:total-cost cost-info))
          purchase-rows))

(defn valid-purchase?
  "Returns true if player has enough credits for all purchases.

  [resources-after snapshot-map] -> boolean"
  [resources-after]
  (>= (:credits resources-after) 0))


;;;;
;;;; UI Components
;;;; - Projections Data
;;;; - Build Table
;;;; - Credit Impact
;;;;

;;;
;;; The Projection Grid shows next-turn resource projections for Credits, Food, and Fuel, each in a 
;;; card.  Each card has a signed total aside and pill rows showing Current, Planets, Military, and 
;;; a fourth source (Taxes or Population).  Dynamic rows update via HTMX OOB as the player adjusts 
;;; purchase inputs.
;;;
;;; Projection Grid:
;;; - projection-row:  one pill for a projection value, with optional OOB id
;;; - projection-card: one card with title, signed total aside, and projection rows
;;; - projection-grid: three cards (Credits, Food, Fuel) in a 3-column grid
;;;

(defn- projection-row
  "Render one projection pill row for a resource value.
  All rows display regardless of value. Supports optional :id for HTMX OOB targeting.

  [label str, amount int, & [{:keys [id]}]] -> hiccup"
  [label amount & [{:keys [id]}]]
  (ui/info-pill {:key label :label label :value amount :signed? true :id id}))

(defn- projection-card
  "Render one projection card with title, signed total aside, and projection rows.
  Supports optional total-id for HTMX OOB targeting of the aside.

  [title str, total int, total-id str-or-nil, & body hiccup] -> hiccup"
  [title total total-id & body]
  [:div
   {:class "flex flex-col gap-1 py-1.5 px-2 border border-game-border rounded-game bg-game-card"}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 title]
    [:span.font-bold
     (cond-> {:class (str "text-[14px] " (if (neg? total) "text-amber-400" "text-green-400"))
              :style {:text-shadow "0 0 10px rgba(61,220,132,0.25)"}}
       total-id (assoc :id total-id))
     (ui/format-signed-number total)]]
   [:div {:class "flex flex-col gap-0.5"}
    body]])

(defn- projection-grid
  "Render the projection grid: three cards (Credits, Food, Fuel) in a 3-column grid, each showing 
  next-turn projections accounting for proposed purchases. Credits 'Current' row reflects credits 
  after the proposed build cost is deducted.

  [player player-map, game game-map, quantities purchase-quantities] -> hiccup"
  [player game quantities]
  (let [required          (expenses/calculate-expenses player game)
        ;; Planet counts including proposed purchases
        new-ore-planets   (+ (:player/ore-planets player) (:ore-planets quantities))
        new-erg-planets   (+ (:player/erg-planets player) (:erg-planets quantities))
        new-mil-planets   (+ (:player/mil-planets player) (:mil-planets quantities))
        new-total-planets (+ new-ore-planets new-erg-planets new-mil-planets)

        ;; Military counts including proposed purchases + new mil planet production
        mil-soldiers      (* new-mil-planets (:game/mil-planet-soldiers game))
        mil-fighters      (* new-mil-planets (:game/mil-planet-fighters game))
        mil-stations      (* new-mil-planets (:game/mil-planet-stations game))
        proj-soldiers     (+ (:player/soldiers player) (:soldiers quantities) mil-soldiers)
        proj-fighters     (+ (:player/fighters player) (:fighters quantities) mil-fighters)
        proj-stations     (+ (:player/stations player) (:stations quantities) mil-stations)
        proj-agents       (+ (:player/agents player) (:agents quantities))

        ;; Credits
        credits-current   (- (:player/credits player)
                             (:total-cost (calculate-purchase-cost quantities game)))
        planets-cr        (- (* new-ore-planets (:game/ore-planet-credits game))
                             (* new-total-planets (:game/planet-upkeep-credits game)))
        military-cr       (- (+ (* proj-soldiers (:game/soldier-upkeep-credits game))
                                (* proj-fighters (:game/fighter-upkeep-credits game))
                                (* proj-stations (:game/station-upkeep-credits game))))
        tax-cr            (* (:player/population player) (:game/population-tax-credits game))
        credits-total     (+ credits-current planets-cr military-cr tax-cr)

        ;; Food
        food-current      (:player/food player)
        planets-food      (- (* new-erg-planets (:game/erg-planet-food game))
                             (* new-total-planets (:game/planet-upkeep-food game)))
        military-food     (- (+ (* proj-soldiers (:game/soldier-upkeep-food game))
                                (* proj-agents (:game/agent-upkeep-food game))))
        pop-food          (- (:population-food required))
        food-total        (+ food-current planets-food military-food pop-food)

        ;; Fuel
        fuel-current      (:player/fuel player)
        planets-fuel      (* new-erg-planets (:game/erg-planet-fuel game))
        military-fuel     (- (+ (* proj-fighters (:game/fighter-upkeep-fuel game))
                                (* proj-stations (:game/station-upkeep-fuel game))
                                (* proj-agents (:game/agent-upkeep-fuel game))))
        pop-fuel          (- (:population-fuel required))
        fuel-total        (+ fuel-current planets-fuel military-fuel pop-fuel)]

    [:div {:class "grid grid-cols-3 gap-2"}
     (projection-card "Credits" credits-total "projection-credits-total"
                      (projection-row "Current"  credits-current {:id "credits-pill-current"})
                      (projection-row "Planets"  planets-cr      {:id "credits-pill-planets"})
                      (projection-row "Military" military-cr     {:id "credits-pill-military"})
                      (projection-row "Taxes"    tax-cr))
     (projection-card "Food" food-total "projection-food-total"
                      (projection-row "Current"    food-current)
                      (projection-row "Planets"    planets-food  {:id "food-pill-planets"})
                      (projection-row "Military"   military-food {:id "food-pill-military"})
                      (projection-row "Population" pop-food))
     (projection-card "Fuel" fuel-total "projection-fuel-total"
                      (projection-row "Current"    fuel-current)
                      (projection-row "Planets"    planets-fuel  {:id "fuel-pill-planets"})
                      (projection-row "Military"   military-fuel {:id "fuel-pill-military"})
                      (projection-row "Population" pop-fuel))]))

;;;
;;; The Build Table shows purchasable units and planets with per-unit cost, max affordable quantity, 
;;; a numeric input, and line cost.  A "max" button beside each input fills in the maximum affordable 
;;; quantity.
;;;
;;; Build Table:
;;; - build-row:   one row for a single purchasable item
;;; - build-table: full table with header and all purchase rows
;;;

(defn- build-row
  "Render one purchase row with item name, unit cost, max affordable, build input, and line cost.

  [row map, purchase-qty int, max-qty int, game game-map, player-id uuid] -> hiccup"
  [row purchase-qty max-qty game player-id]
  (let [cost-per-unit (get game (:cost-key row))
        item-cost     (* cost-per-unit purchase-qty)
        cost-id       (str "cost-" (name (:qty-key row)))
        max-qty-id    (str "max-qty-" (name (:qty-key row)))
        can-afford?   (not (neg? max-qty))]
    [:div.building-purchase-grid
     {:class "items-center gap-2 py-1 px-3 border-b border-game-divider bg-game-row"}

     ;; Item name: abbreviated on mobile, full on desktop
     [:div.text-base.font-bold.text-green-400
      [:span.lg:hidden (:abbrev row)]
      [:span.hidden.lg:inline (:label row)]]

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
        {:class "w-[min(120px,100%)]"}
        (ui/numeric-input (name (:qty-key row)) purchase-qty player-id
                          "/calculate-building" building-hx-include
                          {:input-class "text-xs lg:text-sm text-right min-w-0"
                           :input-style {:color "#7ab88a"
                                         :border-color "#2d6644"
                                         :padding-top "1px"
                                         :padding-bottom "1px"}})]

       [:button.text-xs.shrink-0.border.border-game-green-border.bg-transparent.text-game-green-muted.py-px.px-1.rounded-sm.cursor-pointer.whitespace-nowrap
        {:type "button"
         :onclick (str "var s=document.getElementById('" max-qty-id "');"
                       "var i=document.querySelector('[name=\"" (name (:qty-key row)) "\"]');"
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
     (ui/purchase-table-header "Each" "Build" "Cost" {:action-btn-placeholder "max"})
     (for [row purchase-rows
           :let [qty-key      (:qty-key row)
                 purchase-qty (get quantities qty-key 0)
                 max-qty      (get max-quantities qty-key 0)]]
       (build-row row purchase-qty max-qty game player-id))]))

;;;
;;; Credit Impact shows the before/after effect of the build order on credits
;;; using ui/impact-row with a deduction bar.
;;;
;;; - build-credits-row: single credits impact row
;;;

(defn- build-credits-row
  "Render the credits row in the Credit Impact section using ui/impact-row.

  [player player-map, cost int] -> hiccup"
  [player cost]
  (ui/impact-row "Credits" (:player/credits player) (- (:player/credits player) cost)
                 "build" "glow-build-credits"))

;;;;
;;;; Actions
;;;;

(defn- parse-purchase-quantities
  "Parse all purchase quantity inputs from request params.

  [params ring-params] -> {:soldiers int, :transports int, ...}"
  [params]
  (into {} (for [row purchase-rows]
             [(:qty-key row) (utils/parse-numeric-input (get params (:qty-key row)))])))

(defn apply-building
  "Commit purchases to the database, advance to action phase, and redirect.
  If purchases are no longer affordable against current player state (e.g. credits lost to an
                                                                           incoming attack between page load and submit), redirects back to the building page without writing.

  [ctx ring-ctx] -> ring-response (303 redirect to action or building)"
  [{:keys [path-params params biff/db session] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 3 player-id)]
      redirect
      (let [quantities      (parse-purchase-quantities params)
            cost-info       (calculate-purchase-cost quantities game)
            resources-after (calculate-resources-after-purchases player quantities cost-info)]
        (if-not (valid-purchase? resources-after)
          {:status  303
           :headers {"location" (str "/app/game/" player-id "/building")}
           :session (utils/flash session :warn
                                 "Submission rejected due to a change in your empire (enemy attack or espionage). Please review and resubmit.")}
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
                              :player/current-phase 4
                              :player/score        (utils/calculate-score
                                                     (merge player (utils/qualify-snapshot resources-after)))}])
            {:status 303
             :headers {"location" (str "/app/game/" player-id "/action")}}))))))

(defn calculate-building-oob
  "Provide HTMX out-of-band updates as user changes purchase inputs.
  Updates the max quantities, item costs, credit deduction bar, after-credits, and submit button.

  [ctx ring-ctx] -> hiccup (via biff/render)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [quantities      (parse-purchase-quantities params)
          cost-info       (calculate-purchase-cost quantities game)
          resources-after (calculate-resources-after-purchases player quantities cost-info)
          affordable?     (valid-purchase? resources-after)
          max-quantities  (calculate-max-quantities player quantities game)
          total           (:total-cost cost-info)
          credits-after   (:credits resources-after)
          after-cls       (str "font-bold " (if (neg? credits-after) "text-red-400" "text-green-400"))
          item-costs      (into {} (for [row purchase-rows]
                                     [(:qty-key row) (* (get quantities (:qty-key row))
                                                        (get game (:cost-key row)))]))
          ;; Projection values (same computation as projection-grid)
          required          (expenses/calculate-expenses player game)
          new-ore-planets   (+ (:player/ore-planets player) (:ore-planets quantities))
          new-erg-planets   (+ (:player/erg-planets player) (:erg-planets quantities))
          new-mil-planets   (+ (:player/mil-planets player) (:mil-planets quantities))
          new-total-planets (+ new-ore-planets new-erg-planets new-mil-planets)
          mil-soldiers      (* new-mil-planets (:game/mil-planet-soldiers game))
          mil-fighters      (* new-mil-planets (:game/mil-planet-fighters game))
          mil-stations      (* new-mil-planets (:game/mil-planet-stations game))
          proj-soldiers     (+ (:player/soldiers player) (:soldiers quantities) mil-soldiers)
          proj-fighters     (+ (:player/fighters player) (:fighters quantities) mil-fighters)
          proj-stations     (+ (:player/stations player) (:stations quantities) mil-stations)
          proj-agents       (+ (:player/agents player) (:agents quantities))
          credits-current   (- (:player/credits player) total)
          planets-cr        (- (* new-ore-planets (:game/ore-planet-credits game))
                               (* new-total-planets (:game/planet-upkeep-credits game)))
          military-cr       (- (+ (* proj-soldiers (:game/soldier-upkeep-credits game))
                                  (* proj-fighters (:game/fighter-upkeep-credits game))
                                  (* proj-stations (:game/station-upkeep-credits game))))
          tax-cr            (* (:player/population player) (:game/population-tax-credits game))
          credits-total     (+ credits-current planets-cr military-cr tax-cr)
          planets-food      (- (* new-erg-planets (:game/erg-planet-food game))
                               (* new-total-planets (:game/planet-upkeep-food game)))
          military-food     (- (+ (* proj-soldiers (:game/soldier-upkeep-food game))
                                  (* proj-agents (:game/agent-upkeep-food game))))
          food-total        (+ (:player/food player) planets-food military-food
                               (- (:population-food required)))
          planets-fuel      (* new-erg-planets (:game/erg-planet-fuel game))
          military-fuel     (- (+ (* proj-fighters (:game/fighter-upkeep-fuel game))
                                  (* proj-stations (:game/station-upkeep-fuel game))
                                  (* proj-agents (:game/agent-upkeep-fuel game))))
          fuel-total        (+ (:player/fuel player) planets-fuel military-fuel
                               (- (:population-fuel required)))
          oob-total  (fn [id t]
                       [:span.font-bold
                        {:id id :hx-swap-oob "true"
                         :class (str "text-[14px] " (if (neg? t) "text-amber-400" "text-green-400"))
                         :style {:text-shadow "0 0 10px rgba(61,220,132,0.25)"}}
                        (ui/format-number t)])]
      (biff/render
        [:div
         ;; Renew HTMX swap-target placeholder for the next request
         [:div#resources-after]
         ;; OOB: projection pills — dynamic rows
         (ui/proj-pill-oob "credits-pill-current"  "Current"  credits-current)
         (ui/proj-pill-oob "credits-pill-planets"  "Planets"  planets-cr)
         (ui/proj-pill-oob "credits-pill-military" "Military" military-cr)
         (ui/proj-pill-oob "food-pill-planets"     "Planets"  planets-food)
         (ui/proj-pill-oob "food-pill-military"    "Military" military-food)
         (ui/proj-pill-oob "fuel-pill-planets"     "Planets"  planets-fuel)
         (ui/proj-pill-oob "fuel-pill-military"    "Military" military-fuel)
         ;; OOB: projection card totals
         (oob-total "projection-credits-total" credits-total)
         (oob-total "projection-food-total"    food-total)
         (oob-total "projection-fuel-total"    fuel-total)
         ;; OOB: impact row — after-credits spans (mobile + desktop)
         [:span#after-build-credits-m {:hx-swap-oob "true" :class after-cls} (ui/format-number credits-after)]
         [:span#after-build-credits-d {:hx-swap-oob "true" :class after-cls} (ui/format-number credits-after)]
         ;; OOB: impact row — deduction bar (desktop)
         [:div#bar-build-credits {:hx-swap-oob "true"} (ui/svg-indicator-bar :loss (:player/credits player) total "glow-build-credits")]
         ;; OOB: impact row — change display (mobile + desktop)
         (let [delta      (- credits-after (:player/credits player))
               change-cls (str "tracking-[0.03em] "
                               (if (zero? delta) "text-game-green-muted" "text-green-400"))
               change-val (cond
                            (zero? delta) "0"
                            (pos? delta)  [:<> "+" (ui/format-number delta)]
                            :else         [:<> "−" (ui/format-number (Math/abs (long delta)))])]
           (list
             [:span#change-build-credits-m {:hx-swap-oob "true" :class change-cls} change-val]
             [:span#change-build-credits-d {:hx-swap-oob "true" :class change-cls} change-val]))
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

  [{:keys [player game flash]}] -> hiccup"
  [{:keys [player game flash]}]
  (let [player-id        (:xt/id player)
        zero-quantities  (into {} (map (fn [row] [(:qty-key row) 0]) purchase-rows))
        max-quantities   (calculate-max-quantities player zero-quantities game)]
    (ui/phase-shell 
      player 
      game 
      "Building Phase"
      (biff/form
        {:action (str "/app/game/" player-id "/apply-building") :method "post" :class  "m-0"}
        (ui/phase-body
          player
          (ui/flash-notice flash)
          (ui/snapshot-section player)
          (ui/section-label "Projected Resources" "‣ Next Turn + Building")
          (projection-grid player game zero-quantities)
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
