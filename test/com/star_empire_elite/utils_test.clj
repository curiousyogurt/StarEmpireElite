(ns com.star-empire-elite.utils-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

;;;;
;;;; parse-numeric-input Tests
;;;;

(deftest test-parse-numeric-input-digit-strings
  (testing "Parses plain numeric strings correctly"
    (is (= 0   (utils/parse-numeric-input "0")))
    (is (= 42  (utils/parse-numeric-input "42")))
    (is (= 999 (utils/parse-numeric-input "999")))
    (is (= 1000000 (utils/parse-numeric-input "1000000")))))

(deftest test-parse-numeric-input-empty-and-nil
  (testing "Returns 0 for empty string and nil"
    (is (= 0 (utils/parse-numeric-input "")))
    (is (= 0 (utils/parse-numeric-input nil)))))

(deftest test-parse-numeric-input-non-numeric
  (testing "Strips non-numeric characters and parses remaining digits"
    (is (= 0  (utils/parse-numeric-input "abc")))
    (is (= 12 (utils/parse-numeric-input "1a2b")))
    (is (= 5  (utils/parse-numeric-input "  5  ")))))

(deftest test-parse-numeric-input-negative-sign
  (testing "Strips the minus sign — always returns non-negative"
    ;; The minus sign is not a digit; parse-numeric-input strips it.
    (is (= 50 (utils/parse-numeric-input "-50")))
    (is (= 0  (utils/parse-numeric-input "-")))))

(deftest test-parse-numeric-input-integers
  (testing "Handles integers (converts to string internally)"
    (is (= 7   (utils/parse-numeric-input 7)))
    (is (= 100 (utils/parse-numeric-input 100)))))

(deftest test-parse-numeric-input-overflow-protection
  (testing "Very large inputs are capped at 1 billion to prevent long overflow"
    (is (= 1000000000 (utils/parse-numeric-input "99999999999999999999999")))
    (is (= 1000000000 (utils/parse-numeric-input "1000000001")))
    (is (= 1000000000 (utils/parse-numeric-input 1000000000)))
    (is (= 999999999  (utils/parse-numeric-input "999999999")))))

;;;;
;;;; validate-phase Tests
;;;;

(def ^:private test-phase-player-id #uuid "00000000-0000-0000-0000-000000000001")

(deftest test-validate-phase-correct-phase
  (testing "Returns nil when the player is in the expected phase"
    (let [player {:player/current-phase 3}]
      (is (nil? (utils/validate-phase player 3 test-phase-player-id))))))

(deftest test-validate-phase-wrong-phase
  (testing "Returns a 303 redirect to the game overview when phase does not match"
    (let [player {:player/current-phase 2}
          result (utils/validate-phase player 3 test-phase-player-id)]
      (is (= 303 (:status result)))
      (is (= (str "/app/game/" test-phase-player-id)
             (get-in result [:headers "location"]))))))

(deftest test-validate-phase-boundaries
  (testing "Handles phase 1 and phase 6 correctly"
    (is (nil? (utils/validate-phase {:player/current-phase 1} 1 test-phase-player-id)))
    (is (nil? (utils/validate-phase {:player/current-phase 6} 6 test-phase-player-id)))
    (is (some? (utils/validate-phase {:player/current-phase 1} 6 test-phase-player-id)))))

;;;;
;;;; same-calendar-day? Tests
;;;;

(deftest test-same-calendar-day-now
  (testing "The current instant is on today's UTC calendar day"
    (is (true? (utils/same-calendar-day? (java.util.Date.))))))

(deftest test-same-calendar-day-epoch
  (testing "The Unix epoch (1970-01-01) is not on today's calendar day"
    (is (false? (utils/same-calendar-day? (java.util.Date. 0))))))

(deftest test-same-calendar-day-yesterday
  (testing "A date 24 hours ago is not on today's calendar day (unless run near midnight)"
    ;; 49 hours ago is safely in the past regardless of when the test runs.
    (let [d (java.util.Date. (- (System/currentTimeMillis) (* 49 3600 1000)))]
      (is (false? (utils/same-calendar-day? d))))))

;;;;
;;;; day-exhausted? Tests
;;;;

(deftest test-day-exhausted-all-rounds-used-today
  (testing "Returns true when current-round exceeds rounds-per-day and last completed today"
    (let [player {:player/current-round           5
                  :player/last-round-completed-at (java.util.Date.)}
          game   {:game/rounds-per-day 4}]
      (is (true? (utils/day-exhausted? player game))))))

(deftest test-day-exhausted-round-not-exceeded
  (testing "Returns false when current-round is within the daily limit"
    (let [player {:player/current-round           2
                  :player/last-round-completed-at (java.util.Date.)}
          game   {:game/rounds-per-day 4}]
      (is (false? (utils/day-exhausted? player game))))))

(deftest test-day-exhausted-no-completed-at
  (testing "Returns false when last-round-completed-at is nil (game just started)"
    (let [player {:player/current-round           5
                  :player/last-round-completed-at nil}
          game   {:game/rounds-per-day 4}]
      (is (false? (utils/day-exhausted? player game))))))

(deftest test-day-exhausted-completed-yesterday
  (testing "Returns false when last completion was on a previous UTC day"
    (let [yesterday (java.util.Date. (- (System/currentTimeMillis) (* 49 3600 1000)))
          player    {:player/current-round           5
                     :player/last-round-completed-at yesterday}
          game      {:game/rounds-per-day 4}]
      (is (false? (utils/day-exhausted? player game))))))

;;;;
;;;; round-cooldown-ms Tests
;;;;

(deftest test-round-cooldown-nil-mid-round
  (testing "Returns nil when player is mid-round (turns-used > 0)"
    (let [player {:player/current-round           2
                  :player/current-turn            3
                  :player/turns-used              2
                  :player/last-round-completed-at (java.util.Date.)}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 2}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-nil-no-completed
  (testing "Returns nil when no round has been completed yet"
    (let [player {:player/current-round           1
                  :player/current-turn            1
                  :player/turns-used              0
                  :player/last-round-completed-at nil}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 2}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-between-rounds-active
  (testing "Returns positive ms when between rounds and cooldown has not expired"
    (let [just-now (java.util.Date.)
          player   {:player/current-round           2
                    :player/current-turn            1
                    :player/turns-used              0
                    :player/last-round-completed-at just-now}
          game     {:game/rounds-per-day 4 :game/hours-between-rounds 24}]
      (is (pos? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-between-rounds-expired
  (testing "Returns nil when the between-round cooldown has passed"
    (let [long-ago (java.util.Date. (- (System/currentTimeMillis) (* 25 3600 1000)))
          player   {:player/current-round           2
                    :player/current-turn            1
                    :player/turns-used              0
                    :player/last-round-completed-at long-ago}
          game     {:game/rounds-per-day 4 :game/hours-between-rounds 24}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-zero-hour-cooldown
  (testing "Returns nil when hours-between-rounds is 0 regardless of when last completed"
    (let [just-now (java.util.Date.)
          player   {:player/current-round           2
                    :player/current-turn            1
                    :player/turns-used              0
                    :player/last-round-completed-at just-now}
          game     {:game/rounds-per-day 4 :game/hours-between-rounds 0}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-day-exhausted
  (testing "Returns positive ms (until midnight) when day is exhausted"
    (let [player {:player/current-round           5
                  :player/current-turn            1
                  :player/turns-used              0
                  :player/last-round-completed-at (java.util.Date.)}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 0}]
      (is (pos? (utils/round-cooldown-ms player game))))))

;;;;
;;;; format-cooldown-duration Tests
;;;;

(deftest test-format-cooldown-duration-hours
  (testing "Formats a duration with both hours and minutes"
    (is (= "7h 18m"  (utils/format-cooldown-duration (* 1000 (+ (* 7 3600) (* 18 60))))))))

(deftest test-format-cooldown-duration-minutes
  (testing "Formats a duration with only minutes and seconds (no hours)"
    (is (= "15m 0s"  (utils/format-cooldown-duration (* 900 1000))))
    (is (= "1m 30s"  (utils/format-cooldown-duration (* 90 1000))))))

(deftest test-format-cooldown-duration-seconds
  (testing "Formats a duration of less than 60 seconds"
    (is (= "42s" (utils/format-cooldown-duration (* 42 1000))))
    (is (= "0s"  (utils/format-cooldown-duration 0)))))

;;;;
;;;; display-turn-round Tests
;;;;

(def ^:private test-game-2r-4t {:game/rounds-per-day 2 :game/turns-per-round 4})

(deftest test-display-turn-round-mid-round
  (testing "Returns current turn and round when actively in a round"
    (let [player {:player/current-round 1 :player/current-turn 3
                  :player/turns-used 2 :player/last-round-completed-at nil}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 3 (:turn  result)))
      (is (= 1 (:round result))))))

(deftest test-display-turn-round-between-rounds
  (testing "Shows the COMPLETED round state when waiting between rounds"
    ;; turn=1, turns-used=0, and last-completed is set → player finished round 1,
    ;; now waiting for round 2.  Display should show Turn 4/4 | Round 1/2.
    (let [ts     (java.util.Date.)
          player {:player/current-round 2 :player/current-turn 1
                  :player/turns-used 0 :player/last-round-completed-at ts}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 4 (:turn  result)))   ; shows turns-per-round (max)
      (is (= 1 (:round result))))))  ; shows previous round (current-round - 1)

(deftest test-display-turn-round-day-complete
  (testing "Caps both turn and round at their maxes when the day is complete"
    ;; current-round (3) > rounds-per-day (2) → day exhausted
    (let [player {:player/current-round 3 :player/current-turn 1
                  :player/turns-used 0 :player/last-round-completed-at (java.util.Date.)}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 4 (:turn  result)))  ; turns-per-round
      (is (= 2 (:round result))))))  ; rounds-per-day

(deftest test-display-turn-round-new-round-started
  (testing "Shows the new round once at least one turn has been taken in it"
    (let [ts     (java.util.Date.)
          player {:player/current-round 2 :player/current-turn 2
                  :player/turns-used 1 :player/last-round-completed-at ts}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 2 (:turn  result)))
      (is (= 2 (:round result))))))

(deftest test-display-turn-round-game-start
  (testing "Shows turn 1, round 1 at game start (no completed round yet)"
    (let [player {:player/current-round 1 :player/current-turn 1
                  :player/turns-used 0 :player/last-round-completed-at nil}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 1 (:turn  result)))
      (is (= 1 (:round result))))))

;;;;
;;;; load-player-and-game Tests
;;;;

(def ^:private lpg-player-id #uuid "aaaaaaaa-0000-0000-0000-000000000001")
(def ^:private lpg-game-id   #uuid "bbbbbbbb-0000-0000-0000-000000000002")

(def ^:private lpg-player {:xt/id lpg-player-id :player/game lpg-game-id :player/credits 500})
(def ^:private lpg-game   {:xt/id lpg-game-id   :game/name "Test Game"})

(deftest test-load-player-and-game-player-not-found
  (testing "Returns an :error map with 404 when the player does not exist"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (utils/load-player-and-game nil (str lpg-player-id))]
        (is (some? (:error result)))
        (is (= 404 (get-in result [:error :status])))
        (is (= "Player not found" (get-in result [:error :body])))))))

(deftest test-load-player-and-game-success
  (testing "Returns :player, :game, and :player-id when both entities exist"
    (with-redefs [xt/entity (fn [_ id]
                              (cond (= id lpg-player-id) lpg-player
                                    (= id lpg-game-id)   lpg-game
                                    :else                 nil))]
      (let [result (utils/load-player-and-game nil (str lpg-player-id))]
        (is (nil?           (:error     result)))
        (is (= lpg-player   (:player    result)))
        (is (= lpg-game     (:game      result)))
        (is (= lpg-player-id (:player-id result)))))))

(deftest test-load-player-and-game-bad-uuid
  (testing "Throws IllegalArgumentException for an invalid UUID string"
    (is (thrown? IllegalArgumentException
                 (utils/load-player-and-game nil "not-a-uuid")))))
