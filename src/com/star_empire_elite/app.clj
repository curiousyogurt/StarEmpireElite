(ns com.star-empire-elite.app
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.pages.app.dashboard :as dashboard]
            [com.star-empire-elite.pages.app.game :as game]
            [com.star-empire-elite.pages.app.income :as income]
            [com.star-empire-elite.pages.app.expenses :as expenses]
            [xtdb.api :as xt]
            ))

;; :: main app dashboard showing all player games
(defn app [{:keys [session biff/db] :as ctx}]
  (dashboard/dashboard ctx))

;; :: create a test game and player for the current user
(defn create-test-game [{:keys [session biff/db] :as ctx}]
  (let [game-id (java.util.UUID/randomUUID)
        player-id (java.util.UUID/randomUUID)
        now (java.util.Date.)
        end-date (java.util.Date. (+ (.getTime now) (* 30 24 60 60 1000)))]
    (biff/submit-tx ctx
      [{:db/doc-type :game
        :xt/id game-id
        :game/name "Test Game"
        :game/created-at now
        :game/scheduled-end-at end-date
        :game/status 0
        :game/turns-per-day 6
        :game/rounds-per-day 4
        
        ;; Planet income constants (per planet per round)
        :game/ore-credits-per-planet 500
        :game/ore-fuel-per-planet 200
        :game/ore-galaxars-per-planet 100
        :game/food-per-planet 1000
        :game/mil-soldiers-per-planet 50
        :game/mil-fighters-per-planet 25
        :game/mil-stations-per-planet 10
        :game/mil-agents-per-planet 5
        
        ;; Expense constants (per unit/planet per round)
        :game/planet-credit-cost 10
        :game/planet-food-cost 1
        :game/soldier-credit-cost-per-k 1
        :game/soldier-food-cost-per-k 1
        :game/fighter-credit-cost 2
        :game/fighter-fuel-cost 2
        :game/station-credit-cost 3
        :game/station-fuel-cost 3
        :game/agent-credit-cost 2
        :game/agent-food-cost-per-k 1
        :game/pop-credit-cost-per-k 1
        :game/pop-food-cost-per-k 1
        
        ;; Starting resources
        :game/starting-credits 10000
        :game/starting-food 5000
        :game/starting-fuel 3000
        :game/starting-galaxars 1000
        :game/starting-population 1000000
        :game/starting-stability 75
        
        ;; Starting planets
        :game/starting-military-planets 2
        :game/starting-food-planets 3
        :game/starting-ore-planets 1
        
        ;; Starting units
        :game/starting-generals 5
        :game/starting-admirals 3
        :game/starting-soldiers 1000
        :game/starting-transports 10
        :game/starting-defence-stations 5
        :game/starting-carriers 2
        :game/starting-fighters 50
        :game/starting-command-ships 1
        :game/starting-agents 10}
       {:db/doc-type :player
        :xt/id player-id
        :player/user (:uid session)
        :player/game game-id
        :player/empire-name "Test Empire"
        :player/credits 10000
        :player/food 5000
        :player/fuel 3000
        :player/galaxars 1000
        :player/military-planets 2
        :player/food-planets 3
        :player/ore-planets 1
        :player/population 1000000
        :player/stability 75
        :player/status 0
        :player/score 0
        :player/current-turn 1
        :player/current-round 1
        :player/current-phase 0
        :player/turns-used 0
        :player/generals 5
        :player/admirals 3
        :player/soldiers 1000
        :player/transports 10
        :player/defence-stations 5
        :player/carriers 2
        :player/fighters 50
        :player/command-ships 1
        :player/agents 10}])
    {:status 303
     :headers {"location" "/app"}}))

(defn income-handler [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      (income/income-page {:player player}))))

(defn expenses-handler [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      (expenses/expenses-page {:player player}))))

(defn calculate-expenses [{:keys [path-params params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        ;; parse input values, default to 0 if not provided
        planets-pay (parse-long (or (:planets-pay params) "0"))
        soldiers-credits (parse-long (or (:soldiers-credits params) "0"))
        soldiers-food (parse-long (or (:soldiers-food params) "0"))
        fighters-credits (parse-long (or (:fighters-credits params) "0"))
        fighters-fuel (parse-long (or (:fighters-fuel params) "0"))
        stations-credits (parse-long (or (:stations-credits params) "0"))
        stations-fuel (parse-long (or (:stations-fuel params) "0"))
        agents-credits (parse-long (or (:agents-credits params) "0"))
        agents-food (parse-long (or (:agents-food params) "0"))
        population-credits (parse-long (or (:population-credits params) "0"))
        population-food (parse-long (or (:population-food params) "0"))
        
        ;; calculate remaining resources
        credits-after (- (:player/credits player) planets-pay soldiers-credits 
                         fighters-credits stations-credits agents-credits population-credits)
        food-after (- (:player/food player) soldiers-food agents-food population-food)
        fuel-after (- (:player/fuel player) fighters-fuel stations-fuel)]
    (biff/render
     [:div#resources-after.border.border-green-400.p-4.mb-8.bg-green-100.bg-opacity-5
      [:h3.font-bold.mb-4 "Resources After Expenses"]
      [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
       [:div
        [:p.text-xs "Credits"]
        [:p.font-mono credits-after]]
       [:div
        [:p.text-xs "Food"]
        [:p.font-mono food-after]]
       [:div
        [:p.text-xs "Fuel"]
        [:p.font-mono fuel-after]]
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
        [:p.text-xs "Agents"]
        [:p.font-mono (:player/agents player)]]]])))

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["" {:get app}]
            ["/create-test-game" {:post create-test-game}]
            ["/game/:player-id" {:get game/game-view}]
            ["/game/:player-id/play" {:get income-handler}]
            ["/game/:player-id/expenses" {:get expenses-handler}]
            ["/game/:player-id/calculate-expenses" {:post calculate-expenses}]
            ]})
