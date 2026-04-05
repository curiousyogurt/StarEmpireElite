;;;;;
;;;;; Expenses Phase - Empire Upkeep
;;;;;
;;;;; The expenses phase is the second phase of each round where players pay upkeep costs for their
;;;;; planets, military units, and population. Unlike the income phase, expenses require player
;;;;; decisions, and they can choose how much to pay for each category, with underpayment resulting 
;;;;; in various penalties.
;;;;;
;;;;; This webpage uses htmx for dynamic validation, showing players in real-time whether they can
;;;;; afford their selected expense amounts before submitting.
;;;;;

(ns com.star-empire-elite.pages.app.expenses
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

;;;;
;;;; Calculations
;;;;

;;; Calculate required upkeep costs for all empire assets using game constants
(defn calculate-required-expenses 
  "Calculate total required upkeep costs based on player's assets and game constants.
  Returns map with expense categories and their costs."
  [player game]
  (let [planet-count (+ (:player/mil-planets player)
                        (:player/food-planets player)
                        (:player/ore-planets player))]
    {:planets-credits   (* planet-count (:game/planet-upkeep-credits game))
     :planets-food      planet-count
     :soldiers-credits  (Math/round (/ (* (:player/soldiers player) 
                                          (:game/soldier-upkeep-credits game)) 
                                       1000.0))
     :soldiers-food     (Math/round (/ (* (:player/soldiers player) 
                                          (:game/soldier-upkeep-food game)) 
                                       1000.0))
     :fighters-credits  (* (:player/fighters player) 
                           (:game/fighter-upkeep-credits game))
     :fighters-fuel     (* (:player/fighters player) 
                           (:game/fighter-upkeep-fuel game))
     :stations-credits  (* (:player/stations player) 
                           (:game/station-upkeep-credits game))
     :stations-fuel     (* (:player/stations player) 
                           (:game/station-upkeep-fuel game))
     :agents-food       (Math/round (/ (* (:player/agents player)
                                          (:game/agent-upkeep-food game))
                                       1000.0))
     :agents-fuel       (* (:player/agents player)
                           (:game/agent-upkeep-fuel game))
     :population-food   (Math/round (/ (* (:player/population player)
                                          (:game/population-upkeep-food game))
                                       1000.0))
     :population-fuel   (Math/round (/ (* (:player/population player)
                           (:game/population-upkeep-fuel game))
                                       10000.0))}))

;;; Calculate resources after paying specified expenses
(defn calculate-resources-after-expenses 
  "Calculate player resources after deducting expense payments.
  Returns map of {:credits :food :fuel} after expenses."
  [player payments]
  {:credits (- (:player/credits player)
               (:planets-pay payments)
               (:soldiers-credits payments)
               (:fighters-credits payments)
               (:stations-credits payments))
   :food    (- (:player/food player)
               (:planets-food payments)
               (:soldiers-food payments)
               (:agents-food payments)
               (:population-food payments))
   :fuel    (- (:player/fuel player)
               (:fighters-fuel payments)
               (:stations-fuel payments)
               (:agents-fuel payments)
               (:population-fuel payments))})

;;; Check if player can afford the expenses
(defn can-afford-expenses? 
  "Returns true if all resource values are non-negative"
  [resources-after]
  (and (>= (:credits resources-after) 0)
       (>= (:food resources-after) 0)
       (>= (:fuel resources-after) 0)))

;;; Parse expense payment inputs from request parameters
(defn parse-expense-payments
  "Parse all expense payment inputs from request params.
   Returns map of payments for all expense categories."
  [params]
  {:planets-pay         (utils/parse-numeric-input (:planets-pay params))
   :planets-food        (utils/parse-numeric-input (:planets-food params))
   :soldiers-credits    (utils/parse-numeric-input (:soldiers-credits params))
   :soldiers-food       (utils/parse-numeric-input (:soldiers-food params))
   :fighters-credits    (utils/parse-numeric-input (:fighters-credits params))
   :fighters-fuel       (utils/parse-numeric-input (:fighters-fuel params))
   :stations-credits    (utils/parse-numeric-input (:stations-credits params))
   :stations-fuel       (utils/parse-numeric-input (:stations-fuel params))
   :agents-food         (utils/parse-numeric-input (:agents-food params))
   :agents-fuel         (utils/parse-numeric-input (:agents-fuel params))
   :population-food     (utils/parse-numeric-input (:population-food params))
   :population-fuel     (utils/parse-numeric-input (:population-fuel params))})

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
    "Continue to Building"]))

