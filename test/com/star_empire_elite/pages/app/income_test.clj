(ns com.star-empire-elite.pages.app.income-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.income :as income]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures
;;;;

(def test-game-id   #uuid "00000000-0000-0000-0000-000000000001")
(def test-player-id #uuid "00000000-0000-0000-0000-000000000002")

(def test-game
  {:xt/id                        test-game-id
   :game/ore-planet-credits      100
   :game/erg-planet-food        200
   :game/erg-planet-fuel        50
   :game/mil-planet-soldiers     25
   :game/mil-planet-fighters     15
   :game/mil-planet-stations     5
   :game/population-tax-credits  100
   :game/synergy-credits-per-paired 1000
   :game/synergy-fuel-per-paired    200
   :game/turns-per-round         6
   :game/rounds-per-day          2})

;; Base player in phase 1, turn 1, round 1 with no previous round completed.
(def test-player
  {:xt/id                          test-player-id
   :player/empire-name             "Test Empire"
   :player/game                    test-game-id
   :player/current-phase           1
   :player/current-turn            1
   :player/current-round           1
   :player/turns-used              0
   :player/last-round-completed-at nil
   :player/ore-planets             3
   :player/erg-planets            2
   :player/mil-planets             4
   :player/credits                 1000
   :player/food                    500
   :player/fuel                    300
   :player/galaxars                50
   :player/population              5
   :player/soldiers                100
   :player/fighters                75
   :player/stations                20
   :player/agents                  10})

;; A timestamp clearly in the past — always satisfies the "previous day" branch in day-reset? logic.
(def ^:private past-date #inst "2020-01-01T00:00:00")

;;;;
;;;; calculate-income Tests
;;;;
;;;; calculate-income is a pure function; no mocking needed.
;;;;

(deftest test-calculate-income-basic
  (testing "Calculates income correctly for all sources"
    (let [income (income/calculate-income test-player test-game)]
      ;; Ore: 3 planets * game rates
      (is (= 300 (:ore-credits income)))   ; 3 * 100
      ;; Food: 2 planets * game rates
      (is (= 400 (:erg-food income)))     ; 2 * 200
      (is (= 100 (:erg-fuel income)))     ; 2 * 50
      ;; Military: 4 planets * game rates
      (is (= 100 (:mil-soldiers income)))  ; 4 * 25
      (is (= 60  (:mil-fighters income)))  ; 4 * 15
      (is (= 20  (:mil-stations income)))  ; 4 * 5
      ;; Population tax: 5 million * 100 rate
      (is (= 500 (:tax-credits income))))))

(deftest test-calculate-income-zero-planets
  (testing "Returns zero for all resources when player has no planets"
    (let [player (assoc test-player :player/ore-planets  0
                                    :player/erg-planets 0
                                    :player/mil-planets  0)
          income (income/calculate-income player test-game)]
      (is (= 0 (:ore-credits income)))
      (is (= 0 (:erg-food income)))
      (is (= 0 (:erg-fuel income)))
      (is (= 0 (:mil-soldiers income)))
      (is (= 0 (:mil-fighters income)))
      (is (= 0 (:mil-stations income))))))

(deftest test-calculate-income-single-planet-type
  (testing "Calculates correctly when player has only one planet type"
    (let [player (assoc test-player :player/ore-planets  5
                                    :player/erg-planets 0
                                    :player/mil-planets  0)
          income (income/calculate-income player test-game)]
      (is (= 500 (:ore-credits income)))  ; 5 * 100
      (is (= 0   (:erg-food income)))
      (is (= 0   (:erg-fuel income)))
      (is (= 0   (:mil-soldiers income))))))

(deftest test-calculate-income-zero-rates
  (testing "Returns zero income when all game rates are zero"
    (let [zero-game (assoc test-game
                           :game/ore-planet-credits      0
                           :game/erg-planet-food        0
                           :game/erg-planet-fuel        0
                           :game/mil-planet-soldiers     0
                           :game/mil-planet-fighters     0
                           :game/mil-planet-stations     0
                           :game/population-tax-credits  0
                           :game/synergy-credits-per-paired 0
                           :game/synergy-fuel-per-paired    0)
          income (income/calculate-income test-player zero-game)]
      (is (= 0 (:ore-credits income)))
      (is (= 0 (:erg-food income)))
      (is (= 0 (:mil-soldiers income)))
      (is (= 0 (:tax-credits income))))))

(deftest test-calculate-income-custom-rates
  (testing "Handles custom per-game income rates"
    (let [player (assoc test-player :player/ore-planets  2
                                    :player/erg-planets 3
                                    :player/mil-planets  1)
          game   (assoc test-game :game/ore-planet-credits  500
                                  :game/erg-planet-food    1000
                                  :game/mil-planet-soldiers 100)
          income (income/calculate-income player game)]
      (is (= 1000 (:ore-credits income)))   ; 2 * 500
      (is (= 3000 (:erg-food income)))     ; 3 * 1000
      (is (= 100  (:mil-soldiers income))))))  ; 1 * 100

(deftest test-calculate-income-population-tax
  (testing "Tax credits scale linearly with population and game rate"
    ;; 10 million people at 250/million = 2500 credits
    (let [player (assoc test-player :player/population 10)
          game   (assoc test-game :game/population-tax-credits 250)
          income (income/calculate-income player game)]
      (is (= 2500 (:tax-credits income)))))

  (testing "Zero population yields zero tax credits"
    (let [player (assoc test-player :player/population 0)
          income (income/calculate-income player test-game)]
      (is (= 0 (:tax-credits income)))))

  (testing "Tax income is independent of planet counts"
    ;; Same population, different planet counts — tax is unchanged
    (let [no-planets   (assoc test-player :player/ore-planets 0 :player/erg-planets 0 :player/mil-planets 0)
          many-planets (assoc test-player :player/ore-planets 10 :player/erg-planets 10 :player/mil-planets 10)]
      (is (= (:tax-credits (income/calculate-income no-planets test-game))
             (:tax-credits (income/calculate-income many-planets test-game)))))))

(deftest test-calculate-income-large-planet-counts
  (testing "Handles large planet counts without integer overflow"
    (let [player (assoc test-player :player/ore-planets  1000
                                    :player/erg-planets 500
                                    :player/mil-planets  250)
          income (income/calculate-income player test-game)]
      (is (= 100000 (:ore-credits income)))  ; 1000 * 100
      (is (= 100000 (:erg-food income)))    ; 500 * 200
      (is (= 25000  (:erg-fuel income)))    ; 500 * 50
      (is (= 6250   (:mil-soldiers income)))))) ; 250 * 25

;;;;
;;;; apply-income Tests
;;;;
;;;; apply-income writes to the database and redirects, so xt/entity and
;;;; biff/submit-tx are replaced with test doubles using with-redefs.
;;;;

(deftest test-apply-income-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (income/apply-income {:path-params {:player-id (str test-player-id)}
                                         :biff/db     nil})]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-income-wrong-phase
  (testing "Redirects to game overview when player is not in phase 1"
    (let [player (assoc test-player :player/current-phase 2)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])]
        (let [result (income/apply-income {:path-params {:player-id (str test-player-id)}
                                           :biff/db     nil})]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id)
                 (get-in result [:headers "location"]))))))))

