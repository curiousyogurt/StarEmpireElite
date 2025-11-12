(ns com.star-empire-elite.app
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.pages.app.dashboard :as dashboard]
            [com.star-empire-elite.pages.app.game :as game]
            [com.star-empire-elite.pages.app.income :as income]
            [com.star-empire-elite.pages.app.expenses :as expenses]
            [com.star-empire-elite.pages.app.building :as building]
            [com.star-empire-elite.pages.app.action :as action]
            [com.star-empire-elite.pages.app.espionage :as espionage]
            [com.star-empire-elite.pages.app.outcomes :as outcomes]
            [com.star-empire-elite.constants :as const]
            [xtdb.api :as xt]
            ))

;; :: check if player is in the correct phase for the requested page
;; If not, redirect to the correct phase page
(defn validate-phase [player-id current-phase required-phase]
  (when (not= current-phase required-phase)
    {:status 303
     :headers {"location" (game/get-phase-url player-id current-phase)}}))

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
        ;; Income generation constants
        :game/ore-planet-credits const/ore-planet-credits
        :game/ore-planet-fuel const/ore-planet-fuel
        :game/ore-planet-galaxars const/ore-planet-galaxars
        :game/food-planet-food const/food-planet-food
        :game/military-planet-soldiers const/military-planet-soldiers
        :game/military-planet-fighters const/military-planet-fighters
        :game/military-planet-stations const/military-planet-stations
        :game/military-planet-agents const/military-planet-agents
        ;; Upkeep/expense constants
        :game/planet-upkeep-credits const/planet-upkeep-credits
        :game/planet-upkeep-food const/planet-upkeep-food
        :game/soldier-upkeep-credits const/soldier-upkeep-credits
        :game/soldier-upkeep-food const/soldier-upkeep-food
        :game/fighter-upkeep-credits const/fighter-upkeep-credits
        :game/fighter-upkeep-fuel const/fighter-upkeep-fuel
        :game/station-upkeep-credits const/station-upkeep-credits
        :game/station-upkeep-fuel const/station-upkeep-fuel
        :game/agent-upkeep-credits const/agent-upkeep-credits
        :game/agent-upkeep-food const/agent-upkeep-food
        :game/population-upkeep-credits const/population-upkeep-credits
        :game/population-upkeep-food const/population-upkeep-food
        ;; Building cost constants
        :game/soldier-cost const/soldier-cost
        :game/transport-cost const/transport-cost
        :game/general-cost const/general-cost
        :game/carrier-cost const/carrier-cost
        :game/fighter-cost const/fighter-cost
        :game/admiral-cost const/admiral-cost
        :game/station-cost const/station-cost
        :game/command-ship-cost const/command-ship-cost
        :game/military-planet-cost const/military-planet-cost
        :game/food-planet-cost const/food-planet-cost
        :game/ore-planet-cost const/ore-planet-cost}
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
        :player/current-phase 1
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
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; validate that player is in phase 1 (income phase)
      (or (validate-phase player-id (:player/current-phase player) 1)
          (income/income-page {:player player :game game})))))

(defn expenses-handler [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; validate that player is in phase 2 (expenses phase)
      (or (validate-phase player-id (:player/current-phase player) 2)
          (expenses/expenses-page {:player player :game game})))))

(defn building-handler [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; validate that player is in phase 3 (building phase)
      (or (validate-phase player-id (:player/current-phase player) 3)
          (building/building-page {:player player :game game})))))

(defn action-handler [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; validate that player is in phase 4 (action phase)
      (or (validate-phase player-id (:player/current-phase player) 4)
          (action/action-page {:player player :game game})))))

(defn espionage-handler [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; validate that player is in phase 5 (espionage phase)
      (or (validate-phase player-id (:player/current-phase player) 5)
          (espionage/espionage-page {:player player :game game})))))

(defn outcomes-handler [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; validate that player is in phase 6 (outcomes phase)
      (or (validate-phase player-id (:player/current-phase player) 6)
          (outcomes/outcomes-page {:player player :game game})))))

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["" {:get app}]
            ["/create-test-game" {:post create-test-game}]
            ["/game/:player-id" {:get game/game-view}]
            ["/game/:player-id/income" {:get income-handler}]
            ["/game/:player-id/apply-income" {:post income/apply-income}]
            ["/game/:player-id/expenses" {:get expenses-handler}]
            ["/game/:player-id/apply-expenses" {:post expenses/apply-expenses}]
            ["/game/:player-id/calculate-expenses" {:post expenses/calculate-expenses}]
            ["/game/:player-id/building" {:get building-handler}]
            ["/game/:player-id/apply-building" {:post building/apply-building}]
            ["/game/:player-id/calculate-building" {:post building/calculate-building}]
            ["/game/:player-id/action" {:get action-handler}]
            ["/game/:player-id/apply-action" {:post action/apply-action}]
            ["/game/:player-id/espionage" {:get espionage-handler}]
            ["/game/:player-id/apply-espionage" {:post espionage/apply-espionage}]
            ["/game/:player-id/outcomes" {:get outcomes-handler}]
            ["/game/:player-id/apply-outcomes" {:post outcomes/apply-outcomes}]
            ]})
