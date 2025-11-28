(ns com.star-empire-elite.pages.app.building-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.building :as building]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;; 
;; Fixtures and helpers for building tests
;;

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
   :game/command-ship-cost 80
   :game/military-planet-cost 700
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
   :player/defence-stations 3
   :player/command-ships 1
   :player/military-planets 2
   :player/food-planets 2
   :player/ore-planets 2})

(defn fake-entity [entities]
  (fn [_ id]
    (first (filter #(= (:xt/id %) id) entities))))

;;
;; Test: player not found returns 404
;;
(deftest test-apply-building-player-not-found
  (testing "Returns 404 when player does not exist"
    (with-redefs [xt/entity (fn [_ id] nil)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {}
                 :biff/db nil}
            result (building/apply-building ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

;;
;; Test: player must be in phase 3, otherwise redirect to game
;;
(deftest test-apply-building-wrong-phase
  (testing "Redirects if not phase 3"
    (let [player (assoc test-player :player/current-phase 2)]
      (with-redefs [xt/entity (fake-entity [player])]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params {}
                   :biff/db nil}
              result (building/apply-building ctx)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

;;
;; Test: transaction applied correctly, purchases deducted from credits, and quantities incremented.
;;
(deftest test-apply-building-transaction
  (testing "Purchases update resources and advance phase"
    (let [params {:soldiers 3
                  :transports 2
                  :generals 1
                  :carriers 4
                  :fighters 3
                  :admirals 1
                  :defence-stations 2
                  :command-ships 1
                  :military-planets 2
                  :food-planets 1
                  :ore-planets 3}
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (building/apply-building ctx)
              tx (first @tx-called)
              ;; Calculate total credits spent based on params and costs
              total-cost (+ (* 3 10)    ;; soldiers
                            (* 2 20)    ;; transports
                            (* 1 30)    ;; generals
                            (* 4 40)    ;; carriers
                            (* 3 50)    ;; fighters
                            (* 1 60)    ;; admirals
                            (* 2 70)    ;; defence-stations
                            (* 1 80)    ;; command-ships
                            (* 2 700)   ;; military-planets
                            (* 1 800)   ;; food-planets
                            (* 3 900))] ;; ore-planets
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/action")
                 (get-in result [:headers "location"])))
          (is (= (:player/credits tx) (- 10000 total-cost)))
          (is (= (:player/soldiers tx) (+ 10 3)))
          (is (= (:player/transports tx) (+ 2 2)))
          (is (= (:player/generals tx) (+ 3 1)))
          (is (= (:player/carriers tx) (+ 1 4)))
          (is (= (:player/fighters tx) (+ 5 3)))
          (is (= (:player/admirals tx) (+ 0 1)))
          (is (= (:player/defence-stations tx) (+ 3 2)))
          (is (= (:player/command-ships tx) (+ 1 1)))
          (is (= (:player/military-planets tx) (+ 2 2)))
          (is (= (:player/food-planets tx) (+ 2 1)))
          (is (= (:player/ore-planets tx) (+ 2 3)))
          (is (= (:player/current-phase tx) 4)))))))

;;
;; Test: insufficient credits returns 400 and body
;;
(deftest test-apply-building-insufficient-credits
  (testing "No purchase if insufficient credits"
    (let [params {:soldiers 1000} ;; way too expensive!
          player (assoc test-player :player/credits 100)
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [player test-game])
                    biff/submit-tx (fn [_ _] (reset! tx-called true) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}
              result (building/apply-building ctx)]
          ;; Should not call submit-tx
          (is (not @tx-called))
          (is (= 400 (:status result)))
          (is (= "Insufficient credits for purchase" (:body result))))))))

;;
;; Test: blank, non-numeric, or absent params don't break logic (parsed as 0)
;;
(deftest test-apply-building-parsing-edge-cases
  (testing "Handles blank and non-numeric fields as 0"
    (let [params {:soldiers ""
                  :transports nil
                  :generals "non-numeric"}
          tx-called (atom nil)]
      (with-redefs [xt/entity (fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-called tx) :fake-tx)]
        (let [ctx {:path-params {:player-id (str test-player-id)}
                   :params params
                   :biff/db nil}]
          (building/apply-building ctx)
          (let [tx (first @tx-called)]
            (is (= (:player/soldiers tx) (:player/soldiers test-player)))
            (is (= (:player/transports tx) (:player/transports test-player)))
            (is (= (:player/generals tx) (:player/generals test-player)))))))))

;;
;; Test: if player-id is not a UUID, throws
;;
(deftest test-apply-building-bad-uuid
  (testing "Throws on invalid player-id UUID"
    (let [ctx {:path-params {:player-id "not-a-uuid"}
               :params {}
               :biff/db nil}]
      (is (thrown? IllegalArgumentException (building/apply-building ctx))))))

;;
;; Hiccup render check for calculate-building
;;
(deftest test-calculate-building-hiccup
  (testing "calculate-building renders hiccup for preview"
    (with-redefs [xt/entity (fake-entity [test-player test-game])
                  biff/render (fn [hiccup] hiccup)]
      (let [ctx {:path-params {:player-id (str test-player-id)}
                 :params {:soldiers 2 :fighters 3}
                 :biff/db nil}
            result (building/calculate-building ctx)]
        (is (vector? result))
        (is (some? result))))))

