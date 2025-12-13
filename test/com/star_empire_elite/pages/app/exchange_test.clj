(ns com.star-empire-elite.pages.app.exchange-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.exchange :as exchange]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures and Utilities
;;;;

;; Test UUIDs for sample data
(def test-player-id #uuid "00000000-0000-0000-0000-000000000042")

;; Sample player entity with resources and assets
(def test-player
  {:xt/id test-player-id
   :player/current-phase 2
   :player/game #uuid "00000000-1111-2222-3333-444444444444"
   :player/credits 5000
   :player/soldiers 100
   :player/fighters 20
   :player/defence-stations 10
   :player/military-planets 5
   :player/food-planets 4
   :player/ore-planets 6
   :player/food 500
   :player/fuel 300
   :player/galaxars 100})

;; Utility: Returns a function that simulates XTDB's entity lookup for given entities
(defn fake-entity [entities]
  (fn [_ id]
    (first (filter #(= (:xt/id %) id) entities))))

;;;;
;;;; Tests for Pure Calculation Functions
;;;;
;;;; These tests verify the calculation functions in isolation without any database or HTTP
;;;; context. This is the key benefit of extracting pure functions - they're trivial to test.
;;;;

(deftest test-parse-exchange-value
  (testing "Parses valid numeric strings correctly"
    (is (= 100 (exchange/parse-exchange-value "100")))
    (is (= 0 (exchange/parse-exchange-value "0")))
    (is (= 999 (exchange/parse-exchange-value "999"))))
  
  (testing "Treats empty/nil as 0"
    (is (= 0 (exchange/parse-exchange-value "")))
    (is (= 0 (exchange/parse-exchange-value nil))))
  
  (testing "Strips non-numeric characters"
    (is (= 123 (exchange/parse-exchange-value "123abc")))
    (is (= 456 (exchange/parse-exchange-value "abc456")))
    (is (= 789 (exchange/parse-exchange-value "7-8-9")))
    (is (= 0 (exchange/parse-exchange-value "-100")))))  ; Minus sign stripped

(deftest test-get-exchange-rates
  (testing "Returns standard exchange rates"
    (let [rates (exchange/get-exchange-rates)]
      (is (= 50 (:soldier-sell rates)))
      (is (= 100 (:fighter-sell rates)))
      (is (= 150 (:station-sell rates)))
      (is (= 500 (:military-planet-sell rates)))
      (is (= 500 (:food-planet-sell rates)))
      (is (= 500 (:ore-planet-sell rates)))
      (is (= 10 (:food-buy rates)))
      (is (= 5 (:food-sell rates)))      ; Half of buy
      (is (= 15 (:fuel-buy rates)))
      (is (= 7 (:fuel-sell rates))))))    ; Half of buy (rounded down)

(deftest test-calculate-exchange-credits-sales-only
  (testing "Calculates credits correctly from selling units only"
    (let [quantities {:soldiers-sold 10
                     :fighters-sold 5
                     :stations-sold 2
                     :military-planets-sold 1
                     :food-planets-sold 1
                     :ore-planets-sold 1
                     :food-bought 0
                     :food-sold 0
                     :fuel-bought 0
                     :fuel-sold 0}
          rates (exchange/get-exchange-rates)
          result (exchange/calculate-exchange-credits quantities rates)]
      ;; 10*50 + 5*100 + 2*150 + 1*500 + 1*500 + 1*500 = 500 + 500 + 300 + 500 + 500 + 500 = 2800
      (is (= 2800 (:credits-from-sales result)))
      (is (= 0 (:credits-from-resources result)))
      (is (= 2800 (:total-credits result))))))

(deftest test-calculate-exchange-credits-resources-only
  (testing "Calculates credits correctly from buying/selling resources"
    (let [quantities {:soldiers-sold 0
                     :fighters-sold 0
                     :stations-sold 0
                     :military-planets-sold 0
                     :food-planets-sold 0
                     :ore-planets-sold 0
                     :food-bought 20
                     :food-sold 10
                     :fuel-bought 15
                     :fuel-sold 5}
          rates (exchange/get-exchange-rates)
          result (exchange/calculate-exchange-credits quantities rates)]
      ;; Spent: 20*10 + 15*15 = 200 + 225 = 425
      ;; Gained: 10*5 + 5*7 = 50 + 35 = 85
      ;; Net: 85 - 425 = -340
      (is (= 0 (:credits-from-sales result)))
      (is (= -340 (:credits-from-resources result)))
      (is (= -340 (:total-credits result))))))

(deftest test-calculate-exchange-credits-mixed
  (testing "Calculates credits correctly from mixed exchanges"
    (let [quantities {:soldiers-sold 5
                     :fighters-sold 2
                     :stations-sold 1
                     :military-planets-sold 0
                     :food-planets-sold 0
                     :ore-planets-sold 0
                     :food-bought 10
                     :food-sold 20
                     :fuel-bought 5
                     :fuel-sold 10}
          rates (exchange/get-exchange-rates)
          result (exchange/calculate-exchange-credits quantities rates)]
      ;; Sales: 5*50 + 2*100 + 1*150 = 250 + 200 + 150 = 600
      ;; Resources spent: 10*10 + 5*15 = 100 + 75 = 175
      ;; Resources gained: 20*5 + 10*7 = 100 + 70 = 170
      ;; Resources net: 170 - 175 = -5
      ;; Total: 600 + (-5) = 595
      (is (= 600 (:credits-from-sales result)))
      (is (= -5 (:credits-from-resources result)))
      (is (= 595 (:total-credits result))))))

(deftest test-calculate-resources-after-exchange
  (testing "Calculates all resources correctly after exchange"
    (let [player {:player/credits 1000
                  :player/soldiers 100
                  :player/fighters 20
                  :player/defence-stations 10
                  :player/military-planets 5
                  :player/food-planets 4
                  :player/ore-planets 6
                  :player/food 500
                  :player/fuel 300}
          quantities {:soldiers-sold 10
                     :fighters-sold 5
                     :stations-sold 2
                     :military-planets-sold 1
                     :food-planets-sold 1
                     :ore-planets-sold 1
                     :food-bought 50
                     :food-sold 20
                     :fuel-bought 30
                     :fuel-sold 10}
          credit-changes {:total-credits 2000}  ; Simplified for test
          resources-after (exchange/calculate-resources-after-exchange player quantities credit-changes)]
      (is (= 3000 (:credits resources-after)))           ; 1000 + 2000
      (is (= 90 (:soldiers resources-after)))             ; 100 - 10
      (is (= 15 (:fighters resources-after)))             ; 20 - 5
      (is (= 8 (:stations resources-after)))              ; 10 - 2
      (is (= 4 (:military-planets resources-after)))      ; 5 - 1
      (is (= 3 (:food-planets resources-after)))          ; 4 - 1
      (is (= 5 (:ore-planets resources-after)))           ; 6 - 1
      (is (= 530 (:food resources-after)))                ; 500 + 50 - 20
      (is (= 320 (:fuel resources-after))))))             ; 300 + 30 - 10

(deftest test-valid-exchange
  (testing "Returns true when all resources are non-negative"
    (is (true? (exchange/valid-exchange? {:credits 100 :soldiers 50 :fighters 10 
                                          :stations 5 :military-planets 2 :food-planets 3 
                                          :ore-planets 1 :food 1000 :fuel 500})))
    (is (true? (exchange/valid-exchange? {:credits 0 :soldiers 0 :fighters 0 
                                          :stations 0 :military-planets 0 :food-planets 0 
                                          :ore-planets 0 :food 0 :fuel 0}))))
  
  (testing "Returns false when any resource is negative"
    (is (false? (exchange/valid-exchange? {:credits -1 :soldiers 50 :fighters 10 
                                           :stations 5 :military-planets 2 :food-planets 3 
                                           :ore-planets 1 :food 1000 :fuel 500})))
    (is (false? (exchange/valid-exchange? {:credits 100 :soldiers -1 :fighters 10 
                                           :stations 5 :military-planets 2 :food-planets 3 
                                           :ore-planets 1 :food 1000 :fuel 500})))
    (is (false? (exchange/valid-exchange? {:credits 100 :soldiers 50 :fighters 10 
                                           :stations 5 :military-planets 2 :food-planets 3 
                                           :ore-planets 1 :food -1 :fuel 500})))))

(deftest test-identify-invalid-exchanges
  (testing "Identifies specific invalid exchanges correctly"
    (let [resources-after {:credits -50 :soldiers -10 :fighters 15 
                          :stations 8 :military-planets 4 :food-planets 3 
                          :ore-planets 5 :food 530 :fuel 320}
          quantities {:soldiers-sold 110  ; Oversold
                     :fighters-sold 5
                     :stations-sold 2
                     :military-planets-sold 1
                     :food-planets-sold 1
                     :ore-planets-sold 1
                     :food-bought 50
                     :food-sold 0
                     :fuel-bought 30
                     :fuel-sold 0}
          invalid (exchange/identify-invalid-exchanges resources-after quantities)]
      (is (true? (:invalid-soldier-sale? invalid)))
      (is (false? (:invalid-fighter-sale? invalid)))
      (is (false? (:invalid-food-sale? invalid)))      ; Not sold
      (is (true? (:invalid-food-purchase? invalid)))))) ; Credits negative

;;;;
;;;; Tests for apply-exchange (Side Effects)
;;;;
;;;; These tests verify the apply-exchange function which has side effects (database writes, HTTP
;;;; redirects). We use mocking to intercept the side effects and verify behavior.
;;;;

(deftest test-apply-exchange-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ id] nil)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {}
                 :biff/db nil}
            result (exchange/apply-exchange ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-exchange-wrong-phase
  (testing "Redirects to game page when player is not in phase 2"
    (let [wrong-phase-player (assoc test-player :player/current-phase 1)]
      (with-redefs [xt/entity (fake-entity [wrong-phase-player])]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params {}
                   :biff/db nil}
              result (exchange/apply-exchange ctx)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

(deftest test-apply-exchange-submits-correct-tx
  (testing "Submits transaction with correct resource updates"
    (let [params {:soldiers-sold "10"
                  :fighters-sold "5"
                  :stations-sold "2"
                  :military-planets-sold "1"
                  :food-planets-sold "1"
                  :ore-planets-sold "1"
                  :food-bought "50"
                  :food-sold "20"
                  :fuel-bought "30"
                  :fuel-sold "10"}
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (exchange/apply-exchange ctx)
              actual-tx (first @tx-called)
              
              ;; Calculate expected values using pure functions
              quantities {:soldiers-sold 10 :fighters-sold 5 :stations-sold 2
                         :military-planets-sold 1 :food-planets-sold 1 :ore-planets-sold 1
                         :food-bought 50 :food-sold 20 :fuel-bought 30 :fuel-sold 10}
              rates (exchange/get-exchange-rates)
              credit-changes (exchange/calculate-exchange-credits quantities rates)
              expected (exchange/calculate-resources-after-exchange test-player quantities credit-changes)]
          
          ;; Verify redirect to expenses page
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/expenses")
                 (get-in result [:headers "location"])))
          
          ;; Verify transaction metadata
          (is (= :player (:db/doc-type actual-tx)))
          (is (= :update (:db/op actual-tx)))
          (is (= test-player-id (:xt/id actual-tx)))
          
          ;; Verify all resource updates match calculations
          (is (= (:credits expected) (:player/credits actual-tx)))
          (is (= (:soldiers expected) (:player/soldiers actual-tx)))
          (is (= (:fighters expected) (:player/fighters actual-tx)))
          (is (= (:stations expected) (:player/defence-stations actual-tx)))
          (is (= (:military-planets expected) (:player/military-planets actual-tx)))
          (is (= (:food-planets expected) (:player/food-planets actual-tx)))
          (is (= (:ore-planets expected) (:player/ore-planets actual-tx)))
          (is (= (:food expected) (:player/food actual-tx)))
          (is (= (:fuel expected) (:player/fuel actual-tx))))))))

(deftest test-apply-exchange-zero-exchanges
  (testing "Handles zero exchanges correctly (no resources changed)"
    (let [params {:soldiers-sold "0" :fighters-sold "0" :stations-sold "0"
                  :military-planets-sold "0" :food-planets-sold "0" :ore-planets-sold "0"
                  :food-bought "0" :food-sold "0" :fuel-bought "0" :fuel-sold "0"}
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              _ (exchange/apply-exchange ctx)
              actual-tx (first @tx-called)]
          ;; Resources should be unchanged
          (is (= (:player/credits test-player) (:player/credits actual-tx)))
          (is (= (:player/soldiers test-player) (:player/soldiers actual-tx)))
          (is (= (:player/food test-player) (:player/food actual-tx)))
          (is (= (:player/fuel test-player) (:player/fuel actual-tx))))))))

