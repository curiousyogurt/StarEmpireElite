(ns com.star-empire-elite.pages.app.building-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.building :as building]
            [com.star-empire-elite.test-helpers :as helpers]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures
;;;;

(def test-player-id #uuid "10000000-0000-0000-0000-000000000001")
(def test-game-id   #uuid "20000000-0000-0000-0000-000000000002")

;; Game entity with realistic purchase costs.
(def test-game
  {:xt/id                test-game-id
   :game/soldier-cost    10
   :game/transport-cost  20
   :game/general-cost    30
   :game/carrier-cost    40
   :game/fighter-cost    50
   :game/admiral-cost    60
   :game/station-cost    70
   :game/cmd-ship-cost   80
   :game/agent-cost      90
   :game/mil-planet-cost  700
   :game/erg-planet-cost 800
   :game/ore-planet-cost  900
   ;; Income constants needed by projections-section
   :game/ore-planet-credits      0
   :game/erg-planet-food        0
   :game/erg-planet-fuel        0
   :game/mil-planet-soldiers     0
   :game/mil-planet-fighters     0
   :game/mil-planet-stations     0
   :game/population-tax-credits  0
   ;; Upkeep constants needed by projections-section
   :game/planet-upkeep-credits  0
   :game/planet-upkeep-food     0
   :game/soldier-upkeep-credits 0
   :game/soldier-upkeep-food    0
   :game/fighter-upkeep-credits 0
   :game/fighter-upkeep-fuel    0
   :game/station-upkeep-credits 0
   :game/station-upkeep-fuel    0
   :game/agent-upkeep-food      0
   :game/agent-upkeep-fuel      0
   :game/population-upkeep-food 0
   :game/population-upkeep-fuel 0
   :game/expense-stability-penalty 0
   :game/turns-per-round        6
   :game/rounds-per-day         2})

;; Player in phase 3 with enough credits to buy most things.
(def test-player
  {:xt/id                test-player-id
   :player/game          test-game-id
   :player/current-phase 3
   :player/empire-name   "Test Empire"
   :player/current-turn  1
   :player/current-round 1
   :player/population    6
   :player/stability     75
   :player/credits       10000
   :player/soldiers      10
   :player/transports    2
   :player/generals      3
   :player/carriers      1
   :player/fighters      5
   :player/admirals      0
   :player/stations      3
   :player/cmd-ships     1
   :player/agents        0
   :player/mil-planets   2
   :player/erg-planets  2
   :player/ore-planets   2
   :player/food          100
   :player/fuel          50
   :player/galaxars      0})

;; A baseline purchase quantities map with all keys set to zero. Each test merges
;; its specific values on top to avoid NPEs from missing keys in calculation functions.
(def zero-quantities
  {:soldiers 0 :transports 0 :generals 0 :carriers 0
   :fighters 0 :admirals  0 :stations 0 :cmd-ships 0 :agents 0
   :ore-planets 0 :erg-planets 0 :mil-planets 0})

;;;;
;;;; calculate-purchase-cost Tests
;;;;
;;;; calculate-purchase-cost is a pure function; no mocking needed.
;;;;

(deftest test-calculate-purchase-cost-zero
  (testing "Returns zero cost when no purchases are made"
    (is (= 0 (:total-cost (building/calculate-purchase-cost zero-quantities test-game))))))

(deftest test-calculate-purchase-cost-single-type
  (testing "Calculates cost correctly for a single unit type"
    (is (= 1000 (:total-cost (building/calculate-purchase-cost
                               (assoc zero-quantities :soldiers 100) test-game))))  ; 100 * 10
    (is (= 3500 (:total-cost (building/calculate-purchase-cost
                               (assoc zero-quantities :mil-planets 5) test-game))))))  ; 5 * 700

(deftest test-calculate-purchase-cost-mixed
  (testing "Sums costs correctly across all unit types"
    ;; 10*10 + 5*20 + 2*30 + 3*40 + 8*50 + 1*60 + 4*70 + 2*80 + 1*90 + 1*700 + 1*800 + 1*900
    ;; = 100 + 100 + 60 + 120 + 400 + 60 + 280 + 160 + 90 + 700 + 800 + 900 = 3770
    (let [quantities {:soldiers 10 :transports 5 :generals 2 :carriers 3
                      :fighters 8  :admirals  1 :stations 4 :cmd-ships 2 :agents 1
                      :mil-planets 1 :erg-planets 1 :ore-planets 1}
          expected (+ (* 10 10) (* 5 20) (* 2 30) (* 3 40) (* 8 50)
                      (* 1 60) (* 4 70) (* 2 80) (* 1 90)
                      (* 1 700) (* 1 800) (* 1 900))]
      (is (= expected (:total-cost (building/calculate-purchase-cost quantities test-game)))))))

(deftest test-calculate-purchase-cost-agents
  (testing "Agent cost uses game/agent-cost (provided by enrich-game in the handler)"
    ;; The test-game has :game/agent-cost 90 already set.
    (is (= (* 5 90) (:total-cost (building/calculate-purchase-cost
                                   (assoc zero-quantities :agents 5) test-game))))))

;;;;
;;;; calculate-resources-after-purchases Tests
;;;;

(deftest test-calculate-resources-after-purchases-basic
  (testing "All resource values are updated correctly after purchases"
    (let [player    {:player/credits 5000 :player/agents 2
                     :player/soldiers 100 :player/transports 20 :player/generals 5
                     :player/carriers 10  :player/fighters  50 :player/admirals 2
                     :player/stations 15  :player/cmd-ships  3
                     :player/mil-planets 5 :player/erg-planets 4 :player/ore-planets 6}
          quantities {:soldiers 10 :transports 5 :generals 2 :carriers 3
                      :fighters 8  :admirals  1 :stations 4 :cmd-ships 2 :agents 1
                      :mil-planets 1 :erg-planets 1 :ore-planets 1}
          cost-info {:total-cost 3770}
          r (building/calculate-resources-after-purchases player quantities cost-info)]
      (is (= 1230 (:credits r)))       ; 5000 - 3770
      (is (= 110  (:soldiers r)))      ; 100 + 10
      (is (= 25   (:transports r)))    ; 20 + 5
      (is (= 7    (:generals r)))      ; 5 + 2
      (is (= 13   (:carriers r)))      ; 10 + 3
      (is (= 58   (:fighters r)))      ; 50 + 8
      (is (= 3    (:admirals r)))      ; 2 + 1
      (is (= 19   (:stations r)))      ; 15 + 4
      (is (= 5    (:cmd-ships r)))     ; 3 + 2
      (is (= 3    (:agents r)))        ; 2 + 1
      (is (= 6    (:mil-planets r)))   ; 5 + 1
      (is (= 5    (:erg-planets r)))   ; 4 + 1
      (is (= 7    (:ore-planets r))))) ; 6 + 1

  (testing "Allows negative credits (overspending is caught by can-afford-purchases?, not here)"
    (let [player    {:player/credits 100 :player/agents 0
                     :player/soldiers 0 :player/transports 0 :player/generals 0
                     :player/carriers 0 :player/fighters  0 :player/admirals 0
                     :player/stations 0 :player/cmd-ships  0
                     :player/mil-planets 0 :player/erg-planets 0 :player/ore-planets 0}
          quantities (assoc zero-quantities :soldiers 100)
          cost-info {:total-cost 1000}
          r (building/calculate-resources-after-purchases player quantities cost-info)]
      (is (= -900 (:credits  r)))    ; 100 - 1000
      (is (= 100  (:soldiers r)))))) ; 0 + 100

;;;;
;;;; can-afford-purchases? Tests
;;;;

(deftest test-can-afford-purchases
  (testing "Returns true when credits are zero or positive"
    (is (true?  (building/can-afford-purchases? {:credits 1000})))
    (is (true?  (building/can-afford-purchases? {:credits 0}))))
  (testing "Returns false when credits are negative"
    (is (false? (building/can-afford-purchases? {:credits -1})))
    (is (false? (building/can-afford-purchases? {:credits -1000})))))

;;;;
;;;; calculate-max-quantities Tests
;;;;

(deftest test-calculate-max-quantities-no-prior-spend
  (testing "Max quantities reflect full credit balance when nothing has been selected yet"
    (let [maxq (building/calculate-max-quantities test-player zero-quantities test-game)]
      ;; With 10000 credits: floor(10000/10) = 1000 soldiers, floor(10000/700) = 14 mil-planets, etc.
      (is (= (quot 10000 10)  (:soldiers    maxq)))
      (is (= (quot 10000 700) (:mil-planets maxq)))
      (is (= (quot 10000 90)  (:agents      maxq))))))

(deftest test-calculate-max-quantities-with-prior-spend
  (testing "Prior selections reduce the max of OTHER items but not the item itself"
    (let [with-prior (building/calculate-max-quantities test-player
                                                        (assoc zero-quantities :soldiers 100)
                                                        test-game)
          remaining-after-soldiers (- 10000 (* 100 10))]
      ;; Soldiers' own max is NOT reduced by the soldiers already entered — this keeps
      ;; the max stable while the player is typing. Other items' maxes DO shrink.
      (is (= (quot 10000 10)                     (:soldiers    with-prior)))
      (is (= (quot remaining-after-soldiers 700) (:mil-planets with-prior))))))

;;;;
;;;; apply-building Tests
;;;;
;;;; apply-building writes to the database and redirects, so xt/entity and
;;;; biff/submit-tx are replaced with test doubles using with-redefs.
;;;;

(deftest test-apply-building-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (building/apply-building {:path-params {:player-id (str test-player-id)}
                                             :params {} :biff/db nil})]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-building-wrong-phase
  (testing "Redirects to game overview when player is not in phase 3"
    (let [player (assoc test-player :player/current-phase 2)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])]
        (let [result (building/apply-building {:path-params {:player-id (str test-player-id)}
                                               :params {} :biff/db nil})]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id) (get-in result [:headers "location"]))))))))

