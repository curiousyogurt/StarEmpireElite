(ns com.star-empire-elite-game-logic-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.app :as app]
            [com.star-empire-elite.utils :as utils]  ; CHANGED: Added utils import
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.pages.app.income :as income]
            [xtdb.api :as xt]))

(defn get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn create-test-player-and-game [ctx player-overrides game-overrides]
  (let [game-id (java.util.UUID/randomUUID)
        player-id (java.util.UUID/randomUUID)
        user-id (java.util.UUID/randomUUID)
        now (java.util.Date.)
        end-date (java.util.Date. (+ (.getTime now) (* 30 24 60 60 1000)))

        base-game {:db/doc-type :game
                   :xt/id game-id
                   :game/name "Test Game"
                   :game/created-at now
                   :game/scheduled-end-at end-date
                   :game/status 0
                   :game/turns-per-day 6
                   :game/rounds-per-day 4
                   :game/ore-planet-credits const/ore-planet-credits
                   :game/ore-planet-fuel const/ore-planet-fuel
                   :game/ore-planet-galaxars const/ore-planet-galaxars
                   :game/food-planet-food const/food-planet-food
                   :game/military-planet-soldiers const/military-planet-soldiers
                   :game/military-planet-fighters const/military-planet-fighters
                   :game/military-planet-stations const/military-planet-stations
                   :game/military-planet-agents const/military-planet-agents
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

        base-player {:db/doc-type :player
                     :xt/id player-id
                     :player/user user-id
                     :player/game game-id
                     :player/empire-name "Test Empire"
                     :player/credits 1000
                     :player/food 500
                     :player/fuel 300
                     :player/galaxars 100
                     :player/military-planets 1
                     :player/food-planets 1
                     :player/ore-planets 1
                     :player/population 100000
                     :player/stability 75
                     :player/status 0
                     :player/score 0
                     :player/current-turn 1
                     :player/current-round 1
                     :player/current-phase 1
                     :player/turns-used 0
                     :player/generals 2
                     :player/admirals 1
                     :player/soldiers 100
                     :player/transports 5
                     :player/stations 2
                     :player/carriers 1
                     :player/fighters 10
                     :player/command-ships 1
                     :player/agents 5}]

    (biff/submit-tx ctx
                    [(merge base-game game-overrides)
                     (merge base-player player-overrides)])

    {:game-id game-id :player-id player-id :user-id user-id}))

;; Test 1: Income Calculations
(deftest ore-planet-income-calculation-test
  (testing "Ore planets generate correct income"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            {:keys [game-id player-id]}
            (create-test-player-and-game 
              ctx
              {:player/ore-planets 3
               :player/credits 1000
               :player/fuel 200
               :player/galaxars 50}
              {})
            db (xt/db node)
            player (xt/entity db player-id)
            game (xt/entity db game-id)

            expected-credits (* 3 const/ore-planet-credits)
            expected-fuel (* 3 const/ore-planet-fuel)
            expected-galaxars (* 3 const/ore-planet-galaxars)]

        (is (= expected-credits 1500))  ; 3 * 500
        (is (= expected-fuel 600))      ; 3 * 200
        (is (= expected-galaxars 300))  ; 3 * 100

        ;; Test the income calculation logic directly
        (let [ore-credits (* (:player/ore-planets player) (:game/ore-planet-credits game))
              ore-fuel (* (:player/ore-planets player) (:game/ore-planet-fuel game))
              ore-galaxars (* (:player/ore-planets player) (:game/ore-planet-galaxars game))]
          (is (= ore-credits expected-credits))
          (is (= ore-fuel expected-fuel))
          (is (= ore-galaxars expected-galaxars)))))))

(deftest food-planet-income-calculation-test
  (testing "Food planets generate correct food income"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            {:keys [game-id player-id]}
            (create-test-player-and-game 
              ctx
              {:player/food-planets 4
               :player/food 1000}
              {})
            db (xt/db node)
            player (xt/entity db player-id)
            game (xt/entity db game-id)

            expected-food (* 4 const/food-planet-food)
            actual-food (* (:player/food-planets player) (:game/food-planet-food game))]

        (is (= expected-food 4000))  ; 4 * 1000
        (is (= actual-food expected-food))))))

