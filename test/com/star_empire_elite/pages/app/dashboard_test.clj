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
(defn make-game
  ([turns-per-round rounds-per-day]
   (make-game turns-per-round rounds-per-day 0))
  ([turns-per-round rounds-per-day hours-between-rounds]
   {:xt/id test-game-id
    :game/turns-per-round      turns-per-round
    :game/rounds-per-day       rounds-per-day
    :game/hours-between-rounds hours-between-rounds}))

;; Minimal player map for display tests
(defn make-player
  ([current-turn rounds-started-today]
   {:xt/id test-player-id
    :player/current-turn          current-turn
    :player/rounds-started-today  rounds-started-today})
  ([current-turn rounds-started-today turns-used last-turn-at]
   (assoc (make-player current-turn rounds-started-today)
          :player/turns-used  turns-used
          :player/last-turn-at last-turn-at)))

;;
;; format-turn-round
;;
;; The display caps values at their per-day/per-round maxes so that rolled-over
;; state (waiting for next day) shows the completed values rather than reset ones.
;;

(deftest test-format-turn-round-mid-round
  (testing "In the middle of a round, shows actual turn and round"
    ;; rounds-started-today=0, can-start?=true → display Round inc(0)=1, Turn 1
    (is (= "Turn 1/2 | Round 1/1"
           (dashboard/format-turn-round
            (make-player 1 0 0 nil)
            (make-game 2 1))))))

(deftest test-format-turn-round-last-turn
  (testing "On the last turn of a round, shows the max turn number"
    ;; rounds-started-today=1, turns-used=1 → mid-round, display Round max(1,1)=1, Turn 2
    (is (= "Turn 2/2 | Round 1/1"
           (dashboard/format-turn-round
            (make-player 2 1 1 (java.util.Date.))
            (make-game 2 1))))))

(deftest test-format-turn-round-day-complete
  (testing "After all turns and rounds are used (day complete), shows max/max"
    ;; rounds-started-today=1 (>= rounds-per-day=1) → budget-exhausted
    ;; display: turns-per-round=2, e=1
    (is (= "Turn 2/2 | Round 1/1"
           (dashboard/format-turn-round
            (make-player 1 1 0 (java.util.Date.))
            (make-game 2 1))))))

(deftest test-format-turn-round-multi-round-in-progress
  (testing "Multi-round game mid-day shows current round correctly"
    ;; rounds-started-today=1, turns-used=0, can-start?=true → Round inc(1)=2
    (is (= "Turn 1/3 | Round 2/4"
           (dashboard/format-turn-round
            (make-player 1 1 0 (java.util.Date.))
            (make-game 3 4))))))

(deftest test-format-turn-round-multi-round-day-complete
  (testing "Multi-round game day-complete caps both values at max"
    ;; rounds-started-today=4 (>= rounds-per-day=4) → budget-exhausted
    ;; display: turns-per-round=3, e=4
    (is (= "Turn 3/3 | Round 4/4"
           (dashboard/format-turn-round
            (make-player 1 4 0 (java.util.Date.))
            (make-game 3 4))))))

(def some-timestamp (java.util.Date.))

;; Standard game fixture using real constants — tests that model actual game state
;; should use this so they stay correct if constants change.
(def standard-game (make-game const/turns-per-round const/rounds-per-day 2))

(deftest test-format-turn-round-between-rounds
  (testing "After completing a round, shows completed round state until next round starts"
    ;; rounds-started-today=1, turns-used=0, last-turn-at=just-now, hours-between-rounds=2
    ;; → spacing gate blocks, display shows completed round 1 at max turn
    (is (= (str "Turn " const/turns-per-round "/" const/turns-per-round
                " | Round 1/" const/rounds-per-day)
           (dashboard/format-turn-round
            (make-player 1 1 0 some-timestamp)
            standard-game)))))

(deftest test-format-turn-round-new-round-started
  (testing "After taking the first turn of a new round, shows the new round"
    ;; rounds-started-today=2, turns-used=1 → mid-round, display Round max(2,1)=2
    (is (= (str "Turn 2/" const/turns-per-round
                " | Round 2/" const/rounds-per-day)
           (dashboard/format-turn-round
            (make-player 2 2 1 some-timestamp)
            standard-game)))))

(deftest test-format-turn-round-game-start
  (testing "At game start (no turns taken, no completed round) shows turn 1 round 1"
    ;; rounds-started-today=0, last-turn-at=nil → can-start?=true, Round inc(0)=1
    (is (= (str "Turn 1/" const/turns-per-round
                " | Round 1/" const/rounds-per-day)
           (dashboard/format-turn-round
            (make-player 1 0 0 nil)
            standard-game)))))

;;
;; game-card
;;
;; game-card renders a stretched-link div with empire stats. It takes a map with :player,
;; :game, and :admin?. All fields come from the DB but are plain Clojure maps in tests.
;;

