(ns com.star-empire-elite.pages.app.dashboard-test
  (:require [clojure.test :refer :all]
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
(defn make-player [current-turn current-round]
  {:xt/id test-player-id
   :player/current-turn  current-turn
   :player/current-round current-round})

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

(deftest test-format-turn-round-first-turn-of-new-round
  (testing "First turn of a new round mid-day shows turn 1"
    ;; 3 turns-per-round, 4 rounds-per-day. Just finished round 1, starting round 2.
    (is (= "Turn 1/3 | Round 2/4"
           (dashboard/format-turn-round
            (make-player 1 2)
            (make-game 3 4))))))