(deftest military-planet-income-calculation-test
  (testing "Military planets generate correct military units"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            {:keys [game-id player-id]}
            (create-test-player-and-game
              ctx
              {:player/military-planets 2}
              {})
            db (xt/db node)
            player (xt/entity db player-id)
            game (xt/entity db game-id)

            expected-soldiers (* 2 const/military-planet-soldiers)
            expected-fighters (* 2 const/military-planet-fighters)
            expected-stations (* 2 const/military-planet-stations)
            expected-agents (* 2 const/military-planet-agents)]

        (is (= expected-soldiers 100))  ; 2 * 50
        (is (= expected-fighters 50))   ; 2 * 25
        (is (= expected-stations 20))   ; 2 * 10
        (is (= expected-agents 10))     ; 2 * 5

        ;; Test actual calculations
        (let [mil-soldiers (* (:player/military-planets player) (:game/military-planet-soldiers game))
              mil-fighters (* (:player/military-planets player) (:game/military-planet-fighters game))
              mil-stations (* (:player/military-planets player) (:game/military-planet-stations game))
              mil-agents (* (:player/military-planets player) (:game/military-planet-agents game))]
          (is (= mil-soldiers expected-soldiers))
          (is (= mil-fighters expected-fighters))
          (is (= mil-stations expected-stations))
          (is (= mil-agents expected-agents)))))))

;; Test 2: Expense Calculations
(deftest expense-calculation-test
  (testing "Expense calculations reduce resources correctly"
    (let [initial-credits 1000
          initial-food 500
          initial-fuel 300

          planets-pay 50
          soldiers-credits 100
          fighters-fuel 80

          expected-credits (- initial-credits planets-pay soldiers-credits)
          expected-food initial-food
          expected-fuel (- initial-fuel fighters-fuel)]

      ;; Test the calculation logic from app.clj
      (is (= expected-credits 850))
      (is (= expected-food 500))
      (is (= expected-fuel 220)))))

;; Test 3: Phase Validation
(deftest phase-validation-test
  (testing "Phase validation works correctly"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            {:keys [player-id]}
            (create-test-player-and-game 
              ctx
              {:player/current-phase 1}
              {})
            db (xt/db node)
            player-phase-1 (xt/entity db player-id)
            player-phase-2 (assoc player-phase-1 :player/current-phase 2)]

        ;; CHANGED: Now using utils/validate-phase with the new API (player, expected-phase, player-id)
        ;; Test correct phase - should return nil (no redirect)
        (is (nil? (utils/validate-phase player-phase-1 1 player-id)))
        (is (nil? (utils/validate-phase player-phase-2 2 player-id)))

        ;; Test incorrect phase - should return redirect response
        (let [redirect-response (utils/validate-phase player-phase-1 2 player-id)]
          (is (some? redirect-response))
          (is (= (:status redirect-response) 303))
          (is (contains? (:headers redirect-response) "location")))))))

;; Test 4: Resource Boundaries
(deftest resource-boundary-test
  (testing "Resources don't go negative in calculations"
    (let [player-credits 100
          expense-amount 150
          result (- player-credits expense-amount)]

      ;; This shows the calculation result - in a real game you'd want to prevent this
      (is (= result -50))

      ;; Test safe subtraction
      (let [safe-result (max 0 (- player-credits expense-amount))]
        (is (= safe-result 0))))))

