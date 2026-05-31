(ns com.star-empire-elite.events-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.events :as events])
  (:import (java.util Date UUID)))

;;;;
;;;; Fixtures
;;;;

(def attacker-id (UUID/fromString "00000000-0000-0000-0000-000000000001"))
(def defender-id (UUID/fromString "00000000-0000-0000-0000-000000000002"))
(def game-id     (UUID/fromString "00000000-0000-0000-0000-000000000099"))

(def meta-ctx
  {:game-id game-id
   :turn    3
   :round   2
   :at      (Date.)})

;;;;
;;;; Battle result events
;;;;

(def raid-result
  {:mode              :raid
   :attacker-id       (str attacker-id)
   :attacker-name     "Ironwall"
   :defender-id       (str defender-id)
   :defender-name     "Crimson Hand"
   :attacker-wins?    true
   :margin            0.35
   :attacker-losses   {:soldiers 10 :fighters 5}
   :defender-losses   {:soldiers 50 :fighters 20}
   :planets-transferred {:mil 1 :erg 0 :ore 2}
   :resources-captured  {:credits 5000 :food 1000 :fuel 0}})

(def invade-result
  (assoc raid-result :mode :invade))

(def strike-result
  {:mode                :strike
   :attacker-id         (str attacker-id)
   :attacker-name       "Ironwall"
   :defender-id         (str defender-id)
   :defender-name       "Crimson Hand"
   :cmd-ships-committed 5
   :cmd-ships-dispatched 3
   :cmd-ships-lost      1
   :damage-rate         0.05
   :defender-losses     {:soldiers 100 :transports 2 :fighters 10
                         :carriers 0 :admirals 0 :generals 0 :stations 1}})

(deftest test-event-of-battle-result-raid
  (let [evt (events/event-of-battle-result raid-result meta-ctx)]
    (is (= :raid        (:event/kind evt)))
    (is (= :public      (:event/visibility evt)))
    (is (= game-id      (:event/game evt)))
    (is (= 3            (:event/turn evt)))
    (is (= 2            (:event/round evt)))
    (is (= attacker-id  (:event/attacker evt)))
    (is (= "Ironwall"   (:event/attacker-name evt)))
    (is (= defender-id  (:event/defender evt)))
    (is (= "Crimson Hand" (:event/defender-name evt)))
    (is (string?        (:event/payload evt)))
    (is (= raid-result  (read-string (:event/payload evt))))))

(deftest test-event-of-battle-result-invade
  (let [evt (events/event-of-battle-result invade-result meta-ctx)]
    (is (= :invade (:event/kind evt)))
    (is (= :public (:event/visibility evt)))))

(deftest test-event-of-battle-result-strike
  (let [evt (events/event-of-battle-result strike-result meta-ctx)]
    (is (= :strike (:event/kind evt)))
    (is (= :public (:event/visibility evt)))
    (is (= attacker-id (:event/attacker evt)))
    (is (= defender-id (:event/defender evt)))))

;;;;
;;;; Espionage result events
;;;;

(def spy-result
  {:op             "spy"
   :attacker-id    (str attacker-id)
   :defender-id    (str defender-id)
   :defender-name  "Crimson Hand"
   :attacker-wins? true
   :agents-captured nil
   :intel          {:soldiers 500 :fighters 100}})

(def incite-result
  {:op               "incite"
   :attacker-id      (str attacker-id)
   :defender-id      (str defender-id)
   :defender-name    "Crimson Hand"
   :attacker-wins?   true
   :stability-damage 5
   :agents-captured  nil})

(def bomb-result
  {:op                   "bomb"
   :attacker-id          (str attacker-id)
   :defender-id          (str defender-id)
   :defender-name        "Crimson Hand"
   :attacker-wins?       true
   :soldiers             200
   :transports           5
   :fighters             30
   :carriers             1
   :agents-captured      nil})

(def defect-result
  {:op               "defect"
   :attacker-id      (str attacker-id)
   :defender-id      (str defender-id)
   :defender-name    "Crimson Hand"
   :attacker-wins?   true
   :agents-captured  nil
   :agents-defected  8})

(def failed-spy-result
  {:op             "spy"
   :attacker-id    (str attacker-id)
   :defender-id    (str defender-id)
   :defender-name  "Crimson Hand"
   :attacker-wins? false
   :agents-captured 3
   :intel          nil})

