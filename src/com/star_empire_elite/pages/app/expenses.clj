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
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Calculations
;;;;

(defn calculate-required-expenses
  "Calculate required upkeep costs for all empire assets using game constants.

  [player game] -> {:planets-credits int, :planets-food int,
                    :soldiers-credits int, :soldiers-food int,
                    :fighters-credits int, :fighters-fuel int,
                    :stations-credits int, :stations-fuel int,
                    :agents-food int, :agents-fuel int,
                    :population-food int, :population-fuel int}"
  [player game]
  (let [planet-count (+ (:player/mil-planets player)
                        (:player/food-planets player)
                        (:player/ore-planets player))]
    {:planets-credits   (* planet-count (:game/planet-upkeep-credits game))
     :planets-food      (* planet-count (:game/planet-upkeep-food game))
     :soldiers-credits  (* (:player/soldiers player) (:game/soldier-upkeep-credits game))
     :soldiers-food     (* (:player/soldiers player) (:game/soldier-upkeep-food game))
     :fighters-credits  (* (:player/fighters player) 
                           (:game/fighter-upkeep-credits game))
     :fighters-fuel     (* (:player/fighters player) 
                           (:game/fighter-upkeep-fuel game))
     :stations-credits  (* (:player/stations player) 
                           (:game/station-upkeep-credits game))
     :stations-fuel     (* (:player/stations player) 
                           (:game/station-upkeep-fuel game))
     :agents-food       (* (:player/agents player) (:game/agent-upkeep-food game))
     :agents-fuel       (* (:player/agents player)
                           (:game/agent-upkeep-fuel game))
     :population-food   (* (:player/population player) (:game/population-upkeep-food game))
     :population-fuel   (* (:player/population player) (:game/population-upkeep-fuel game))}))

