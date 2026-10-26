(ns com.star-empire-elite.resolution-test
  (:require [clojure.test :refer :all]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.resolution :as resolution]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]))

;;;;
;;;; Fixtures
;;;;

;;; Uses the production threshold/cap (25/20), not test-helpers' hardcoded 20/25,
;;; since these tests exercise the exact new-constant behavior.
(def game (merge helpers/game-defaults
                  {:game/stability-breakaway-threshold 25
                   :game/stability-breakaway-cap       20}))

(def player
  (merge helpers/player-defaults
         {:player/population   1000000
          :player/credits      10000
          :player/food         5000
          :player/fuel         3000
          :player/soldiers     1000
          :player/transports   10
          :player/fighters     50
          :player/carriers     2
          :player/cmd-ships    1
          :player/stations     5
          :player/generals     5
          :player/admirals     3
          :player/agents       10
          :player/galaxars     1000
          :player/ore-planets  10
          :player/erg-planets  10
          :player/mil-planets  10}))

;;;;
;;;; calculate-stability-breakaway
;;;;

(deftest safe-floor-no-trigger
  (testing "stability at the safe floor (75) never triggers, even on the max roll"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [result (resolution/calculate-stability-breakaway
                     (assoc player :player/stability 75) game)]
        (is (false? (:triggered? result)))))))

(deftest just-below-floor-triggers-minimally
  (testing "one point below the safe floor, only the max roll triggers, for a minimal loss"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [result (resolution/calculate-stability-breakaway
                     (assoc player :player/stability 74) game)]
        (is (true? (:triggered? result)))
        (is (= 0.01 (:fraction result)))
        (is (= 1 (:total-lost result)))
        (is (= 100 (get-in result [:resources-lost :player/credits])))))))

(deftest zero-stability-caps-at-max-loss
  (testing "at 0 stability, a high roll is capped at the 20% max loss"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [result (resolution/calculate-stability-breakaway
                     (assoc player :player/stability 0) game)]
        (is (true? (:triggered? result)))
        (is (= 0.2 (:fraction result)))
        (is (= 6 (:total-lost result)) "20% of 30 planets")
        (is (= 2000 (get-in result [:resources-lost :player/credits])))
        (is (= 200000 (get-in result [:resources-lost :player/population])))
        (is (not (contains? (:resources-lost result) :player/agents))
            "agents are the espionage track, not a breakaway loss")
        (is (not (contains? (:resources-lost result) :player/galaxars))
            "galaxars is a premium resource, excluded from breakaway")))))

;;;;
;;;; breakaway-player-updates (private — accessed via var for testing)
;;;;

