(ns com.star-empire-elite.pages.app.expenses-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.expenses :as expenses]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.star-empire-elite.utils :as utils]
            [com.biffweb :as biff]))

;;
;; Test fixtures and helpers
;;

;; Sample UUIDs
(def test-game-id #uuid "00000000-0000-0000-0000-000000000001")
(def test-player-id #uuid "00000000-0000-0000-0000-000000000002")

;; Sample player and game for testing
(def test-game
  {:xt/id test-game-id
   :game/planet-upkeep-credits 75
   :game/planet-upkeep-food 1
   :game/soldier-upkeep-credits 6
   :game/soldier-upkeep-food 9
   :game/fighter-upkeep-credits 4
   :game/fighter-upkeep-fuel 3
   :game/station-upkeep-credits 12
   :game/station-upkeep-fuel 8
   :game/agent-upkeep-food 16
   :game/agent-upkeep-fuel 9
   :game/population-upkeep-food 2
   :game/population-upkeep-fuel 7
   :game/expense-stability-penalty 3})

(def test-player
  {:xt/id test-player-id
   :player/game test-game-id
   :player/current-phase 2
   :player/ore-planets 2
   :player/erg-planets 2
   :player/mil-planets 1
   :player/credits 1000
   :player/food 800
   :player/fuel 200
   :player/galaxars 666
   :player/soldiers 3000
   :player/fighters 7
   :player/stations 2
   :player/agents 1500
   :player/population 9000})

;;;;
;;;; Pure Function Tests
;;;;

(deftest test-calculate-required-expenses-basic
  (testing "Computes per-category upkeep from player counts and game constants"
    (let [req (expenses/calculate-required-expenses test-player test-game)]
      ;; planets: (2+2+1)=5 planets
      (is (= (* 5 75) (:planets-credits req)))
      (is (= (* 5  1) (:planets-food    req)))
      ;; soldiers
      (is (= (* 3000  6) (:soldiers-credits req)))
      (is (= (* 3000  9) (:soldiers-food    req)))
      ;; fighters
      (is (= (* 7  4) (:fighters-credits req)))
      (is (= (* 7  3) (:fighters-fuel    req)))
      ;; stations
      (is (= (* 2 12) (:stations-credits req)))
      (is (= (* 2  8) (:stations-fuel    req)))
      ;; agents
      (is (= (* 1500 16) (:agents-food req)))
      (is (= (* 1500  9) (:agents-fuel req)))
      ;; population
      (is (= (* 9000 2) (:population-food req)))
      (is (= (* 9000 7) (:population-fuel req))))))

(deftest test-calculate-required-expense-totals
  (testing "Aggregates per-category expenses into per-resource totals"
    (let [req    (expenses/calculate-required-expenses test-player test-game)
          totals (expenses/calculate-required-expense-totals req)]
      (is (= (+ (:planets-credits req) (:soldiers-credits req)
                (:fighters-credits req) (:stations-credits req))
             (:credits totals)))
      (is (= (+ (:planets-food req) (:soldiers-food req)
                (:agents-food req) (:population-food req))
             (:food totals)))
      (is (= (+ (:fighters-fuel req) (:stations-fuel req)
                (:agents-fuel req) (:population-fuel req))
             (:fuel totals))))))

(deftest test-calculate-resources-after-expenses
  (testing "Deducts payments from player snapshot resources"
    (let [payments {:credits-pay 100 :food-pay 50 :fuel-pay 20}
          after    (expenses/calculate-resources-after-expenses test-player payments)]
      (is (= (- (:player/credits test-player) 100) (:credits after)))
      (is (= (- (:player/food    test-player)  50) (:food    after)))
      (is (= (- (:player/fuel    test-player)  20) (:fuel    after)))))
  (testing "Allows negative resources (overspending is caught by can-afford-expenses?)"
    (let [after (expenses/calculate-resources-after-expenses test-player
                                                             {:credits-pay 9999 :food-pay 0 :fuel-pay 0})]
      (is (neg? (:credits after))))))

(deftest test-calculate-expense-stability-penalty
  (testing "Returns 0 when all expenses fully paid"
    (let [totals {:credits 100 :food 50 :fuel 30}
          paid   {:credits-pay 100 :food-pay 50 :fuel-pay 30}]
      (is (= 0 (expenses/calculate-expense-stability-penalty totals paid {:game/expense-stability-penalty 10})))))
  (testing "Returns 0 when game penalty constant is 0"
    (let [totals {:credits 100 :food 50 :fuel 30}
          paid   {:credits-pay 0 :food-pay 0 :fuel-pay 0}]
      (is (= 0 (expenses/calculate-expense-stability-penalty totals paid {:game/expense-stability-penalty 0})))))
  (testing "Scales with shortfall fraction: one resource fully unpaid = full fraction of penalty"
    ;; credits: required 100, paid 0 → shortfall fraction = 1.0
    ;; food and fuel fully paid → fractions = 0
    ;; total fraction = 1.0, penalty constant = 6 → penalty = 6
    (let [totals {:credits 100 :food 50 :fuel 30}
          paid   {:credits-pay 0 :food-pay 50 :fuel-pay 30}]
      (is (= 6 (expenses/calculate-expense-stability-penalty totals paid {:game/expense-stability-penalty 6})))))
  (testing "Accumulates partial shortfalls across multiple resources"
    ;; credits: 100 req, 50 paid → shortfall = 50/100 = 0.5
    ;; food: 100 req, 75 paid → shortfall = 25/100 = 0.25
    ;; fuel: fully paid → 0
    ;; total fraction = 0.75, penalty = 4 → (long (* 0.75 4)) = 3
    (let [totals {:credits 100 :food 100 :fuel 50}
          paid   {:credits-pay 50 :food-pay 75 :fuel-pay 50}]
      (is (= 3 (expenses/calculate-expense-stability-penalty totals paid {:game/expense-stability-penalty 4}))))))

(deftest test-can-afford-expenses
  (testing "Returns true when all resources are non-negative"
    (is (true?  (expenses/can-afford-expenses? {:credits 0    :food 0   :fuel 0})))
    (is (true?  (expenses/can-afford-expenses? {:credits 1000 :food 500 :fuel 100}))))
  (testing "Returns false when any resource is negative"
    (is (false? (expenses/can-afford-expenses? {:credits -1   :food 0   :fuel 0})))
    (is (false? (expenses/can-afford-expenses? {:credits 100  :food -1  :fuel 0})))
    (is (false? (expenses/can-afford-expenses? {:credits 100  :food 100 :fuel -1})))))

;;
;; Happy path and error/edge-case tests for apply-expenses
;;

(deftest test-apply-expenses-player-not-found
  (testing "Player not found returns 404"
    (with-redefs [xt/entity (fn [_ id] nil)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {}
                 :biff/db nil}
            result (expenses/apply-expenses ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-expenses-phase-check
  (testing "Not phase 2 redirects to game (phase check)"
    (let [player (assoc test-player :player/current-phase 1)]
      (with-redefs [xt/entity (helpers/fake-entity [player])]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params {}
                   :biff/db nil}
              result (expenses/apply-expenses ctx)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

;; This test checks that apply-expenses computes and submits the correct resource and phase update
;; Use atoms and with-redefs to verify that biff/submit-tx is called with expected data
(deftest test-apply-expenses-submits-tx
  (testing "Correct transaction data is submitted and player is advanced to phase 3"
    ;; Test inputs: consolidated three-resource payments.
    ;; credits-pay = 225 (planets) + 18 (soldiers) + 28 (fighters) + 24 (stations) = 295
    ;; food-pay    = 3 (planets) + 27 (soldiers) + 24 (agents) + 18 (population)   = 72
    ;; fuel-pay    = 21 (fighters) + 16 (stations) + 13 (agents) + 63 (population) = 113
    (let [tx-called (atom nil)
          params {:credits-pay "295"
                  :food-pay    "72"
                  :fuel-pay    "113"}
          ;; Expected new resource totals match the original per-category breakdown
          expected-credits (- 1000 295)
          expected-food    (- 800 72)
          expected-fuel    (- 200 113)]
      (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (expenses/apply-expenses ctx)
              tx (first @tx-called)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/building")
                 (get-in result [:headers "location"])))
          (is (= (:player/credits tx) expected-credits))
          (is (= (:player/food tx) expected-food))
          (is (= (:player/fuel tx) expected-fuel))
          (is (= (:player/current-phase tx) 3)))))))

;; Edge case: inputs are blank or missing (should treat as zero)
(deftest test-apply-expenses-missing-params
  (testing "Blank, nil, or missing params handled as zero"
    (let [params {} ;; no params at all
          start-player (assoc test-player :player/current-phase 2)
          tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [start-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}]
          (expenses/apply-expenses ctx)
          (let [tx (first @tx-called)]
            (is (= (:player/credits tx) (:player/credits start-player)))
            (is (= (:player/food tx) (:player/food start-player)))
            (is (= (:player/fuel tx) (:player/fuel start-player)))))))))

;; Input parsing: non-numeric, blank, and weird strings all should be handled as zero
(deftest test-apply-expenses-nonnumeric-param
  (testing "Non-numeric and empty-string params handled as zero"
    (let [params {"planets-pay" "abc" "agents-food" ""}
          player (assoc test-player :player/current-phase 2)
          tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}]
          (expenses/apply-expenses ctx)
          (let [tx (first @tx-called)]
            (is (= (:player/credits tx) (:player/credits player)))
            (is (= (:player/food tx) (:player/food player)))))))))

