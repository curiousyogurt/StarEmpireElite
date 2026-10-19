(ns com.star-empire-elite.resolution-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.resolution :as resolution]
            [com.star-empire-elite.test-helpers :as helpers]))

;;;;
;;;; Fixtures
;;;;

;;; Uses the production threshold/cap (25/20), not test-helpers' hardcoded 20/25,
;;; since these tests exercise the exact new-constant behavior.
(def game (merge helpers/game-defaults
                  {:game/stability-breakaway-threshold 25
                   :game/stability-breakaway-cap       20}))

(def player
  (merge helpers/player-defaults
         {:player/population   1000000
          :player/credits      10000
          :player/food         5000
          :player/fuel         3000
          :player/soldiers     1000
          :player/transports   10
          :player/fighters     50
          :player/carriers     2
          :player/cmd-ships    1
          :player/stations     5
          :player/generals     5
          :player/admirals     3
          :player/agents       10
          :player/galaxars     1000
          :player/ore-planets  10
          :player/erg-planets  10
          :player/mil-planets  10}))

;;;;
;;;; calculate-stability-breakaway
;;;;

(deftest safe-floor-no-trigger
  (testing "stability at the safe floor (75) never triggers, even on the max roll"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [result (resolution/calculate-stability-breakaway
                     (assoc player :player/stability 75) game)]
        (is (false? (:triggered? result)))))))

(deftest just-below-floor-triggers-minimally
  (testing "one point below the safe floor, only the max roll triggers, for a minimal loss"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [result (resolution/calculate-stability-breakaway
                     (assoc player :player/stability 74) game)]
        (is (true? (:triggered? result)))
        (is (= 0.01 (:fraction result)))
        (is (= 1 (:total-lost result)))
        (is (= 100 (get-in result [:resources-lost :player/credits])))))))

(deftest zero-stability-caps-at-max-loss
  (testing "at 0 stability, a high roll is capped at the 20% max loss"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [result (resolution/calculate-stability-breakaway
                     (assoc player :player/stability 0) game)]
        (is (true? (:triggered? result)))
        (is (= 0.2 (:fraction result)))
        (is (= 6 (:total-lost result)) "20% of 30 planets")
        (is (= 2000 (get-in result [:resources-lost :player/credits])))
        (is (= 200000 (get-in result [:resources-lost :player/population])))
        (is (not (contains? (:resources-lost result) :player/agents))
            "agents are the espionage track, not a breakaway loss")
        (is (not (contains? (:resources-lost result) :player/galaxars))
            "galaxars is a premium resource, excluded from breakaway")))))

;;;;
;;;; breakaway-player-updates (private — accessed via var for testing)
;;;;

(deftest player-updates-subtract-losses-and-skip-excluded-fields
  (testing "breakaway-player-updates subtracts every loss and leaves agents/galaxars untouched"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [stability0 (assoc player :player/stability 0)
            result     (resolution/calculate-stability-breakaway stability0 game)
            updates    (#'resolution/breakaway-player-updates stability0 result)]
        (is (= (- 10000 2000) (:player/credits updates)))
        (is (= (- 1000000 200000) (:player/population updates)))
        (is (= (- 10 (:ore-lost result)) (:player/ore-planets updates)))
        (is (not (contains? updates :player/agents)))
        (is (not (contains? updates :player/galaxars)))))))
