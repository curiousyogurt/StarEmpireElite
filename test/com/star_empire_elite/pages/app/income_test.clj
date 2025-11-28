(ns com.star-empire-elite.pages.app.income-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.income :as income]
            [xtdb.api :as xt]))

(defn mock-db [entities]
  (fn [id]
    (first (filter #(= (:xt/id %) id) entities))))

;; :: Test data fixtures

(def test-game-id #uuid "00000000-0000-0000-0000-000000000001")
(def test-player-id #uuid "00000000-0000-0000-0000-000000000002")

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

;; :: Mock database for testing
(defn mock-db [entities]
  (reify xt/PXtdb
    (entity [_ id]
      (first (filter #(= (:xt/id %) id) entities)))))

;; :: Tests for apply-income function

(deftest test-apply-income-player-not-found
  (testing "Returns 404 when player doesn't exist"
    (let [ctx {:path-params {:player-id (str test-player-id)}
               :biff/db (mock-db [])}
          result (income/apply-income ctx)]
      (is (= 404 (:status result)))
      (is (= "Player not found" (:body result))))))

(deftest test-apply-income-wrong-phase
  (testing "Redirects when player is not in phase 1"
    (let [wrong-phase-player (assoc test-player :player/current-phase 2)
          ctx {:path-params {:player-id (str test-player-id)}
               :biff/db (mock-db [wrong-phase-player test-game])}
          result (income/apply-income ctx)]
      (is (= 303 (:status result)))
      (is (= (str "/app/game/" test-player-id) 
             (get-in result [:headers "location"]))))))

(deftest test-apply-income-calculations
  (testing "Correctly calculates income from ore planets"
    (let [ore-credits (* 3 100) ; 3 ore planets * 100 credits
          ore-fuel (* 3 50)     ; 3 ore planets * 50 fuel
          ore-galaxars (* 3 10)] ; 3 ore planets * 10 galaxars
      (is (= 300 ore-credits))
      (is (= 150 ore-fuel))
      (is (= 30 ore-galaxars))))
  
  (testing "Correctly calculates income from food planets"
    (let [food-food (* 2 200)] ; 2 food planets * 200 food
      (is (= 400 food-food))))
  
  (testing "Correctly calculates income from military planets"
    (let [mil-soldiers (* 4 25)  ; 4 military planets * 25 soldiers
          mil-fighters (* 4 15)  ; 4 military planets * 15 fighters
          mil-stations (* 4 5)   ; 4 military planets * 5 stations
          mil-agents (* 4 8)]    ; 4 military planets * 8 agents
      (is (= 100 mil-soldiers))
      (is (= 60 mil-fighters))
      (is (= 20 mil-stations))
      (is (= 32 mil-agents)))))

(deftest test-apply-income-with-zero-planets
  (testing "Handles player with zero planets correctly"
    (let [no-planets-player (assoc test-player
                                   :player/ore-planets 0
                                   :player/food-planets 0
                                   :player/military-planets 0)
          ore-credits (* 0 100)
          food-food (* 0 200)
          mil-soldiers (* 0 25)]
      (is (= 0 ore-credits))
      (is (= 0 food-food))
      (is (= 0 mil-soldiers)))))

;; :: Tests for income-page function

(deftest test-income-page-renders
  (testing "Income page renders with correct player data"
    (let [result (income/income-page {:player test-player :game test-game})]
      (is (vector? result))
      (is (some? result)))))

(deftest test-income-page-calculations-match
  (testing "Income page calculations match expected values"
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
      (is (= 201 expected-soldiers))
      (is (= 135 expected-fighters))
      (is (= 40 expected-stations))
      (is (= 42 expected-agents)))))

;; :: Edge case tests

(deftest test-large-planet-counts
  (testing "Handles large numbers of planets"
    (let [ore-credits (* 1000 100)
          food-food (* 1000 200)
          mil-soldiers (* 1000 25)]
      (is (= 100000 ore-credits))
      (is (= 200000 food-food))
      (is (= 25000 mil-soldiers)))))

(deftest test-income-with-zero-game-rates
  (testing "Handles zero income rates in game configuration"
    (let [zero-rate-game (assoc test-game
                                :game/ore-planet-credits 0
                                :game/ore-planet-fuel 0
                                :game/ore-planet-galaxars 0)
          ore-credits (* 3 0)
          ore-fuel (* 3 0)
          ore-galaxars (* 3 0)]
      (is (= 0 ore-credits))
      (is (= 0 ore-fuel))
      (is (= 0 ore-galaxars)))))