(deftest test-apply-income-submits-correct-tx
  (testing "Commits correct resource deltas, advances to phase 2, and redirects to expenses"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (income/apply-income {:path-params {:player-id (str test-player-id)}
                                           :biff/db     nil})
              tx     (first @tx-atom)
              income (income/calculate-income test-player test-game)]
          ;; Redirect
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/expenses")
                 (get-in result [:headers "location"])))
          ;; Transaction metadata
          (is (= :player       (:db/doc-type tx)))
          (is (= :update        (:db/op tx)))
          (is (= test-player-id (:xt/id tx)))
          (is (= 2              (:player/current-phase tx)))
          ;; Resource deltas
          (is (= (+ (:player/credits  test-player) (:ore-credits income) (:tax-credits income) (:synergy-credits income)) (:player/credits  tx)))
          (is (= (+ (:player/food     test-player) (:erg-food    income)) (:player/food     tx)))
          (is (= (+ (:player/fuel     test-player) (:erg-fuel    income) (:synergy-fuel income)) (:player/fuel     tx)))
          (is (= (+ (:player/soldiers test-player) (:mil-soldiers income)) (:player/soldiers tx)))
          (is (= (+ (:player/fighters test-player) (:mil-fighters income)) (:player/fighters tx)))
          (is (= (+ (:player/stations test-player) (:mil-stations income)) (:player/stations tx))))))))