;;; Expense row with responsive layout - compact on mobile, table on desktop
(defn expense-row
  "Renders an expense row that adapts from mobile card to desktop table row.
   
   Parameters:
   - category-name: Display name (e.g. 'Planets', 'Soldiers')
   - category-name-mobile: Abbreviated name for mobile (e.g. 'Plnts', 'Sold')
   - row-id: Unique identifier for this row (e.g. 'planets', 'soldiers')
   - asset-count: Asset count value (numeric, will be formatted)
   - required-display: Total required display (e.g. '50 crd, 5 food')
   - credits-field: Map with :field-name, :default-value, and :required, or nil if not used
   - food-field: Map with :field-name, :default-value, and :required, or nil if not used
   - fuel-field: Map with :field-name, :default-value, and :required, or nil if not used
   - player-id: Player UUID
   - hx-include: HTMX include selector string"
  [category-name category-name-mobile row-id asset-count required-display credits-field food-field fuel-field player-id hx-include]
  [:div.border-b.border-green-400.last:border-b-0.grid.items-center.gap-1.px-2.py-2.text-xs.leading-tight.lg:gap-3.lg:px-4.lg:py-2.lg:text-base.expense-row-grid
   
   ;; Col 1: Category name (abbreviated on mobile, full on desktop)
   [:div.font-mono.lg:pr-4
    [:span.lg:hidden category-name-mobile]
    [:span.hidden.lg:inline category-name]]
   
   ;; Col 2: Asset count (with parentheses on mobile, formatted number)
   [:div.text-right.font-mono.lg:pr-4
    [:span.lg:hidden "(" (ui/format-number asset-count) ")"]
    [:span.hidden.lg:inline (ui/format-number asset-count)]]
   
   ;; Col 3: Total required (with ID for HTMX swapping, will turn red if underpaid)
   [:div.font-mono.text-xxs.lg:text-base.lg:pr-4
    {:id (str "required-" row-id)}
    required-display]
   
   ;; Col 4: Pay Credits
   [:div.px-1.lg:pr-4
    (if credits-field
      (ui/numeric-input (:field-name credits-field) (:default-value credits-field) 
                        player-id "/calculate-expenses" hx-include
                        {:input-class "py-0.5 text-xs lg:py-1 lg:text-sm"})
      [:div.font-mono.opacity-30 "0"])]
   
   ;; Col 5: Pay Food
   [:div.px-1.lg:pr-4
    (if food-field
      (ui/numeric-input (:field-name food-field) (:default-value food-field) 
                        player-id "/calculate-expenses" hx-include
                        {:input-class "py-0.5 text-xs lg:py-1 lg:text-sm"})
      [:div.font-mono.opacity-30 "0"])]
   
   ;; Col 6: Pay Fuel
   [:div.px-1.lg:pr-4
    (if fuel-field
      (ui/numeric-input (:field-name fuel-field) (:default-value fuel-field) 
                        player-id "/calculate-expenses" hx-include
                        {:input-class "py-0.5 text-xs lg:py-1 lg:text-sm"})
      [:div.font-mono.opacity-30 "0"])]])

