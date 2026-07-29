(ns com.star-empire-elite.fate-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.fate :as fate]))

;;;;
;;;; Fixtures
;;;;

(defn seq-rng
  "Build a deterministic rng stub from a fixed sequence of doubles.
  Returns a 0-arg function that returns values in order, throwing if exhausted.
  Throwing on exhaustion prevents silent test failures from a stale sequence."
  [vals]
  (let [remaining (atom (seq vals))]
    (fn []
      (let [v (first @remaining)]
        (when (nil? v)
          (throw (ex-info "seq-rng exhausted" {})))
        (swap! remaining rest)
        v))))

(def test-game
  "Minimal game map with the constants roll-fate reads."
  {:game/fate-probability       0.15
   :game/fate-rank-tilt         0.4
   :game/erg-planet-food        3000
   :game/ore-planet-credits     10000
   :game/population-tax-credits 2000})

(def test-player
  "A mid-range player for use as baseline in most tests."
  {:player/score       100
   :player/credits     50000
   :player/food        20000
   :player/population  6
   :player/soldiers    200
   :player/transports  5
   :player/fighters    10
   :player/carriers    2
   :player/erg-planets 2
   :player/ore-planets 1})

;;;;
;;;; Tests
;;;;

;;;
;;; 1. No event when rng draw >= fate-probability.
;;;
(deftest test-no-event-above-probability
  ;; First draw is 0.20, which is >= fate-probability (0.15) → nil immediately.
  (is (nil? (fate/roll-fate test-player test-game [test-player] (seq-rng [0.20])))))

;;;
;;; 2. Rank weighting: a leader draws disaster; a trailer draws boon for the same rng sequence.
;;;
(deftest test-rank-weighting
  ;; Two active players — a high-score leader and a low-score trailer.
  ;; For the leader:  r = 1.0, p-disaster = 0.70.  Draw 0.60 < 0.70 → disaster.
  ;; For the trailer: r = 0.0, p-disaster = 0.30.  Draw 0.60 ≥ 0.30 → boon.
  (let [leader  (assoc test-player :player/score 1000)
        trailer (assoc test-player :player/score 10)
        active  [leader trailer]
        ;; gate (0.05 passes), polarity draw (0.60), entry pick, mult
        draws   [0.05 0.60 0.50 0.50]]
    (testing "leader (r=1.0, p-disaster=0.70) draws disaster on polarity draw 0.60"
      (is (= :disaster (:polarity (fate/roll-fate leader  test-game active (seq-rng draws))))))
    (testing "trailer (r=0.0, p-disaster=0.30) draws boon on polarity draw 0.60"
      (is (= :boon     (:polarity (fate/roll-fate trailer test-game active (seq-rng draws))))))))

;;;
;;; 3. Zero-holdings disaster yields delta of zero — the player cannot lose what they don't have.
;;;
(deftest test-zero-holdings-disaster-yields-zero-delta
  ;; Force solar-flare (disaster entry index 2 of 4) on a player with no military units.
  ;; Solo active list → r = 0.5, p-disaster = 0.5.
  ;; rng: 0.05 (gate), 0.10 (< 0.5 → disaster), 0.50 (int(0.5*4)=2 → solar-flare), 0.50 (mult)
  (let [broke  (assoc test-player
                  :player/soldiers   0
                  :player/transports 0
                  :player/fighters   0
                  :player/carriers   0)
        rng    (seq-rng [0.05 0.10 0.50 0.50])
        result (fate/roll-fate broke test-game [broke] rng)]
    (is (= :solar-flare (:type result)))
    (doseq [[k delta] (:effect result)]
      (is (zero? delta)
          (str k " delta should be 0 — player holds nothing")))))

;;;
;;; 4. Disaster delta magnitude never exceeds the player's current holdings.
;;;
(deftest test-disaster-never-exceeds-holdings
  ;; Sweep all four disaster catalog entries by varying the entry-pick draw.
  ;; rng: 0.05 (gate), 0.10 (disaster), frac (entry pick), 0.99 (mult near max).
  (let [disasters (filterv #(= :disaster (:polarity %)) fate/fate-catalog)
        n         (count disasters)]  ; should be 4
    (doseq [i (range n)]
      (let [frac   (/ (double i) n)
            rng    (seq-rng [0.05 0.10 frac 0.99])
            result (fate/roll-fate test-player test-game [test-player] rng)]
        (is (= :disaster (:polarity result)))
        (doseq [[k delta] (:effect result)]
          (is (<= (Math/abs (long delta)) (get test-player k 0))
              (str "delta " delta " exceeds holding for " k)))))))

;;;
;;; 5. Boon delta is positive.
;;;
(deftest test-boon-delta-is-positive
  ;; Solo player → r=0.5, p-disaster=0.5.
  ;; rng: 0.05 (gate), 0.51 (≥ 0.50 → boon), 0.50 (entry pick), 0.50 (mult)
  ;; Boon entry index 2 of 4 = foreign-aid (:player/credits, :production).
  (let [rng    (seq-rng [0.05 0.51 0.50 0.50])
        result (fate/roll-fate test-player test-game [test-player] rng)]
    (is (= :boon (:polarity result)))
    (doseq [[_k delta] (:effect result)]
      (is (pos? delta) "boon delta must be positive"))))

;;;
;;; 6. Production-basis magnitudes match income arithmetic for known player/game values.
;;;    food    = 2 erg-planets × 3000 = 6000
;;;    credits = 1 ore-planet × 10000 + 6 pop × 2000 = 22000
;;;
(deftest test-production-basis-matches-income
  (testing "bumper-harvest food delta"
    ;; Force bumper-harvest (boon entry #0): pick draw int(0.10*4)=0.
    ;; rng: 0.05 (gate), 0.80 (≥ 0.50 → boon), 0.10 (entry #0 = bumper-harvest), 1.00 (mult)
    ;; mult = 0.75 + 1.0*(1.25-0.75) = 1.25
    ;; delta = round(1.25 × 6000) = 7500
    (let [rng    (seq-rng [0.05 0.80 0.10 1.00])
          result (fate/roll-fate test-player test-game [test-player] rng)]
      (is (= :bumper-harvest (:type result)))
      (is (= 7500 (get-in result [:effect :player/food])))))

  (testing "gold-rush credits delta"
    ;; Force gold-rush (boon entry #1): pick draw int(0.30*4)=1.
    ;; rng: 0.05 (gate), 0.80 (boon), 0.30 (entry #1 = gold-rush), 0.00 (mult)
    ;; mult = 0.50 + 0.0*(1.00-0.50) = 0.50
    ;; delta = round(0.50 × 22000) = 11000
    (let [rng    (seq-rng [0.05 0.80 0.30 0.00])
          result (fate/roll-fate test-player test-game [test-player] rng)]
      (is (= :gold-rush (:type result)))
      (is (= 11000 (get-in result [:effect :player/credits]))))))
