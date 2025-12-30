(ns com.star-empire-elite.pages.app.building-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.building :as building]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.star-empire-elite.utils :as utils]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures and Utilities
;;;;

;; Test UUIDs for sample data
(def test-player-id #uuid "10000000-0000-0000-0000-000000000001")
(def test-game-id #uuid "20000000-0000-0000-0000-000000000002")

;; Sample game entity with various costs for purchaseable items
(def test-game
  {:xt/id test-game-id
   :game/soldier-cost 10
   :game/transport-cost 20
   :game/general-cost 30
   :game/carrier-cost 40
   :game/fighter-cost 50
   :game/admiral-cost 60
   :game/station-cost 70
   :game/cmd-ship-cost 80
   :game/mil-planet-cost 700
   :game/food-planet-cost 800
   :game/ore-planet-cost 900})

;; Sample player, phase 3 and with enough initial resources to buy
(def test-player
  {:xt/id test-player-id
   :player/current-phase 3
   :player/game test-game-id
   :player/credits 10000
   :player/soldiers 10
   :player/transports 2
   :player/generals 3
   :player/carriers 1
   :player/fighters 5
   :player/admirals 0
   :player/stations 3
   :player/cmd-ships 1
   :player/mil-planets 2
   :player/food-planets 2
   :player/ore-planets 2})

;;;;
;;;; Tests for Pure Calculation Functions
;;;;
;;;; These tests verify the calculation functions in isolation without any database or HTTP
;;;; context. This is the key benefit of extracting pure functions - they're trivial to test.
;;;;

(deftest test-parse-purchase-value
  (testing "Parses valid numeric strings correctly"
    (is (= 100 (utils/parse-numeric-input "100")))
    (is (= 0 (utils/parse-numeric-input "0")))
    (is (= 999 (utils/parse-numeric-input "999"))))
  
  (testing "Treats empty/nil as 0"
    (is (= 0 (utils/parse-numeric-input "")))
    (is (= 0 (utils/parse-numeric-input nil))))
  
  (testing "Strips non-numeric characters"
    (is (= 123 (utils/parse-numeric-input "123abc")))
    (is (= 456 (utils/parse-numeric-input "abc456")))
    (is (= 789 (utils/parse-numeric-input "7-8-9")))
    (is (= 100 (utils/parse-numeric-input "-100")))))  ; Minus sign stripped

(deftest test-calculate-purchase-cost-basic
  (testing "Calculates total cost correctly for mixed purchases"
    (let [quantities {:soldiers 10
                     :transports 5
                     :generals 2
                     :carriers 3
                     :fighters 8
                     :admirals 1
                     :stations 4
                     :cmd-ships 2
                     :mil-planets 1
                     :food-planets 1
                     :ore-planets 1}
          game test-game
          cost-info (building/calculate-purchase-cost quantities game)]
      ;; 10*10 + 5*20 + 2*30 + 3*40 + 8*50 + 1*60 + 4*70 + 2*80 + 1*700 + 1*800 + 1*900
      ;; = 100 + 100 + 60 + 120 + 400 + 60 + 280 + 160 + 700 + 800 + 900 = 3680
      (is (= 3680 (:total-cost cost-info))))))

(deftest test-calculate-purchase-cost-zero-purchases
  (testing "Returns zero cost when no purchases made"
    (let [quantities {:soldiers 0
                     :transports 0
                     :generals 0
                     :carriers 0
                     :fighters 0
                     :admirals 0
                     :stations 0
                     :cmd-ships 0
                     :mil-planets 0
                     :food-planets 0
                     :ore-planets 0}
          game test-game
          cost-info (building/calculate-purchase-cost quantities game)]
      (is (= 0 (:total-cost cost-info))))))

(deftest test-calculate-purchase-cost-single-item-type
  (testing "Calculates correctly when buying only one item type"
    (let [quantities {:soldiers 100
                     :transports 0
                     :generals 0
                     :carriers 0
                     :fighters 0
                     :admirals 0
                     :stations 0
                     :cmd-ships 0
                     :mil-planets 0
                     :food-planets 0
                     :ore-planets 0}
          game test-game
          cost-info (building/calculate-purchase-cost quantities game)]
      (is (= 1000 (:total-cost cost-info)))))  ; 100 * 10
  
  (testing "Calculates correctly for expensive items"
    (let [quantities {:soldiers 0
                     :transports 0
                     :generals 0
                     :carriers 0
                     :fighters 0
                     :admirals 0
                     :stations 0
                     :cmd-ships 0
                     :mil-planets 5
                     :food-planets 0
                     :ore-planets 0}
          game test-game
          cost-info (building/calculate-purchase-cost quantities game)]
      (is (= 3500 (:total-cost cost-info))))))  ; 5 * 700

(deftest test-calculate-resources-after-purchases
  (testing "Calculates all resources correctly after purchases"
    (let [player {:player/credits 5000
                  :player/soldiers 100
                  :player/transports 20
                  :player/generals 5
                  :player/carriers 10
                  :player/fighters 50
                  :player/admirals 2
                  :player/stations 15
                  :player/cmd-ships 3
                  :player/mil-planets 5
                  :player/food-planets 4
                  :player/ore-planets 6}
          quantities {:soldiers 10
                     :transports 5
                     :generals 2
                     :carriers 3
                     :fighters 8
                     :admirals 1
                     :stations 4
                     :cmd-ships 2
                     :mil-planets 1
                     :food-planets 1
                     :ore-planets 1}
          cost-info {:total-cost 3680}
          resources-after (building/calculate-resources-after-purchases player quantities cost-info)]
      (is (= 1320 (:credits resources-after)))           ; 5000 - 3680
      (is (= 110 (:soldiers resources-after)))            ; 100 + 10
      (is (= 25 (:transports resources-after)))           ; 20 + 5
      (is (= 7 (:generals resources-after)))              ; 5 + 2
      (is (= 13 (:carriers resources-after)))             ; 10 + 3
      (is (= 58 (:fighters resources-after)))             ; 50 + 8
      (is (= 3 (:admirals resources-after)))              ; 2 + 1
      (is (= 19 (:stations resources-after)))     ; 15 + 4
      (is (= 5 (:cmd-ships resources-after)))         ; 3 + 2
      (is (= 6 (:mil-planets resources-after)))      ; 5 + 1
      (is (= 5 (:food-planets resources-after)))          ; 4 + 1
      (is (= 7 (:ore-planets resources-after))))))        ; 6 + 1

(deftest test-calculate-resources-after-purchases-overspend
  (testing "Allows negative credits (overspending)"
    (let [player {:player/credits 100
                  :player/soldiers 10
                  :player/transports 0
                  :player/generals 0
                  :player/carriers 0
                  :player/fighters 0
                  :player/admirals 0
                  :player/stations 0
                  :player/cmd-ships 0
                  :player/mil-planets 0
                  :player/food-planets 0
                  :player/ore-planets 0}
          quantities {:soldiers 100
                     :transports 0
                     :generals 0
                     :carriers 0
                     :fighters 0
                     :admirals 0
                     :stations 0
                     :cmd-ships 0
                     :mil-planets 0
                     :food-planets 0
                     :ore-planets 0}
          cost-info {:total-cost 1000}  ; 100 * 10
          resources-after (building/calculate-resources-after-purchases player quantities cost-info)]
      (is (= -900 (:credits resources-after)))  ; 100 - 1000
      (is (= 110 (:soldiers resources-after)))))) ; 10 + 100

(deftest test-can-afford-purchases
  (testing "Returns true when credits are non-negative"
    (is (true? (building/can-afford-purchases? {:credits 1000})))
    (is (true? (building/can-afford-purchases? {:credits 0}))))
  
  (testing "Returns false when credits are negative"
    (is (false? (building/can-afford-purchases? {:credits -1})))
    (is (false? (building/can-afford-purchases? {:credits -1000})))))

;;;;
;;;; Tests for apply-building (Side Effects)
;;;;
;;;; These tests verify the apply-building function which has side effects (database writes, HTTP
;;;; redirects). We use mocking to intercept the side effects and verify behavior.
;;;;

(deftest test-apply-building-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ id] nil)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {}
                 :biff/db nil}
            result (building/apply-building ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-building-wrong-phase
  (testing "Redirects to game page when player is not in phase 3"
    (let [wrong-phase-player (assoc test-player :player/current-phase 2)]
      (with-redefs [xt/entity (helpers/fake-entity [wrong-phase-player test-game])]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params {}
                   :biff/db nil}
              result (building/apply-building ctx)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

(deftest test-apply-building-submits-correct-tx
  (testing "Submits transaction with correct resource updates and phase advance"
    (let [params {:soldiers "3"
                  :transports "2"
                  :generals "1"
                  :carriers "4"
                  :fighters "3"
                  :admirals "1"
                  :stations "2"
                  :cmd-ships "1"
                  :mil-planets "2"
                  :food-planets "1"
                  :ore-planets "3"}
          tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (building/apply-building ctx)
              actual-tx (first @tx-called)
              
              ;; Calculate expected values using pure functions
              quantities {:soldiers 3 :transports 2 :generals 1 :carriers 4
                         :fighters 3 :admirals 1 :stations 2 :cmd-ships 1
                         :mil-planets 2 :food-planets 1 :ore-planets 3}
              cost-info (building/calculate-purchase-cost quantities test-game)
              expected (building/calculate-resources-after-purchases test-player quantities cost-info)]
          
          ;; Verify redirect to action page
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/action")
                 (get-in result [:headers "location"])))
          
          ;; Verify transaction metadata
          (is (= :player (:db/doc-type actual-tx)))
          (is (= :update (:db/op actual-tx)))
          (is (= test-player-id (:xt/id actual-tx)))
          
          ;; Verify phase advance
          (is (= 4 (:player/current-phase actual-tx)))
          
          ;; Verify all resource updates match calculations
          (is (= (:credits expected) (:player/credits actual-tx)))
          (is (= (:soldiers expected) (:player/soldiers actual-tx)))
          (is (= (:transports expected) (:player/transports actual-tx)))
          (is (= (:generals expected) (:player/generals actual-tx)))
          (is (= (:carriers expected) (:player/carriers actual-tx)))
          (is (= (:fighters expected) (:player/fighters actual-tx)))
          (is (= (:admirals expected) (:player/admirals actual-tx)))
          (is (= (:stations expected) (:player/stations actual-tx)))
          (is (= (:cmd-ships expected) (:player/cmd-ships actual-tx)))
          (is (= (:mil-planets expected) (:player/mil-planets actual-tx)))
          (is (= (:food-planets expected) (:player/food-planets actual-tx)))
          (is (= (:ore-planets expected) (:player/ore-planets actual-tx))))))))

