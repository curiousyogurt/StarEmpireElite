(ns com.star-empire-elite.combat-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.combat :as combat]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.test-helpers :as helpers]))

;;;;
;;;; Fixtures
;;;;

(def game helpers/game-defaults)

;;; A well-equipped attacker: enough transports and generals to field full forces.
(def strong-attacker
  (merge helpers/player-defaults
         {:xt/id              #uuid "00000000-0000-0000-0000-000000000001"
          :player/empire-name "Attacker"
          :player/soldiers    1000
          :player/transports  20    ; 20 * 100 = 2000 capacity — not the bottleneck
          :player/generals    2     ; 2 * 1000 = 2000 capacity — not the bottleneck
          :player/fighters    200
          :player/carriers    4     ; 4 * 100 = 400 capacity — not the bottleneck
          :player/admirals    1     ; 1 * 1000 = 1000 capacity — not the bottleneck
          :player/cmd-ships   1
          :player/stations    0}))

;;; A defender with fewer units but stations for the home-ground bonus.
(def strong-defender
  (merge helpers/player-defaults
         {:xt/id              #uuid "00000000-0000-0000-0000-000000000002"
          :player/empire-name "Defender"
          :player/soldiers    500
          :player/transports  5
          :player/generals    1     ; 1 * 1000 = 1000 capacity — not the bottleneck
          :player/fighters    100
          :player/carriers    2
          :player/admirals    1
          :player/cmd-ships   0
          :player/stations    10
          :player/mil-planets 3
          :player/erg-planets 2
          :player/ore-planets 1}))

;;; An attacker bottlenecked by transports (only 2 transports = 200 soldier capacity).
(def transport-limited-attacker
  (merge helpers/player-defaults
         {:xt/id              #uuid "00000000-0000-0000-0000-000000000003"
          :player/empire-name "Transport-Limited"
          :player/soldiers    1000
          :player/transports  2     ; 2 * 100 = 200 — this is the bottleneck
          :player/generals    5
          :player/fighters    500
          :player/carriers    1     ; 1 * 100 = 100 — carrier bottleneck
          :player/admirals    2
          :player/cmd-ships   0
          :player/stations    0}))

;;; An attacker bottlenecked by generals (only 1 general = 1000 soldier capacity, still less
;;; than soldiers count but more than transports here).
(def general-limited-attacker
  (merge helpers/player-defaults
         {:xt/id              #uuid "00000000-0000-0000-0000-000000000004"
          :player/empire-name "General-Limited"
          :player/soldiers    5000
          :player/transports  100   ; 100 * 100 = 10000 — not the bottleneck
          :player/generals    1     ; 1 * 1000 = 1000 — this is the bottleneck
          :player/fighters    5000
          :player/carriers    100
          :player/admirals    1     ; 1 * 1000 = 1000 — admiral bottleneck
          :player/cmd-ships   0
          :player/stations    0}))

;;;;
;;;; effective-forces Tests
;;;;

(deftest test-effective-forces-uncapped
  (testing "When capacity exceeds unit count, all units are effective"
    (let [forces (combat/effective-forces strong-attacker)]
      (is (= 1000 (:soldiers forces)))
      (is (= 200  (:fighters forces))))))

(deftest test-effective-forces-transport-cap
  (testing "Transport capacity limits soldiers for attacker"
    (let [forces (combat/effective-forces transport-limited-attacker)]
      ;; transports: 2 * 100 = 200, generals: 5 * 1000 = 5000 — transport wins
      (is (= 200 (:soldiers forces)))))
  (testing "Carrier capacity limits fighters for attacker"
    (let [forces (combat/effective-forces transport-limited-attacker)]
      ;; carriers: 1 * 100 = 100, admirals: 2 * 1000 = 2000 — carrier wins
      (is (= 100 (:fighters forces))))))

