(ns com.star-empire-elite.pages.app.outcomes-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.outcomes :as outcomes]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures
;;;;

(def test-player-id #uuid "00000000-0000-0000-0000-000000000050")
(def test-game-id   #uuid "00000000-0000-0000-0000-000000000060")

;; Game with 3 turns per round (so turn 3 triggers end-of-round).
(def test-game
  {:xt/id                  test-game-id
   :game/turns-per-round   3
   :game/rounds-per-day    2})

;; Player in phase 6 (outcomes), mid-round at turn 1 of 3.
(def test-player
  {:xt/id                           test-player-id
   :player/game                     test-game-id
   :player/empire-name              "Test Empire"
   :player/current-phase            6
   :player/current-turn             1
   :player/current-round            1
   :player/turns-used               0
   ;; Resources (needed for score calculation)
   :player/credits                  5000
   :player/population               0
   :player/mil-planets              3
   :player/erg-planets             2
   :player/ore-planets              1
   :player/soldiers                 100
   :player/fighters                 20
   :player/cmd-ships                1
   :player/stations                 5
   :player/generals                 2
   :player/admirals                 1
   ;; Fields cleared by apply-outcomes
   :player/last-battle-result        "some-result"
   :player/last-espionage-result     "some-result"
   :player/last-population-growth    3
   :player/pending-espionage         test-game-id ; non-nil to verify it is cleared
   :player/incoming-attacks          ["attack-1"]
   :player/incoming-espionage-fails  2})

;;;;
;;;; calculate-score Tests
;;;;
;;;; calculate-score is private. Access it via the var to verify the scoring formula
;;;; without going through a full apply-outcomes call.
;;;;

(def calculate-score #'outcomes/calculate-score)

(deftest test-calculate-score-formula
  (testing "Planets dominate the score, military and credits are tie-breakers"
    (let [player {:player/mil-planets  2 :player/erg-planets 1 :player/ore-planets  0
                  :player/soldiers     10 :player/fighters     5 :player/cmd-ships    1
                  :player/stations     3  :player/generals     1 :player/admirals     0
                  :player/credits      3000}
          ;; 2*500 + 1*300 + 0*200 + 10*1 + 5*3 + 1*20 + 3*5 + 1*50 + 0*100 + floor(3000/1000)
          ;; = 1000 + 300 + 0 + 10 + 15 + 20 + 15 + 50 + 0 + 3 = 1413
          expected (+ (* 2 500) (* 1 300) (* 0 200)
                      (* 10 1) (* 5 3) (* 1 20) (* 3 5) (* 1 50) (* 0 100)
                      (quot 3000 1000))]
      (is (= expected (calculate-score player))))))

(deftest test-calculate-score-zero-player
  (testing "Returns zero when all values are zero"
    (let [player {:player/mil-planets 0 :player/erg-planets 0 :player/ore-planets 0
                  :player/soldiers 0 :player/fighters 0 :player/cmd-ships 0
                  :player/stations 0 :player/generals 0 :player/admirals 0
                  :player/credits 0}]
      (is (= 0 (calculate-score player))))))

(deftest test-calculate-score-negative-credits-clamped
  (testing "Negative credits contribute 0 to score (clamped via max 0)"
    (let [player {:player/mil-planets 0 :player/erg-planets 0 :player/ore-planets 0
                  :player/soldiers 0 :player/fighters 0 :player/cmd-ships 0
                  :player/stations 0 :player/generals 0 :player/admirals 0
                  :player/credits -9999}]
      (is (= 0 (calculate-score player))))))

(deftest test-calculate-score-planets-dominate
  (testing "A single military planet outweighs hundreds of soldiers"
    (let [planet-player  {:player/mil-planets 1 :player/erg-planets 0 :player/ore-planets 0
                          :player/soldiers 0 :player/fighters 0 :player/cmd-ships 0
                          :player/stations 0 :player/generals 0 :player/admirals 0
                          :player/credits 0}
          soldier-player {:player/mil-planets 0 :player/erg-planets 0 :player/ore-planets 0
                          :player/soldiers 499 :player/fighters 0 :player/cmd-ships 0
                          :player/stations 0 :player/generals 0 :player/admirals 0
                          :player/credits 0}]
      ;; 1 mil-planet = 500 points; 499 soldiers = 499 points.
      (is (> (calculate-score planet-player) (calculate-score soldier-player))))))

;;;;
;;;; apply-outcomes Tests
;;;;
;;;; apply-outcomes writes to the database. xt/entity and biff/submit-tx are replaced
;;;; with test doubles.
;;;;

(deftest test-apply-outcomes-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                             :biff/db nil})]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-outcomes-wrong-phase
  (testing "Redirects to game overview when player is not in phase 6"
    (let [player (assoc test-player :player/current-phase 5)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])]
        (let [result (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                               :biff/db nil})]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id) (get-in result [:headers "location"]))))))))

(deftest test-apply-outcomes-mid-round
  (testing "Mid-round: increments turn, keeps round, resets to phase 1"
    ;; Player is at turn 1 of 3, turns-used 0. After submit: turn 2, round stays 1.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                               :biff/db nil})
              tx     (first @tx-atom)]
          ;; Redirect to income
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/income") (get-in result [:headers "location"])))
          ;; Turn advances, round stays the same
          (is (= 2 (:player/current-turn  tx)))
          (is (= 1 (:player/current-round tx)))
          ;; Phase resets to 1 (income)
          (is (= 1 (:player/current-phase tx)))
          ;; Turns used increments
          (is (= 1 (:player/turns-used tx)))
          ;; last-round-completed-at is NOT set mid-round
          (is (nil? (:player/last-round-completed-at tx))))))))