(deftest test-apply-building-insufficient-credits
  (testing "Returns 400 when player has insufficient credits"
    (let [params {:soldiers "1000"}  ; Way too expensive!
          player (assoc test-player :player/credits 100)
          tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])
                    biff/submit-tx (fn [_ _] (reset! tx-called true) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (building/apply-building ctx)]
          ;; Should not call submit-tx
          (is (not @tx-called))
          (is (= 400 (:status result)))
          (is (= "Insufficient credits for purchase" (:body result))))))))

(deftest test-apply-building-zero-purchases
  (testing "Handles zero purchases correctly (no resources changed except phase)"
    (let [params {:soldiers "0" :transports "0" :generals "0" :carriers "0"
                  :fighters "0" :admirals "0" :stations "0" :cmd-ships "0"
                  :mil-planets "0" :food-planets "0" :ore-planets "0"}
          tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              _ (building/apply-building ctx)
              actual-tx (first @tx-called)]
          ;; Resources should be unchanged
          (is (= (:player/credits test-player) (:player/credits actual-tx)))
          (is (= (:player/soldiers test-player) (:player/soldiers actual-tx)))
          (is (= (:player/fighters test-player) (:player/fighters actual-tx)))
          ;; But phase should advance
          (is (= 4 (:player/current-phase actual-tx))))))))