(deftest test-effective-forces-general-cap
  (testing "General capacity limits soldiers when it is the binding constraint"
    (let [forces (combat/effective-forces general-limited-attacker)]
      ;; generals: 1 * 1000 = 1000, transports: 100 * 100 = 10000 — general wins
      (is (= 1000 (:soldiers forces)))))
  (testing "Admiral capacity limits fighters when it is the binding constraint"
    (let [forces (combat/effective-forces general-limited-attacker)]
      ;; admirals: 1 * 1000 = 1000, carriers: 100 * 100 = 10000 — admiral wins
      (is (= 1000 (:fighters forces))))))

(deftest test-effective-forces-passthrough-fields
  (testing "Non-capped fields pass through unchanged"
    (let [forces (combat/effective-forces strong-attacker)]
      (is (= (:player/transports strong-attacker) (:transports forces)))
      (is (= (:player/generals   strong-attacker) (:generals   forces)))
      (is (= (:player/carriers   strong-attacker) (:carriers   forces)))
      (is (= (:player/admirals   strong-attacker) (:admirals   forces)))
      (is (= (:player/cmd-ships  strong-attacker) (:cmd-ships  forces))))))

;;;;
;;;; effective-defending-forces Tests
;;;;

(deftest test-defending-forces-no-transport-cap
  (testing "Defenders are not limited by transports — only generals cap soldiers"
    (let [forces (combat/effective-defending-forces transport-limited-attacker)]
      ;; generals: 5 * 1000 = 5000, but soldiers = 1000 — so 1000 is effective
      (is (= 1000 (:soldiers forces)))))
  (testing "Defenders are not limited by carriers — only admirals cap fighters"
    (let [forces (combat/effective-defending-forces transport-limited-attacker)]
      ;; admirals: 2 * 1000 = 2000, fighters = 500 — so 500 is effective
      (is (= 500 (:fighters forces))))))

(deftest test-defending-forces-includes-stations
  (testing "Stations are present in defending forces"
    (let [forces (combat/effective-defending-forces strong-defender)]
      (is (= 10 (:stations forces)))))
  (testing "Stations are zero for attacker forces"
    (let [forces (combat/effective-forces strong-attacker)]
      (is (= 0 (:stations forces))))))

;;;;
;;;; base-power Tests
;;;;

(deftest test-base-power-attacker
  (testing "Attacker power sums units without stations"
    (let [forces (combat/effective-forces strong-attacker)
          power  (combat/base-power game forces true)]
      ;; 1000 soldiers * 1 + 200 fighters * 3 + 1 cmd-ship * 20
      ;;   + 2 generals * 5 + 1 admiral * 10 = 1000 + 600 + 20 + 10 + 10 = 1640
      (is (= 1640 power)))))

(deftest test-base-power-defender
  (testing "Defender power includes stations"
    (let [forces (combat/effective-defending-forces strong-defender)
          power  (combat/base-power game forces false)]
      ;; 500 soldiers * 1 + 100 fighters * 3 + 0 cmd-ships * 20
      ;;   + 1 general * 5 + 1 admiral * 10 + 10 stations * 5
      ;;   = 500 + 300 + 0 + 5 + 10 + 50 = 865
      (is (= 865 power)))))

(deftest test-base-power-stations-excluded-for-attacker
  (testing "Stations do not contribute to attacker power even if present in force map"
    (let [forces (assoc (combat/effective-forces strong-attacker) :stations 999)
          with-stations    (combat/base-power game forces false)
          without-stations (combat/base-power game forces true)]
      (is (> with-stations without-stations)))))

;;;;
;;;; random-factor Tests
;;;;

(deftest test-random-factor-range
  (testing "Random factor stays within [1 - variance, 1 + variance]"
    (let [lo (- 1.0 const/combat-variance)
          hi (+ 1.0 const/combat-variance)]
      (dotimes [_ 1000]
        (let [f (combat/random-factor)]
          (is (>= f lo))
          (is (<= f hi)))))))

;;;;
;;;; resolve-espionage Tests
;;;;