;;; Helper function to build expense row from simplified specification
(defn build-expense-row
  "Builds an expense-row call from a simplified specification map.
   
   Spec map keys:
   - :category - Full category name (e.g. 'Planets')
   - :abbrev - Abbreviated name (e.g. 'Plnts')
   - :row-id - Unique row identifier (e.g. 'planets')
   - :count - Asset count value (numeric, e.g. planet-count or (:player/soldiers player))
   - :credits - Map with :field-name and :required-key, or nil
   - :food - Map with :field-name and :required-key, or nil
   - :fuel - Map with :field-name and :required-key, or nil
   
   Example:
   {:category 'Planets' :abbrev 'Plnts' :row-id 'planets' :count planet-count
    :credits {:field-name 'planets-pay' :required-key :planets-credits}
    :food {:field-name 'planets-food' :required-key :planets-food}}"
  [spec required player-id hx-include]
  (let [{:keys [category abbrev row-id count credits food fuel]} spec
        ;; Build required display as "credits/food/fuel" format
        credits-val (if credits (get required (:required-key credits)) 0)
        food-val (if food (get required (:required-key food)) 0)
        fuel-val (if fuel (get required (:required-key fuel)) 0)
        required-display (str credits-val "/" food-val "/" fuel-val)
        
        ;; Build field maps
        credits-field (when credits
                        {:field-name (:field-name credits)
                         :default-value (get required (:required-key credits))
                         :required (get required (:required-key credits))})
        food-field (when food
                     {:field-name (:field-name food)
                      :default-value (get required (:required-key food))
                      :required (get required (:required-key food))})
        fuel-field (when fuel
                     {:field-name (:field-name fuel)
                      :default-value (get required (:required-key fuel))
                      :required (get required (:required-key fuel))})]
    
    (expense-row category abbrev row-id count required-display
                 credits-field food-field fuel-field player-id hx-include)))

;;;;
;;;; Actions
;;;;
;;;; There are three parts to the actions for this phase: (i) expenses-page, which shows the
;;;; costs and input fields, (ii) calculate-expenses which provides htmx dynamic updates as the user 
;;;; changes values, and (iii) apply-expenses which commits the payments to the database and advances 
;;;; to the next phase.
;;;;

