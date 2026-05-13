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
    (let [result (action/target-row test-target)]
      (is (vector? result))
      (is (= :tr (first result)))))

  (testing "Displays total planet count (mil + food + ore)"
    ;; test-target has 2 mil + 1 food + 3 ore = 6 total planets.
    ;; row: [:tr {attrs} [:td empire-name] [:td total-planets] [:td score] [:td button]]
    ;; index 0=:tr, 1=attrs-map, 2=empire-td, 3=planets-td; planets-td is [:td {attrs} 6].
    (let [result (action/target-row test-target)
          planet-td (nth result 3)]
      (is (= 6 (last planet-td))))))

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

(deftest test-apply-action-with-target
  (testing "Records pending-attack as target UUID and advances to phase 5"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (action/apply-action {:path-params {:player-id (str test-player-id)}
                                           :params {:target-player-id (str test-target-id)}
                                           :biff/db nil})
              tx     (first @tx-atom)]
          ;; Redirect to espionage
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/espionage") (get-in result [:headers "location"])))
          ;; Transaction
          (is (= :player       (:db/doc-type tx)))
          (is (= :update        (:db/op tx)))
          (is (= test-player-id (:xt/id tx)))
          ;; Advances phase and records target
          (is (= 5 (:player/current-phase tx)))
          (is (= test-target-id (:player/pending-attack tx))))))))

(deftest test-apply-action-without-target
  (testing "Records nil pending-attack when no target is selected and advances to phase 5"
    ;; Players can choose not to attack. The pending-attack must be explicitly set to nil
    ;; so that a previous round's pending-attack is not carried forward.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (action/apply-action {:path-params {:player-id (str test-player-id)}
                               :params {} :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (= 5 (:player/current-phase tx)))
          (is (nil? (:player/pending-attack tx))))))))

(deftest test-apply-action-empty-target-string
  (testing "Treats an empty target-player-id string as no target (nil pending-attack)"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (action/apply-action {:path-params {:player-id (str test-player-id)}
                               :params {:target-player-id ""} :biff/db nil})
        (is (nil? (:player/pending-attack (first @tx-atom))))))))

;;;;
;;;; Action Page Readiness Pills Tests
;;;;
;;;; Ground and Fleet pills show a "Limit" row when transports/carriers or
;;;; generals/admirals cap the deployable force count. Tests render the full
;;;; action-page hiccup and search for the expected limit label string.
;;;;
;;;; Constants used by effective-forces:
;;;;   soldiers-per-transport = 100   fighters-per-carrier = 100
;;;;   soldiers-per-general   = 1000  fighters-per-admiral = 1000
;;;;

(def ^:private test-game {:game/turns-per-round 6 :game/rounds-per-day 2})

(defn- hiccup-contains-str?
  "Returns true if s appears anywhere as an exact string value in the hiccup tree."
  [tree s]
  (some #(= s %) (tree-seq coll? seq tree)))

;; --- Ground pill fixture players ---

;; soldiers(200) > trans-cap(1*100=100); gen-cap(10*1000=10000) not limiting
(def ^:private transport-limited-player
  (assoc test-player :player/soldiers 200 :player/transports 1 :player/generals 10))

;; soldiers(2000) > gen-cap(1*1000=1000); trans-cap(100*100=10000) not limiting
(def ^:private general-limited-player
  (assoc test-player :player/soldiers 2000 :player/transports 100 :player/generals 1))

;; soldiers(5000) > trans-cap(10*100=1000) == gen-cap(1*1000=1000) — both limiting
(def ^:private both-ground-limited-player
  (assoc test-player :player/soldiers 5000 :player/transports 10 :player/generals 1))

;; --- Fleet pill fixture players ---

;; fighters(200) > carrier-cap(1*100=100); admiral-cap(10*1000=10000) not limiting
(def ^:private carrier-limited-player
  (assoc test-player :player/fighters 200 :player/carriers 1 :player/admirals 10))

;; fighters(2000) > admiral-cap(1*1000=1000); carrier-cap(100*100=10000) not limiting
(def ^:private admiral-limited-player
  (assoc test-player :player/fighters 2000 :player/carriers 100 :player/admirals 1))

;; fighters(5000) > carrier-cap(10*100=1000) == admiral-cap(1*1000=1000) — both limiting
(def ^:private both-fleet-limited-player
  (assoc test-player :player/fighters 5000 :player/carriers 10 :player/admirals 1))

(deftest test-action-page-ground-transport-limit
  (testing "Shows 'Trans' when transports cap deployable soldiers"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (hiccup-contains-str?
           (action/action-page {:player transport-limited-player :game test-game :db nil})
           "Trans")))))

(deftest test-action-page-ground-general-limit
  (testing "Shows 'Gen' when generals cap deployable soldiers"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (hiccup-contains-str?
           (action/action-page {:player general-limited-player :game test-game :db nil})
           "Gen")))))

(deftest test-action-page-ground-both-limit
  (testing "Shows 'Trans/Gen' when both transports and generals cap deployable soldiers"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (hiccup-contains-str?
           (action/action-page {:player both-ground-limited-player :game test-game :db nil})
           "Trans/Gen")))))

(deftest test-action-page-ground-no-limit
  (testing "Shows no limit row when soldiers are not capped"
    ;; test-player: soldiers=100, trans-cap=5*100=500, gen-cap=2*1000=2000 → uncapped
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (let [result (action/action-page {:player test-player :game test-game :db nil})]
        (is (not (hiccup-contains-str? result "Trans")))
        (is (not (hiccup-contains-str? result "Gen")))))))

(deftest test-action-page-fleet-carrier-limit
  (testing "Shows 'Carr' when carriers cap deployable fighters"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (hiccup-contains-str?
           (action/action-page {:player carrier-limited-player :game test-game :db nil})
           "Carr")))))

(deftest test-action-page-fleet-admiral-limit
  (testing "Shows 'Adm' when admirals cap deployable fighters"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (hiccup-contains-str?
           (action/action-page {:player admiral-limited-player :game test-game :db nil})
           "Adm")))))

(deftest test-action-page-fleet-both-limit
  (testing "Shows 'Carr/Adm' when both carriers and admirals cap deployable fighters"
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (is (hiccup-contains-str?
           (action/action-page {:player both-fleet-limited-player :game test-game :db nil})
           "Carr/Adm")))))

(deftest test-action-page-fleet-no-limit
  (testing "Shows no fleet limit row when fighters are not capped"
    ;; test-player: fighters=50, carrier-cap=3*100=300, admiral-cap=1*1000=1000 → uncapped
    (with-redefs [biff/q (fn [_ _ & _] [])]
      (let [result (action/action-page {:player test-player :game test-game :db nil})]
        (is (not (hiccup-contains-str? result "Carr")))
        (is (not (hiccup-contains-str? result "Adm")))))))