(deftest player-updates-subtract-losses-and-skip-excluded-fields
  (testing "breakaway-player-updates subtracts every loss and leaves agents/galaxars untouched"
    (with-redefs [rand-int (constantly 99)] ; roll = 100
      (let [stability0 (assoc player :player/stability 0)
            result     (resolution/calculate-stability-breakaway stability0 game)
            updates    (#'resolution/breakaway-player-updates stability0 result)]
        (is (= (- 10000 2000) (:player/credits updates)))
        (is (= (- 1000000 200000) (:player/population updates)))
        (is (= (- 10 (:ore-lost result)) (:player/ore-planets updates)))
        (is (not (contains? updates :player/agents)))
        (is (not (contains? updates :player/galaxars)))))))

;;;;
;;;; resolve-combat — breaking a peace treaty by attack (DB-backed)
;;;;
;;;; A fresh (non-cached) Action-phase attack against an active treaty partner breaks the
;;;; treaty atomically with the battle. An attack against a merely :proposed treaty — one
;;;; that hasn't "arrived" yet, see diplomacy.clj — leaves it untouched.
;;;;

(defn- get-ctx [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- submit! [ctx doc]
  (biff/submit-tx ctx [doc])
  doc)

(defn- alliance-fixture [overrides]
  (merge {:xt/id                 (random-uuid)
          :db/doc-type           :alliance
          :alliance/kind         :peace
          :alliance/proposed-at  (java.util.Date.)}
         overrides))

(deftest test-attack-on-active-treaty-partner-breaks-it-once
  (testing "a fresh raid against an active treaty partner flags :treaty-broken? and breaks
            the alliance; a refresh (cache hit) does neither a second time"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0     (get-ctx node)
            game-id  (random-uuid)
            att-id   (random-uuid)
            def-id   (random-uuid)
            game     (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id                      att-id
                                           :player/game                 game-id
                                           :player/empire-name          "Attacker"
                                           :player/pending-attack       def-id
                                           :player/pending-attack-mode  :raid}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id              def-id
                                           :player/game         game-id
                                           :player/empire-name  "Defender"}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game       game-id
                                      :alliance/proposer   att-id
                                      :alliance/recipient  def-id
                                      :alliance/status     :active
                                      :alliance/formed-at  (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result1 _ broken1] (#'resolution/resolve-combat ctx1 att-id attacker game)]
        (is (true? (:treaty-broken? result1)))
        (is (= :broken (:alliance/status broken1)))
        (is (= att-id (:alliance/ended-by broken1)))

        ;; Refresh: reload the attacker (now cached) and resolve again from a fresh db snapshot.
        (let [db2       (xt/db node)
              attacker2 (xt/entity db2 att-id)
              ctx2      (assoc ctx0 :biff/db db2)
              [result2 _ broken2] (#'resolution/resolve-combat ctx2 att-id attacker2 game)]
          (is (= result1 result2) "the cached result is served unchanged")
          (is (nil? broken2) "no second break on a cache-hit refresh"))

        ;; The alliance document itself only ever flips to :broken once.
        (let [alliances (biff/q (xt/db node) '{:find (pull a [*]) :where [[a :alliance/game _]]})]
          (is (= 1 (count alliances)))
          (is (= :broken (:alliance/status (first alliances)))))))))

(deftest test-attack-on-proposed-treaty-does-not-break-it
  (testing "an attack while the treaty is only :proposed leaves it :proposed — it hasn't
            'arrived' yet, so the recipient can still accept it in the diplomacy phase"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0     (get-ctx node)
            game-id  (random-uuid)
            att-id   (random-uuid)
            def-id   (random-uuid)
            game     (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id                      att-id
                                           :player/game                 game-id
                                           :player/empire-name          "Attacker"
                                           :player/pending-attack       def-id
                                           :player/pending-attack-mode  :raid}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id              def-id
                                           :player/game         game-id
                                           :player/empire-name  "Defender"}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game       game-id
                                      :alliance/proposer   att-id
                                      :alliance/recipient  def-id
                                      :alliance/status     :proposed}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ broken] (#'resolution/resolve-combat ctx1 att-id attacker game)]
        (is (nil? (:treaty-broken? result)))
        (is (nil? broken))
        (let [alliances (biff/q (xt/db node) '{:find (pull a [*]) :where [[a :alliance/game _]]})]
          (is (= 1 (count alliances)))
          (is (= :proposed (:alliance/status (first alliances)))))))))

;;;;
;;;; resolve-espionage — bomb success writes a single tx (DB-backed)
;;;;
;;;; Regression for a bug where the bomb-success branch and a separate :player/incoming-seq
;;;; bump were two different :db/op :update maps targeting the same defender in one
;;;; submit-tx. biff's :db/op :update builds a match+put pair per map; the second map's match
;;;; is checked against a stale pre-tx snapshot the first map's put has already changed, so
;;;; the whole tx fails every time (not just on contention) — submit-tx throws after
;;;; exhausting its retries. See the incoming-seq comment in resolve-espionage.
;;;;

(deftest test-resolve-espionage-bomb-success-single-tx
  (testing "a bomb against a defenseless target (guaranteed win) resolves without throwing
            and bumps :player/incoming-seq exactly once"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0     (get-ctx node)
            game-id  (random-uuid)
            att-id   (random-uuid)
            def-id   (random-uuid)
            game     (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id                       att-id
                                           :player/game                  game-id
                                           :player/empire-name           "Attacker"
                                           :player/agents                10000
                                           :player/pending-espionage     def-id
                                           :player/pending-espionage-op  "bomb"}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id              def-id
                                           :player/game         game-id
                                           :player/empire-name  "Defender"
                                           :player/agents       0}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _] (#'resolution/resolve-espionage ctx1 att-id attacker game)
            defender (xt/entity (xt/db node) def-id)]
        (is (true? (:attacker-wins? result)))
        (is (some? (:player/incoming-bomb-result defender)))
        (is (= 1 (:player/incoming-seq defender)))))))
