(ns com.star-empire-elite.pages.app.espionage-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.espionage :as espionage]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.star-empire-elite.utils :as utils]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures
;;;;

(def test-player-id #uuid "00000000-0000-0000-0000-000000000011")
(def test-game-id   #uuid "00000000-0000-0000-0000-000000000021")
(def test-target-id #uuid "00000000-0000-0000-0000-000000000031")

(def test-player
  {:xt/id               test-player-id
   :player/game         test-game-id
   :player/empire-name  "Test Empire"
   :player/current-phase 5
   :player/current-turn  1
   :player/current-round 1
   :player/population    0
   :player/score        1000
   :player/agents       3})

;; Player with no agents — cannot perform espionage.
(def agent-less-player
  (assoc test-player :player/agents 0))

(def test-target
  {:xt/id              test-target-id
   :player/game        test-game-id
   :player/empire-name "Enemy Empire"
   :player/score       500
   :player/mil-planets 2
   :player/erg-planets 1
   :player/ore-planets 3})

;;;;
;;;; get-other-players Tests
;;;;
;;;; get-other-players queries the database and sorts by score descending.
;;;; Tests mock biff/q to avoid a live database connection.
;;;;

(deftest test-get-other-players-sorted-by-score
  (testing "Returns other players sorted by score descending"
    (let [higher {:xt/id (java.util.UUID/randomUUID) :player/score 900}
          lower  {:xt/id (java.util.UUID/randomUUID) :player/score 100}]
      (with-redefs [biff/q (fn [_ _ & _] [lower higher])]
        (let [result (utils/get-other-players nil test-game-id test-player-id)]
          (is (= 2 (count result)))
          ;; Higher score first.
          (is (= 900 (:player/score (first  result))))
          (is (= 100 (:player/score (second result)))))))))

(deftest test-get-other-players-empty
  (testing "Returns empty seq when no other players exist"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (empty? (utils/get-other-players nil test-game-id test-player-id))))))

;;;;
;;;; target-row Tests
;;;;

(deftest test-target-row-renders
  (testing "Returns a hiccup table row"
    (let [result (espionage/target-row test-target "attacker-id")]
      (is (vector? result))
      (is (clojure.string/starts-with? (name (first result)) "tr"))))

  (testing "Displays correct total planet count"
    ;; row: [:tr.classes [:td empire-name] [:td total-planets] [:td score] [:td op] ...]
    ;; index 0=:tr, 1=empire-td, 2=planets-td; planets-td is [:td {attrs} 6].
    (let [planet-td (nth (espionage/target-row test-target "attacker-id") 2)]
      (is (= 6 (last planet-td))))))

;;;;
;;;; espionage-page Tests
;;;;
;;;; espionage-page is a pure UI function. Tests verify it renders correctly in the
;;;; key scenarios: with agents available, without agents, and with no targets.
;;;;

(deftest test-espionage-page-renders-with-agents-and-targets
  (testing "Returns a hiccup vector when player has agents and targets exist"
    (with-redefs [biff/q (fn [_ _ & _] [test-target])]
      (is (vector? (espionage/espionage-page {:player test-player :game {:game/turns-per-round 6 :game/rounds-per-day 2} :db nil}))))))

(deftest test-espionage-page-renders-without-agents
  (testing "Returns a hiccup vector with a warning when player has no agents"
    ;; When agents == 0 the page shows a warning instead of the target table.
    (with-redefs [biff/q (fn [_ _ & _] [test-target])]
      (is (vector? (espionage/espionage-page {:player agent-less-player :game {:game/turns-per-round 6 :game/rounds-per-day 2} :db nil}))))))

(deftest test-espionage-page-renders-without-targets
  (testing "Returns a hiccup vector when no other players exist"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (vector? (espionage/espionage-page {:player test-player :game {:game/turns-per-round 6 :game/rounds-per-day 2} :db nil}))))))

;;;;
;;;; apply-espionage Tests
;;;;
;;;; apply-espionage writes to the database and redirects. xt/entity and biff/submit-tx
;;;; are replaced with test doubles.
;;;;

(deftest test-apply-espionage-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (espionage/apply-espionage {:path-params {:player-id (str test-player-id)}
                                               :params {} :biff/db nil})]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-espionage-wrong-phase
  (testing "Redirects to game overview when player is not in phase 5"
    (let [player (assoc test-player :player/current-phase 4)]
      (with-redefs [xt/entity (helpers/fake-entity [player])]
        (let [result (espionage/apply-espionage {:path-params {:player-id (str test-player-id)}
                                                 :params {} :biff/db nil})]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id) (get-in result [:headers "location"]))))))))

(deftest test-apply-espionage-with-spy
  (testing "Records pending-espionage and op=spy when spy action submitted"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (espionage/apply-espionage
                       {:path-params {:player-id (str test-player-id)}
                        :params      {:espionage-action (str "spy:" test-target-id)}
                        :biff/db     nil})
              tx     (first @tx-atom)]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/outcomes") (get-in result [:headers "location"])))
          (is (= :player        (:db/doc-type tx)))
          (is (= :update         (:db/op tx)))
          (is (= test-player-id  (:xt/id tx)))
          (is (= 6               (:player/current-phase tx)))
          (is (= test-target-id  (:player/pending-espionage tx)))
          (is (= "spy"           (:player/pending-espionage-op tx))))))))

(deftest test-apply-espionage-with-incite
  (testing "Records pending-espionage and op=incite when incite action submitted"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (espionage/apply-espionage
                       {:path-params {:player-id (str test-player-id)}
                        :params      {:espionage-action (str "incite:" test-target-id)}
                        :biff/db     nil})
              tx     (first @tx-atom)]
          (is (= 303 (:status result)))
          (is (= test-target-id (:player/pending-espionage tx)))
          (is (= "incite"        (:player/pending-espionage-op tx))))))))

(deftest test-apply-espionage-without-target
  (testing "Records nil pending-espionage when no action is selected"
    ;; Players may choose to skip espionage. pending-espionage is explicitly set to nil
    ;; so a previous round's value is not carried forward.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (espionage/apply-espionage {:path-params {:player-id (str test-player-id)}
                                    :params {} :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (= 6 (:player/current-phase tx)))
          (is (nil? (:player/pending-espionage tx)))
          (is (nil? (:player/pending-espionage-op tx))))))))

(deftest test-apply-espionage-empty-action-string
  (testing "Treats an empty espionage-action as no target (nil pending-espionage)"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (espionage/apply-espionage {:path-params {:player-id (str test-player-id)}
                                    :params {:espionage-action ""} :biff/db nil})
        (is (nil? (:player/pending-espionage (first @tx-atom))))))))