(deftest test-event-of-espionage-result-spy
  (let [[att def-evt] (events/event-of-espionage-result spy-result "Ironwall" meta-ctx)]
    (testing "attacker event"
      (is (= :spy            (:event/kind att)))
      (is (= :attacker-only  (:event/visibility att)))
      (is (= attacker-id     (:event/attacker att)))
      (is (= "Ironwall"      (:event/attacker-name att)))
      (is (= defender-id     (:event/defender att)))
      (is (= "Crimson Hand"  (:event/defender-name att)))
      (is (= spy-result      (read-string (:event/payload att)))))
    (testing "defender event has attacker redacted"
      (is (= :spy            (:event/kind def-evt)))
      (is (= :defender-only  (:event/visibility def-evt)))
      (is (nil?              (:event/attacker def-evt)))
      (is (nil?              (:event/attacker-name def-evt)))
      (is (= defender-id     (:event/defender def-evt)))
      (let [payload (read-string (:event/payload def-evt))]
        (is (nil? (:attacker-id payload)) "attacker-id redacted from defender payload")
        (is (= "Crimson Hand" (:defender-name payload)))))))

(deftest test-event-of-espionage-result-incite
  (let [[att _] (events/event-of-espionage-result incite-result "Ironwall" meta-ctx)]
    (is (= :incite (:event/kind att)))
    (is (= :attacker-only (:event/visibility att)))))

(deftest test-event-of-espionage-result-bomb
  (let [[att def-evt] (events/event-of-espionage-result bomb-result "Ironwall" meta-ctx)]
    (is (= :bomb (:event/kind att)))
    (is (= :attacker-only (:event/visibility att)))
    (is (= :defender-only (:event/visibility def-evt)))))

(deftest test-event-of-espionage-result-defect
  (let [[att _] (events/event-of-espionage-result defect-result "Ironwall" meta-ctx)]
    (is (= :defect (:event/kind att)))
    (is (= :attacker-only (:event/visibility att)))))

(deftest test-event-of-espionage-result-failure
  (let [[att def-evt] (events/event-of-espionage-result failed-spy-result "Ironwall" meta-ctx)]
    (testing "attacker event records failure"
      (is (= :spy (:event/kind att)))
      (let [payload (read-string (:event/payload att))]
        (is (false? (:attacker-wins? payload)))
        (is (= 3 (:agents-captured payload)))))
    (testing "defender event records interception"
      (is (= :defender-only (:event/visibility def-evt)))
      (is (nil? (:event/attacker def-evt))))))

;;;;
;;;; Breakaway events
;;;;

(def breakaway-result
  {:roll       85
   :stability  60
   :triggered? true
   :ore-lost   1
   :erg-lost   2
   :mil-lost   0
   :total-lost 3})

(deftest test-event-of-breakaway
  (let [player-id defender-id
        evt       (events/event-of-breakaway breakaway-result player-id "Crimson Hand" meta-ctx)]
    (is (= :breakaway (:event/kind evt)))
    (is (= :public    (:event/visibility evt)))
    (is (nil?         (:event/attacker evt)))
    (is (nil?         (:event/attacker-name evt)))
    (is (= player-id  (:event/defender evt)))
    (is (= "Crimson Hand" (:event/defender-name evt)))
    (let [payload (read-string (:event/payload evt))]
      (is (true? (:triggered? payload)))
      (is (= 3 (:total-lost payload))))))

;;;;
;;;; Elimination events
;;;;

(deftest test-event-of-elimination
  (let [player-id defender-id
        evt       (events/event-of-elimination player-id "Crimson Hand" meta-ctx)]
    (is (= :elimination (:event/kind evt)))
    (is (= :public      (:event/visibility evt)))
    (is (nil?           (:event/attacker evt)))
    (is (nil?           (:event/attacker-name evt)))
    (is (= player-id    (:event/defender evt)))
    (is (= "Crimson Hand" (:event/defender-name evt)))))

;;;;
;;;; Common field validation
;;;;

(deftest test-all-events-carry-meta-fields
  (let [events [(events/event-of-battle-result raid-result meta-ctx)
                (first (events/event-of-espionage-result spy-result "Ironwall" meta-ctx))
                (events/event-of-breakaway breakaway-result defender-id "Crimson Hand" meta-ctx)
                (events/event-of-elimination defender-id "Crimson Hand" meta-ctx)]]
    (doseq [evt events]
      (is (= game-id (:event/game evt)))
      (is (= 3       (:event/turn evt)))
      (is (= 2       (:event/round evt)))
      (is (inst?     (:event/at evt))))))
