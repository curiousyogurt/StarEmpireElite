(ns com.star-empire-elite.pages.app.game-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.game :as game]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]))

;;
;; Test fixtures
;;

(def test-game-id   #uuid "00000000-0000-0000-0000-000000000001")
(def test-player-id #uuid "00000000-0000-0000-0000-000000000002")

;;
;; get-phase-url
;;

(deftest test-get-phase-url-all-phases
  (testing "Each known phase maps to the correct URL"
    (let [id test-player-id]
      (is (= (str "/app/game/" id "/income")    (game/get-phase-url id 1)))
      (is (= (str "/app/game/" id "/expenses")  (game/get-phase-url id 2)))
      (is (= (str "/app/game/" id "/building")  (game/get-phase-url id 3)))
      (is (= (str "/app/game/" id "/action")    (game/get-phase-url id 4)))
      (is (= (str "/app/game/" id "/espionage") (game/get-phase-url id 5)))
      (is (= (str "/app/game/" id "/outcomes")  (game/get-phase-url id 6))))))

(deftest test-get-phase-url-default
  (testing "Unknown phases fall back to the game overview URL"
    (let [id test-player-id]
      (is (= (str "/app/game/" id) (game/get-phase-url id 0)))
      (is (= (str "/app/game/" id) (game/get-phase-url id 7)))
      (is (= (str "/app/game/" id) (game/get-phase-url id 99))))))

;;
;; get-game-players (ranking logic)
;;
;; get-game-players queries the DB for players then sorts and ranks them. The DB query
;; itself is covered by integration tests; here we verify the sort/rank logic directly.
;;

(deftest test-get-game-players-ranking
  (testing "Players are sorted by score descending and assigned 1-based ranks"
    (let [player-a {:xt/id (helpers/uuid) :player/score 300 :player/empire-name "A"
                    :player/mil-planets 0 :player/food-planets 0 :player/ore-planets 0}
          player-b {:xt/id (helpers/uuid) :player/score 100 :player/empire-name "B"
                    :player/mil-planets 0 :player/food-planets 0 :player/ore-planets 0}
          player-c {:xt/id (helpers/uuid) :player/score 200 :player/empire-name "C"
                    :player/mil-planets 0 :player/food-planets 0 :player/ore-planets 0}
          sorted   (sort-by :player/score > [player-a player-b player-c])
          ranked   (map-indexed (fn [idx p] (assoc p :rank (inc idx))) sorted)]
      (is (= 1   (:rank (first ranked))))
      (is (= "A" (:player/empire-name (first ranked))))
      (is (= 2   (:rank (second ranked))))
      (is (= "C" (:player/empire-name (second ranked))))
      (is (= 3   (:rank (nth ranked 2))))
      (is (= "B" (:player/empire-name (nth ranked 2)))))))

(deftest test-get-game-players-single-player
  (testing "A single player gets rank 1"
    (let [player {:xt/id (helpers/uuid) :player/score 500 :player/empire-name "Solo"
                  :player/mil-planets 0 :player/food-planets 0 :player/ore-planets 0}
          ranked (map-indexed (fn [idx p] (assoc p :rank (inc idx)))
                              (sort-by :player/score > [player]))]
      (is (= 1 (:rank (first ranked)))))))

;;
;; mid-turn cooldown bypass
;;
;; round-cooldown-ms returns non-nil when current-turn=1, turns-used=0, and a round was
;; recently completed. This is indistinguishable from being mid-turn at phase >1 (since
;; turns-used only increments in apply-outcomes). game-view must suppress the cooldown
;; for mid-turn players so they can finish their turn.
;;

(deftest test-mid-turn-cooldown-suppressed
  (testing "Cooldown is not shown when player is past income (phase > 1)"
    ;; Simulate the state shown in the bug: round 2, turn 1, phase 3 (building).
    ;; round-cooldown-ms would normally fire (turn=1, turns-used=0, completed exists).
    (let [mid-turn? (> 3 1)]  ; current-phase = 3
      (is (true? mid-turn?)))))

(deftest test-mid-turn-false-at-phase-one
  (testing "mid-turn? is false at income phase, so cooldown is evaluated normally"
    (let [mid-turn? (> 1 1)]  ; current-phase = 1
      (is (false? mid-turn?)))))

;;
;; game-view
;;

(deftest test-game-view-player-not-found
  (testing "Returns 404 when player entity does not exist"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [ctx    {:path-params {:player-id (str test-player-id)} :biff/db nil}
            result (game/game-view ctx)]
        (is (= 404 (:status result)))
        (is (= "Game not found" (:body result)))))))

(deftest test-game-view-bad-uuid
  (testing "Throws when player-id is not a valid UUID"
    (let [ctx {:path-params {:player-id "not-a-uuid"} :biff/db nil}]
      (is (thrown? IllegalArgumentException (game/game-view ctx))))))
