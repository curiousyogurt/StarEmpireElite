(ns com.star-empire-elite.pages.app.dashboard-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.pages.app.dashboard :as dashboard]))

;;
;; Test fixtures
;;

(def test-game-id #uuid "00000000-0000-0000-0000-000000000001")
(def test-player-id #uuid "00000000-0000-0000-0000-000000000002")

;; Minimal game map for display tests — only the turn/round fields are needed
(defn make-game [turns-per-round rounds-per-day]
  {:xt/id test-game-id
   :game/turns-per-round turns-per-round
   :game/rounds-per-day  rounds-per-day})

;; Minimal player map for display tests
(defn make-player
  ([current-turn current-round]
   {:xt/id test-player-id
    :player/current-turn  current-turn
    :player/current-round current-round})
  ([current-turn current-round turns-used last-completed]
   (assoc (make-player current-turn current-round)
          :player/turns-used               turns-used
          :player/last-round-completed-at  last-completed)))

;;
;; format-turn-round
;;
;; The display caps values at their per-day/per-round maxes so that rolled-over
;; state (waiting for next day) shows the completed values rather than reset ones.
;;

(deftest test-format-turn-round-mid-round
  (testing "In the middle of a round, shows actual turn and round"
    (is (= "Turn 1/2 | Round 1/1"
           (dashboard/format-turn-round
            (make-player 1 1)
            (make-game 2 1))))))

(deftest test-format-turn-round-last-turn
  (testing "On the last turn of a round, shows the max turn number"
    (is (= "Turn 2/2 | Round 1/1"
           (dashboard/format-turn-round
            (make-player 2 1)
            (make-game 2 1))))))

(deftest test-format-turn-round-day-complete
  (testing "After all turns and rounds are used (day complete), shows max/max"
    ;; With 2 turns-per-round and 1 round-per-day, after completing everything:
    ;; current-turn rolls to 1 and current-round increments to 2 (> max of 1).
    (is (= "Turn 2/2 | Round 1/1"
           (dashboard/format-turn-round
            (make-player 1 2)   ; rolled over: turn=1, round=2
            (make-game 2 1))))))

(deftest test-format-turn-round-multi-round-in-progress
  (testing "Multi-round game mid-day shows current round correctly"
    (is (= "Turn 1/3 | Round 2/4"
           (dashboard/format-turn-round
            (make-player 1 2)
            (make-game 3 4))))))

(deftest test-format-turn-round-multi-round-day-complete
  (testing "Multi-round game day-complete caps both values at max"
    ;; 3 turns-per-round, 4 rounds-per-day. After last round, current-round=5.
    (is (= "Turn 3/3 | Round 4/4"
           (dashboard/format-turn-round
            (make-player 1 5)   ; rolled over: turn=1, round=5 (> max 4)
            (make-game 3 4))))))

(def some-timestamp (java.util.Date.))

;; Standard game fixture using real constants — tests that model actual game state
;; should use this so they stay correct if constants change.
(def standard-game (make-game const/turns-per-round const/rounds-per-day))

(deftest test-format-turn-round-between-rounds
  (testing "After completing a round, shows completed round state until next round starts"
    ;; Counters have reset (turn=1, round=2, turns-used=0) but no turns taken in round 2 yet.
    (is (= (str "Turn " const/turns-per-round "/" const/turns-per-round
                " | Round 1/" const/rounds-per-day)
           (dashboard/format-turn-round
            (make-player 1 2 0 some-timestamp)
            standard-game)))))

(deftest test-format-turn-round-new-round-started
  (testing "After taking the first turn of a new round, shows the new round"
    ;; turns-used=1 means a turn has been submitted in round 2.
    (is (= (str "Turn 2/" const/turns-per-round
                " | Round 2/" const/rounds-per-day)
           (dashboard/format-turn-round
            (make-player 2 2 1 some-timestamp)
            standard-game)))))

(deftest test-format-turn-round-game-start
  (testing "At game start (no turns taken, no completed round) shows turn 1 round 1"
    ;; last-round-completed-at is nil — game hasn't started yet.
    (is (= (str "Turn 1/" const/turns-per-round
                " | Round 1/" const/rounds-per-day)
           (dashboard/format-turn-round
            (make-player 1 1 0 nil)
            standard-game)))))
