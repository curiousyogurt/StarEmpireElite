(ns com.star-empire-elite.app
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.pages.app.dashboard :as dashboard]))

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
        :game/rounds-per-day 4}
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

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["" {:get app}]
            ["/create-test-game" {:post create-test-game}]]})
