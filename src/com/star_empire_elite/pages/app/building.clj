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
   :erg-planets  (utils/parse-numeric-input (:erg-planets params))
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
   :food         (:player/food player)
   :fuel         (:player/fuel player)
   :population   (:player/population player)
   :stability    (:player/stability player)
   :galaxars     (:player/galaxars player)
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
   :erg-planets  (+ (:player/erg-planets player)  (:erg-planets quantities))
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
;;;; SVG Deduction Bar
;;;;

(def ^:private nice-scale-multipliers [1 2.5 5 7.5])

(def ^:private nice-scales
  "Nice scale ceilings for resource bars."
  (vec (for [exp (range 1 35) m nice-scale-multipliers]
         (* m (Math/pow 10 exp)))))

(defn- choose-scale-max [before after]
  (let [needed (max 1 (double before) (double after))]
    (or (first (drop-while #(< % needed) nice-scales)) needed)))

(defn- fmt-tick [v]
  (let [abs-v (Math/abs (double v))
        sign  (if (neg? (double v)) "-" "")]
    (cond
      (< abs-v 1000)    (format "%.0f" (double v))
      (< abs-v 1000000) (str sign (str/replace (format "%.1f" (/ abs-v 1000.0)) #"\.0$" "") "K")
      :else             (ui/format-number-str v))))

(defn- deduction-bar
  "Render an SVG indicator bar with a left-pointing arrow showing a resource deduction.

  [before int, payment int, filter-id str] -> hiccup"
  [before payment filter-id]
  (let [after      (max 0.0 (- (double before) (double payment)))
        scale-max  (choose-scale-max before after)
        x-before   (min (* (/ (double before) scale-max) 100.0) 100.0)
        x-after    (min (* (/ after            scale-max) 100.0) 100.0)
        has-arrow? (pos? payment)
        ly         5
        arrow-w    3.5
        arrow-h    2.5]
    [:div.flex.flex-col.justify-center.h-full.px-8
     [:svg.block.w-full.overflow-visible.mt-1.mb-1
      {:viewBox "0 0 100 10" :preserveAspectRatio "none" :style {:height "8px"}}
      [:defs
       [:filter {:id filter-id :x "-50%" :y "-50%" :width "200%" :height "200%"}
        [:feGaussianBlur {:stdDeviation "0.8" :result "blur"}]
        [:feMerge [:feMergeNode {:in "blur"}] [:feMergeNode {:in "SourceGraphic"}]]]]
      [:line {:x1 0 :y1 ly :x2 100 :y2 ly :stroke "#152a1e" :stroke-width "0.8"}]
      (when has-arrow?
        (let [tip-x  x-after
              base-x (+ x-after (* 2 arrow-w))]
          (list
            [:line {:x1 x-before :y1 ly
                    :x2 (+ base-x (* arrow-w 0.55)) :y2 ly
                    :stroke "#4ade80" :stroke-width "1.2"
                    :filter (str "url(#" filter-id ")")}]
            [:polygon {:points (str tip-x  "," ly " "
                                    base-x "," (- ly arrow-h) " "
                                    base-x "," (+ ly arrow-h))
                       :fill "#4ade80" :filter (str "url(#" filter-id ")")}])))
      [:line {:x1 0   :y1 (+ ly 1) :x2 0   :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 25  :y1 (+ ly 1) :x2 25  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 50  :y1 (+ ly 1) :x2 50  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 75  :y1 (+ ly 1) :x2 75  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 100 :y1 (+ ly 1) :x2 100 :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]]
     [:div.text-xs.text-gray-400.relative.mb-2
      {:style {:height "1em"}}
      [:span.absolute.left-0 "0"]
      [:span.absolute {:style {:left "50%" :transform "translateX(-50%)"}} (fmt-tick (* 0.5 scale-max))]
      [:span.absolute.right-0 (fmt-tick scale-max)]]]))

;;;;
;;;; UI Components
;;;;

(defn- snapshot-section
  "Render the empire state snapshot panel showing all resources in a compact 2-row grid.

  [player player-map] -> hiccup"
  [player]
  (let [row1 [["CREDITS"    (:player/credits player)     nil]
               ["FOOD"       (:player/food player)        nil]
               ["FUEL"       (:player/fuel player)        nil]
               ["GALAXARS"   (:player/galaxars player)    nil]
               ["POPULATION" (:player/population player)  #(str (str/replace (format "%.1f" (double %)) #"\.0$" "") "M")]
               ["STABILITY"  (:player/stability player)   #(str % "%")]
               ["ORE PLTS"   (:player/ore-planets player) nil]
               ["ERG PLTS"   (:player/erg-planets player) nil]
               ["MIL PLTS"   (:player/mil-planets player) nil]]
        row2 [["SOLDIERS"   (:player/soldiers player)   nil]
               ["TRANSPORTS" (:player/transports player) nil]
               ["GENERALS"   (:player/generals player)   nil]
               ["FIGHTERS"   (:player/fighters player)   nil]
               ["CARRIERS"   (:player/carriers player)   nil]
               ["ADMIRALS"   (:player/admirals player)   nil]
               ["STATIONS"   (:player/stations player)   nil]
               ["CMD SHIPS"  (:player/cmd-ships player)  nil]
               ["AGENTS"     (:player/agents player)     nil]]
        render-row
        (fn [items]
          [:div {:style {:display "grid" :grid-template-columns "repeat(9, 1fr)" :gap "4px"}}
           (for [[label v display-fn] items]
             [:div {:key label}
              [:div {:style {:color "#4a6a58" :letter-spacing "0.04em" :font-size "9px"
                             :text-transform "uppercase" :overflow "hidden"
                             :text-overflow "ellipsis" :white-space "nowrap"}}
               label]
              [:div.font-bold {:style {:color "#9adaaa" :font-size "13px"}}
               (if display-fn (display-fn v) (ui/format-number v))]])])]
    [:div
     {:style {:background "#0a120d" :border "1px solid #1e3a2a"
              :border-radius "3px" :padding "7px 10px" :overflow-x "auto"}}
     [:div.flex.justify-between.items-center.mb-2
      [:span.text-xs.uppercase
       {:style {:letter-spacing "0.15em" :color "#4ade80"}}
       "Snapshot"]
      [:span.text-xs
       {:style {:color "#7ab88a" :letter-spacing "0.1em"}}
       "STATS @ T-0"]]
     [:div.flex.flex-col {:style {:gap "6px" :min-width "500px"}}
      (render-row row1)
      (render-row row2)]]))

(defn- projection-pill
  "Render one projection pill card with a title, right-aligned total, and income/expense rows.
  Pill style matches expense-summary-card: same span classes, colors, and font sizes.

  [title str, total number, rows [{:keys [label value suffix]}]] -> hiccup"
  [title total rows & [{:keys [total-id]}]]
  [:div.flex.flex-col.gap-1
   {:style {:border "1px solid #253530" :border-radius "3px"
            :padding "6px 8px" :background "#1e1e1e"}}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 title]
    [:span.text-xs
     (cond-> {:style {:color "#7ab88a"}}
       total-id (assoc :id total-id))
     (ui/format-number total)]]
   [:div {:class "flex flex-col gap-0.5"}
    (for [{:keys [label value suffix id]} rows]
      [:span.text-xs.inline-block.rounded-sm.text-green-400
       (cond-> {:style {:padding "1px 5px" :background "#1a3a28"}}
         id (assoc :id id))
       label " "
       (if (neg? value) "-" "+")
       (ui/format-number (Math/abs (long value)))
       " "
       suffix])]])

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
     [:div.text-xs.uppercase.mb-1
      {:style {:letter-spacing "0.12em" :color "#7ab88a"}}
      "Resource Projections for Next Turn"]
     [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-1.5"}
      (projection-pill "Credits" credits-total credits-rows {:total-id "projection-credits-total"})
      (projection-pill "Food"    food-total    food-rows {:total-id "projection-food-total"})
      (projection-pill "Fuel"    fuel-total    fuel-rows {:total-id "projection-fuel-total"})]]))

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
      [:span {:id max-qty-id :data-value (str (if (neg? max-qty) 0 max-qty))}
       (ui/format-number (if (neg? max-qty) 0 max-qty))]]
     ;; Build input + max button
     [:div.flex.items-center.gap-1.justify-self-center
      (ui/numeric-input (name (:qty-key spec)) purchase-qty player-id
                        "/calculate-building" building-hx-include
                        {:input-class "text-xs lg:text-sm text-right"
                         :input-style {:color "#7ab88a" :border-color "#2d6644"
                                       :padding-top "1px" :padding-bottom "1px"}})
      [:button.text-xs
       {:type "button"
        :style {:border "1px solid #2d6644" :background "transparent" :color "#7ab88a"
                :padding "1px 4px" :border-radius "2px" :cursor "pointer"
                :font-family "inherit" :flex-shrink "0"}
        :onclick (str "var s=document.getElementById('" max-qty-id "');"
                      "var i=document.querySelector('[name=\"" (name (:qty-key spec)) "\"]');"
                      "if(s&&i){i.value=s.dataset.value||'0';"
                      "i.dispatchEvent(new Event('input',{bubbles:true}));}")}
       "max"]]
     ;; Line cost
     [:div.text-base.text-right
      {:style {:color (if (pos? item-cost) "#4ade80" "#3a5040")}}
      [:span {:id cost-id}
       (if (pos? item-cost) (ui/format-number item-cost) "—")]]]))

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

(defn- build-credits-summary-header
  "Render the column-label row for the credit impact table.
  Emits mobile (4-col) and desktop (5-col with bar) variants.

  [] -> hiccup"
  []
  (let [header-style {:background "#151f1a" :border-bottom "1px solid #253530"}
        header-class "gap-4 py-1 px-2 items-center"
        col-style    {:letter-spacing "0.08em" :color "#4ade80"}
        item-style   (assoc col-style :letter-spacing "0.1em")
        label        (fn [text style extra-class]
                       [:span.text-xs.uppercase {:class extra-class :style style} text])
        r            (fn [text] (label text col-style "text-right justify-self-end"))
        c            (fn [text] (label text col-style "text-center justify-self-center"))]
    [:<>
     [:div.expense-row-mobile {:class (str "md:hidden " header-class) :style header-style}
      (label "Item" item-style nil) (r "Before") (c "Change") (r "After")]
     [:div.expense-row-desktop {:class (str "hidden md:grid " header-class) :style header-style}
      (label "Item" item-style nil) [:span] (r "Before") (c "Change") (r "After")]]))

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

(defn submit-button
  "Renders the submit button with terminal-shell styling.
  Disabled state shows muted appearance when the player cannot afford purchases.

  ([affordable? bool]) ([affordable? bool, extra-attrs map]) -> hiccup"
  ([affordable?] (submit-button affordable? {}))
  ([affordable? extra-attrs]
   [:button#submit-button.text-sm.tracking-wider.rounded-sm
    (merge {:type "submit"
            :disabled (not affordable?)
            :style (merge {:padding "5px 14px" :font-family "'Courier New', monospace"
                           :letter-spacing "0.05em" :border-radius "2px"}
                          (if affordable?
                            {:border "1px solid #4ade80" :background "#1a3a28" :color "#4ade80"}
                            {:border "1px solid #253530" :background "transparent"
                             :color "#7ab88a" :opacity "0.5" :cursor "not-allowed"}))}
           extra-attrs)
    "Continue to Action"]))

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
         [:span#credits-pill-current.text-xs.inline-block.rounded-sm.text-green-400
          {:hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
          "Current "
          (if (neg? credits-after) "-" "+")
          (ui/format-number (Math/abs (long credits-after)))
          " cr"]
         ;; OOB: credits pill "Planets" row — updates as ore/erg/mil planet purchases change
         [:span#credits-pill-planets.text-xs.inline-block.rounded-sm.text-green-400
          {:hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
          "Planets "
          (if (neg? new-planets-value) "-" "+")
          (ui/format-number (Math/abs (long new-planets-value)))
          " cr"]
         ;; OOB: credits pill "Military" row — updates as soldier/fighter/station purchases change
         [:span#credits-pill-military.text-xs.inline-block.rounded-sm.text-green-400
          {:hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
          "Military "
          (if (neg? new-military-value) "-" "+")
          (ui/format-number (Math/abs (long new-military-value)))
          " cr"]
         ;; OOB: credits pill total — sum of all rows (current + planets + military + taxes)
         [:span#projection-credits-total {:hx-swap-oob "true" :style {:color "#7ab88a"}}
          (ui/format-number proj-total)]
         ;; OOB: food pill "Planets" row — updates as erg/ore/mil planet purchases change
         [:span#food-pill-planets.text-xs.inline-block.rounded-sm.text-green-400
          {:hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
          "Planets "
          (if (neg? new-planets-food) "-" "+")
          (ui/format-number (Math/abs (long new-planets-food)))
          " food"]
         ;; OOB: food pill "Military" row — updates as soldier/agent purchases change
         [:span#food-pill-military.text-xs.inline-block.rounded-sm.text-green-400
          {:hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
          "Military "
          (if (neg? new-military-food) "-" "+")
          (ui/format-number (Math/abs (long new-military-food)))
          " food"]
         ;; OOB: food pill total
         [:span#projection-food-total {:hx-swap-oob "true" :style {:color "#7ab88a"}}
          (ui/format-number food-proj-total)]
         ;; OOB: fuel pill "Planets" row — updates as erg planet purchases change
         [:span#fuel-pill-planets.text-xs.inline-block.rounded-sm.text-green-400
          {:hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
          "Planets "
          (if (neg? new-erg-fuel-income) "-" "+")
          (ui/format-number (Math/abs (long new-erg-fuel-income)))
          " fuel"]
         ;; OOB: fuel pill "Military" row — updates as fighter/station/agent purchases change
         [:span#fuel-pill-military.text-xs.inline-block.rounded-sm.text-green-400
          {:hx-swap-oob "true" :style {:padding "1px 5px" :background "#1a3a28"}}
          "Military "
          (if (neg? new-military-fuel) "-" "+")
          (ui/format-number (Math/abs (long new-military-fuel)))
          " fuel"]
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
         (submit-button affordable? {:hx-swap-oob "true"})]))))

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
       [:div.absolute.inset-0.pointer-events-none.z-10
        {:style {:background "repeating-linear-gradient(to bottom, transparent 0px, transparent 2px, rgba(0,0,0,0.07) 2px, rgba(0,0,0,0.07) 3px)"}}]

       ;; Topbar
       [:div.flex.items-center.justify-between
        {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "7px 14px"}}
        [:div
         [:div.text-3xl.font-bold.text-green-400
          {:style {:letter-spacing "0.05em"}}
          (:player/empire-name player)]
         [:div.text-sm.mt-px
          {:style {:color "#9adaaa"}}
          (str "BUILDING PHASE · Turn " (:player/current-turn player)
               " · Round " (:player/current-round player))]]
        (ui/phase-stepper (:player/current-phase player))]

       ;; Form wraps body + action bar so the submit button works
       (biff/form
         {:action (str "/app/game/" player-id "/apply-building") :method "post"
          :style  {:margin 0}}

         ;; Body
         [:div.flex.flex-col.gap-2
          {:style {:padding "10px 14px"}}

          ;; 1. Snapshot — full empire state at T-0
          (snapshot-section player)

          ;; 2. Projections — income/expense flows for Credits, Food, Fuel
          (projections-section player game)

          ;; 3. Build orders table
          [:div
           [:div.text-xs.uppercase.mb-1
            {:style {:letter-spacing "0.12em" :color "#7ab88a"}}
            "Build Orders"]
           (build-table player game zero-quantities max-quantities)]

          ;; 4. Credit impact — deduction bar showing before/change/after for credits
          [:div
           [:div.text-xs.uppercase.mb-1
            {:style {:letter-spacing "0.12em" :color "#7ab88a"}}
            "Credit Impact"]
           [:div.overflow-hidden
            {:style {:border "1px solid #253530" :border-radius "3px" :background "#161616"}}
            (build-credits-summary-header)
            (build-credits-row player 0)]]

          ;; Hidden HTMX swap-target placeholder
          [:div#resources-after {:style {:display "none"}}]

          ;; Warning message area — populated by HTMX if player can't afford purchases
          [:div#building-warning.flex.items-center]

          (ui/incoming-alert player)]

         ;; Action bar
         [:div.flex.gap-2
          {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
          [:a.text-sm.no-underline
           {:href  (str "/app/game/" player-id)
            :style {:padding "5px 14px" :border "1px solid #1e6e44" :background "transparent"
                    :color "#9adaaa" :border-radius "2px" :letter-spacing "0.05em"
                    :font-family "'Courier New', monospace"}}
           "Pause"]
          (submit-button true)])])))