(deftest test-resolve-espionage-returns-required-keys
  (testing "Result always contains the expected keys"
    (let [result (combat/resolve-espionage strong-attacker strong-defender)]
      (is (contains? result :attacker-id))
      (is (contains? result :defender-id))
      (is (contains? result :defender-name))
      (is (contains? result :attacker-wins?))
      (is (contains? result :intel)))))

(deftest test-resolve-espionage-ids-are-strings
  (testing "UUIDs are stored as strings for safe serialisation"
    (let [result (combat/resolve-espionage strong-attacker strong-defender)]
      (is (string? (:attacker-id result)))
      (is (string? (:defender-id result))))))

(deftest test-resolve-espionage-intel-on-win
  (testing "Intel is populated when attacker wins (force attacker win with overwhelming agents)"
    (let [big-attacker (assoc strong-attacker :player/agents 100000)
          small-defender (assoc strong-defender :player/agents 1)
          result (combat/resolve-espionage big-attacker small-defender)]
      (when (:attacker-wins? result)
        (is (some? (:intel result)))
        (is (contains? (:intel result) :soldiers))
        (is (contains? (:intel result) :fighters))
        (is (contains? (:intel result) :agents))))))

(deftest test-resolve-espionage-no-intel-on-loss
  (testing "Intel is nil when attacker loses"
    (let [weak-attacker  (assoc strong-attacker :player/agents 1)
          strong-def     (assoc strong-defender :player/agents 100000)
          result (combat/resolve-espionage weak-attacker strong-def)]
      (when-not (:attacker-wins? result)
        (is (nil? (:intel result)))))))

;;;;
;;;; resolve-combat Tests
;;;;

(deftest test-resolve-combat-returns-required-keys
  (testing "Result contains all expected top-level keys"
    (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)]
      (doseq [k [:attacker-id :attacker-name :defender-id :defender-name
                 :attacker-counts :defender-counts
                 :attacker-forces :defender-forces
                 :attacker-roll :defender-roll :attacker-wins?
                 :margin :attacker-losses :defender-losses
                 :mode :planets-transferred :resources-captured]]
        (is (contains? result k) (str "Missing key: " k))))))

(deftest test-resolve-combat-ids-are-strings
  (testing "UUIDs are stored as strings"
    (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)]
      (is (string? (:attacker-id result)))
      (is (string? (:defender-id result))))))

(deftest test-resolve-combat-margin-bounds
  (testing "Margin is always between 0.0 and 1.0"
    (dotimes [_ 50]
      (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)]
        (is (>= (:margin result) 0.0))
        (is (<= (:margin result) 1.0))))))

(deftest test-resolve-combat-planets-only-on-attacker-win
  (testing "Planets are only transferred when attacker wins"
    (dotimes [_ 50]
      (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)
            xfer   (:planets-transferred result)]
        (when-not (:attacker-wins? result)
          (is (= 0 (:mil  xfer)))
          (is (= 0 (:food xfer)))
          (is (= 0 (:ore  xfer))))))))

(deftest test-resolve-combat-planet-transfer-does-not-exceed-total
  (testing "Total planets transferred never exceeds defender's total"
    (let [total-planets (+ (:player/mil-planets  strong-defender)
                           (:player/erg-planets strong-defender)
                           (:player/ore-planets  strong-defender))]
      (dotimes [_ 50]
        (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)
              xfer   (:planets-transferred result)
              xfer-total (+ (:mil xfer) (:food xfer) (:ore xfer))]
          (is (<= xfer-total total-planets)))))))

(deftest test-resolve-combat-losses-are-non-negative
  (testing "No unit type ever has negative losses"
    (dotimes [_ 50]
      (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)]
        (doseq [[_ v] (:attacker-losses result)]
          (is (>= v 0)))
        (doseq [[_ v] (:defender-losses result)]
          (is (>= v 0)))))))