(deftest test-apply-income-zero-planets
  (testing "Advances to phase 2 with only tax income when player has no planets"
    (let [player  (assoc test-player :player/ore-planets  0
                                     :player/erg-planets 0
                                     :player/mil-planets  0)
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (income/apply-income {:path-params {:player-id (str test-player-id)}
                              :biff/db     nil})
        (let [tx    (first @tx-atom)
              ;; Planet income is zero; only population tax is applied to credits
              expected-credits (+ (:player/credits player)
                                  (* (:player/population player) (:game/population-tax-credits test-game)))]
          (is (= expected-credits (:player/credits  tx)))
          (is (= (:player/food    player) (:player/food  tx)))
          (is (= (:player/fuel    player) (:player/fuel  tx)))
          (is (= 2                         (:player/current-phase tx))))))))

;;;;
;;;; apply-income Day-Reset Tests
;;;;
;;;; When last-round-completed-at is from a previous UTC calendar day and current-round > 1,
;;;; apply-income resets current-round to 1. This covers both the "used all rounds yesterday"
;;;; and "skipped round 2" cases. Round 1 and nil last-round-completed-at are never reset.
;;;;

(deftest test-apply-income-resets-round-on-new-day
  (testing "Sets current-round to 1 when last round was completed on a previous day"
    (let [player  (assoc test-player :player/current-round           2
                                     :player/last-round-completed-at past-date)
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (income/apply-income {:path-params {:player-id (str test-player-id)}
                              :biff/db     nil})
        (is (= 1 (:player/current-round (first @tx-atom))))))))

(deftest test-apply-income-no-reset-same-day
  (testing "Does not include current-round in tx when last round was completed today"
    ;; cond-> skips the assoc, so the key is absent from the tx map
    (let [now     (java.util.Date.)
          player  (assoc test-player :player/current-round           2
                                     :player/last-round-completed-at now)
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (income/apply-income {:path-params {:player-id (str test-player-id)}
                              :biff/db     nil})
        (is (nil? (:player/current-round (first @tx-atom)))))))  )

(deftest test-apply-income-no-reset-on-round-1
  (testing "Does not reset current-round when already on round 1, even with a past date"
    ;; day-reset? requires current-round > 1; round 1 is never touched
    (let [player  (assoc test-player :player/current-round           1
                                     :player/last-round-completed-at past-date)
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (income/apply-income {:path-params {:player-id (str test-player-id)}
                              :biff/db     nil})
        (is (nil? (:player/current-round (first @tx-atom)))))))  )

(deftest test-apply-income-no-reset-first-ever-round
  (testing "Does not reset current-round when no previous round exists (nil last-round-completed-at)"
    ;; test-player has current-round 1 and last-round-completed-at nil
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (income/apply-income {:path-params {:player-id (str test-player-id)}
                              :biff/db     nil})
        (is (nil? (:player/current-round (first @tx-atom)))))))  )