(def ^:private minimal-game
  {:xt/id                     test-game-id
   :game/name                 "Galactic Conquest"
   :game/turns-per-round      4
   :game/rounds-per-day       3
   :game/hours-between-rounds 0})

(def ^:private minimal-player
  {:xt/id                     test-player-id
   :player/empire-name        "Star Federation"
   :player/rank               2
   :player/score              12000
   :player/current-turn       2
   :player/rounds-started-today 1
   :player/turns-used         1
   :player/last-turn-at       nil
   :player/credits            10000
   :player/food               5000
   :player/fuel               3000
   :player/galaxars           500
   :player/population         7
   :player/stability          80
   :player/ore-planets        1
   :player/erg-planets        2
   :player/mil-planets        3
   :player/soldiers           200
   :player/transports         10
   :player/generals           2
   :player/fighters           50
   :player/carriers           3
   :player/admirals           1
   :player/stations           5
   :player/cmd-ships          1
   :player/agents             8})

(deftest test-game-card-renders
  (testing "Returns a relative hiccup div for a player+game pair"
    (let [result (dashboard/game-card {:player minimal-player
                                        :game   minimal-game
                                        :admin? false})]
      (is (vector? result))
      (is (clojure.string/starts-with? (name (first result)) "div.relative")))))

(deftest test-game-card-contains-empire-name
  (testing "The empire name appears in the rendered hiccup"
    (let [result (pr-str (dashboard/game-card {:player minimal-player
                                               :game   minimal-game
                                               :admin? false}))]
      (is (clojure.string/includes? result "Star Federation")))))

(deftest test-game-card-contains-game-name
  (testing "The game name appears in the rendered hiccup"
    (let [result (pr-str (dashboard/game-card {:player minimal-player
                                               :game   minimal-game
                                               :admin? false}))]
      (is (clojure.string/includes? result "Galactic Conquest")))))

(deftest test-game-card-no-delete-button-for-non-admin
  (testing "Non-admin player does not see the delete button"
    (let [result (pr-str (dashboard/game-card {:player minimal-player
                                               :game   minimal-game
                                               :admin? false}))]
      ;; The delete game action URL should not be present
      (is (not (clojure.string/includes? result "delete-game"))))))

(deftest test-game-card-delete-button-for-admin
  (testing "Admin player sees the delete game button"
    (let [result (pr-str (dashboard/game-card {:player minimal-player
                                               :game   minimal-game
                                               :admin? true}))]
      (is (clojure.string/includes? result "delete-game")))))

(deftest test-game-card-stretched-link
  (testing "Card contains an absolute-positioned anchor linking to the game page"
    (let [result (dashboard/game-card {:player minimal-player
                                        :game   minimal-game
                                        :admin? false})
          tree   (pr-str result)]
      (is (clojure.string/includes? tree (str "/app/game/" test-player-id))))))

;;
;; available-game-card
;;
;; available-game-card renders a card for games the user hasn't joined yet.
;;

(deftest test-available-game-card-renders
  (testing "Returns a hiccup div for a game the user can join"
    (let [result (dashboard/available-game-card {:game         minimal-game
                                                  :player-count 3
                                                  :admin?       false})]
      (is (vector? result))
      (is (clojure.string/starts-with? (name (first result)) "div")))))

(deftest test-available-game-card-shows-game-name
  (testing "The game name appears in the rendered card"
    (let [result (pr-str (dashboard/available-game-card {:game         minimal-game
                                                          :player-count 3
                                                          :admin?       false}))]
      (is (clojure.string/includes? result "Galactic Conquest")))))

(deftest test-available-game-card-shows-player-count
  (testing "The player count appears in the rendered card"
    (let [result (pr-str (dashboard/available-game-card {:game         minimal-game
                                                          :player-count 7
                                                          :admin?       false}))]
      (is (clojure.string/includes? result "7")))))

(deftest test-available-game-card-join-link
  (testing "Card contains a join-game link pointing to the game id"
    (let [result (pr-str (dashboard/available-game-card {:game         minimal-game
                                                          :player-count 2
                                                          :admin?       false}))]
      (is (clojure.string/includes? result (str "/app/join-game/" test-game-id))))))

(deftest test-available-game-card-admin-sees-delete
  (testing "Admin sees the delete button alongside the join link"
    (let [result (pr-str (dashboard/available-game-card {:game         minimal-game
                                                          :player-count 1
                                                          :admin?       true}))]
      (is (clojure.string/includes? result "delete-game")))))

(deftest test-available-game-card-non-admin-no-delete
  (testing "Non-admin does not see the delete button"
    (let [result (pr-str (dashboard/available-game-card {:game         minimal-game
                                                          :player-count 1
                                                          :admin?       false}))]
      (is (not (clojure.string/includes? result "delete-game"))))))
