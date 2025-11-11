(ns com.star-empire-elite-test
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.app :as app]
            [malli.generator :as mg]
            [rum.core :as rum]
            [xtdb.api :as xt]))

(deftest example-test
  (is (= 4 (+ 2 2))))

(defn get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(deftest create-game-and-player-test
  (with-open [node (test-xtdb-node [])]
    (let [user (mg/generate :user main/malli-opts)
          game-id (java.util.UUID/randomUUID)
          player-id (java.util.UUID/randomUUID)
          now (java.util.Date.)
          end-date (java.util.Date. (+ (.getTime now) (* 30 24 60 60 1000)))
          ctx (assoc (get-context node) :session {:uid (:xt/id user)})
          _ (biff/submit-tx ctx
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
                :player/user (:xt/id user)
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
          db (xt/db node)
          game (xt/entity db game-id)
          player (xt/entity db player-id)]
      (is (some? game))
      (is (= (:game/name game) "Test Game"))
      (is (some? player))
      (is (= (:player/empire-name player) "Test Empire"))
      (is (= (:player/credits player) 10000)))))

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
        :game/population-upkeep-food const/population-upkeep-food}
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
        :command-ships 1
        :player/agents 10}])
    {:status 303
     :headers {"location" "/app"}}))