;; Player ID that isn't a UUID should throw
(deftest test-apply-expenses-bad-uuid
  (testing "Throws exception when player-id is not a valid UUID"
    (let [ctx {:path-params {:player-id "not-a-uuid"}
               :params {}
               :biff/db nil}]
      (is (thrown? IllegalArgumentException (expenses/apply-expenses ctx))))))

;; If the game entity is missing, and player/game is referenced, this will blow up as an NPE or similar;
;; it's reasonable to document this with a test.
(deftest test-apply-expenses-missing-player-game
  (testing "Throws when the player's referenced game does not exist"
    (let [player (assoc test-player :player/game #uuid "12345678-0000-1111-2222-abcdefabcdef")]
      (with-redefs [xt/entity (fn [_ id]
                                (if (= id (:xt/id player)) player nil))]
        (let [ctx {:path-params {:player-id (str (:xt/id player))}
                   :params {}
                   :biff/db nil}]
          (is (thrown? Exception (expenses/apply-expenses ctx))))))))

;;
;; Testing expense calculation logic directly would normally be done for pure fns,
;; but since calculate-expenses renders a hiccup page (and is tied to parameters),
;; we'll check that under sample conditions it returns a vector and that major logic holds.
;;

(deftest test-calculate-expenses-hiccup-render
  (testing "calculate-expenses returns hiccup and handles negative result markup"
    (let [params {"planets-pay" "123"}]
      (with-redefs [utils/load-player-and-game
                    (fn [db player-id-str]
                      {:player test-player
                       :game test-game
                       :player-id test-player-id})
                    biff/render identity]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (expenses/calculate-expenses ctx)]
          ;; Check the basic form of rendering
          (is (vector? result))
          (is (seq result)))))))