(defn calculate-resources-after-expenses
  "Calculate player resources after deducting expense payments.

  [player payments] -> {:credits int, :food int, :fuel int}"
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

(defn can-afford-expenses?
  "Returns true if all resource values after expenses are non-negative.

  [resources-after {:credits int, :food int, :fuel int}] -> boolean"
  [resources-after]
  (and (>= (:credits resources-after) 0)
       (>= (:food resources-after) 0)
       (>= (:fuel resources-after) 0)))

(defn parse-expense-payments
  "Parse all expense payment inputs from request params. Invalid or missing values default to 0.

  [params ring-params] -> {:planets-pay int, :planets-food int, :soldiers-credits int,
                           :soldiers-food int, :fighters-credits int, :fighters-fuel int,
                           :stations-credits int, :stations-fuel int, :agents-food int,
                           :agents-fuel int, :population-food int, :population-fuel int}"
  [params]
  {:planets-pay      (utils/parse-numeric-input (:planets-pay params))
   :planets-food     (utils/parse-numeric-input (:planets-food params))
   :soldiers-credits (utils/parse-numeric-input (:soldiers-credits params))
   :soldiers-food    (utils/parse-numeric-input (:soldiers-food params))
   :fighters-credits (utils/parse-numeric-input (:fighters-credits params))
   :fighters-fuel    (utils/parse-numeric-input (:fighters-fuel params))
   :stations-credits (utils/parse-numeric-input (:stations-credits params))
   :stations-fuel    (utils/parse-numeric-input (:stations-fuel params))
   :agents-food      (utils/parse-numeric-input (:agents-food params))
   :agents-fuel      (utils/parse-numeric-input (:agents-fuel params))
   :population-food  (utils/parse-numeric-input (:population-food params))
   :population-fuel  (utils/parse-numeric-input (:population-fuel params))})

;;;;
;;;; UI Components
;;;;

(defn submit-button
  "Render the submit button, disabled when the player cannot afford expenses.
  extra-attrs is merged into the button attributes — used to add hx-swap-oob in HTMX responses.

  ([affordable?] | [affordable? extra-attrs map]) -> hiccup"
  ([affordable?] (submit-button affordable? {}))
  ([affordable? extra-attrs]
   [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
    (merge {:type "submit"
            :disabled (not affordable?)
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           extra-attrs)
    "Continue to Building"]))

(defn expense-row
  "Render a responsive expense row: compact on mobile, full table row on desktop.
  credits-field, food-field, and fuel-field are either {:field-name str, :default-value int,
  :required int} or nil when that resource type is not applicable to this category.

  [category-name str, category-name-mobile str, row-id str, asset-count int|str,
   required-display str, credits-field map|nil, food-field map|nil, fuel-field map|nil,
   player-id uuid, hx-include str] -> hiccup"
  [category-name category-name-mobile row-id asset-count required-display credits-field food-field fuel-field player-id hx-include]
  [:div.border-b.border-green-400.last:border-b-0.grid.items-center.gap-1.px-2.py-2.text-xs.leading-tight.lg:gap-3.lg:px-4.lg:py-2.lg:text-base.expense-row-grid

   ;; Col 1: Category name (abbreviated on mobile, full on desktop)
   [:div.font-mono.lg:pr-4
    [:span.lg:hidden category-name-mobile]
    [:span.hidden.lg:inline category-name]]

   ;; Col 2: Asset count (with parentheses on mobile, formatted number)
   (let [count-display (if (string? asset-count) asset-count (ui/format-number asset-count))]
     [:div.text-right.font-mono.lg:pr-4
      [:span.lg:hidden "(" count-display ")"]
      [:span.hidden.lg:inline count-display]])

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

(defn build-expense-row
  "Build an expense-row from a spec map, deriving required values and input fields from it.
  Spec keys: :category str, :abbrev str, :row-id str, :count int|str,
             :credits {:field-name str, :required-key kw} or nil,
             :food   {:field-name str, :required-key kw} or nil,
             :fuel   {:field-name str, :required-key kw} or nil.

  [spec map, required map, player-id uuid, hx-include str] -> hiccup"
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
;;;; expenses-page shows costs and input fields. calculate-expenses handles HTMX dynamic updates
;;;; as the player changes values. apply-expenses commits payments and advances to building phase.
;;;;

(defn apply-expenses
  "Commit expense payments to the database and advance to the building phase.

  [ctx ring-ctx] -> ring-response (303 redirect to building)"
  [{:keys [path-params params biff/db] :as ctx}]
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

(defn calculate-expenses
  "Return HTMX out-of-band fragments updating the resources display, warning, submit button,
  and required-display cells in real time as the player adjusts expense inputs.

  [ctx ring-ctx] -> hiccup (HTMX oob fragments)"
  [{:keys [path-params params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Parse expense payment inputs using extracted function
    (let [payments (parse-expense-payments params)
          required (calculate-required-expenses player game)

          ;; Calculate resulting resources and whether affordable
          resources-after (calculate-resources-after-expenses player payments)
          affordable? (can-afford-expenses? resources-after)

          ;; Helper to render required display with red text if underpaid
          render-required
          (fn [row-id required-text credits-paid credits-req food-paid food-req fuel-paid fuel-req]
            (let [underpaid? (or (and credits-req (< credits-paid credits-req))
                                 (and food-req (< food-paid food-req))
                                 (and fuel-req (< fuel-paid fuel-req)))]
              [:div.font-mono.text-xxs.lg:text-base.lg:pr-4
               {:id (str "required-" row-id)
                :hx-swap-oob "true"
                :class (when underpaid? "text-red-400")}
               required-text]))

          ;; Helper to build render-required call from same spec format as build-expense-row
          build-render-required
          (fn [spec]
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
                   :agents   (:player/agents player))
            "Resources After Expenses" true)]

         ;; Warning message if expenses exceed available resources
         [:div#expense-warning.flex.items-center
          {:hx-swap-oob "true"}
          (when (not affordable?)
            [:p.text-yellow-400.font-bold "WARNING: Insufficient resources to pay expenses!"])]

         ;; Submit button - disabled if player can't afford expenses
         (submit-button affordable? {:hx-swap-oob "true"})

         ;; Update required displays with red highlighting if underpaid - using same specs as build-expense-row
         (build-render-required
           {:row-id "planets"
            :credits {:field-name "planets-pay" :required-key :planets-credits}
            :food {:field-name "planets-food" :required-key :planets-food}})
         (build-render-required
           {:row-id "soldiers"
            :credits {:field-name "soldiers-credits" :required-key :soldiers-credits}
            :food {:field-name "soldiers-food" :required-key :soldiers-food}})
         (build-render-required
           {:row-id "fighters"
            :credits {:field-name "fighters-credits" :required-key :fighters-credits}
            :fuel {:field-name "fighters-fuel" :required-key :fighters-fuel}})
         (build-render-required
           {:row-id "stations"
            :credits {:field-name "stations-credits" :required-key :stations-credits}
            :fuel {:field-name "stations-fuel" :required-key :stations-fuel}})
         (build-render-required
           {:row-id "agents"
            :food {:field-name "agents-food" :required-key :agents-food}
            :fuel {:field-name "agents-fuel" :required-key :agents-fuel}})
         (build-render-required
           {:row-id "population"
            :food {:field-name "population-food" :required-key :population-food}
            :fuel {:field-name "population-fuel" :required-key :population-fuel}})
         ]))))

(defn expenses-page
  "Show expense requirements and input fields for the player to choose payment amounts.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
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

       (ui/phase-header (:player/current-phase player) "EXPENSES"
                        (str "Turn " (:player/current-turn player) " | Round " (:player/current-round player)))

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
              {:category "Population" :abbrev "Pop" :row-id "population" :count (str (:player/population player) "M")
               :food {:field-name "population-food" :required-key :population-food}
               :fuel {:field-name "population-fuel" :required-key :population-fuel}}
              required player-id hx-include)]]]

         ;; Resources after expenses - initial copy, updated via HTMX
         [:div#resources-after
          (ui/resource-display-grid player "Resources After Expenses")]

         ;; Warning message area - populated by HTMX if player can't afford expenses
         [:div#expense-warning.flex.items-center]

         (ui/incoming-alert player)

         ;; Navigation and submit buttons
         [:div.flex.gap-4.mt-2
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id)} "Pause"]
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id "/exchange")} "Continue to Exchange"]
          ;; Initial button starts disabled - HTMX updates will enable it when affordable
          (submit-button false)])])))
