(ns com.star-empire-elite.pages.app.income-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.income :as income]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures and Utilities
;;;;

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
   :game/mil-planet-soldiers 25
   :game/mil-planet-fighters 15
   :game/mil-planet-stations 5
   :game/mil-planet-agents 8})

;; Sample player entity as "before income"
(def test-player
  {:xt/id test-player-id
   :player/empire-name "Test Empire"
   :player/game test-game-id
   :player/current-phase 1
   :player/ore-planets 3
   :player/food-planets 2
   :player/mil-planets 4
   :player/credits 1000
   :player/food 500
   :player/fuel 300
   :player/galaxars 50
   :player/soldiers 100
   :player/fighters 75
   :player/stations 20
   :player/agents 10})

;;;;
;;;; Calculation Tests
;;;;
;;;; These tests verify the calculate-income function in isolation without any database or http
;;;; context.
;;;;

(deftest test-calculate-income-basic
  (testing "Calculates income correctly for standard player with all planet types"
    (let [player {:player/ore-planets 3
                  :player/food-planets 2
                  :player/mil-planets 4}
          game {:game/ore-planet-credits 100
                :game/ore-planet-fuel 50
                :game/ore-planet-galaxars 10
                :game/food-planet-food 200
                :game/mil-planet-soldiers 25
                :game/mil-planet-fighters 15
                :game/mil-planet-stations 5
                :game/mil-planet-agents 8}
          income (income/calculate-income player game)]
      ;; Ore planet income: 3 planets
      (is (= 300 (:ore-credits income)))  ; 3 Ã— 100
      (is (= 150 (:ore-fuel income)))     ; 3 Ã— 50
      (is (= 30 (:ore-galaxars income)))  ; 3 Ã— 10
      ;; Food planet income: 2 planets
      (is (= 400 (:food-food income)))    ; 2 Ã— 200
      ;; Military planet income: 4 planets
      (is (= 100 (:mil-soldiers income))) ; 4 Ã— 25
      (is (= 60 (:mil-fighters income)))  ; 4 Ã— 15
      (is (= 20 (:mil-stations income)))  ; 4 Ã— 5
      (is (= 32 (:mil-agents income)))))) ; 4 Ã— 8

(deftest test-calculate-income-zero-planets
  (testing "Returns zero income when player has no planets"
    (let [player {:player/ore-planets 0
                  :player/food-planets 0
                  :player/mil-planets 0}
          game test-game
          income (income/calculate-income player game)]
      (is (= 0 (:ore-credits income)))
      (is (= 0 (:ore-fuel income)))
      (is (= 0 (:ore-galaxars income)))
      (is (= 0 (:food-food income)))
      (is (= 0 (:mil-soldiers income)))
      (is (= 0 (:mil-fighters income)))
      (is (= 0 (:mil-stations income)))
      (is (= 0 (:mil-agents income))))))

(deftest test-calculate-income-single-planet-type
  (testing "Calculates correctly when player has only one planet type"
    (let [player {:player/ore-planets 5
                  :player/food-planets 0
                  :player/mil-planets 0}
          game test-game
          income (income/calculate-income player game)]
      (is (= 500 (:ore-credits income)))
      (is (= 250 (:ore-fuel income)))
      (is (= 50 (:ore-galaxars income)))
      (is (= 0 (:food-food income)))
      (is (= 0 (:mil-soldiers income))))))

(deftest test-calculate-income-large-numbers
  (testing "Handles large planet counts correctly"
    (let [player {:player/ore-planets 1000
                  :player/food-planets 500
                  :player/mil-planets 250}
          game test-game
          income (income/calculate-income player game)]
      (is (= 100000 (:ore-credits income)))   ; 1000 Ã— 100
      (is (= 50000 (:ore-fuel income)))       ; 1000 Ã— 50
      (is (= 100000 (:food-food income)))     ; 500 Ã— 200
      (is (= 6250 (:mil-soldiers income))))))  ; 250 Ã— 25

(deftest test-calculate-income-zero-rates
  (testing "Returns zero income when game rates are zero"
    (let [player {:player/ore-planets 3
                  :player/food-planets 2
                  :player/mil-planets 4}
          game {:game/ore-planet-credits 0
                :game/ore-planet-fuel 0
                :game/ore-planet-galaxars 0
                :game/food-planet-food 0
                :game/mil-planet-soldiers 0
                :game/mil-planet-fighters 0
                :game/mil-planet-stations 0
                :game/mil-planet-agents 0}
          income (income/calculate-income player game)]
      (is (= 0 (:ore-credits income)))
      (is (= 0 (:food-food income)))
      (is (= 0 (:mil-soldiers income))))))

