;;; Development Notes
;;; In order to add a new page <p>, the following changes must be made to app.clj:
;;;  - Import the <p> module at the top require with :as alias
;;;    - This corresponds to a <p>.clj file in the pages/app directory
;;;  - Create a handler function <p>-handler that uses with-player-and-game macro
;;;  - Add a route that maps a URL to the handler

(ns com.star-empire-elite.app
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.pages.app.dashboard :as dashboard]
            [com.star-empire-elite.pages.app.game :as game]
            [com.star-empire-elite.pages.app.income :as income]
            [com.star-empire-elite.pages.app.expenses :as expenses]
            [com.star-empire-elite.pages.app.exchange :as exchange]
            [com.star-empire-elite.pages.app.building :as building]
            [com.star-empire-elite.pages.app.action :as action]
            [com.star-empire-elite.pages.app.espionage :as espionage]
            [com.star-empire-elite.pages.app.outcomes :as outcomes]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.utils :as utils]))

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
        :game/mil-planet-soldiers const/mil-planet-soldiers
        :game/mil-planet-fighters const/mil-planet-fighters
        :game/mil-planet-stations const/mil-planet-stations
        :game/mil-planet-agents const/mil-planet-agents
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
        :game/mil-planet-cost const/mil-planet-cost
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
        :player/mil-planets 2
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
        :player/stations 5
        :player/carriers 2
        :player/fighters 50
        :player/command-ships 1
        :player/agents 10}])
    {:status 303
     :headers {"location" "/app"}}))

;;;;
;;;; Phase Handlers - Streamlined using utils/with-player-and-game
;;;;
;;;; Each handler now uses the with-player-and-game macro which:
;;;;  1. Loads player and game entities from the database
;;;;  2. Handles the 404 error case automatically
;;;;  3. Provides clean bindings for player, game, and player-id
;;;;  4. Validates the player is in the correct phase
;;;;  5. Calls the appropriate page function with the entities
;;;;

(defn income-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 1 (income), then show page
    (or (utils/validate-phase player 1 player-id)
        (income/income-page {:player player :game game}))))

(defn expenses-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 2 (expenses), then show page
    (or (utils/validate-phase player 2 player-id)
        (expenses/expenses-page {:player player :game game}))))

(defn exchange-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Exchange is a sub-phase of expenses (phase 2)
    (or (utils/validate-phase player 2 player-id)
        (exchange/exchange-page {:player player :game game}))))

(defn building-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 3 (building), then show page
    (or (utils/validate-phase player 3 player-id)
        (building/building-page {:player player :game game}))))

(defn action-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 4 (action), then show page
    (or (utils/validate-phase player 4 player-id)
        (action/action-page {:player player :game game}))))

(defn espionage-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 5 (espionage), then show page
    (or (utils/validate-phase player 5 player-id)
        (espionage/espionage-page {:player player :game game}))))

(defn outcomes-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 6 (outcomes), then show page
    (or (utils/validate-phase player 6 player-id)
        (outcomes/outcomes-page {:player player :game game}))))

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
            ["/game/:player-id/exchange" {:get exchange-handler}]
            ["/game/:player-id/apply-exchange" {:post exchange/apply-exchange}]
            ["/game/:player-id/calculate-exchange" {:post exchange/calculate-exchange}]
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