(deftest test-apply-building-commits-correct-tx
  (testing "Commits correct resource deltas, advances to phase 4, and redirects to action"
    (let [params   {:soldiers "3" :transports "2" :generals "1" :carriers "0"
                    :fighters "3" :admirals   "0" :stations "2" :cmd-ships "0" :agents "1"
                    :mil-planets "0" :erg-planets "0" :ore-planets "0"}
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (building/apply-building {:path-params {:player-id (str test-player-id)}
                                               :params params :biff/db nil})
              tx     (first @tx-atom)
              ;; Derive expected values from the same pure functions the handler uses.
              quantities (building/parse-purchase-quantities params)
              cost-info  (building/calculate-purchase-cost quantities test-game)
              expected   (building/calculate-resources-after-purchases test-player quantities cost-info)]
          ;; Redirect
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/action") (get-in result [:headers "location"])))
          ;; Transaction metadata
          (is (= :player       (:db/doc-type tx)))
          (is (= :update        (:db/op tx)))
          (is (= test-player-id (:xt/id tx)))
          ;; Phase advance
          (is (= 4 (:player/current-phase tx)))
          ;; Resources — all 12 purchasable fields must be committed
          (is (= (:credits     expected) (:player/credits     tx)))
          (is (= (:soldiers    expected) (:player/soldiers    tx)))
          (is (= (:transports  expected) (:player/transports  tx)))
          (is (= (:generals    expected) (:player/generals    tx)))
          (is (= (:carriers    expected) (:player/carriers    tx)))
          (is (= (:fighters    expected) (:player/fighters    tx)))
          (is (= (:admirals    expected) (:player/admirals    tx)))
          (is (= (:stations    expected) (:player/stations    tx)))
          (is (= (:cmd-ships   expected) (:player/cmd-ships   tx)))
          (is (= (:agents      expected) (:player/agents      tx)))
          (is (= (:ore-planets expected) (:player/ore-planets tx)))
          (is (= (:erg-planets expected) (:player/erg-planets tx)))
          (is (= (:mil-planets expected) (:player/mil-planets tx))))))))