(deftest test-calculate-income-mixed-rates
  (testing "Handles different income rates per game configuration"
    (let [player {:player/ore-planets 2
                  :player/food-planets 3
                  :player/mil-planets 1}
          game {:game/ore-planet-credits 500
                :game/ore-planet-fuel 100
                :game/ore-planet-galaxars 50
                :game/food-planet-food 1000
                :game/mil-planet-soldiers 100
                :game/mil-planet-fighters 50
                :game/mil-planet-stations 25
                :game/mil-planet-agents 10}
          income (income/calculate-income player game)]
      (is (= 1000 (:ore-credits income)))     ; 2 Ã— 500
      (is (= 200 (:ore-fuel income)))         ; 2 Ã— 100
      (is (= 3000 (:food-food income)))       ; 3 Ã— 1000
      (is (= 100 (:mil-soldiers income)))     ; 1 Ã— 100
      (is (= 50 (:mil-fighters income))))))   ; 1 Ã— 50

;;;;
;;;; Action Tests (apply-income)
;;;;
;;;; These tests verify the apply-income function which has side effects (database writes, http
                                                                                   ;;;; redirects). Mocking intercepts the side effects and verify the behaviour.
;;;;

(deftest test-apply-income-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ id] nil)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :biff/db nil}
            result (income/apply-income ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-income-wrong-phase
  (testing "Redirects to game page when player is not in phase 1"
    (let [wrong-phase-player (assoc test-player :player/current-phase 2)]
      (with-redefs [xt/entity (helpers/fake-entity [wrong-phase-player test-game])]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :biff/db nil}
              result (income/apply-income ctx)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

(deftest test-apply-income-submits-correct-tx
  (testing "Submits transaction with correct resource updates and phase advance"
    (let [tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)} :biff/db nil}
              result (income/apply-income ctx)
              actual-tx (first @tx-called)
              expected-income (income/calculate-income test-player test-game)]
          ;; Verify redirect to expenses page
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/expenses")
                 (get-in result [:headers "location"])))
          ;; Verify transaction metadata
          (is (= :player (:db/doc-type actual-tx)))
          (is (= :update (:db/op actual-tx)))
          (is (= test-player-id (:xt/id actual-tx)))
          ;; Verify phase advance
          (is (= 2 (:player/current-phase actual-tx)))
          ;; Verify all resource updates match calculate-income
          (is (= (+ (:player/credits test-player) (:ore-credits expected-income))
                 (:player/credits actual-tx)))
          (is (= (+ (:player/food test-player) (:food-food expected-income))
                 (:player/food actual-tx)))
          (is (= (+ (:player/fuel test-player) (:ore-fuel expected-income))
                 (:player/fuel actual-tx)))
          (is (= (+ (:player/galaxars test-player) (:ore-galaxars expected-income))
                 (:player/galaxars actual-tx)))
          (is (= (+ (:player/soldiers test-player) (:mil-soldiers expected-income))
                 (:player/soldiers actual-tx)))
          (is (= (+ (:player/fighters test-player) (:mil-fighters expected-income))
                 (:player/fighters actual-tx)))
          (is (= (+ (:player/stations test-player) (:mil-stations expected-income))
                 (:player/stations actual-tx)))
          (is (= (+ (:player/agents test-player) (:mil-agents expected-income))
                 (:player/agents actual-tx))))))))

(deftest test-apply-income-zero-planets
  (testing "Correctly handles player with zero planets (no-op on resources)"
    (let [player (assoc test-player 
                        :player/ore-planets 0 
                        :player/food-planets 0 
                        :player/mil-planets 0)
          tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)} :biff/db nil}
              _ (income/apply-income ctx)
              actual-tx (first @tx-called)]
          ;; Resources should be unchanged (0 + 0 = original value)
          (is (= (:player/credits player)  (:player/credits actual-tx)))
          (is (= (:player/food player)     (:player/food actual-tx)))
          (is (= (:player/fuel player)     (:player/fuel actual-tx)))
          (is (= (:player/galaxars player) (:player/galaxars actual-tx)))
          (is (= (:player/soldiers player) (:player/soldiers actual-tx)))
          (is (= (:player/fighters player) (:player/fighters actual-tx)))
          (is (= (:player/stations player) (:player/stations actual-tx)))
          (is (= (:player/agents player)   (:player/agents actual-tx)))
          ;; But phase should still advance
          (is (= 2 (:player/current-phase actual-tx))))))))

