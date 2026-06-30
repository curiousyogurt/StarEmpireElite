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
  (testing "Returns true when rounds-started-today >= rounds-per-day and last-turn-at is today"
    (let [player {:player/rounds-started-today 4
                  :player/turns-used           0
                  :player/last-turn-at         (java.util.Date.)}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 0}]
      (is (true? (utils/day-exhausted? player game))))))

(deftest test-day-exhausted-round-not-exceeded
  (testing "Returns false when rounds-started-today is within the daily limit"
    (let [player {:player/rounds-started-today 2
                  :player/turns-used           0
                  :player/last-turn-at         (java.util.Date.)}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 0}]
      (is (false? (utils/day-exhausted? player game))))))

(deftest test-day-exhausted-no-completed-at
  (testing "Returns false when last-turn-at is nil (game just started)"
    (let [player {:player/rounds-started-today 0
                  :player/turns-used           0
                  :player/last-turn-at         nil}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 0}]
      (is (false? (utils/day-exhausted? player game))))))

(deftest test-day-exhausted-completed-yesterday
  (testing "Returns false when last-turn-at was on a previous UTC day (budget resets)"
    (let [yesterday (java.util.Date. (- (System/currentTimeMillis) (* 49 3600 1000)))
          player    {:player/rounds-started-today 4
                     :player/turns-used           0
                     :player/last-turn-at         yesterday}
          game      {:game/rounds-per-day 4 :game/hours-between-rounds 0}]
      (is (false? (utils/day-exhausted? player game))))))

;;;;
;;;; round-cooldown-ms Tests
;;;;

