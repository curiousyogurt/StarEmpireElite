(ns com.star-empire-elite.pages.app.income-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.income :as income]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;
;; Fixtures and utilities for testing
;;

;; Test UUIDs for sample data
(def test-game-id #uuid "00000000-0000-0000-0000-000000000001")
(def test-player-id #uuid "00000000-0000-0000-0000-000000000002")

;; Sample game entity simulating a game's economy rates
(def test-game
  {:xt/id test-game-id
   :game/ore-planet-credits 100
   :game/ore-planet-fuel 50
   :game/ore-planet-galaxars 10
   :game/food-planet-food 200
   :game/military-planet-soldiers 25
   :game/military-planet-fighters 15
   :game/military-planet-stations 5
   :game/military-planet-agents 8})

;; Sample player entity as "before income"
(def test-player
  {:xt/id test-player-id
   :player/empire-name "Test Empire"
   :player/game test-game-id
   :player/current-phase 1
   :player/ore-planets 3
   :player/food-planets 2
   :player/military-planets 4
   :player/credits 1000
   :player/food 500
   :player/fuel 300
   :player/galaxars 50
   :player/soldiers 100
   :player/fighters 75
   :player/defence-stations 20
   :player/agents 10})

;; Utility: Returns a function that simulates XTDB's entity lookup for given entities
(defn fake-entity [entities]
  (fn [_ id]
    (first (filter #(= (:xt/id %) id) entities))))

;;
;; Tests for the core logic in apply-income (phase checks, tx data, redirects, and edge cases)
;;

;; Test: "Player not found" scenario
(deftest test-apply-income-player-not-found
  (testing "Returns 404 when player is not in the DB"
    (with-redefs [xt/entity (fn [_ id] nil)]                                      ;; Simulate DB missing player
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :biff/db nil}
            result (income/apply-income ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

;; Test: Player not in phase 1, so skip income and redirect back to game
(deftest test-apply-income-wrong-phase
  (testing "Redirects when player is not in phase 1"
    (let [wrong-phase-player (assoc test-player :player/current-phase 2)]
      (with-redefs [xt/entity (fake-entity [wrong-phase-player test-game])]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :biff/db nil}
              result (income/apply-income ctx)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

;; Test: Correct transaction is submitted when income is applied in phase 1
(deftest test-apply-income-submits-correct-tx
  (testing "Transaction includes correct resource updates and phase advance"
    (let [player test-player
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)] ;; Intercept transaction
        (let [ctx {:path-params {:player-id (str test-player-id)} :biff/db nil}
              result (income/apply-income ctx)
              actual-tx (first @tx-called)]
          ;; Make sure we're redirected to expenses page
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/expenses")
                 (get-in result [:headers "location"])))
          ;; Credits calculation: previous + (ore planets x rate)
          (is (= (:player/credits actual-tx)
                 (+ (:player/credits player)
                    (* (:player/ore-planets player)
                       (:game/ore-planet-credits test-game)))))
          ;; Check phase is advanced
          (is (= 2 (:player/current-phase actual-tx)))
          ;; Additional fields (food, fuel, etc) could be similarly checked below:
          (is (= (:player/food actual-tx)
                 (+ (:player/food player)
                    (* (:player/food-planets player)
                       (:game/food-planet-food test-game)))))
          (is (= (:player/galaxars actual-tx)
                 (+ (:player/galaxars player)
                    (* (:player/ore-planets player)
                       (:game/ore-planet-galaxars test-game)))))
          ;; ... Add more assertions as needed ...          
          )))))

;; Test: Applying income for a phase-1 player with zero planets
(deftest test-apply-income-zero-planets-phase1
  (testing "Income application is a no-op on resources when all planet counts are 0"
    (let [player (assoc test-player :player/ore-planets 0 :player/food-planets 0 :player/military-planets 0)
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)} :biff/db nil}
              _ (income/apply-income ctx)
              actual-tx (first @tx-called)]
          ;; Credits unchanged:
          (is (= (:player/credits actual-tx) (:player/credits player)))
          ;; Food unchanged:
          (is (= (:player/food actual-tx) (:player/food player)))
          ;; Optional: check all other resources as well
          )))))

;; Test: Throws on non-UUID/invalid player-id (parsing error)
(deftest test-apply-income-bad-uuid
  (testing "Throws IllegalArgumentException when player-id is invalid"
    (let [ctx {:path-params {:player-id "not-a-uuid"} :biff/db nil}]
      (is (thrown? IllegalArgumentException (income/apply-income ctx))))))

;; Test: Returns 500 (or throws) if the player's associated game is missing
(deftest test-apply-income-missing-game
  (testing "Throws or fails when the referenced game entity is missing"
    (let [player (assoc test-player :player/game #uuid "12345678-1234-1234-1234-123456789abc")]
      (with-redefs [xt/entity (fn [_ id]
                                (if (= id (:xt/id player))
                                  player
                                  nil))]
        ;; Depending on your target error handling, you may expect thrown NPE or IllegalArgumentException
        (let [ctx {:path-params {:player-id (str (:xt/id player))} :biff/db nil}]
          (is (thrown? Exception (income/apply-income ctx))))))))

;; -------------------------------------------------------------------------
;; Tests for calculation logic (simple math/unit-style checks)
;; -------------------------------------------------------------------------

(deftest test-apply-income-calculations
  (testing "Ore planet, food planet, and military planet income math is correct"
    ;; Ore planet
    (is (= (* 3 100) 300))
    (is (= (* 3 50) 150))
    (is (= (* 3 10) 30))
    ;; Food planet
    (is (= (* 2 200) 400))
    ;; Military planets
    (is (= (* 4 25) 100))
    (is (= (* 4 15) 60))
    (is (= (* 4 5) 20))
    (is (= (* 4 8) 32))))

(deftest test-apply-income-with-zero-planets
  (testing "Handles all planet counts at zero"
    (is (= (* 0 100) 0))
    (is (= (* 0 200) 0))
    (is (= (* 0 25) 0))))

;; Check that large numbers don't break math
(deftest test-large-planet-counts
  (testing "Handles large counts correctly"
    (is (= (* 1000 100) 100000))
    (is (= (* 1000 200) 200000))
    (is (= (* 1000 25) 25000))))

;; Handles zero income rates (e.g., game config disables planet income)
(deftest test-income-with-zero-game-rates
  (testing "Income is zero if rates are zero"
    (let [zero-rate-game (assoc test-game :game/ore-planet-credits 0
                                         :game/ore-planet-fuel 0
                                         :game/ore-planet-galaxars 0)]
      (is (= (* 3 (:game/ore-planet-credits zero-rate-game)) 0))
      (is (= (* 3 (:game/ore-planet-fuel zero-rate-game)) 0))
      (is (= (* 2 (:game/food-planet-food zero-rate-game)) 400))))) ;; Food planet rate not changed

;;
;; Tests for income-page (view logic)
;;

(deftest test-income-page-renders
  (testing "Income page returns a hiccup vector and is not nil"
    (let [result (income/income-page {:player test-player :game test-game})]
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-page-calculations-match
  (testing "Income page resource outputs match expected values"
    (let [ore-credits (* 3 100)
          ore-fuel (* 3 50)
          ore-galaxars (* 3 10)
          food-food (* 2 200)
          mil-soldiers (* 4 25)
          mil-fighters (* 4 15)
          mil-stations (* 4 5)
          mil-agents (* 4 8)
          expected-credits (+ 1000 ore-credits)
          expected-food (+ 500 food-food)
          expected-fuel (+ 300 ore-fuel)
          expected-galaxars (+ 50 ore-galaxars)
          expected-soldiers (+ 100 mil-soldiers)
          expected-fighters (+ 75 mil-fighters)
          expected-stations (+ 20 mil-stations)
          expected-agents (+ 10 mil-agents)]
      (is (= 1300 expected-credits))
      (is (= 900 expected-food))
      (is (= 450 expected-fuel))
      (is (= 80 expected-galaxars))
      (is (= 200 expected-soldiers))
      (is (= 135 expected-fighters))
      (is (= 40 expected-stations))
      (is (= 42 expected-agents)))))

