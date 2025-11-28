(ns com.star-empire-elite.pages.app.exchange
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: apply exchange - save exchanges to database and stay on exchange page
(defn apply-exchange [{:keys [path-params params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; only allow exchange if player is in phase 2
      (if (not= (:player/current-phase player) 2)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (let [;; helper function to safely parse numbers, treating empty/nil as 0
              safe-parse (fn [val] (or (parse-long (if (empty? (str val)) "0" (str val))) 0))

              ;; parse exchange input values
              soldiers-sold (safe-parse (:soldiers-sold params))
              fighters-sold (safe-parse (:fighters-sold params))
              stations-sold (safe-parse (:stations-sold params))
              military-planets-sold (safe-parse (:military-planets-sold params))
              food-planets-sold (safe-parse (:food-planets-sold params))
              ore-planets-sold (safe-parse (:ore-planets-sold params))
              food-bought (safe-parse (:food-bought params))
              food-sold (safe-parse (:food-sold params))
              fuel-bought (safe-parse (:fuel-bought params))
              fuel-sold (safe-parse (:fuel-sold params))

              ;; TODO: Define exchange rates (these should come from game constants eventually)
              soldier-sell-rate 50
              fighter-sell-rate 100
              station-sell-rate 150
              mil-planet-sell-rate 500
              food-planet-sell-rate 500
              ore-planet-sell-rate 500
              food-buy-rate 10
              food-sell-rate (quot food-buy-rate 2)
              fuel-buy-rate 15
              fuel-sell-rate (quot fuel-buy-rate 2)

              ;; calculate credits from selling units and planets
              credits-from-sales (+ (* soldiers-sold soldier-sell-rate)
                                    (* fighters-sold fighter-sell-rate)
                                    (* stations-sold station-sell-rate)
                                    (* military-planets-sold mil-planet-sell-rate)
                                    (* food-planets-sold food-planet-sell-rate)
                                    (* ore-planets-sold ore-planet-sell-rate))

              ;; calculate credits from buying/selling resources
              credits-from-exchanges (- (+ (* food-sold food-sell-rate)
                                           (* fuel-sold fuel-sell-rate))
                                        (+ (* food-bought food-buy-rate)
                                           (* fuel-bought fuel-buy-rate)))

              ;; calculate new totals
              new-credits (+ (:player/credits player) credits-from-sales credits-from-exchanges)
              new-soldiers (- (:player/soldiers player) soldiers-sold)
              new-fighters (- (:player/fighters player) fighters-sold)
              new-stations (- (:player/defence-stations player) stations-sold)
              new-mil-planets (- (:player/military-planets player) military-planets-sold)
              new-food-planets (- (:player/food-planets player) food-planets-sold)
              new-ore-planets (- (:player/ore-planets player) ore-planets-sold)
              new-food (+ (:player/food player) food-bought (- food-sold))
              new-fuel (+ (:player/fuel player) fuel-bought (- fuel-sold))]

          ;; submit transaction to update player resources (stay in phase 2)
          (biff/submit-tx ctx
            [{:db/doc-type :player
              :db/op :update
              :xt/id player-id
              :player/credits new-credits
              :player/soldiers new-soldiers
              :player/fighters new-fighters
              :player/defence-stations new-stations
              :player/military-planets new-mil-planets
              :player/food-planets new-food-planets
              :player/ore-planets new-ore-planets
              :player/food new-food
              :player/fuel new-fuel}])
          ;; redirect back to expenses page
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/expenses")}})))))

;; :: calculate exchange preview dynamically for HTMX updates
(defn calculate-exchange [{:keys [path-params params biff/db] :as ctx}]
  (println "=== calculate-exchange called ===")
  (println "path-params:" path-params)
  (println "params:" params)
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        _ (println "player found:" (boolean player))

        ;; helper function to safely parse numbers
        safe-parse (fn [val] 
                     (let [s (str val)
                           cleaned (clojure.string/replace s #"[^0-9]" "")]
                       (if (empty? cleaned)
                         0
                         (parse-long cleaned))))

        ;; parse exchange quantities
        soldiers-sold (safe-parse (:soldiers-sold params))
        fighters-sold (safe-parse (:fighters-sold params))
        stations-sold (safe-parse (:stations-sold params))
        military-planets-sold (safe-parse (:military-planets-sold params))
        food-planets-sold (safe-parse (:food-planets-sold params))
        ore-planets-sold (safe-parse (:ore-planets-sold params))
        food-bought (safe-parse (:food-bought params))
        food-sold (safe-parse (:food-sold params))
        fuel-bought (safe-parse (:fuel-bought params))
        fuel-sold (safe-parse (:fuel-sold params))

        ;; exchange rates
        soldier-sell-rate 50
        fighter-sell-rate 100
        station-sell-rate 150
        mil-planet-sell-rate 500
        food-planet-sell-rate 500
        ore-planet-sell-rate 500
        food-buy-rate 10
        food-sell-rate (quot food-buy-rate 2)
        fuel-buy-rate 15
        fuel-sell-rate (quot fuel-buy-rate 2)

        ;; calculate credits from exchanges
        credits-from-sales (+ (* soldiers-sold soldier-sell-rate)
                              (* fighters-sold fighter-sell-rate)
                              (* stations-sold station-sell-rate)
                              (* military-planets-sold mil-planet-sell-rate)
                              (* food-planets-sold food-planet-sell-rate)
                              (* ore-planets-sold ore-planet-sell-rate))

        credits-from-exchanges (- (+ (* food-sold food-sell-rate)
                                     (* fuel-sold fuel-sell-rate))
                                  (+ (* food-bought food-buy-rate)
                                     (* fuel-bought fuel-buy-rate)))

        ;; calculate new totals
        credits-after (+ (:player/credits player) credits-from-sales credits-from-exchanges)
        soldiers-after (- (:player/soldiers player) soldiers-sold)
        fighters-after (- (:player/fighters player) fighters-sold)
        stations-after (- (:player/defence-stations player) stations-sold)
        mil-planets-after (- (:player/military-planets player) military-planets-sold)
        food-planets-after (- (:player/food-planets player) food-planets-sold)
        ore-planets-after (- (:player/ore-planets player) ore-planets-sold)
        food-after (+ (:player/food player) food-bought (- food-sold))
        fuel-after (+ (:player/fuel player) fuel-bought (- fuel-sold))

        ;; validate exchanges are valid
        can-execute? (and (>= soldiers-after 0)
                          (>= fighters-after 0)
                          (>= stations-after 0)
                          (>= mil-planets-after 0)
                          (>= food-planets-after 0)
                          (>= ore-planets-after 0)
                          (>= food-after 0)
                          (>= fuel-after 0))
        
        ;; track which specific exchanges are invalid
        invalid-soldier-sale? (< soldiers-after 0)
        invalid-fighter-sale? (< fighters-after 0)
        invalid-station-sale? (< stations-after 0)
        invalid-mil-planet-sale? (< mil-planets-after 0)
        invalid-food-planet-sale? (< food-planets-after 0)
        invalid-ore-planet-sale? (< ore-planets-after 0)
        invalid-food-sale? (and (> food-sold 0) (< food-after 0))
        invalid-fuel-sale? (and (> fuel-sold 0) (< fuel-after 0))
        invalid-food-purchase? (and (> food-bought 0) (< credits-after 0))
        invalid-fuel-purchase? (and (> fuel-bought 0) (< credits-after 0))
        
        ;; any invalid sale or purchase involving credits
        invalid-credits-transaction? (or invalid-soldier-sale?
                                          invalid-fighter-sale?
                                          invalid-station-sale?
                                          invalid-mil-planet-sale?
                                          invalid-food-planet-sale?
                                          invalid-ore-planet-sale?
                                          invalid-food-sale?
                                          invalid-fuel-sale?
                                          invalid-food-purchase?
                                          invalid-fuel-purchase?)]

    (biff/render
      [:div
       [:div#resources-after.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
        [:h3.font-bold.mb-4 "Resources After Exchange"]
        [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
         [:div
          [:p.text-xs "Credits"]
          [:p.font-mono {:class (when invalid-credits-transaction? "text-red-400")} credits-after]]
         [:div
          [:p.text-xs "Food"]
          [:p.font-mono {:class (when (or invalid-food-sale? invalid-food-purchase?) "text-red-400")} food-after]]
         [:div
          [:p.text-xs "Fuel"]
          [:p.font-mono {:class (when (or invalid-fuel-sale? invalid-fuel-purchase?) "text-red-400")} fuel-after]]
         [:div
          [:p.text-xs "Galaxars"]
          [:p.font-mono (:player/galaxars player)]]
         [:div
          [:p.text-xs "Soldiers"]
          [:p.font-mono {:class (when invalid-soldier-sale? "text-red-400")} soldiers-after]]
         [:div
          [:p.text-xs "Fighters"]
          [:p.font-mono {:class (when invalid-fighter-sale? "text-red-400")} fighters-after]]
         [:div
          [:p.text-xs "Stations"]
          [:p.font-mono {:class (when invalid-station-sale? "text-red-400")} stations-after]]
         [:div
          [:p.text-xs "Mil Plts"]
          [:p.font-mono {:class (when invalid-mil-planet-sale? "text-red-400")} mil-planets-after]]
         [:div
          [:p.text-xs "Food Plts"]
          [:p.font-mono {:class (when invalid-food-planet-sale? "text-red-400")} food-planets-after]]
         [:div
          [:p.text-xs "Ore Plts"]
          [:p.font-mono {:class (when invalid-ore-planet-sale? "text-red-400")} ore-planets-after]]]]
       [:div#exchange-warning.h-8.flex.items-center
        {:hx-swap-oob "true"}
        (when (not can-execute?)
          [:p.text-yellow-400.font-bold "⚠ Invalid exchanges! You cannot sell more than you own."])]
       [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
        {:type "submit"
         :disabled (not can-execute?)
         :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"
         :hx-swap-oob "true"}
        "Make Exchange"]])))

;; :: helper function for exchange input fields with reset button
(defn exchange-input [name value player-id hx-include]
  [:div.relative.mt-1
   [:input.w-full.bg-black.border.border-green-400.text-green-400.p-2.pr-6.font-mono
    {:type "text" 
     :name name 
     :value value
     :autocomplete "off"
     :autocapitalize "off"
     :autocorrect "off"
     :spellcheck "false"
     :data-lpignore "true"
     :data-form-type "other"
     :hx-post (str "/app/game/" player-id "/calculate-exchange")
     :hx-target "#resources-after" 
     :hx-swap "outerHTML"
     :hx-trigger "load, input"
     :hx-include hx-include
     :onblur (str "let val = this.value.replace(/[^0-9]/g, ''); "
                  "if (val === '' || val === '0') { val = '0'; } "
                  "else { val = String(parseInt(val, 10)); } "
                  "this.value = val;")}]
   [:button
    {:type "button"
     :tabindex "-1"
     :class "absolute right-2 top-1/2 -translate-y-1/2 text-green-400 hover:text-green-300 transition-colors text-2xl font-bold p-0 bg-none border-none cursor-pointer"
     :onclick (str "document.querySelector('[name=\"" name "\"]').value = 0; "
                   "document.querySelector('[name=\"" name "\"]').dispatchEvent(new Event('input', {bubbles: true}))")
     :title "Reset"}
    "\u25e6"]])

;; :: exchange page - players buy/sell resources and units to manage their economy
(defn exchange-page [{:keys [player game]}]
  (let [player-id (:xt/id player)
        hx-include "[name='soldiers-sold'],[name='fighters-sold'],[name='stations-sold'],[name='military-planets-sold'],[name='food-planets-sold'],[name='ore-planets-sold'],[name='food-bought'],[name='food-sold'],[name='fuel-bought'],[name='fuel-sold']"]

    (ui/page
      {}
      [:div.text-green-400.font-mono
       [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

       (ui/phase-header (:player/current-phase player) "EXCHANGE")

       ;; :: resources before exchange
       [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
        [:h3.font-bold.mb-4 "Resources Before Exchange"]
        [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
         [:div
          [:p.text-xs "Credits"]
          [:p.font-mono (:player/credits player)]]
         [:div
          [:p.text-xs "Food"]
          [:p.font-mono (:player/food player)]]
         [:div
          [:p.text-xs "Fuel"]
          [:p.font-mono (:player/fuel player)]]
         [:div
          [:p.text-xs "Galaxars"]
          [:p.font-mono (:player/galaxars player)]]
         [:div
          [:p.text-xs "Soldiers"]
          [:p.font-mono (:player/soldiers player)]]
         [:div
          [:p.text-xs "Fighters"]
          [:p.font-mono (:player/fighters player)]]
         [:div
          [:p.text-xs "Stations"]
          [:p.font-mono (:player/defence-stations player)]]
         [:div
          [:p.text-xs "Mil Plts"]
          [:p.font-mono (:player/military-planets player)]]
         [:div
          [:p.text-xs "Food Plts"]
          [:p.font-mono (:player/food-planets player)]]
         [:div
          [:p.text-xs "Ore Plts"]
          [:p.font-mono (:player/ore-planets player)]]]]

       ;; :: exchange form
       (biff/form
         {:action (str "/app/game/" player-id "/apply-exchange")
          :method "post"}

         [:h3.font-bold.mb-4 "Exchanges This Round"]
         [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8

          ;; :: sell soldiers
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Soldiers"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "50 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/soldiers player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "soldiers-sold" 0 player-id hx-include)]]]

          ;; :: sell fighters
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Fighters"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "100 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/fighters player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "fighters-sold" 0 player-id hx-include)]]]

          ;; :: sell stations
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Stations"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "150 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/defence-stations player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "stations-sold" 0 player-id hx-include)]]]

          ;; :: sell ore planets
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Ore Planets"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "500 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/ore-planets player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "ore-planets-sold" 0 player-id hx-include)]]]

          ;; :: sell food planets
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Food Planets"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "500 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/food-planets player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "food-planets-sold" 0 player-id hx-include)]]]

          ;; :: sell military planets
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Military Planets"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "500 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/military-planets player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "military-planets-sold" 0 player-id hx-include)]]]

          ;; :: sell food
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Food"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "5 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/food player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "food-sold" 0 player-id hx-include)]]]

          ;; :: sell fuel
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Sell Fuel"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "7 credits"]]
            [:div
             [:p.text-xs "Max Available"]
             [:p.font-mono (:player/fuel player)]]
            [:div
             [:label.text-xs "Sell Quantity"]
             (exchange-input "fuel-sold" 0 player-id hx-include)]]]

         ;; :: invisible placeholder for grid alignment (only visible on lg screens)
          [:div.hidden.lg:block.invisible.border.border-green-400.p-4]


          ;; :: buy food
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Food"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "10 credits"]]
            [:div
             [:label.text-xs "Buy Quantity"]
             (exchange-input "food-bought" 0 player-id hx-include)]]]

          ;; :: buy fuel
          [:div.border.border-green-400.p-4
           [:h4.font-bold.mb-3 "Buy Fuel"]
           [:div.space-y-2
            [:div
             [:p.text-xs "Price per Unit"]
             [:p.font-mono "15 credits"]]
            [:div
             [:label.text-xs "Buy Quantity"]
             (exchange-input "fuel-bought" 0 player-id hx-include)]]]

          ;; :: invisible placeholder for grid alignment (only visible on lg screens)
          [:div.hidden.lg:block.invisible.border.border-green-400.p-4]
  
           ]

         ;; :: resources after exchange
         [:div#resources-after.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
          [:h3.font-bold.mb-4 "Resources After Exchange"]
          [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
           [:div
            [:p.text-xs "Credits"]
            [:p.font-mono (:player/credits player)]]
           [:div
            [:p.text-xs "Food"]
            [:p.font-mono (:player/food player)]]
           [:div
            [:p.text-xs "Fuel"]
            [:p.font-mono (:player/fuel player)]]
           [:div
            [:p.text-xs "Galaxars"]
            [:p.font-mono (:player/galaxars player)]]
           [:div
            [:p.text-xs "Soldiers"]
            [:p.font-mono (:player/soldiers player)]]
           [:div
            [:p.text-xs "Fighters"]
            [:p.font-mono (:player/fighters player)]]
           [:div
            [:p.text-xs "Stations"]
            [:p.font-mono (:player/defence-stations player)]]
           [:div
            [:p.text-xs "Mil Plts"]
            [:p.font-mono (:player/military-planets player)]]
           [:div
            [:p.text-xs "Food Plts"]
            [:p.font-mono (:player/food-planets player)]]
           [:div
            [:p.text-xs "Ore Plts"]
            [:p.font-mono (:player/ore-planets player)]]]]

         [:div#exchange-warning.h-8.flex.items-center]
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href (str "/app/game/" player-id "/expenses")} "Cancel Exchange"]
          [:button#submit-button.bg-green-400.text-black.px-6.py-2.font-bold.transition-colors
           {:type "submit"
            :disabled true
            :class "disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-600 disabled:hover:bg-gray-600"}
           "Make Exchange"]])
       ]))) ;; end of biff/form