(deftest test-resolve-combat-overwhelming-attacker-wins
  (testing "An overwhelming attacker wins consistently"
    (let [massive-attacker (merge strong-attacker
                                  {:player/soldiers  1000000
                                   :player/fighters  1000000
                                   :player/transports 10000
                                   :player/generals   1000
                                   :player/carriers   10000
                                   :player/admirals   1000
                                   :player/cmd-ships  500})
          tiny-defender    (merge strong-defender
                                  {:player/soldiers  1
                                   :player/fighters  1
                                   :player/stations  1
                                   :player/generals  1
                                   :player/admirals  1})]
      (dotimes [_ 20]
        (let [result (combat/resolve-combat game massive-attacker tiny-defender :invade)]
          (is (:attacker-wins? result)))))))

(deftest test-resolve-combat-defender-counts-include-stations
  (testing "Defender counts snapshot includes stations"
    (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)]
      (is (contains? (:defender-counts result) :stations))))
  (testing "Attacker counts snapshot does not include stations"
    (let [result (combat/resolve-combat game strong-attacker strong-defender :invade)]
      (is (not (contains? (:attacker-counts result) :stations))))))

;;;;
;;;; Combat Mode Tests
;;;;

;;; Fixtures for mode tests: a tiny defender and an overwhelming attacker so
;;; we can control outcomes deterministically across runs.

(def ^:private massive-attacker
  (merge helpers/player-defaults
         {:xt/id             #uuid "00000000-0000-0000-0000-000000000010"
          :player/empire-name "Massive Attacker"
          :player/soldiers    1000000
          :player/transports  10000
          :player/generals    1000
          :player/fighters    1000000
          :player/carriers    10000
          :player/admirals    1000
          :player/cmd-ships   500}))

(def ^:private large-defender
  (merge helpers/player-defaults
         {:xt/id             #uuid "00000000-0000-0000-0000-000000000011"
          :player/empire-name "Large Defender"
          :player/soldiers    100
          :player/transports  1
          :player/generals    1
          :player/fighters    100
          :player/carriers    1
          :player/admirals    1
          :player/cmd-ships   0
          :player/stations    1
          :player/mil-planets  50
          :player/erg-planets 30
          :player/ore-planets  20
          :player/credits      100000
          :player/food         10000
          :player/fuel         10000}))

(def ^:private resourced-defender
  (merge helpers/player-defaults
         {:xt/id             #uuid "00000000-0000-0000-0000-000000000012"
          :player/empire-name "Resourced Defender"
          :player/soldiers    1
          :player/transports  1
          :player/generals    1
          :player/fighters    1
          :player/carriers    1
          :player/admirals    1
          :player/cmd-ships   0
          :player/stations    0
          :player/mil-planets  1
          :player/erg-planets 1
          :player/ore-planets  1
          :player/credits      100000
          :player/food         10000
          :player/fuel         10000}))

