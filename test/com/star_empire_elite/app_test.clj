(ns com.star-empire-elite.app-test
  (:require [clojure.test :refer :all]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.app :as app]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.utils :as utils]
            [malli.generator :as mg]
            [xtdb.api :as xt]))

;;;;
;;;; delete-game — archives standings for a completed game, skips it for an early kill
;;;;

(defn- get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- make-admin! [ctx]
  (let [admin (merge (mg/generate :user main/malli-opts)
                      {:xt/id       (random-uuid)
                       :user/email  (first const/admin-emails)})]
    (biff/submit-tx ctx [(assoc admin :db/doc-type :user)])
    admin))

(defn- make-game! [ctx overrides]
  (let [game (merge (mg/generate :game main/malli-opts)
                     {:xt/id (random-uuid)}
                     overrides)]
    (biff/submit-tx ctx [(assoc game :db/doc-type :game)])
    game))

(defn- make-player! [ctx game-id overrides]
  (let [player (merge (mg/generate :player main/malli-opts)
                       {:xt/id         (random-uuid)
                        :player/game   game-id
                        :player/status const/player-status-active}
                       overrides)]
    (biff/submit-tx ctx [(assoc player :db/doc-type :player)])
    player))

(defn- delete-game! [node admin game-id]
  (app/delete-game (assoc (get-context node)
                           :session     {:uid (:xt/id admin)}
                           :path-params {:game-id (str game-id)})))

(def ^:private day-ms (* 24 60 60 1000))

(deftest test-delete-game-archives-completed-game
  (testing "A game past its scheduled end is snapshotted into :game-result before deletion"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            admin      (make-admin! ctx)
            created-at (java.util.Date. (- (.getTime (java.util.Date.)) (* 8 day-ms)))
            end-at     (java.util.Date. (+ (.getTime created-at) (* 7 day-ms))) ; 1 day in the past
            game       (make-game! ctx {:game/name "Finished Game"
                                         :game/created-at created-at
                                         :game/scheduled-end-at end-at})
            game-id (:xt/id game)
            winner  (make-player! ctx game-id {:player/empire-name "Winner" :player/score 500
                                                :player/mil-planets 1 :player/erg-planets 1 :player/ore-planets 1})
            loser   (make-player! ctx game-id {:player/empire-name "Loser" :player/score 100
                                                :player/mil-planets 0 :player/erg-planets 0 :player/ore-planets 0})
            dead    (make-player! ctx game-id {:player/empire-name "Eliminated" :player/score 999
                                                :player/status const/player-status-eliminated})]
        (delete-game! node admin game-id)
        (let [db      (xt/db node)
              results (biff/q db '{:find (pull r [*]) :where [[r :game-result/game-name _]]})
              result  (first results)]
          (is (= 1 (count results)))
          (is (= "Finished Game" (:game-result/game-name result)))
          ;; survivors only, ranked by score descending
          (is (= [{:standing/rank 1 :standing/empire-name "Winner" :standing/planets 3 :standing/score 500}
                  {:standing/rank 2 :standing/empire-name "Loser"  :standing/planets 0 :standing/score 100}]
                 (:game-result/standings result)))
          ;; game and all players (including the eliminated one) are gone
          (is (nil? (xt/entity db game-id)))
          (is (nil? (xt/entity db (:xt/id winner))))
          (is (nil? (xt/entity db (:xt/id loser))))
          (is (nil? (xt/entity db (:xt/id dead)))))))))

(deftest test-delete-game-early-kill-does-not-archive
  (testing "A game killed before its scheduled end deletes cleanly with no :game-result"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            admin      (make-admin! ctx)
            created-at (java.util.Date.)
            end-at     (java.util.Date. (+ (.getTime created-at) (* 7 day-ms)))
            game       (make-game! ctx {:game/name "Cut Short"
                                         :game/created-at created-at
                                         :game/scheduled-end-at end-at})
            game-id (:xt/id game)
            player  (make-player! ctx game-id {:player/empire-name "Player" :player/score 200})]
        (delete-game! node admin game-id)
        (let [db      (xt/db node)
              results (biff/q db '{:find (pull r [*]) :where [[r :game-result/game-name _]]})]
          (is (empty? results))
          (is (nil? (xt/entity db game-id)))
          (is (nil? (xt/entity db (:xt/id player)))))))))

(deftest test-delete-game-exactly-at-scheduled-end-archives
  (testing "A game exactly at (not just past) its scheduled end still counts as finished"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            admin      (make-admin! ctx)
            created-at (java.util.Date. (- (.getTime (java.util.Date.)) (* 7 day-ms)))
            end-at     (java.util.Date.) ; created-at + 7 days = right now
            game       (make-game! ctx {:game/name "Right On Time"
                                         :game/created-at created-at
                                         :game/scheduled-end-at end-at})
            game-id  (:xt/id game)
            player   (make-player! ctx game-id {:player/empire-name "Player" :player/score 50})]
        (delete-game! node admin game-id)
        (let [db      (xt/db node)
              results (biff/q db '{:find (pull r [*]) :where [[r :game-result/game-name _]]})]
          (is (= 1 (count results))))))))

(deftest test-delete-game-archives-when-day-counter-shows-final-day
  (testing "Matches the reported bug: the on-screen 'Day N/N' counter has rolled over to the
            final day, but the exact scheduled-end-at instant (up to ~24h later) hasn't been
            reached yet. The admin sees a finished game and expects it archived."
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            admin      (make-admin! ctx)
            ;; 6 days 20 hours elapsed: current-day = 7 (final day of a 7-day game), but
            ;; scheduled-end-at (created-at + 7 days) is still 4 hours in the future.
            created-at (java.util.Date. (- (.getTime (java.util.Date.))
                                            (+ (* 6 day-ms) (* 20 60 60 1000))))
            end-at     (java.util.Date. (+ (.getTime created-at) (* 7 day-ms)))
            game       (make-game! ctx {:game/name "On The Last Day"
                                         :game/created-at created-at
                                         :game/scheduled-end-at end-at})
            game-id    (:xt/id game)
            player     (make-player! ctx game-id {:player/empire-name "Player" :player/score 50})]
        ;; Sanity-check the scenario: exact-instant check is false, day-counter check is true.
        (is (false? (utils/game-ended? game (java.util.Date.))))
        (is (true?  (utils/game-days-complete? game (java.util.Date.))))
        (delete-game! node admin game-id)
        (let [db      (xt/db node)
              results (biff/q db '{:find (pull r [*]) :where [[r :game-result/game-name _]]})]
          (is (= 1 (count results))))))))

(deftest test-delete-game-non-admin-does-nothing
  (testing "A non-admin's delete request is rejected without touching the game"
    (with-open [node (test-xtdb-node [])]
      (let [ctx      (get-context node)
            non-admin (merge (mg/generate :user main/malli-opts) {:xt/id (random-uuid)})
            _         (biff/submit-tx ctx [(assoc non-admin :db/doc-type :user)])
            game      (make-game! ctx {})
            game-id   (:xt/id game)
            response  (app/delete-game (assoc ctx
                                               :session     {:uid (:xt/id non-admin)}
                                               :path-params {:game-id (str game-id)}))]
        (is (= 303 (:status response)))
        (is (some? (xt/entity (xt/db node) game-id)))))))