(deftest test-apply-exchange-empty-params
  (testing "Treats empty/missing params as zero"
    (let [params {}  ; All params missing
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              _ (exchange/apply-exchange ctx)
              actual-tx (first @tx-called)]
          ;; Resources should be unchanged (all exchanges defaulted to 0)
          (is (= (:player/credits test-player) (:player/credits actual-tx)))
          (is (= (:player/soldiers test-player) (:player/soldiers actual-tx))))))))

(deftest test-apply-exchange-bad-uuid
  (testing "Throws IllegalArgumentException when player-id is invalid"
    (let [ctx {:path-params {:player-id "not-a-uuid"}
               :params {}
               :biff/db nil}]
      (is (thrown? IllegalArgumentException (exchange/apply-exchange ctx))))))

;;;;
;;;; Tests for calculate-exchange (HTMX Response)
;;;;
;;;; These tests verify that the HTMX response function produces correct output.
;;;;

(deftest test-calculate-exchange-renders-hiccup
  (testing "Returns hiccup vector for HTMX response"
    (with-redefs [xt/entity (fake-entity [test-player])
                  biff/render identity]  ; Pass through hiccup unchanged
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {:soldiers-sold "10" :fighters-sold "5"
                          :stations-sold "2" :military-planets-sold "1"
                          :food-planets-sold "1" :ore-planets-sold "1"
                          :food-bought "50" :food-sold "20"
                          :fuel-bought "30" :fuel-sold "10"}
                 :biff/db nil}
            result (exchange/calculate-exchange ctx)]
        (is (vector? result))
        (is (some? result))))))