(deftest raid-uses-reduced-defense
  (testing "With :raid mode, an attacker consistently beats a strong defender they would lose to in :invade"
    ;; A moderate attacker vs a defender whose power is entirely in stations.
    ;; Under :invade the defender's station power dominates; under :raid it's scaled to 10%.
    ;; strong-attacker power ≈ 1640; with 3000 stations (power 5 each), invade def_power ≈ 15015
    ;; (attacker never wins) while raid def_power ≈ 1502 (attacker frequently wins).
    (let [station-heavy-defender (assoc large-defender
                                        :player/stations   3000
                                        :player/soldiers   0
                                        :player/fighters   0)
          wins-raid   (count (filter :attacker-wins?
                                     (repeatedly 20 #(combat/resolve-combat game strong-attacker station-heavy-defender :raid))))
          wins-invade (count (filter :attacker-wins?
                                     (repeatedly 20 #(combat/resolve-combat game strong-attacker station-heavy-defender :invade))))]
      ;; Raid should win far more often since the defender's station power is reduced to 10%
      (is (> wins-raid wins-invade)))))

(deftest raid-caps-planet-capture-at-10-percent
  (testing "Raid mode never captures more than reward-mult × margin × total planets"
    ;; large-defender has 100 total planets; max possible capture = 0.75 × 0.1 × 100 = 7
    (dotimes [_ 50]
      (let [result (combat/resolve-combat game massive-attacker large-defender :raid)
            xfer   (:planets-transferred result)
            total  (+ (:mil xfer) (:food xfer) (:ore xfer))]
        (is (<= total 8))))))  ; floor(0.75 * 0.1 * 100) = 7, +1 tolerance for floating point

(deftest invade-can-capture-up-to-margin-planets
  (testing "Invade mode can capture up to 0.75 × total defender planets"
    ;; Run many times and verify at least some runs capture > 10 planets (impossible in raid).
    (let [results (repeatedly 50 #(combat/resolve-combat game massive-attacker large-defender :invade))
          max-capture (apply max (map #(let [pt (:planets-transferred %)]
                                         (+ (:mil pt) (:food pt) (:ore pt)))
                                       results))]
      (is (> max-capture 10)))))

(deftest raid-captures-resources-proportionally
  (testing "Raid mode :resources-captured = min(margin,0.75) × 0.1 × defender's resources (on win)"
    ;; Use massive attacker so we always win, and resourced-defender with known resources.
    ;; Captures use loser-rate = min(margin, 0.75), not raw margin.
    (dotimes [_ 20]
      (let [result (combat/resolve-combat game massive-attacker resourced-defender :raid)]
        (when (:attacker-wins? result)
          (let [eff-margin (min (:margin result) 0.75)
                rc         (:resources-captured result)
                expect-cr  (long (* eff-margin const/raid-reward-multiplier (:player/credits resourced-defender)))
                expect-fd  (long (* eff-margin const/raid-reward-multiplier (:player/food    resourced-defender)))
                expect-fu  (long (* eff-margin const/raid-reward-multiplier (:player/fuel    resourced-defender)))]
            (is (= expect-cr (:credits rc)))
            (is (= expect-fd (:food    rc)))
            (is (= expect-fu (:fuel    rc)))))))))

(deftest invade-captures-resources-proportionally
  (testing "Invade mode :resources-captured = min(margin,0.75) × 1.0 × defender's resources (on win)"
    (dotimes [_ 20]
      (let [result (combat/resolve-combat game massive-attacker resourced-defender :invade)]
        (when (:attacker-wins? result)
          (let [eff-margin (min (:margin result) 0.75)
                rc         (:resources-captured result)
                expect-cr  (long (* eff-margin const/invade-reward-multiplier (:player/credits resourced-defender)))
                expect-fd  (long (* eff-margin const/invade-reward-multiplier (:player/food    resourced-defender)))
                expect-fu  (long (* eff-margin const/invade-reward-multiplier (:player/fuel    resourced-defender)))]
            (is (= expect-cr (:credits rc)))
            (is (= expect-fd (:food    rc)))
            (is (= expect-fu (:fuel    rc)))))))))

(deftest losing-attacker-captures-nothing
  (testing "Failed attack captures zero planets and zero resources in both modes"
    (let [weak-attacker (assoc strong-attacker
                               :player/soldiers  1 :player/fighters  1
                               :player/transports 1 :player/carriers  1
                               :player/cmd-ships  0)]
      (doseq [mode [:raid :invade]]
        (dotimes [_ 20]
          (let [result (combat/resolve-combat game weak-attacker large-defender mode)]
            (when-not (:attacker-wins? result)
              (let [pt (:planets-transferred result)
                    rc (:resources-captured result)]
                (is (= 0 (:mil  pt)))
                (is (= 0 (:food pt)))
                (is (= 0 (:ore  pt)))
                (is (= 0 (:credits rc)))
                (is (= 0 (:food    rc)))
                (is (= 0 (:fuel    rc)))))))))))

(deftest mode-defaults-to-invade-when-nil
  (testing "Passing nil mode behaves identically to :invade mode"
    ;; Both should produce the same defender defense multiplier (1.0).
    ;; We verify by checking that the mode key is :invade in the result.
    (let [result (combat/resolve-combat game strong-attacker strong-defender nil)]
      (is (= :invade (:mode result))))))