(deftest test-round-cooldown-nil-mid-round
  (testing "Returns nil when player is mid-round (turns-used > 0)"
    (let [player {:player/rounds-started-today 2
                  :player/current-turn         3
                  :player/turns-used           2
                  :player/last-turn-at         (java.util.Date.)}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 2}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-nil-no-completed
  (testing "Returns nil when no round has been completed yet"
    (let [player {:player/rounds-started-today 0
                  :player/current-turn         1
                  :player/turns-used           0
                  :player/last-turn-at         nil}
          game   {:game/rounds-per-day 4 :game/hours-between-rounds 2}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-between-rounds-active
  (testing "Returns positive ms when between rounds and cooldown has not expired"
    (let [just-now (java.util.Date.)
          player   {:player/rounds-started-today 1
                    :player/current-turn         1
                    :player/turns-used           0
                    :player/last-turn-at         just-now}
          game     {:game/rounds-per-day 4 :game/hours-between-rounds 24}]
      (is (pos? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-between-rounds-expired
  (testing "Returns nil when the between-round cooldown has passed"
    (let [long-ago (java.util.Date. (- (System/currentTimeMillis) (* 25 3600 1000)))
          player   {:player/rounds-started-today 1
                    :player/current-turn         1
                    :player/turns-used           0
                    :player/last-turn-at         long-ago}
          game     {:game/rounds-per-day 4 :game/hours-between-rounds 24}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-zero-hour-cooldown
  (testing "Returns nil when hours-between-rounds is 0 regardless of when last completed"
    (let [just-now (java.util.Date.)
          player   {:player/rounds-started-today 1
                    :player/current-turn         1
                    :player/turns-used           0
                    :player/last-turn-at         just-now}
          game     {:game/rounds-per-day 4 :game/hours-between-rounds 0}]
      (is (nil? (utils/round-cooldown-ms player game))))))

(deftest test-round-cooldown-day-exhausted
  (testing "Returns positive ms (until midnight) when day is exhausted"
    (let [player {:player/rounds-started-today 4
                  :player/current-turn         1
                  :player/turns-used           0
                  :player/last-turn-at         (java.util.Date.)}
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
;;;; round-status Tests
;;;;
;;;; Constants for all tests: rounds-per-day=2, turns-per-round=6, hours-between-rounds=2.
;;;; The pure round-status function is private; access via #'utils/round-status.
;;;;

(def ^:private rs @#'utils/round-status)

(defn- make-date
  "Create a Date from a day offset and hour:minute within that day (UTC).
  Day 0 is an arbitrary base date (2026-06-01). Negative days go before it."
  [day hour minute]
  (let [cal (doto (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))
              (.set 2026 5 1 0 0 0) ;; June 1 2026 00:00:00 UTC
              (.set java.util.Calendar/MILLISECOND 0)
              (.add java.util.Calendar/DAY_OF_MONTH day)
              (.set java.util.Calendar/HOUR_OF_DAY hour)
              (.set java.util.Calendar/MINUTE minute))]
    (java.util.Date. (.getTimeInMillis cal))))

;;; Scenario tests — one per row of the test table in the instruction file.

(deftest test-rs-1-brand-new-player
  (testing "Case 1: Brand-new player, no history"
    (let [now (make-date 0 12 0)
          s   (rs 0 0 1 1 nil 2 6 2 now)]
      (is (= 1 (:display-round s)))
      (is (= 1 (:display-turn  s)))
      (is (true? (:can-act? s)))
      (is (= :active (:state s))))))

(deftest test-rs-2-mid-round-fresh-turn
  (testing "Case 2: Mid-round, just started turn 3"
    (let [now (make-date 0 12 0)
          lta (make-date 0 11 59)
          s   (rs 1 2 3 1 lta 2 6 2 now)]
      (is (= 1 (:display-round s)))
      (is (= 3 (:display-turn  s)))
      (is (true? (:can-act? s)))
      (is (= :active (:state s))))))

(deftest test-rs-3-mid-turn-committed
  (testing "Case 3: Mid-turn, committed (phase 4)"
    (let [now (make-date 0 12 0)
          lta (make-date 0 11 59)
          s   (rs 1 2 3 4 lta 2 6 2 now)]
      (is (= 1 (:display-round s)))
      (is (= 3 (:display-turn  s)))
      (is (true? (:can-act? s)))
      (is (= :active (:state s))))))

(deftest test-rs-4-finished-round-spacing-not-elapsed
  (testing "Case 4: Finished round 1, spacing not elapsed (30m < 2h)"
    (let [now (make-date 0 12 30)
          lta (make-date 0 12 0)
          s   (rs 1 0 1 1 lta 2 6 2 now)]
      (is (= 1 (:display-round s)))
      (is (= 6 (:display-turn  s)))
      (is (false? (:can-act? s)))
      (is (= :spacing (:state s)))
      ;; unlock-at should be last-turn-at + 2h = 14:00
      (is (= (.getTime (make-date 0 14 0)) (.getTime (:unlock-at s)))))))

(deftest test-rs-5-finished-round-spacing-elapsed
  (testing "Case 5: Finished round 1, spacing elapsed (2h05m), budget left"
    (let [now (make-date 0 14 5)
          lta (make-date 0 12 0)
          s   (rs 1 0 1 1 lta 2 6 2 now)]
      (is (= 2 (:display-round s)))
      (is (= 1 (:display-turn  s)))
      (is (true? (:can-act? s)))
      (is (= :active (:state s))))))

(deftest test-rs-6-both-rounds-spent
  (testing "Case 6: Both rounds spent today, before midnight"
    (let [now (make-date 0 21 0)
          lta (make-date 0 18 0)
          s   (rs 2 0 1 1 lta 2 6 2 now)]
      (is (= 2 (:display-round s)))
      (is (= 6 (:display-turn  s)))
      (is (false? (:can-act? s)))
      (is (= :budget-exhausted (:state s)))
      ;; unlock-at should be next UTC midnight (day 1 00:00), not last-turn+2h
      (is (= (.getTime (make-date 1 0 0)) (.getTime (:unlock-at s)))))))

(deftest test-rs-7-spillover-mid-round-at-midnight
  (testing "Case 7: Spillover — mid-round at midnight, carried round"
    (let [now (make-date 1 0 1)
          lta (make-date 0 23 58)
          ;; rounds-started-today=2 from yesterday, but stale-day? resets E to 0
          s   (rs 2 4 5 1 lta 2 6 2 now)]
      (is (= 1 (:display-round s)))   ;; max(E=0, 1) = 1
      (is (= 5 (:display-turn  s)))
      (is (true? (:can-act? s)))       ;; mid-round exemption
      (is (= :active (:state s))))))

(deftest test-rs-8-dump-attempt-spilled-round-finished
  (testing "Case 8: Dump attempt — spilled round just finished, spacing blocks"
    (let [now (make-date 1 0 3)
          lta (make-date 1 0 2)
          ;; E=0 (fresh day), poised (turns-used=0), but spacing: 00:02+2h = 02:02
          s   (rs 0 0 1 1 lta 2 6 2 now)]
      (is (= 1 (:display-round s)))
      (is (= 1 (:display-turn  s)))
      (is (false? (:can-act? s)))
      (is (= :spacing (:state s)))
      ;; unlock-at = 02:02
      (is (= (.getTime (make-date 1 2 2)) (.getTime (:unlock-at s)))))))

(deftest test-rs-9-finished-round-at-2300-midnight-passes
  (testing "Case 9: Finished round 1 at 23:00, midnight passes, spacing still blocks"
    (let [now (make-date 1 0 10)
          lta (make-date 0 23 0)
          ;; E resets to 0 (stale), budget has room, but spacing: 23:00+2h = 01:00
          s   (rs 1 0 1 1 lta 2 6 2 now)]
      (is (= 1 (:display-round s)))
      (is (= 1 (:display-turn  s)))
      (is (false? (:can-act? s)))
      (is (= :spacing (:state s)))
      ;; unlock-at = max(0, 23:00+2h) = 01:00 on day 1
      (is (= (.getTime (make-date 1 1 0)) (.getTime (:unlock-at s)))))))

(deftest test-rs-10-lapsed-player-returns
  (testing "Case 10: Lapsed player returns next day, no in-flight round"
    (let [now (make-date 1 9 0)
          lta (make-date 0 14 0)
          ;; E resets to 0 (stale), spacing: 14:00+2h = 16:00 yesterday — elapsed
          s   (rs 0 0 1 1 lta 2 6 2 now)]
      (is (= 1 (:display-round s)))
      (is (= 1 (:display-turn  s)))
      (is (true? (:can-act? s)))
      (is (= :active (:state s))))))

;;; Regression tests — reproduce the old display bugs

(deftest test-rs-regression-r0-underflow
  (testing "Old R0 bug: first turn of a new day after clean prior-day finish"
    ;; Old display-turn-round would show Round 0 here because (dec current-round)
    ;; with current-round=1 yields 0. The new derivation must show R1 T1.
    (let [now (make-date 1 8 0)
          lta (make-date 0 22 0)
          ;; Simulates: player finished all rounds yesterday, income reset current-round to 1.
          ;; turns-used=0, current-turn=1, last-round-completed-at=yesterday.
          s   (rs 0 0 1 1 lta 2 6 2 now)]
      (is (>= (:display-round s) 1) "Round must never be 0")
      (is (= 1 (:display-round s)))
      (is (= 1 (:display-turn  s)))
      (is (true? (:can-act? s))))))

(deftest test-rs-regression-stranded-turns-used
  (testing "Old stranded-turns-used bug: mid-round at midnight, income resets round but not turn"
    ;; Old code: income day-reset set current-round=1 but left turns-used=4, current-turn=5.
    ;; Display showed Turn 5/6 | Round 1/2 — a contradictory state.
    ;; The new derivation shows the carried round correctly.
    (let [now (make-date 1 0 5)
          lta (make-date 0 23 55)
          ;; mid-round: turns-used=4, current-turn=5. Day rolled over → E=0.
          s   (rs 2 4 5 1 lta 2 6 2 now)]
      (is (>= (:display-round s) 1) "Round must never be 0")
      (is (= 5 (:display-turn s)))
      (is (true? (:can-act? s)) "Must be able to finish the carried round"))))

;;; Property tests — invariants I1, I2, I3

(deftest test-rs-property-i1-budget-limit
  (testing "I1: At most rounds-per-day round-starts per UTC day"
    ;; Simulate attempting to start 3 rounds in one day with rounds-per-day=2.
    ;; After 2 starts, the 3rd should be blocked.
    (let [base    (make-date 0 8 0)
          ;; Round 1 start: E=0, can-start? should be true
          s1      (rs 0 0 1 1 nil 2 6 2 base)
          ;; After round 1 played, E=1, spacing elapsed
          s2-time (make-date 0 12 0)
          s2      (rs 1 0 1 1 (make-date 0 9 0) 2 6 2 s2-time)
          ;; After round 2 played, E=2, attempt round 3
          s3-time (make-date 0 18 0)
          s3      (rs 2 0 1 1 (make-date 0 15 0) 2 6 2 s3-time)]
      (is (true?  (:can-act? s1)) "1st round should be startable")
      (is (true?  (:can-act? s2)) "2nd round should be startable")
      (is (false? (:can-act? s3)) "3rd round must be blocked (budget exhausted)")
      (is (= :budget-exhausted (:state s3))))))

(deftest test-rs-property-i2-spacing
  (testing "I2: Consecutive round-starts are never closer than hours-between-rounds"
    ;; Finish round 1 at 10:00. Try to start round 2 at 11:00 (< 2h) and 12:01 (> 2h).
    (let [lta     (make-date 0 10 0)
          too-soon (make-date 0 11 0)
          ok-time  (make-date 0 12 1)
          s-soon  (rs 1 0 1 1 lta 2 6 2 too-soon)
          s-ok    (rs 1 0 1 1 lta 2 6 2 ok-time)]
      (is (false? (:can-act? s-soon)) "Must block before spacing elapses")
      (is (= :spacing (:state s-soon)))
      (is (true?  (:can-act? s-ok))   "Must allow after spacing elapses"))))

(deftest test-rs-property-i3-finish-started-round
  (testing "I3: A started round can always be finished, even across midnight"
    ;; Start round 2 at 23:50. At 00:10 the next day, mid-round, must still be able to act.
    ;; Also test that every turn 1-6 within the round is playable.
    (doseq [turns-used (range 1 6)]
      (let [now (make-date 1 0 10)
            lta (make-date 0 23 50)
            s   (rs 2 turns-used (inc turns-used) 1 lta 2 6 2 now)]
        (is (true? (:can-act? s))
            (str "Must be able to finish turn " (inc turns-used) " of carried round"))))))

;;;;
;;;; display-turn-round Tests
;;;;

(def ^:private test-game-2r-4t {:game/rounds-per-day 2 :game/turns-per-round 4
                                :game/hours-between-rounds 2})

(deftest test-display-turn-round-mid-round
  (testing "Returns current turn and round when actively in a round"
    (let [player {:player/rounds-started-today 1 :player/current-turn 3
                  :player/turns-used 2 :player/last-turn-at nil}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 3 (:turn  result)))
      (is (= 1 (:round result))))))

(deftest test-display-turn-round-between-rounds
  (testing "Shows the COMPLETED round state when waiting between rounds"
    ;; rounds-started-today=1, turns-used=0, last-turn-at=just now, spacing=2h
    ;; → spacing gate blocks. Display: Turn 4/4 | Round 1/2.
    (let [ts     (java.util.Date.)
          player {:player/rounds-started-today 1 :player/current-turn 1
                  :player/turns-used 0 :player/last-turn-at ts}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 4 (:turn  result)))   ; shows turns-per-round (max)
      (is (= 1 (:round result))))))  ; shows completed round count

(deftest test-display-turn-round-day-complete
  (testing "Caps both turn and round at their maxes when the day is complete"
    ;; rounds-started-today=2 (>= rounds-per-day=2) → budget-exhausted
    (let [player {:player/rounds-started-today 2 :player/current-turn 1
                  :player/turns-used 0 :player/last-turn-at (java.util.Date.)}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 4 (:turn  result)))  ; turns-per-round
      (is (= 2 (:round result))))))  ; rounds-per-day

(deftest test-display-turn-round-new-round-started
  (testing "Shows the new round once at least one turn has been taken in it"
    (let [ts     (java.util.Date.)
          player {:player/rounds-started-today 2 :player/current-turn 2
                  :player/turns-used 1 :player/last-turn-at ts}
          result (utils/display-turn-round player test-game-2r-4t)]
      (is (= 2 (:turn  result)))
      (is (= 2 (:round result))))))

(deftest test-display-turn-round-game-start
  (testing "Shows turn 1, round 1 at game start (no completed round yet)"
    (let [player {:player/rounds-started-today 0 :player/current-turn 1
                  :player/turns-used 0 :player/last-turn-at nil}
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
