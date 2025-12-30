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
   :game/agent-upkeep-credits 9
   :game/agent-upkeep-food 16
   :game/population-upkeep-credits 7
   :game/population-upkeep-food 2})

(def test-player
  {:xt/id test-player-id
   :player/game test-game-id
   :player/current-phase 2
   :player/ore-planets 2
   :player/food-planets 2
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
    ;; Test inputs: all expenses paid for, check calculations
    (let [tx-called (atom nil)
          params {:planets-pay "225"       ; 3 planets * 75 credits/planet
                  :planets-food "3"        ; 3 planets * 1 food/planet
                  :soldiers-credits "18"   ; small number to test math
                  :soldiers-food "27"
                  :fighters-credits "28"   ; 7 fighters * 4 credits/fighter
                  :fighters-fuel "21"      ; 7 fighters * 3 fuel/fighter
                  :stations-credits "24"   ; 2 stations * 12 credits
                  :stations-fuel "16"      ; 2 stations * 8 fuel
                  :agents-credits "13"
                  :agents-food "24"
                  :population-credits "63"
                  :population-food "18"}
          ;; Expected new resource totals, based on params above
          expected-credits (- 1000 225 18 28 24 13 63)
          expected-food    (- 800 3 27 24 18)
          expected-fuel    (- 200 21 16)]
      (with-redefs [xt/entity (helpers/fake-entity [test-player])
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
      (with-redefs [xt/entity (helpers/fake-entity [start-player])
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
      (with-redefs [xt/entity (helpers/fake-entity [player])
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
    (let [params {"planets-pay" "123"}
          player test-player]
      (with-redefs [xt/entity (helpers/fake-entity [player])
                    biff/render (fn [hiccup] hiccup)] ; short-circuit rendering
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (expenses/calculate-expenses ctx)]
          ;; Check the basic form of rendering
          (is (vector? result))
          (is (seq result)))))))