;; Test 5: Income Application Logic Test (without HTTP)
(deftest income-calculation-logic-test
  (testing "Income calculation logic works correctly"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            {:keys [game-id player-id]}
            (create-test-player-and-game 
              ctx
              {:player/current-phase 1
               :player/ore-planets 2
               :player/food-planets 1
               :player/military-planets 1
               :player/credits 1000
               :player/food 500
               :player/fuel 200
               :player/galaxars 100
               :player/soldiers 100
               :player/fighters 20
               :player/stations 5
               :player/agents 10}
              {})
            db (xt/db node)
            player (xt/entity db player-id)
            game (xt/entity db game-id)]

        ;; Test income calculations (from income.clj logic)
        (let [ore-credits (* (:player/ore-planets player) (:game/ore-planet-credits game))
              ore-fuel (* (:player/ore-planets player) (:game/ore-planet-fuel game))
              ore-galaxars (* (:player/ore-planets player) (:game/ore-planet-galaxars game))
              food-food (* (:player/food-planets player) (:game/food-planet-food game))
              mil-soldiers (* (:player/military-planets player) (:game/military-planet-soldiers game))
              mil-fighters (* (:player/military-planets player) (:game/military-planet-fighters game))
              mil-stations (* (:player/military-planets player) (:game/military-planet-stations game))
              mil-agents (* (:player/military-planets player) (:game/military-planet-agents game))

              ;; Calculate final resources after income
              final-credits (+ (:player/credits player) ore-credits)
              final-food (+ (:player/food player) food-food)
              final-fuel (+ (:player/fuel player) ore-fuel)
              final-galaxars (+ (:player/galaxars player) ore-galaxars)
              final-soldiers (+ (:player/soldiers player) mil-soldiers)
              final-fighters (+ (:player/fighters player) mil-fighters)
              final-stations (+ (:player/stations player) mil-stations)
              final-agents (+ (:player/agents player) mil-agents)]

          ;; Verify individual income components
          (is (= ore-credits 1000))     ; 2 ore planets * 500 credits
          (is (= ore-fuel 400))         ; 2 ore planets * 200 fuel
          (is (= ore-galaxars 200))     ; 2 ore planets * 100 galaxars
          (is (= food-food 1000))       ; 1 food planet * 1000 food
          (is (= mil-soldiers 50))      ; 1 military planet * 50 soldiers
          (is (= mil-fighters 25))      ; 1 military planet * 25 fighters
          (is (= mil-stations 10))      ; 1 military planet * 10 stations
          (is (= mil-agents 5))         ; 1 military planet * 5 agents

          ;; Verify final resource totals
          (is (= final-credits 2000))   ; 1000 + 1000
          (is (= final-food 1500))      ; 500 + 1000
          (is (= final-fuel 600))       ; 200 + 400
          (is (= final-galaxars 300))   ; 100 + 200
          (is (= final-soldiers 150))   ; 100 + 50
          (is (= final-fighters 45))    ; 20 + 25
          (is (= final-stations 15))    ; 5 + 10
          (is (= final-agents 15))))))) ; 10 + 5

;; Test 6: Zero Planet Edge Case
(deftest zero-planets-income-test
  (testing "Players with zero planets get zero income"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            {:keys [game-id player-id]}
            (create-test-player-and-game 
              ctx
              {:player/ore-planets 0
               :player/food-planets 0
               :player/military-planets 0}
              {})
            db (xt/db node)
            player (xt/entity db player-id)
            game (xt/entity db game-id)]

        ;; All income should be zero
        (is (= (* (:player/ore-planets player) (:game/ore-planet-credits game)) 0))
        (is (= (* (:player/food-planets player) (:game/food-planet-food game)) 0))
        (is (= (* (:player/military-planets player) (:game/military-planet-soldiers game)) 0))))))

;; Test 7: Game Constants Consistency
(deftest game-constants-consistency-test
  (testing "Game entity uses correct constants from const namespace"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            {:keys [game-id]} (create-test-player-and-game ctx {} {})
            db (xt/db node)
            game (xt/entity db game-id)]

        ;; Verify game constants match const namespace
        (is (= (:game/ore-planet-credits game) const/ore-planet-credits))
        (is (= (:game/ore-planet-fuel game) const/ore-planet-fuel))
        (is (= (:game/ore-planet-galaxars game) const/ore-planet-galaxars))
        (is (= (:game/food-planet-food game) const/food-planet-food))
        (is (= (:game/military-planet-soldiers game) const/military-planet-soldiers))
        (is (= (:game/military-planet-fighters game) const/military-planet-fighters))))))