(deftest test-apply-outcomes-end-of-round
  (testing "End-of-round: resets turn to 1, increments round, records completion time, resets turns-used"
    ;; turns-per-round is 3; player at turns-used 2, so this is the last turn.
    (let [end-round-player (assoc test-player :player/current-turn 3 :player/turns-used 2)
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [end-round-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                  :biff/db nil})
        (let [tx (first @tx-atom)]
          ;; Turn wraps back to 1, round increments
          (is (= 1 (:player/current-turn  tx)))
          (is (= 2 (:player/current-round tx)))
          ;; Turns used resets to 0 at round end
          (is (= 0 (:player/turns-used tx)))
          ;; Completion timestamp is set (non-nil)
          (is (some? (:player/last-round-completed-at tx))))))))

(deftest test-apply-outcomes-clears-all-results
  (testing "Clears all battle/espionage results and incoming notifications"
    ;; test-player has non-nil values for all clearable fields.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                  :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (nil? (:player/last-battle-result       tx)))
          (is (nil? (:player/last-espionage-result    tx)))
          (is (nil? (:player/last-population-growth   tx)))
          (is (nil? (:player/pending-espionage        tx)))
          (is (nil? (:player/incoming-attacks         tx)))
          (is (= 0  (:player/incoming-espionage-fails tx))))))))

(deftest test-apply-outcomes-records-score
  (testing "Writes a computed score to the transaction"
    ;; Score is computed from the player's current resources by calculate-score.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                  :biff/db nil})
        (let [tx (first @tx-atom)
              expected-score (calculate-score test-player)]
          (is (= expected-score (:player/score tx))))))))

;;;;
;;;; outcomes-page Tests
;;;;
;;;; outcomes-page is a pure UI function. Tests verify it renders without error across
;;;; the key combinations of battle/espionage results.
;;;;

(def sample-battle-result
  {:attacker-wins? true
   :defender-name  "Enemy Empire"
   :attacker-forces {:soldiers 50 :fighters 10 :cmd-ships 0 :transports 5
                     :carriers 1 :generals 1 :admirals 0}
   :defender-forces {:soldiers 30 :fighters 5 :cmd-ships 0 :transports 0
                     :carriers 0 :generals 0 :admirals 0 :stations 3}
   :attacker-losses {:soldiers-lost 5 :fighters-lost 1 :cmd-ships-lost 0
                     :transports-lost 0 :carriers-lost 0 :generals-lost 0
                     :admirals-lost 0 :stations-lost 0}
   :defender-losses {:soldiers-lost 15 :fighters-lost 3 :cmd-ships-lost 0
                     :transports-lost 0 :carriers-lost 0 :generals-lost 0
                     :admirals-lost 0 :stations-lost 1}
   :attacker-counts {:soldiers 50 :fighters 10 :cmd-ships 0 :transports 5
                     :carriers 1 :generals 1 :admirals 0}
   :planets-transferred {:mil 0 :erg 0 :ore 0}})

(def sample-espionage-result
  {:attacker-wins? true
   :defender-name  "Enemy Empire"
   :intel {:soldiers 30 :fighters 5 :cmd-ships 0 :transports 0
           :carriers 0 :generals 0 :admirals 0 :stations 3 :agents 1}})

(deftest test-outcomes-page-no-results
  (testing "Renders without error when no battle or espionage result"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil})))))

(deftest test-outcomes-page-with-battle-result
  (testing "Renders without error when a battle result is present"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result sample-battle-result
                                          :espionage-result nil})))))

(deftest test-outcomes-page-with-espionage-result
  (testing "Renders without error when an espionage result is present"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil
                                          :espionage-result sample-espionage-result})))))

(deftest test-outcomes-page-with-all-results
  (testing "Renders without error when both battle and espionage results are present"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result sample-battle-result
                                          :espionage-result sample-espionage-result})))))

(deftest test-outcomes-page-with-incoming-attacks
  (testing "Renders without error when player received incoming attacks this round"
    ;; Incoming attacks are stored as pr-str'd maps in :player/incoming-attacks.
    (let [attacker-result {:attacker-wins? true
                           :attacker-name  "Aggressor"
                           :defender-counts {:soldiers 50 :fighters 5 :cmd-ships 0 :transports 2
                                             :carriers 0 :generals 1 :admirals 0 :stations 3}
                           :attacker-losses {:soldiers-lost 0 :fighters-lost 0 :cmd-ships-lost 0
                                             :transports-lost 0 :carriers-lost 0 :generals-lost 0
                                             :admirals-lost 0 :stations-lost 0}
                           :defender-losses {:soldiers-lost 10 :fighters-lost 2 :cmd-ships-lost 0
                                             :transports-lost 0 :carriers-lost 0 :generals-lost 0
                                             :admirals-lost 0 :stations-lost 1}
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player-with-attacks (assoc test-player
                                     :player/incoming-attacks [(pr-str attacker-result)]
                                     :player/incoming-espionage-fails 1)]
      (is (vector? (outcomes/outcomes-page {:player player-with-attacks :game test-game
                                            :battle-result nil :espionage-result nil}))))))

(deftest test-outcomes-page-with-pop-growth
  (testing "Renders population growth message when pop-growth is positive"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil
                                          :pop-growth 2}))))
  (testing "Renders held-steady message when pop-growth is zero"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil
                                          :pop-growth 0}))))
  (testing "Does not render population section when pop-growth is nil (mid-round)"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil
                                          :pop-growth nil})))))