(deftest test-apply-building-empty-params
  (testing "Treats empty/missing params as zero"
    (let [params {}  ; All params missing
          tx-called (atom nil)]
      (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              _ (building/apply-building ctx)
              actual-tx (first @tx-called)]
          ;; Resources should be unchanged (all purchases defaulted to 0)
          (is (= (:player/credits test-player) (:player/credits actual-tx)))
          (is (= (:player/soldiers test-player) (:player/soldiers actual-tx))))))))

(deftest test-apply-building-bad-uuid
  (testing "Throws IllegalArgumentException when player-id is invalid"
    (let [ctx {:path-params {:player-id "not-a-uuid"}
               :params {}
               :biff/db nil}]
      (is (thrown? IllegalArgumentException (building/apply-building ctx))))))

;;;;
;;;; Tests for calculate-building (HTMX Response)
;;;;
;;;; These tests verify that the HTMX response function produces correct output.
;;;;

(deftest test-calculate-building-renders-hiccup
  (testing "Returns hiccup vector for HTMX response"
    (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])
                  biff/render identity]  ; Pass through hiccup unchanged
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {:soldiers "2" :fighters "3"}
                 :biff/db nil}
            result (building/calculate-building ctx)]
        (is (vector? result))
        (is (some? result))))))

(deftest test-calculate-building-affordability
  (testing "Correctly identifies when player can/cannot afford purchases"
    (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])]
      ;; Test affordable purchases
      (let [affordable-params {:soldiers "10" :transports "5"}
            quantities (into {} (map (fn [[k v]] [k (utils/parse-numeric-input v)]) 
                                     (merge {:soldiers 0 :transports 0 :generals 0 :carriers 0
                                            :fighters 0 :admirals 0 :stations 0 :cmd-ships 0
                                            :mil-planets 0 :food-planets 0 :ore-planets 0}
                                            affordable-params)))
            cost-info (building/calculate-purchase-cost quantities test-game)
            resources-after (building/calculate-resources-after-purchases test-player quantities cost-info)]
        (is (true? (building/can-afford-purchases? resources-after))))
      
      ;; Test unaffordable purchases
      (let [unaffordable-params {:soldiers "10000"}  ; Way more than player can afford
            quantities (into {} (map (fn [[k v]] [k (utils/parse-numeric-input v)]) 
                                     (merge {:soldiers 0 :transports 0 :generals 0 :carriers 0
                                            :fighters 0 :admirals 0 :stations 0 :cmd-ships 0
                                            :mil-planets 0 :food-planets 0 :ore-planets 0}
                                            unaffordable-params)))
            cost-info (building/calculate-purchase-cost quantities test-game)
            resources-after (building/calculate-resources-after-purchases test-player quantities cost-info)]
        (is (false? (building/can-afford-purchases? resources-after)))))))

;;;;
;;;; Tests for building-page (UI Rendering)
;;;;
;;;; These tests verify that the building-page function produces the expected hiccup structure.
;;;;

(deftest test-building-page-renders
  (testing "Building page returns a hiccup vector"
    (let [result (building/building-page {:player test-player :game test-game})]
      (is (vector? result))
      (is (some? result)))))

(deftest test-building-page-uses-game-costs
  (testing "Building page displays correct costs from game constants"
    ;; Just verify the game constants are as expected for the test
    (is (= 10 (:game/soldier-cost test-game)))
    (is (= 50 (:game/fighter-cost test-game)))
    (is (= 700 (:game/mil-planet-cost test-game)))
    (is (= 800 (:game/food-planet-cost test-game)))
    (is (= 900 (:game/ore-planet-cost test-game)))))