;;;;
;;;; calculate-resources-after-income Tests
;;;;
;;;; calculate-resources-after-income is a pure function. Tests verify resource deltas and
;;;; that non-income resources pass through unchanged.
;;;;

(def full-test-player
  "Player with all keys populated, used for calculate-resources-after-income tests."
  (assoc test-player
         :player/stability   80
         :player/transports  3
         :player/generals    1
         :player/carriers    2
         :player/admirals    0
         :player/cmd-ships   0))

(deftest test-calculate-resources-after-income-basic
  (testing "Applies income deltas to the correct resources"
    (let [income {:ore-credits 300 :erg-food 400 :erg-fuel 100
                  :mil-soldiers 25 :mil-fighters 15 :mil-stations 5
                  :tax-credits 500 :synergy-credits 0 :synergy-fuel 0}
          after  (income/calculate-resources-after-income full-test-player income)]
      ;; Credits: base + ore + tax
      (is (= (+ (:player/credits full-test-player) 300 500) (:credits after)))
      ;; Food: base + erg-food
      (is (= (+ (:player/food full-test-player) 400) (:food after)))
      ;; Fuel: base + erg-fuel
      (is (= (+ (:player/fuel full-test-player) 100) (:fuel after)))
      ;; Military units increased
      (is (= (+ (:player/soldiers full-test-player) 25)  (:soldiers after)))
      (is (= (+ (:player/fighters full-test-player) 15)  (:fighters after)))
      (is (= (+ (:player/stations full-test-player) 5)   (:stations after))))))

(deftest test-calculate-resources-after-income-pass-through
  (testing "Non-income resources pass through unchanged"
    (let [income {:ore-credits 0 :erg-food 0 :erg-fuel 0
                  :mil-soldiers 0 :mil-fighters 0 :mil-stations 0
                  :tax-credits 0 :synergy-credits 0 :synergy-fuel 0}
          after  (income/calculate-resources-after-income full-test-player income)]
      (is (= (:player/population  full-test-player) (:population after)))
      (is (= (:player/stability   full-test-player) (:stability  after)))
      (is (= (:player/galaxars    full-test-player) (:galaxars   after)))
      (is (= (:player/transports  full-test-player) (:transports after)))
      (is (= (:player/generals    full-test-player) (:generals   after)))
      (is (= (:player/carriers    full-test-player) (:carriers   after)))
      (is (= (:player/admirals    full-test-player) (:admirals   after)))
      (is (= (:player/cmd-ships   full-test-player) (:cmd-ships  after)))
      (is (= (:player/agents      full-test-player) (:agents     after)))
      (is (= (:player/ore-planets full-test-player) (:ore-planets after)))
      (is (= (:player/erg-planets full-test-player) (:erg-planets after)))
      (is (= (:player/mil-planets full-test-player) (:mil-planets after))))))

(deftest test-calculate-resources-after-income-zero-income
  (testing "All resources unchanged when income is all zeros"
    (let [zero-income {:ore-credits 0 :erg-food 0 :erg-fuel 0
                       :mil-soldiers 0 :mil-fighters 0 :mil-stations 0
                       :tax-credits 0 :synergy-credits 0 :synergy-fuel 0}
          after       (income/calculate-resources-after-income full-test-player zero-income)]
      (is (= (:player/credits  full-test-player) (:credits  after)))
      (is (= (:player/food     full-test-player) (:food     after)))
      (is (= (:player/fuel     full-test-player) (:fuel     after)))
      (is (= (:player/soldiers full-test-player) (:soldiers after)))
      (is (= (:player/fighters full-test-player) (:fighters after)))
      (is (= (:player/stations full-test-player) (:stations after))))))