(deftest test-apply-building-insufficient-credits
  (testing "Redirects back to building and does not call submit-tx when player cannot afford purchases"
    (let [poor-player (assoc test-player :player/credits 50)
          tx-called?  (atom false)]
      (with-redefs [xt/entity      (helpers/fake-entity [poor-player test-game])
                    biff/submit-tx (fn [_ _] (reset! tx-called? true) :ok)]
        (let [result (building/apply-building {:path-params {:player-id (str test-player-id)}
                                               :params {:soldiers "1000"} :biff/db nil})]
          (is (false? @tx-called?))
          (is (= 303 (:status result)))
          (is (clojure.string/ends-with? (get-in result [:headers "location"]) "/building")))))))
(deftest test-apply-building-zero-purchases
  (testing "Zero purchases advance phase without changing resource counts"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (building/apply-building {:path-params {:player-id (str test-player-id)}
                                  :params {} :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (= (:player/credits  test-player) (:player/credits  tx)))
          (is (= (:player/soldiers test-player) (:player/soldiers tx)))
          (is (= 4 (:player/current-phase tx))))))))

;;;;
;;;; calculate-building Tests
;;;;
;;;; calculate-building is the HTMX endpoint that provides OOB updates as the
;;;; user changes purchase quantities. Tests focus on phase validation and that
;;;; the handler produces renderable output under normal and edge conditions.
;;;;

(deftest test-calculate-building-any-phase-renders
  ;; calculate-building is a read-only HTMX endpoint; phase validation is only
  ;; enforced on the state-mutating apply-building handler.
  (testing "Renders regardless of current phase"
    (let [player (assoc test-player :player/current-phase 2)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])]
        (let [result (building/calculate-building {:path-params {:player-id (str test-player-id)}
                                                   :params {} :biff/db nil})]
          (is (some? result)))))))

(deftest test-calculate-building-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (building/calculate-building {:path-params {:player-id (str test-player-id)}
                                                 :params {} :biff/db nil})]
        (is (= 404 (:status result)))))))

(deftest test-calculate-building-renders
  (testing "Returns renderable hiccup for a valid phase-3 player with no purchases"
    (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])]
      (let [result (building/calculate-building {:path-params {:player-id (str test-player-id)}
                                                 :params {} :biff/db nil})]
        (is (some? result)))))

  (testing "Returns renderable hiccup with purchases that do not exceed credits"
    (with-redefs [xt/entity (helpers/fake-entity [test-player test-game])]
      (let [result (building/calculate-building {:path-params {:player-id (str test-player-id)}
                                                 :params {:soldiers "5" :fighters "2"} :biff/db nil})]
        (is (some? result)))))

  (testing "Returns renderable hiccup when player cannot afford purchases"
    (let [poor-player (assoc test-player :player/credits 5)]
      (with-redefs [xt/entity (helpers/fake-entity [poor-player test-game])]
        (let [result (building/calculate-building {:path-params {:player-id (str test-player-id)}
                                                   :params {:soldiers "1000"} :biff/db nil})]
          (is (some? result)))))))

;;;;
;;;; UI Component Tests
;;;;

(deftest test-building-page-renders
  (testing "Returns a hiccup vector for a standard player"
    (is (vector? (building/building-page {:player test-player :game test-game})))))
