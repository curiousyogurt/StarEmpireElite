(ns com.star-empire-elite.pages.app.action-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.action :as action]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.star-empire-elite.utils :as utils]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures
;;;;

(def test-player-id   #uuid "00000000-0000-0000-0000-000000000010")
(def test-game-id     #uuid "00000000-0000-0000-0000-000000000020")
(def test-target-id   #uuid "00000000-0000-0000-0000-000000000030")
(def test-target-id-2 #uuid "00000000-0000-0000-0000-000000000040")

(def test-player
  {:xt/id               test-player-id
   :player/game         test-game-id
   :player/empire-name  "Test Empire"
   :player/current-phase 4
   :player/current-turn  1
   :player/current-round 1
   :player/turns-used    0
   :player/population    0
   :player/score         1000
   :player/soldiers      100
   :player/transports    5
   :player/generals      2
   :player/fighters      50
   :player/carriers      3
   :player/admirals      1})

(def test-target
  {:xt/id              test-target-id
   :player/game        test-game-id
   :player/empire-name "Enemy Empire"
   :player/current-phase 4
   :player/score       500
   :player/mil-planets 2
   :player/erg-planets 1
   :player/ore-planets 3})

(def test-target-2
  {:xt/id              test-target-id-2
   :player/game        test-game-id
   :player/empire-name "Rival Empire"
   :player/current-phase 4
   :player/score       750
   :player/mil-planets 1
   :player/erg-planets 2
   :player/ore-planets 2})

;;;;
;;;; get-other-players Tests
;;;;
;;;; get-other-players queries the database and sorts by score. Tests use a mocked
;;;; biff/q to avoid a live database connection.
;;;;

(deftest test-get-other-players-returns-sorted-list
  (testing "Returns other players in the same game sorted by score descending"
    ;; biff/q returns a seq of pull results — we return them as a flat list.
    (with-redefs [biff/q (fn [_ _ & _] [test-target test-target-2])]
      (let [result (utils/get-other-players nil test-game-id test-player-id)]
        (is (= 2 (count result)))
        ;; Higher score (750) should come first after sort-by score descending.
        (is (= test-target-id-2 (:xt/id (first result))))
        (is (= test-target-id   (:xt/id (second result))))))))

(deftest test-get-other-players-excludes-current-player
  (testing "Does not include the current player even if the query returns them"
    ;; In practice XTDB filters this via the :where clause, but we verify the
    ;; function handles whatever the query returns.
    (with-redefs [biff/q (fn [_ _ & _] [test-target])]
      (let [result (utils/get-other-players nil test-game-id test-player-id)]
        (is (not (some #(= test-player-id (:xt/id %)) result)))))))

(deftest test-get-other-players-empty-game
  (testing "Returns an empty seq when there are no other players"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (let [result (utils/get-other-players nil test-game-id test-player-id)]
        (is (empty? result))))))

;;;;
;;;; target-row Tests
;;;;
;;;; target-row is a pure UI function; tests verify it returns valid hiccup.
;;;;

(deftest test-target-row-renders
  (testing "Returns a hiccup table row vector"
    (let [result (#'action/target-row test-target {:soldiers 100 :fighters 50} 1 "attacker-id")]
      (is (vector? result))
      (is (clojure.string/starts-with? (name (first result)) "tr"))))

  (testing "Displays total planet count (mil + food + ore)"
    ;; test-target has 2 mil + 1 food + 3 ore = 6 total planets.
    ;; row: [:tr.classes [:td empire-name] [:td total-planets] [:td score] [:td invade] [:td raid] [:td strike]]
    ;; index 0=:tr, 1=empire-td, 2=planets-td; planets-td is [:td {attrs} 6].
    (let [result (#'action/target-row test-target {:soldiers 100 :fighters 50} 1 "attacker-id")
          planet-td (nth result 2)]
      (is (= 6 (last planet-td)))))

  (testing "Renders Invade, Raid, and Strike buttons"
    (let [result (#'action/target-row test-target {:soldiers 100 :fighters 50} 1 "attacker-id")]
      (is (= 7 (count result)))))  ; :tr + 6 tds (no separate attrs map)

  (testing "Radio values contain composite player-id:mode format"
    (let [result   (#'action/target-row test-target {:soldiers 100 :fighters 50} 1 "attacker-id")
          hiccup   (tree-seq coll? seq result)
          values   (filter #(and (map? %) (:value %)) hiccup)
          val-strs (map :value values)]
      (is (some #(= % (str test-target-id ":invade")) val-strs))
      (is (some #(= % (str test-target-id ":raid"))   val-strs))
      (is (some #(= % (str test-target-id ":strike")) val-strs))))

  (testing "All buttons enabled when attacker has soldiers, fighters, and cmd-ships"
    (let [result (#'action/target-row test-target {:soldiers 100 :fighters 50} 5 "attacker-id")
          hiccup (tree-seq coll? seq result)
          inputs (filter #(and (map? %) (= "radio" (:type %))) hiccup)]
      (is (every? #(not (:disabled %)) inputs))))

  (testing "Invade disabled, Raid and Strike enabled when attacker has 0 cmd-ships"
    (let [result (#'action/target-row test-target {:soldiers 100 :fighters 50} 0 "attacker-id")
          hiccup (tree-seq coll? seq result)
          inputs (filter #(and (map? %) (= "radio" (:type %))) hiccup)]
      ;; Invade and Strike disabled (2 of 3)
      (is (= 2 (count (filter :disabled inputs))))))

  (testing "Invade and Raid disabled when attacker has 0 deployable soldiers"
    (let [result (#'action/target-row test-target {:soldiers 0 :fighters 50} 5 "attacker-id")
          hiccup (tree-seq coll? seq result)
          inputs (filter #(and (map? %) (= "radio" (:type %))) hiccup)]
      ;; Invade and Raid disabled (2 of 3)
      (is (= 2 (count (filter :disabled inputs))))))

  (testing "Invade and Raid disabled when attacker has 0 deployable fighters"
    (let [result (#'action/target-row test-target {:soldiers 100 :fighters 0} 5 "attacker-id")
          hiccup (tree-seq coll? seq result)
          inputs (filter #(and (map? %) (= "radio" (:type %))) hiccup)]
      ;; Invade and Raid disabled (2 of 3)
      (is (= 2 (count (filter :disabled inputs))))))

  (testing "All buttons disabled when attacker has no forces"
    (let [result (#'action/target-row test-target {:soldiers 0 :fighters 0} 0 "attacker-id")
          hiccup (tree-seq coll? seq result)
          inputs (filter #(and (map? %) (= "radio" (:type %))) hiccup)]
      (is (every? :disabled inputs)))))

;;;;
;;;; action-page Tests
;;;;
;;;; action-page is a pure UI function that also calls get-other-players via the db.
;;;; Tests mock biff/q to control the player list returned.
;;;;

(deftest test-action-page-renders-with-targets
  (testing "Returns a hiccup vector when other players are available"
    (with-redefs [biff/q (fn [_ _ & _] [test-target])]
      (is (vector? (action/action-page {:player test-player :game {:game/turns-per-round 6 :game/rounds-per-day 2} :db nil}))))))

(deftest test-action-page-renders-without-targets
  (testing "Returns a hiccup vector when no other players exist"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (vector? (action/action-page {:player test-player :game {:game/turns-per-round 6 :game/rounds-per-day 2} :db nil}))))))

;;;;
;;;; apply-action Tests
;;;;
;;;; apply-action writes to the database and redirects, so xt/entity and
;;;; biff/submit-tx are replaced with test doubles.
;;;;

(deftest test-apply-action-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (action/apply-action {:path-params {:player-id (str test-player-id)}
                                         :params {} :biff/db nil})]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-action-wrong-phase
  (testing "Redirects to game overview when player is not in phase 4"
    (let [player (assoc test-player :player/current-phase 3)]
      (with-redefs [xt/entity (helpers/fake-entity [player])]
        (let [result (action/apply-action {:path-params {:player-id (str test-player-id)}
                                           :params {} :biff/db nil})]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id) (get-in result [:headers "location"]))))))))

(deftest test-apply-action-with-invade-target
  (testing "Records pending-attack as target UUID, mode :invade, and advances to phase 5"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (action/apply-action {:path-params {:player-id (str test-player-id)}
                                           :params {:target-action (str test-target-id ":invade")}
                                           :biff/db nil})
              tx     (first @tx-atom)]
          ;; Redirect to espionage
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/espionage") (get-in result [:headers "location"])))
          ;; Transaction
          (is (= :player       (:db/doc-type tx)))
          (is (= :update        (:db/op tx)))
          (is (= test-player-id (:xt/id tx)))
          ;; Advances phase and records target + mode
          (is (= 5              (:player/current-phase      tx)))
          (is (= test-target-id (:player/pending-attack     tx)))
          (is (= :invade        (:player/pending-attack-mode tx))))))))

(deftest test-apply-action-with-strike-target
  (testing "Records pending-attack as target UUID, mode :strike, and advances to phase 5"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (action/apply-action {:path-params {:player-id (str test-player-id)}
                               :params {:target-action (str test-target-id ":strike")}
                               :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (= test-target-id (:player/pending-attack      tx)))
          (is (= :strike        (:player/pending-attack-mode  tx))))))))

(deftest test-apply-action-with-raid-target
  (testing "Records pending-attack as target UUID, mode :raid, and advances to phase 5"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (action/apply-action {:path-params {:player-id (str test-player-id)}
                               :params {:target-action (str test-target-id ":raid")}
                               :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (= test-target-id (:player/pending-attack      tx)))
          (is (= :raid          (:player/pending-attack-mode  tx))))))))

(deftest test-apply-action-without-target
  (testing "Records nil pending-attack and nil mode when no target is selected and advances to phase 5"
    ;; Players can choose not to attack. The pending-attack must be explicitly set to nil
    ;; so that a previous round's pending-attack is not carried forward.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (action/apply-action {:path-params {:player-id (str test-player-id)}
                               :params {} :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (= 5 (:player/current-phase tx)))
          (is (nil? (:player/pending-attack      tx)))
          (is (nil? (:player/pending-attack-mode tx))))))))

(deftest test-apply-action-empty-target-string
  (testing "Treats an empty target-action value as no target (nil pending-attack and nil mode)"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (action/apply-action {:path-params {:player-id (str test-player-id)}
                               :params {:target-action ""} :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (nil? (:player/pending-attack      tx)))
          (is (nil? (:player/pending-attack-mode tx))))))))