(deftest test-calculate-resources-after-income-large-values
  (testing "Handles large income values without overflow"
    (let [income {:ore-credits 1000000000 :erg-food 999999999 :erg-fuel 500000000
                  :mil-soldiers 1000000 :mil-fighters 500000 :mil-stations 100000
                  :tax-credits 750000000 :synergy-credits 0 :synergy-fuel 0}
          after  (income/calculate-resources-after-income full-test-player income)]
      (is (= (+ (:player/credits  full-test-player) 1000000000 750000000) (:credits  after)))
      (is (= (+ (:player/food     full-test-player) 999999999)             (:food     after)))
      (is (= (+ (:player/soldiers full-test-player) 1000000)               (:soldiers after))))))

;;;;
;;;; income-page Tests
;;;;
;;;; income-page is a pure UI function. Tests verify it renders valid hiccup across
;;;; different player configurations.
;;;;

(deftest test-income-page-renders
  (testing "Returns valid hiccup for a standard player"
    (is (vector? (income/income-page {:player test-player :game test-game})))))

(deftest test-income-page-zero-planets
  (testing "Renders without error when player has no planets"
    (let [player (assoc test-player :player/ore-planets  0
                                    :player/erg-planets 0
                                    :player/mil-planets  0)]
      (is (vector? (income/income-page {:player player :game test-game}))))))

(deftest test-income-page-extreme-values
  (testing "Renders without error for very large planet counts and resource values"
    (let [player (assoc test-player :player/ore-planets 999999
                                    :player/credits     1234567890)
          game   (assoc test-game :game/ore-planet-credits 9999)]
      (is (vector? (income/income-page {:player player :game game}))))))

;;;;
;;;; Industrial Synergy Tests
;;;;

(deftest synergy-pure-ore-stacker
  (testing "Player with ore planets but no erg planets gets no synergy"
    (let [player (assoc test-player :player/ore-planets 10 :player/erg-planets 0)
          income (income/calculate-income player test-game)]
      (is (= 0 (:synergy-credits income)))
      (is (= 0 (:synergy-fuel    income))))))

(deftest synergy-pure-erg-stacker
  (testing "Player with erg planets but no ore planets gets no synergy"
    (let [player (assoc test-player :player/ore-planets 0 :player/erg-planets 10)
          income (income/calculate-income player test-game)]
      (is (= 0 (:synergy-credits income)))
      (is (= 0 (:synergy-fuel    income))))))

(deftest synergy-balanced-player
  (testing "Player with equal ore and erg gets synergy on all pairs"
    (let [player (assoc test-player :player/ore-planets 5 :player/erg-planets 5)
          income (income/calculate-income player test-game)]
      (is (= (* 5 (:game/synergy-credits-per-paired test-game)) (:synergy-credits income)))  ; 5000
      (is (= (* 5 (:game/synergy-fuel-per-paired    test-game)) (:synergy-fuel    income))))));  1000

(deftest synergy-asymmetric-uses-min
  (testing "Player with 10 ore and 3 erg gets synergy on 3 pairs"
    (let [player (assoc test-player :player/ore-planets 10 :player/erg-planets 3)
          income (income/calculate-income player test-game)]
      (is (= (* 3 (:game/synergy-credits-per-paired test-game)) (:synergy-credits income)))  ; 3000
      (is (= (* 3 (:game/synergy-fuel-per-paired    test-game)) (:synergy-fuel    income))))))  ; 600

(deftest synergy-applied-to-resources
  (testing "Synergy credits and fuel are added to player totals via calculate-resources-after-income"
    (let [player (assoc test-player :player/ore-planets 4 :player/erg-planets 4
                                    :player/credits 1000 :player/fuel 500)
          income (income/calculate-income player test-game)
          after  (income/calculate-resources-after-income player income)]
      ;; 4 pairs → 4000 synergy credits, 800 synergy fuel
      (is (= (+ 1000
                (:ore-credits     income)
                (:tax-credits     income)
                (:synergy-credits income))
             (:credits after)))
      (is (= (+ 500
                (:erg-fuel     income)
                (:synergy-fuel income))
             (:fuel after))))))