;;; Applies expense payments and advances to the next phase. Uses calculations to determine resource 
;;; changes, then commits them in a single transaction.
(defn apply-expenses [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Phase validation prevents players from applying expenses multiple times or out of order
    (if-let [redirect (utils/validate-phase player 2 player-id)]
      redirect
      ;; Parse all expense payment inputs using extracted function
      (let [payments (parse-expense-payments params)

            ;; Calculate the final resource values
            resources-after (calculate-resources-after-expenses player payments)]

        ;; Single atomic transaction updates resources and advances phase together
        ;; This prevents partial updates if something fails midway through
        (biff/submit-tx ctx
                        [{:db/doc-type :player
                          :db/op :update
                          :xt/id player-id
                          :player/credits (:credits resources-after)
                          :player/food (:food resources-after)
                          :player/fuel (:fuel resources-after)
                          :player/current-phase 3}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/building")}}))))

;;; Provides htmx dynamic updates showing resources after expenses as user changes input values.
;;; This gives immediate feedback on whether the player can afford their selected expenses.
(defn calculate-expenses [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Parse expense payment inputs using extracted function
    (let [payments (parse-expense-payments params)
          required (calculate-required-expenses player game)

          ;; Calculate resulting resources and whether affordable
          resources-after (calculate-resources-after-expenses player payments)
          affordable? (can-afford-expenses? resources-after)
          
          ;; Helper to render required display with red text if underpaid
          render-required (fn [row-id required-text credits-paid credits-req food-paid food-req fuel-paid fuel-req]
                            (let [underpaid? (or (and credits-req (< credits-paid credits-req))
                                                 (and food-req (< food-paid food-req))
                                                 (and fuel-req (< fuel-paid fuel-req)))]
                              [:div.font-mono.text-xxs.lg:text-base.lg:pr-4
                               {:id (str "required-" row-id)
                                :hx-swap-oob "true"
                                :class (when underpaid? "text-red-400")}
                               required-text]))
          
          ;; Helper to build render-required call from same spec format as build-expense-row
          build-render-required (fn [spec]
                                  (let [{:keys [row-id credits food fuel]} spec
                                        ;; Build required display as "credits/food/fuel" format
                                        credits-val (if credits (get required (:required-key credits)) 0)
                                        food-val (if food (get required (:required-key food)) 0)
                                        fuel-val (if fuel (get required (:required-key fuel)) 0)
                                        required-text (str credits-val "/" food-val "/" fuel-val)
                                        
                                        ;; Get payment and required values for each resource type
                                        credits-paid (when credits (get payments (keyword (:field-name credits))))
                                        credits-req (when credits (get required (:required-key credits)))
                                        food-paid (when food (get payments (keyword (:field-name food))))
                                        food-req (when food (get required (:required-key food)))
                                        fuel-paid (when fuel (get payments (keyword (:field-name fuel))))
                                        fuel-req (when fuel (get required (:required-key fuel)))]
                                    
                                    (render-required row-id required-text 
                                                     credits-paid credits-req 
                                                     food-paid food-req 
                                                     fuel-paid fuel-req)))]

      ;; Render htmx response fragments that replace specific page elements
      (biff/render
        [:div
         ;; Resources display with red highlighting for negative values; use shared component
         [:div#resources-after
          (ui/resource-display-grid 
            (assoc resources-after 
                   :galaxars (:player/galaxars player)
                   :soldiers (:player/soldiers player)
                   :fighters (:player/fighters player)
                   :stations (:player/stations player)
                   :agents (:player/agents player))
            "Resources After Expenses"
            true)]  ; Enable negative highlighting

         ;; Warning message if expenses exceed available resources
         [:div#expense-warning.h-8.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.font-bold "WARNING: Insufficient resources to pay expenses!"])]

         ;; Submit button - disabled if player can't afford expenses
         (submit-button affordable? {:hx-swap-oob "true"})
         
         ;; Update required displays with red highlighting if underpaid - using same specs as build-expense-row
         (build-render-required {:row-id "planets"
                                 :credits {:field-name "planets-pay" :required-key :planets-credits}
                                 :food {:field-name "planets-food" :required-key :planets-food}})
         (build-render-required {:row-id "soldiers"
                                 :credits {:field-name "soldiers-credits" :required-key :soldiers-credits}
                                 :food {:field-name "soldiers-food" :required-key :soldiers-food}})
         (build-render-required {:row-id "fighters"
                                 :credits {:field-name "fighters-credits" :required-key :fighters-credits}
                                 :fuel {:field-name "fighters-fuel" :required-key :fighters-fuel}})
         (build-render-required {:row-id "stations"
                                 :credits {:field-name "stations-credits" :required-key :stations-credits}
                                 :fuel {:field-name "stations-fuel" :required-key :stations-fuel}})
         (build-render-required {:row-id "agents"
                                 :food {:field-name "agents-food" :required-key :agents-food}
                                 :fuel {:field-name "agents-fuel" :required-key :agents-fuel}})
         (build-render-required {:row-id "population"
                                 :food {:field-name "population-food" :required-key :population-food}
                                 :fuel {:field-name "population-fuel" :required-key :population-fuel}})
         ]))))

;;; Shows expense requirements and input fields for player to choose how much to pay
(defn expenses-page [{:keys [player game]}]
  (let [required (calculate-required-expenses player game)
        player-id (:xt/id player)
        hx-include "[name='planets-pay'],[name='planets-food'],[name='soldiers-credits'],[name='soldiers-food'],[name='fighters-credits'],[name='fighters-fuel'],[name='stations-credits'],[name='stations-fuel'],[name='agents-food'],[name='agents-fuel'],[name='population-food'],[name='population-fuel']"
        planet-count (+ (:player/mil-planets player)
                        (:player/food-planets player)
                        (:player/ore-planets player))]
    (ui/page
      {}
      [:div.mx-auto.max-w-4xl.w-full.text-green-400.font-mono
       ;; CSS for responsive grid columns
       [:style "
         .expense-row-grid {
           grid-template-columns: 0.7fr 0.4fr 1.3fr 1fr 1fr 1fr;
         }
         @media (min-width: 1024px) {
           .expense-row-grid {
             grid-template-columns: 1fr 1fr 1fr 1fr 1fr 1fr;
           }
         }
         .text-xxs {
           font-size: 0.65rem;
           line-height: 0.9rem;
         }
       "]
       
       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "EXPENSES")

       ;; Current resources before expenses
       (ui/resource-display-grid player "Resources Before Expenses")

       (biff/form
         {:action (str "/app/game/" player-id "/apply-expenses")
          :method "post"}

         [:h3.font-bold.mb-4 "Expenses This Round"]

         ;; Table container with header and rows
         [:div.w-full.mb-8
          [:div.border.border-green-400.overflow-x-auto
           [:div
            
            ;; Mobile header - abbreviated
            [:div.grid.gap-1.px-2.py-2.bg-green-400.bg-opacity-10.font-bold.border-b.border-green-400.text-xs.lg:hidden
             {:style {:grid-template-columns "0.7fr 0.4fr 1.3fr 1fr 1fr 1fr"}}
             [:div "Item"]
             [:div.text-right ""]  ; Empty for count (will show in parentheses)
             [:div "C/F/F"]
             [:div.text-center "Crd"]
             [:div.text-center "Food"]
             [:div.text-center "Fuel"]]
            
            ;; Desktop header - full text
            (ui/phase-table-header
              [{:label "Item" :class "pr-4"}
               {:label "Count" :class "text-right pr-4"}
               {:label "Cred/Food/Fuel" :class "pr-4"}
               {:label "Pay Credits" :class "pr-4"}
               {:label "Pay Food" :class "pr-4"}
               {:label "Pay Fuel" :class "pr-4"}])

            ;; All expense rows using build-expense-row helper for cleaner code
            (build-expense-row 
              {:category "Planets" :abbrev "Plnts" :row-id "planets" :count planet-count
               :credits {:field-name "planets-pay" :required-key :planets-credits}
               :food {:field-name "planets-food" :required-key :planets-food}}
              required player-id hx-include)
            
            (build-expense-row
              {:category "Soldiers" :abbrev "Sold" :row-id "soldiers" :count (:player/soldiers player)
               :credits {:field-name "soldiers-credits" :required-key :soldiers-credits}
               :food {:field-name "soldiers-food" :required-key :soldiers-food}}
              required player-id hx-include)
            
            (build-expense-row
              {:category "Fighters" :abbrev "Fght" :row-id "fighters" :count (:player/fighters player)
               :credits {:field-name "fighters-credits" :required-key :fighters-credits}
               :fuel {:field-name "fighters-fuel" :required-key :fighters-fuel}}
              required player-id hx-include)
            
            (build-expense-row
              {:category "Defence Stns" :abbrev "Def" :row-id "stations" :count (:player/stations player)
               :credits {:field-name "stations-credits" :required-key :stations-credits}
               :fuel {:field-name "stations-fuel" :required-key :stations-fuel}}
              required player-id hx-include)
            
            (build-expense-row
              {:category "Agents" :abbrev "Agnt" :row-id "agents" :count (:player/agents player)
               :food {:field-name "agents-food" :required-key :agents-food}
               :fuel {:field-name "agents-fuel" :required-key :agents-fuel}}
              required player-id hx-include)

            (build-expense-row
              {:category "Population" :abbrev "Pop" :row-id "population" :count (:player/population player)
               :food {:field-name "population-food" :required-key :population-food}
               :fuel {:field-name "population-fuel" :required-key :population-fuel}}
              required player-id hx-include)]]]

         ;; Resources after expenses - initial copy, updated via HTMX
         [:div#resources-after
          (ui/resource-display-grid player "Resources After Expenses")]

         ;; Warning message area - populated by HTMX if player can't afford expenses
         [:div#expense-warning.h-8.flex.items-center]

         ;; Navigation and submit buttons
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id)} "Back to Game"]
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id "/exchange")} "Continue to Exchange"]
          ;; Initial button starts disabled - HTMX updates will enable it when affordable
          (submit-button false)])])))