(deftest test-apply-income-bad-uuid
  (testing "Throws IllegalArgumentException when player-id is invalid"
    (let [ctx {:path-params {:player-id "not-a-uuid"} :biff/db nil}]
      (is (thrown? IllegalArgumentException (income/apply-income ctx))))))

(deftest test-apply-income-missing-game
  (testing "Throws NullPointerException when referenced game entity is missing"
    (let [player (assoc test-player :player/game #uuid "12345678-1234-1234-1234-123456789abc")]
      (with-redefs [xt/entity (fn [_ id]
                                (if (= id (:xt/id player))
                                  player
                                  nil))]
        (let [ctx {:path-params {:player-id (str (:xt/id player))} :biff/db nil}]
          (is (thrown? NullPointerException (income/apply-income ctx))))))))

;;;;
;;;; Action Tests (income-page)
;;;;
;;;; These tests verify that the income-page function produces the expected hiccup structure.
;;;;

(deftest test-income-page-renders
  (testing "Income page returns a hiccup vector"
    (let [result (income/income-page {:player test-player :game test-game})]
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-page-uses-calculate-income
  (testing "Income page displays match calculate-income results"
    (let [expected-income (income/calculate-income test-player test-game)
          ;; Calculate expected totals
          expected-credits  (+ (:player/credits test-player)  (:ore-credits expected-income))
          expected-food     (+ (:player/food test-player)     (:food-food expected-income))
          expected-fuel     (+ (:player/fuel test-player)     (:ore-fuel expected-income))
          expected-galaxars (+ (:player/galaxars test-player) (:ore-galaxars expected-income))
          expected-soldiers (+ (:player/soldiers test-player) (:mil-soldiers expected-income))
          expected-fighters (+ (:player/fighters test-player) (:mil-fighters expected-income))
          expected-stations (+ (:player/stations test-player) (:mil-stations expected-income))
          expected-agents   (+ (:player/agents test-player)   (:mil-agents expected-income))]
      ;; Verify expected values (useful for documentation/regression testing)
      (is (= 1300 expected-credits))    ; 1000 + 300
      (is (= 900 expected-food))        ; 500 + 400
      (is (= 450 expected-fuel))        ; 300 + 150
      (is (= 80 expected-galaxars))     ; 50 + 30
      (is (= 200 expected-soldiers))    ; 100 + 100
      (is (= 135 expected-fighters))    ; 75 + 60
      (is (= 40 expected-stations))     ; 20 + 20
      (is (= 42 expected-agents)))))    ; 10 + 32

(deftest test-income-page-zero-planets
  (testing "Income page handles player with zero planets"
    (let [player (assoc test-player 
                        :player/ore-planets 0 
                        :player/food-planets 0 
                        :player/mil-planets 0)
          result (income/income-page {:player player :game test-game})]
      ;; Should render without error
      (is (vector? result))
      ;; Income should be zero
      (let [income (income/calculate-income player test-game)]
        (is (= 0 (:ore-credits income)))
        (is (= 0 (:food-food income)))
        (is (= 0 (:mil-soldiers income)))))))

;;;;
;;;; UI Component Tests (income-row)
;;;;
;;;; These tests verify that the income-row component renders correctly with various inputs.
;;;;

(deftest test-income-row-renders-hiccup
  (testing "Income row returns valid hiccup structure"
    (let [income-map {:credits 500 :fuel 200 :galaxars 100}
          result (income/income-row "Ore" 3 income-map)]
      (is (vector? result))
      (is (= :div.border-b.border-green-400.last:border-b-0 (first result))))))

(deftest test-income-row-handles-all-resources
  (testing "Income row correctly renders all resource types"
    (let [income-map {:credits 500 :fuel 200 :galaxars 100
                      :food 1000 :soldiers 50 :fighters 25
                      :stations 10 :agents 5}
          result (income/income-row "Mil" 4 income-map)]
      ;; Should render without error with all resources present
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-row-handles-zero-values
  (testing "Income row correctly handles zero values in income map"
    (let [income-map {:credits 0 :fuel 0 :galaxars 0 :food 0 
                      :soldiers 0 :fighters 0 :stations 0 :agents 0}
          result (income/income-row "Food" 2 income-map)]
      ;; Should render without error even with all zeros
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-row-handles-missing-keys
  (testing "Income row handles income map with missing keys"
    (let [income-map {:credits 500}  ; Only credits, rest missing
          result (income/income-row "Ore" 1 income-map)]
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-row-handles-nil-values
  (testing "Income row handles nil values gracefully using (or (k income-map) 0)"
    (let [income-map {:credits nil :fuel 200 :galaxars nil}
          result (income/income-row "Ore" 1 income-map)]
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-row-handles-large-numbers
  (testing "Income row handles very large numbers"
    (let [income-map {:credits 1234567890 :fuel 999999999}
          result (income/income-row "Ore" 1000 income-map)]
      ;; Should render without error
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-row-handles-zero-planet-count
  (testing "Income row renders with zero planets"
    (let [income-map {:credits 0 :fuel 0}
          result (income/income-row "Ore" 0 income-map)]
      (is (vector? result))
      (is (some? result)))))

;;;;
;;;; Edge Case Tests for calculate-income
;;;;
;;;; Additional edge cases to ensure robustness.
;;;;

(deftest test-calculate-income-negative-planets
  (testing "Handles negative planet counts (defensive programming)"
    (let [player {:player/ore-planets -1
                  :player/food-planets 0
                  :player/mil-planets 0}
          game test-game
          income (income/calculate-income player game)]
      ;; Negative planets should produce negative income
      (is (= -100 (:ore-credits income)))
      (is (= -50 (:ore-fuel income)))
      (is (= -10 (:ore-galaxars income))))))

(deftest test-calculate-income-very-large-planets
  (testing "Handles very large planet counts without overflow"
    (let [player {:player/ore-planets 100000
                  :player/food-planets 100000
                  :player/mil-planets 100000}
          game test-game
          income (income/calculate-income player game)]
      ;; Should calculate without overflow
      (is (= 10000000 (:ore-credits income)))    ; 100000 × 100
      (is (= 5000000 (:ore-fuel income)))        ; 100000 × 50
      (is (= 20000000 (:food-food income)))      ; 100000 × 200
      (is (= 2500000 (:mil-soldiers income))))))  ; 100000 × 25

(deftest test-calculate-income-fractional-rates
  (testing "Handles fractional income rates from game configuration"
    (let [player {:player/ore-planets 3
                  :player/food-planets 2
                  :player/mil-planets 0}
          game {:game/ore-planet-credits 33.33
                :game/ore-planet-fuel 16.67
                :game/ore-planet-galaxars 5.5
                :game/food-planet-food 250.5
                :game/mil-planet-soldiers 0
                :game/mil-planet-fighters 0
                :game/mil-planet-stations 0
                :game/mil-planet-agents 0}
          income (income/calculate-income player game)]
      ;; Results will be floating point
      (is (< (Math/abs (- 99.99 (:ore-credits income))) 0.01))
      (is (< (Math/abs (- 50.01 (:ore-fuel income))) 0.01))
      (is (< (Math/abs (- 16.5 (:ore-galaxars income))) 0.01))
      (is (< (Math/abs (- 501.0 (:food-food income))) 0.01)))))

;;;;
;;;; Integration Tests
;;;;
;;;; Tests that verify the full income page flow works correctly.
;;;;

(deftest test-income-page-integration
  (testing "Income page correctly integrates calculate-income and displays all data"
    (let [page (income/income-page {:player test-player :game test-game})
          income (income/calculate-income test-player test-game)]
      ;; Page should be valid hiccup
      (is (vector? page))
      ;; Verify income calculations are correct
      (is (= 300 (:ore-credits income)))
      (is (= 150 (:ore-fuel income)))
      (is (= 30 (:ore-galaxars income)))
      (is (= 400 (:food-food income)))
      (is (= 100 (:mil-soldiers income)))
      (is (= 60 (:mil-fighters income)))
      (is (= 20 (:mil-stations income)))
      (is (= 32 (:mil-agents income))))))

(deftest test-income-page-with-extreme-values
  (testing "Income page handles extreme values without breaking"
    (let [extreme-player (assoc test-player
                                :player/ore-planets 999999
                                :player/credits 1234567890
                                :player/food 999999999)
          extreme-game (assoc test-game
                              :game/ore-planet-credits 9999)
          result (income/income-page {:player extreme-player :game extreme-game})]
      ;; Should render without error even with huge numbers
      (is (vector? result))
      (is (some? result)))))
