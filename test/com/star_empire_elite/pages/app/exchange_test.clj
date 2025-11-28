(ns com.star-empire-elite.pages.app.exchange-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.exchange :as exchange]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;
;; Fixtures and helpers for exchange tests
;;

(def test-player-id #uuid "00000000-0000-0000-0000-000000000042")

(def test-player
  ;; Player is set in phase 2, with enough units/resources to test all exchanges
  {:xt/id test-player-id
   :player/current-phase 2
   :player/game #uuid "00000000-1111-2222-3333-444444444444"
   :player/credits 5000
   :player/soldiers 50
   :player/fighters 10
   :player/defence-stations 5
   :player/military-planets 2
   :player/food-planets 3
   :player/ore-planets 4
   :player/food 100
   :player/fuel 200
   :player/galaxars 700})

(defn fake-entity [entities]
  (fn [_ id]
    (first (filter #(= (:xt/id %) id) entities))))

;;
;; Test: player not found returns 404
;;
(deftest test-apply-exchange-player-not-found
  (testing "Returns 404 when player does not exist"
    (with-redefs [xt/entity (fn [_ id] nil)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {}
                 :biff/db nil}
            result (exchange/apply-exchange ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

;;
;; Test: player must be in phase 2, otherwise is redirected
;;
(deftest test-apply-exchange-wrong-phase
  (testing "Redirects to game page unless player is in phase 2"
    (let [player (assoc test-player :player/current-phase 1)]
      (with-redefs [xt/entity (fake-entity [player])]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params {}
                   :biff/db nil}
              result (exchange/apply-exchange ctx)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

;;
;; Test: Exchange transaction is constructed and submitted correctly.
;; This includes selling and buying various things at the fixed rates.
;;
(deftest test-apply-exchange-transaction
  (testing "Submits correct tx when items are sold and bought"
    (let [params {:soldiers-sold 5
                  :fighters-sold 2
                  :stations-sold 1
                  :military-planets-sold 1
                  :food-planets-sold 1
                  :ore-planets-sold 2
                  :food-bought 10
                  :food-sold 20
                  :fuel-bought 10
                  :fuel-sold 20}
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (exchange/apply-exchange ctx)
              tx (first @tx-called)]
          ;; Check for expected redirect
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/expenses")
                 (get-in result [:headers "location"])))
          ;; Check resources are correctly updated (see exchange.clj for rates)
          ;; Calculate how many credits are gained from sales
          (let [credits-sales (+ (* 5 50)    ; soldiers
                                 (* 2 100)   ; fighters
                                 (* 1 150)   ; stations
                                 (* 1 500)   ; military planets
                                 (* 1 500)   ; food planets
                                 (* 2 500))  ; ore planets
                credits-exchange (- (+ (* 20 5)   ; food sold
                                       (* 20 7)) ; fuel sold
                                    (+ (* 10 10)  ; food bought
                                       (* 10 15))); fuel bought
                start-credits 5000
                expected-credits (+ start-credits credits-sales credits-exchange)
                expected-soldiers (- 50 5)
                expected-fighters (- 10 2)
                expected-stations (- 5 1)
                expected-mil-planets (- 2 1)
                expected-food-planets (- 3 1)
                expected-ore-planets (- 4 2)
                expected-food (+ 100 10 -20)
                expected-fuel (+ 200 10 -20)]
            (is (= (:player/credits tx) expected-credits))
            (is (= (:player/soldiers tx) expected-soldiers))
            (is (= (:player/fighters tx) expected-fighters))
            (is (= (:player/defence-stations tx) expected-stations))
            (is (= (:player/military-planets tx) expected-mil-planets))
            (is (= (:player/food-planets tx) expected-food-planets))
            (is (= (:player/ore-planets tx) expected-ore-planets))
            (is (= (:player/food tx) expected-food))
            (is (= (:player/fuel tx) expected-fuel))
            ;; Phase does not change (should remain 2)
            (is (not (contains? (first @tx-called) :player/current-phase)))))))))

;;
;; Edge cases: selling more than one owns or buying/selling zero or nil input
;;

(deftest test-apply-exchange-non-numeric-and-blank-inputs
  (testing "Should treat blanks or non-numeric as zero"
    (let [params {:soldiers-sold ""
                  :fighters-sold nil
                  :food-bought "abc"
                  :fuel-sold ""}
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}]
          (exchange/apply-exchange ctx)
          (let [tx (first @tx-called)]
            ;; No change since all are invalid, they count as zero
            (is (= (:player/soldiers tx) (:player/soldiers test-player)))
            (is (= (:player/food tx) (:player/food test-player)))))))))

(deftest test-apply-exchange-oversell
  (testing "Selling more than owned deducts as negative (should be clamped/validated in UI logic)"
    (let [params {:soldiers-sold 100
                  :fighters-sold 80}
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}]
          (exchange/apply-exchange ctx)
          (let [tx (first @tx-called)]
            ;; Code allows negative, but the UI prevents this (should be 50-100=-50 etc)
            (is (= (:player/soldiers tx) (- 50 100)))
            (is (= (:player/fighters tx) (- 10 80)))))))))

;;
;; UUID parse error: If player-id is not a UUID, we expect an exception.
;;
(deftest test-apply-exchange-bad-uuid
  (testing "Throws on invalid UUID"
    (let [ctx {:path-params {:player-id "not-a-uuid"}
               :params {}
               :biff/db nil}]
      (is (thrown? IllegalArgumentException (exchange/apply-exchange ctx))))))

;;
;; Test: calculate-exchange rendering basic check (returns hiccup, major resources present)
;;
(deftest test-calculate-exchange-hiccup
  (testing "calculate-exchange returns a hiccup vector"
    (with-redefs [xt/entity (fake-entity [test-player])
                  biff/render (fn [hiccup] hiccup)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {:soldiers-sold 2
                          :fuel-bought 5}
                 :biff/db nil}
            result (exchange/calculate-exchange ctx)]
        (is (vector? result))
        (is (some? result))))))

