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
    (let [result (combat/resolve-combat game strong-attacker strong-defender)]
      (doseq [k [:attacker-id :attacker-name :defender-id :defender-name
                 :attacker-counts :defender-counts
                 :attacker-forces :defender-forces
                 :attacker-roll :defender-roll :attacker-wins?
                 :margin :attacker-losses :defender-losses
                 :planets-transferred]]
        (is (contains? result k) (str "Missing key: " k))))))

(deftest test-resolve-combat-ids-are-strings
  (testing "UUIDs are stored as strings"
    (let [result (combat/resolve-combat game strong-attacker strong-defender)]
      (is (string? (:attacker-id result)))
      (is (string? (:defender-id result))))))

(deftest test-resolve-combat-margin-bounds
  (testing "Margin is always between 0.0 and 1.0"
    (dotimes [_ 50]
      (let [result (combat/resolve-combat game strong-attacker strong-defender)]
        (is (>= (:margin result) 0.0))
        (is (<= (:margin result) 1.0))))))

(deftest test-resolve-combat-planets-only-on-attacker-win
  (testing "Planets are only transferred when attacker wins"
    (dotimes [_ 50]
      (let [result (combat/resolve-combat game strong-attacker strong-defender)
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
        (let [result (combat/resolve-combat game strong-attacker strong-defender)
              xfer   (:planets-transferred result)
              xfer-total (+ (:mil xfer) (:food xfer) (:ore xfer))]
          (is (<= xfer-total total-planets)))))))

(deftest test-resolve-combat-losses-are-non-negative
  (testing "No unit type ever has negative losses"
    (dotimes [_ 50]
      (let [result (combat/resolve-combat game strong-attacker strong-defender)]
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
        (let [result (combat/resolve-combat game massive-attacker tiny-defender)]
          (is (:attacker-wins? result)))))))

(deftest test-resolve-combat-defender-counts-include-stations
  (testing "Defender counts snapshot includes stations"
    (let [result (combat/resolve-combat game strong-attacker strong-defender)]
      (is (contains? (:defender-counts result) :stations))))
  (testing "Attacker counts snapshot does not include stations"
    (let [result (combat/resolve-combat game strong-attacker strong-defender)]
      (is (not (contains? (:attacker-counts result) :stations))))))
