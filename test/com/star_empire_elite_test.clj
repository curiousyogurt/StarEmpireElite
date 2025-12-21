(ns com.star-empire-elite-test
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.app :as app]
            [com.star-empire-elite.constants :as const]
            [malli.generator :as mg]
            [rum.core :as rum]
            [xtdb.api :as xt]))

(deftest example-test
  (is (= 4 (+ 2 2))))

(defn get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

;; Test 1: Constants Validation (no database needed)
(deftest constants-test
  (testing "Game constants are properly defined"
    (is (= const/ore-planet-credits 500))
    (is (= const/food-planet-food 1000))
    (is (= const/mil-planet-soldiers 50))
    (is (pos? const/planet-upkeep-credits))
    (is (pos? const/soldier-upkeep-food))))

;; Test 2: Simple Math/Logic Test (no database needed)
(deftest game-logic-test
  (testing "Basic game calculations"
    (let [player-credits 1000
          cost-per-unit 50
          units-to-buy 10
          total-cost (* cost-per-unit units-to-buy)
          remaining-credits (- player-credits total-cost)]
      (is (= total-cost 500))
      (is (= remaining-credits 500))
      (is (>= player-credits total-cost)))))

;; Test 3: Database Connection Test (minimal)
(deftest database-connection-test
  (testing "Database node creation works"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)]
        (is (some? (:biff.xtdb/node ctx)))
        (is (some? (:biff/db ctx)))))))

;; Test 4: Complete Game Entity (matching schema exactly)
(deftest complete-game-creation-test
  (testing "Create a complete game entity matching schema"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            game-id (java.util.UUID/randomUUID)
            now (java.util.Date.)
            end-date (java.util.Date. (+ (.getTime now) (* 30 24 60 60 1000)))]
        (biff/submit-tx ctx
          [{:db/doc-type :game
            :xt/id game-id
            :game/name "Complete Test Game"
            :game/created-at now
            :game/scheduled-end-at end-date
            :game/status 0
            :game/turns-per-day 6
            :game/rounds-per-day 4
            ;; All the required income generation constants
            :game/ore-planet-credits const/ore-planet-credits
            :game/ore-planet-fuel const/ore-planet-fuel
            :game/ore-planet-galaxars const/ore-planet-galaxars
            :game/food-planet-food const/food-planet-food
            :game/mil-planet-soldiers const/mil-planet-soldiers
            :game/mil-planet-fighters const/mil-planet-fighters
            :game/mil-planet-stations const/mil-planet-stations
            :game/mil-planet-agents const/mil-planet-agents
            ;; All the required upkeep constants
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
            ;; Building/purchase cost constants
            :game/soldier-cost const/soldier-cost
            :game/transport-cost const/transport-cost
            :game/general-cost const/general-cost
            :game/carrier-cost const/carrier-cost
            :game/fighter-cost const/fighter-cost
            :game/admiral-cost const/admiral-cost
            :game/station-cost const/station-cost
            :game/cmd-ship-cost const/cmd-ship-cost
            :game/mil-planet-cost const/mil-planet-cost
            :game/food-planet-cost const/food-planet-cost
            :game/ore-planet-cost const/ore-planet-cost}])

        (let [db (xt/db node)
              game (xt/entity db game-id)]
          (is (some? game))
          (is (= (:game/name game) "Complete Test Game"))
          (is (= (:game/status game) 0))
          (is (= (:game/ore-planet-credits game) const/ore-planet-credits)))))))

;; Test 5: Complete Player Entity (matching schema exactly)
(deftest complete-player-creation-test
  (testing "Create a complete player entity matching schema"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            player-id (java.util.UUID/randomUUID)
            user-id (java.util.UUID/randomUUID)
            game-id (java.util.UUID/randomUUID)]
        (biff/submit-tx ctx
          [{:db/doc-type :player
            :xt/id player-id
            :player/user user-id
            :player/game game-id
            :player/empire-name "Complete Test Empire"
            ;; Currencies
            :player/credits 10000
            :player/food 5000
            :player/fuel 3000
            :player/galaxars 1000
            ;; Resources
            :player/mil-planets 2
            :player/food-planets 3
            :player/ore-planets 1
            :player/population 1000000
            ;; Status  
            :player/stability 75
            :player/status 0
            :player/score 0
            ;; Turn/Round/Phase tracking
            :player/current-turn 1
            :player/current-round 1
            :player/current-phase 0
            :player/turns-used 0
            ;; Military units
            :player/soldiers 1000
            :player/transports 10
            :player/stations 5
            :player/carriers 2
            :player/fighters 50
            :player/cmd-ships 1
            :player/generals 5
            :player/admirals 3
            :player/agents 10}])
        
        (let [db (xt/db node)
              player (xt/entity db player-id)]
          (is (some? player))
          (is (= (:player/empire-name player) "Complete Test Empire"))
          (is (= (:player/credits player) 10000))
          (is (= (:player/user player) user-id))
          (is (= (:player/game player) game-id)))))))

;; Keep your existing comprehensive test
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
                ;; Building constants
                :game/soldier-cost const/soldier-cost
                :game/transport-cost const/transport-cost
                :game/general-cost const/general-cost
                :game/carrier-cost const/carrier-cost
                :game/fighter-cost const/fighter-cost
                :game/admiral-cost const/admiral-cost
                :game/station-cost const/station-cost
                :game/cmd-ship-cost const/cmd-ship-cost
                :game/mil-planet-cost const/mil-planet-cost
                :game/food-planet-cost const/food-planet-cost
                :game/ore-planet-cost const/ore-planet-cost}
               {:db/doc-type :player
                :xt/id player-id
                :player/user (:xt/id user)
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
                :player/current-phase 0
                :player/turns-used 0
                :player/generals 5
                :player/admirals 3
                :player/soldiers 1000
                :player/transports 10
                :player/stations 5
                :player/carriers 2
                :player/fighters 50
                :player/cmd-ships 1
                :player/agents 10}])
          db (xt/db node)
          game (xt/entity db game-id)
          player (xt/entity db player-id)]
      (is (some? game))
      (is (= (:game/name game) "Test Game"))
      (is (some? player))
      (is (= (:player/empire-name player) "Test Empire"))
      (is (= (:player/credits player) 10000)))))