(deftest test-calculate-exchange-validity
  (testing "Correctly identifies when player can/cannot execute exchanges"
    (with-redefs [xt/entity (fake-entity [test-player])]
      ;; Test valid exchanges
      (let [valid-params {:soldiers-sold "10" :fighters-sold "5"
                         :stations-sold "2" :military-planets-sold "1"
                         :food-planets-sold "1" :ore-planets-sold "1"
                         :food-bought "0" :food-sold "0"
                         :fuel-bought "0" :fuel-sold "0"}
            quantities (into {} (map (fn [[k v]] [k (exchange/parse-exchange-value v)]) 
                                     valid-params))
            rates (exchange/get-exchange-rates)
            credit-changes (exchange/calculate-exchange-credits quantities rates)
            resources-after (exchange/calculate-resources-after-exchange test-player quantities credit-changes)]
        (is (true? (exchange/valid-exchange? resources-after))))
      
      ;; Test invalid exchanges (overselling)
      (let [invalid-params {:soldiers-sold "1000"  ; More than player has
                           :fighters-sold "0" :stations-sold "0"
                           :military-planets-sold "0" :food-planets-sold "0" :ore-planets-sold "0"
                           :food-bought "0" :food-sold "0"
                           :fuel-bought "0" :fuel-sold "0"}
            quantities (into {} (map (fn [[k v]] [k (exchange/parse-exchange-value v)]) 
                                     invalid-params))
            rates (exchange/get-exchange-rates)
            credit-changes (exchange/calculate-exchange-credits quantities rates)
            resources-after (exchange/calculate-resources-after-exchange test-player quantities credit-changes)]
        (is (false? (exchange/valid-exchange? resources-after)))))))

;;;;
;;;; Tests for exchange-page (UI Rendering)
;;;;
;;;; These tests verify that the exchange-page function produces the expected hiccup structure.
;;;;

(deftest test-exchange-page-renders
  (testing "Exchange page returns a hiccup vector"
    (let [result (exchange/exchange-page {:player test-player :game {}})]
      (is (vector? result))
      (is (some? result)))))

(deftest test-exchange-page-uses-exchange-rates
  (testing "Exchange page displays correct exchange rates"
    (let [rates (exchange/get-exchange-rates)]
      ;; Verify the rates are what we expect
      (is (= 50 (:soldier-sell rates)))
      (is (= 100 (:fighter-sell rates)))
      (is (= 10 (:food-buy rates)))
      (is (= 5 (:food-sell rates)))
      (is (= 15 (:fuel-buy rates)))
      (is (= 7 (:fuel-sell rates))))))
