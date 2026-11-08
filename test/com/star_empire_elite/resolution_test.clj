(ns com.star-empire-elite.resolution-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer :all]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.diplomacy :as diplomacy]
            [com.star-empire-elite.resolution :as resolution]
            [com.star-empire-elite.test-helpers :as helpers]
            [com.star-empire-elite.utils :as utils]
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
;;;; resolve-combat — defence treaty allies (DB-backed)
;;;;
;;;; The attacker here is always wildly overwhelming, so the ground phase is a blowout
;;;; (loser-rate near combat-loser-cap, never zero — see constants.clj) and combat-variance
;;;; is pinned to 0 so the roll itself is deterministic. That's enough to guarantee the
;;;; defending side actually takes ground losses, without needing to hand-predict the exact
;;;; combat math — every assertion below instead cross-checks the persisted DB state against
;;;; whatever the result itself says was lost, which holds regardless of the exact numbers.
;;;;

(def massive-attacker-overrides
  {:player/soldiers 100000 :player/transports 10000 :player/generals 1000
   :player/fighters 100000 :player/carriers 10000 :player/admirals 1000
   :player/cmd-ships 0})

(deftest test-resolve-combat-pools-defence-ally-and-splits-losses
  (testing "an active defence ally's forces join the fight; the real defender is only ever
            debited their own share (:defender-own-losses), and the ally is debited
            separately, in its own tx"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0    (get-ctx node)
            game-id (random-uuid)
            att-id  (random-uuid)
            def-id  (random-uuid)
            ally-id (random-uuid)
            game    (submit! ctx0 (merge helpers/game-defaults
                                        {:xt/id game-id :game/combat-variance 0.0}))
            attacker (submit! ctx0 (merge helpers/player-defaults massive-attacker-overrides
                                         {:xt/id att-id :player/game game-id
                                          :player/empire-name "Attacker"
                                          :player/pending-attack def-id
                                          :player/pending-attack-mode :invade}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id def-id :player/game game-id
                                          :player/empire-name "Defender"
                                          :player/soldiers 1000 :player/transports 100
                                          :player/generals 100 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0
                                          :player/cmd-ships 0 :player/stations 0}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id ally-id :player/game game-id
                                          :player/empire-name "Ally"
                                          :player/soldiers 400 :player/transports 40
                                          :player/generals 40 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :defence
                                      :alliance/proposer def-id :alliance/recipient ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ _] (#'resolution/resolve-combat ctx1 att-id attacker game)
            ally2    (xt/entity (xt/db node) ally-id)
            def2     (xt/entity (xt/db node) def-id)
            ally-result (first (:defender-allies result))
            own         (:defender-own-losses result)
            ally-loss   (get-in ally-result [:losses :soldiers] 0)]
        (is (true? (:attacker-wins? result)) "overwhelming attacker always wins")
        (is (= 1 (count (:defender-allies result))))
        (is (pos? ally-loss) "the ally actually took ground losses")
        (is (<= ally-loss 100) "never more than the 25% contribution (100 of 400 soldiers)")
        ;; Each side was debited exactly what the result says it lost — not the pooled total.
        (is (= (- 400  ally-loss)        (:player/soldiers ally2)))
        (is (= (- 1000 (:soldiers own))  (:player/soldiers def2)))
        ;; The ally gets its own alert row and battle summary.
        (is (= 1 (:player/incoming-seq ally2)))
        (is (= 1 (count (:player/incoming-ally-battles ally2))))
        ;; Score must be computed from the ally's full post-loss unit counts — including
        ;; transports, which calculate-score weights via :game/score-transport — not just
        ;; the subset of fields ally-update-tx happens to override on top of the stale
        ;; (pre-loss) ally map.
        (is (= (:player/score ally2) (utils/calculate-score game ally2))
            "stored score matches a fresh recompute off the ally's actual persisted counts")))))

(deftest test-resolve-combat-ally-with-a-treaty-against-the-attacker-sits-out
  (testing "a defence ally who separately holds an active treaty with the attacker stays
            out of the fight entirely — their forces never join and they take no losses"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0    (get-ctx node)
            game-id (random-uuid)
            att-id  (random-uuid)
            def-id  (random-uuid)
            ally-id (random-uuid)
            game    (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults massive-attacker-overrides
                                         {:xt/id att-id :player/game game-id
                                          :player/empire-name "Attacker"
                                          :player/pending-attack def-id
                                          :player/pending-attack-mode :invade}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id def-id :player/game game-id
                                          :player/empire-name "Defender"
                                          :player/soldiers 1000}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id ally-id :player/game game-id
                                          :player/empire-name "Ally"
                                          :player/soldiers 400}))
            ;; Ally is in a defence treaty with the defender...
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :defence
                                      :alliance/proposer def-id :alliance/recipient ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ;; ...but also holds an active PEACE treaty with the attacker — any kind of
            ;; treaty with the enemy is enough to sit out, not just the same kind.
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :peace
                                      :alliance/proposer ally-id :alliance/recipient att-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ _] (#'resolution/resolve-combat ctx1 att-id attacker game)
            ally2    (xt/entity (xt/db node) ally-id)]
        (is (empty? (:defender-allies result)))
        (is (= 400 (:player/soldiers ally2)) "the ally's forces never joined")
        (is (nil? (:player/incoming-ally-battles ally2)))))))

(deftest test-resolve-combat-strike-does-not-pull-in-allies
  (testing "a strike has no unit-vs-unit battle, so a defence ally never joins and never
            loses anything, even though they hold an active treaty with the defender"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0    (get-ctx node)
            game-id (random-uuid)
            att-id  (random-uuid)
            def-id  (random-uuid)
            ally-id (random-uuid)
            game    (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id att-id :player/game game-id
                                          :player/empire-name "Attacker"
                                          :player/cmd-ships 10
                                          :player/pending-attack def-id
                                          :player/pending-attack-mode :strike}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id def-id :player/game game-id
                                          :player/empire-name "Defender"
                                          :player/soldiers 1000}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id ally-id :player/game game-id
                                          :player/empire-name "Ally"
                                          :player/soldiers 400}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :defence
                                      :alliance/proposer def-id :alliance/recipient ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ _] (#'resolution/resolve-combat ctx1 att-id attacker game)
            ally2    (xt/entity (xt/db node) ally-id)]
        (is (nil? (:defender-allies result)) "strike's result shape has no ally keys at all")
        (is (= 400 (:player/soldiers ally2)))
        (is (nil? (:player/incoming-ally-battles ally2)))))))

(deftest test-resolve-combat-cache-hit-does-not-re-apply-ally-losses
  (testing "refreshing the outcomes page after the first resolve does not touch the ally a
            second time — the whole ally-pooling path only runs on the fresh-resolve branch"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0    (get-ctx node)
            game-id (random-uuid)
            att-id  (random-uuid)
            def-id  (random-uuid)
            ally-id (random-uuid)
            game    (submit! ctx0 (merge helpers/game-defaults
                                        {:xt/id game-id :game/combat-variance 0.0}))
            attacker (submit! ctx0 (merge helpers/player-defaults massive-attacker-overrides
                                         {:xt/id att-id :player/game game-id
                                          :player/empire-name "Attacker"
                                          :player/pending-attack def-id
                                          :player/pending-attack-mode :invade}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id def-id :player/game game-id
                                          :player/empire-name "Defender"
                                          :player/soldiers 1000 :player/transports 100
                                          :player/generals 100 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0
                                          :player/cmd-ships 0 :player/stations 0}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id ally-id :player/game game-id
                                          :player/empire-name "Ally"
                                          :player/soldiers 400 :player/transports 40
                                          :player/generals 40 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :defence
                                      :alliance/proposer def-id :alliance/recipient ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result1 _ _] (#'resolution/resolve-combat ctx1 att-id attacker game)
            ally-after-first (xt/entity (xt/db node) ally-id)

            ;; Refresh: reload the attacker (now cached) and resolve again from a fresh
            ;; db snapshot — exactly like the existing treaty-break refresh tests above.
            db2       (xt/db node)
            attacker2 (xt/entity db2 att-id)
            ctx2      (assoc ctx0 :biff/db db2)
            [result2 _ _] (#'resolution/resolve-combat ctx2 att-id attacker2 game)
            ally-after-second (xt/entity (xt/db node) ally-id)]
        (is (= result1 result2) "the cached result is served unchanged")
        (is (= (:player/soldiers ally-after-first) (:player/soldiers ally-after-second))
            "the ally isn't debited a second time on a cache-hit refresh")
        (is (= 1 (:player/incoming-seq ally-after-second))
            "still just the one alert row from the fresh resolve")))))

;;;;
;;;; resolve-combat — offence treaty allies (DB-backed)
;;;;
;;;; Mirrors the defence-ally tests above, on the attacker's side instead: the defender here
;;;; is always wildly overwhelming, so the attacker loses the ground phase (a loser-rate
;;;; blowout, never zero), guaranteeing the attacker's pooled side actually takes losses.
;;;;

(def massive-defender-overrides
  {:player/soldiers 100000 :player/transports 10000 :player/generals 1000
   :player/fighters 100000 :player/carriers 10000 :player/admirals 1000
   :player/cmd-ships 0})

(deftest test-resolve-combat-pools-offence-ally-and-splits-losses
  (testing "an active offence ally's forces join the attack; the real attacker is only ever
            debited their own share (:attacker-own-losses), and the ally is debited
            separately, in its own tx"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0    (get-ctx node)
            game-id (random-uuid)
            att-id  (random-uuid)
            def-id  (random-uuid)
            ally-id (random-uuid)
            game    (submit! ctx0 (merge helpers/game-defaults
                                        {:xt/id game-id :game/combat-variance 0.0}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id att-id :player/game game-id
                                          :player/empire-name "Attacker"
                                          :player/soldiers 1000 :player/transports 100
                                          :player/generals 100 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0
                                          :player/cmd-ships 0
                                          :player/pending-attack def-id
                                          :player/pending-attack-mode :invade}))
            _        (submit! ctx0 (merge helpers/player-defaults massive-defender-overrides
                                         {:xt/id def-id :player/game game-id
                                          :player/empire-name "Defender"}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id ally-id :player/game game-id
                                          :player/empire-name "Ally"
                                          :player/soldiers 400 :player/transports 40
                                          :player/generals 40 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :offence
                                      :alliance/proposer att-id :alliance/recipient ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ _] (#'resolution/resolve-combat ctx1 att-id attacker game)
            ally2    (xt/entity (xt/db node) ally-id)
            att2     (xt/entity (xt/db node) att-id)
            ally-result (first (:attacker-allies result))
            own         (:attacker-own-losses result)
            ally-loss   (get-in ally-result [:losses :soldiers] 0)]
        (is (false? (:attacker-wins? result)) "overwhelming defender always wins")
        (is (= 1 (count (:attacker-allies result))))
        (is (pos? ally-loss) "the ally actually took ground losses")
        (is (<= ally-loss 100) "never more than the 25% contribution (100 of 400 soldiers)")
        ;; Each side was debited exactly what the result says it lost — not the pooled total.
        (is (= (- 400  ally-loss)        (:player/soldiers ally2)))
        (is (= (- 1000 (:soldiers own))  (:player/soldiers att2)))
        ;; The ally gets its own alert row and battle summary.
        (is (= 1 (:player/incoming-seq ally2)))
        (is (= 1 (count (:player/incoming-ally-battles ally2))))
        (is (= (:player/score ally2) (utils/calculate-score game ally2))
            "stored score matches a fresh recompute off the ally's actual persisted counts")))))

(deftest test-resolve-combat-offence-ally-with-a-treaty-against-the-defender-sits-out
  (testing "an offence ally who separately holds an active treaty with the defender stays
            out of the fight entirely — their forces never join and they take no losses"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0    (get-ctx node)
            game-id (random-uuid)
            att-id  (random-uuid)
            def-id  (random-uuid)
            ally-id (random-uuid)
            game    (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id att-id :player/game game-id
                                          :player/empire-name "Attacker"
                                          :player/soldiers 1000
                                          :player/pending-attack def-id
                                          :player/pending-attack-mode :invade}))
            _        (submit! ctx0 (merge helpers/player-defaults massive-defender-overrides
                                         {:xt/id def-id :player/game game-id
                                          :player/empire-name "Defender"}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id ally-id :player/game game-id
                                          :player/empire-name "Ally"
                                          :player/soldiers 400}))
            ;; Ally is in an offence treaty with the attacker...
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :offence
                                      :alliance/proposer att-id :alliance/recipient ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ;; ...but also holds an active PEACE treaty with the defender — any kind of
            ;; treaty with the enemy is enough to sit out, not just the same kind.
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :peace
                                      :alliance/proposer ally-id :alliance/recipient def-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ _] (#'resolution/resolve-combat ctx1 att-id attacker game)
            ally2    (xt/entity (xt/db node) ally-id)]
        (is (empty? (:attacker-allies result)))
        (is (= 400 (:player/soldiers ally2)) "the ally's forces never joined")
        (is (nil? (:player/incoming-ally-battles ally2)))))))

(deftest test-resolve-combat-pools-offence-and-defence-allies-independently
  (testing "an offence ally on the attacker's side and a defence ally on the defender's side
            in the same battle are each debited only their own losses"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0     (get-ctx node)
            game-id  (random-uuid)
            att-id   (random-uuid)
            def-id   (random-uuid)
            att-ally-id (random-uuid)
            def-ally-id (random-uuid)
            game     (submit! ctx0 (merge helpers/game-defaults
                                         {:xt/id game-id :game/combat-variance 0.0}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id att-id :player/game game-id
                                          :player/empire-name "Attacker"
                                          :player/soldiers 1000 :player/transports 100
                                          :player/generals 100 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0
                                          :player/cmd-ships 0
                                          :player/pending-attack def-id
                                          :player/pending-attack-mode :invade}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id def-id :player/game game-id
                                          :player/empire-name "Defender"
                                          :player/soldiers 1000 :player/transports 100
                                          :player/generals 100 :player/fighters 0
                                          :player/carriers 0 :player/admirals 0
                                          :player/cmd-ships 0 :player/stations 0}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id att-ally-id :player/game game-id
                                          :player/empire-name "Att Ally"
                                          :player/soldiers 400 :player/transports 40
                                          :player/generals 40}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                         {:xt/id def-ally-id :player/game game-id
                                          :player/empire-name "Def Ally"
                                          :player/soldiers 400 :player/transports 40
                                          :player/generals 40}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :offence
                                      :alliance/proposer att-id :alliance/recipient att-ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game game-id :alliance/kind :defence
                                      :alliance/proposer def-id :alliance/recipient def-ally-id
                                      :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ _] (#'resolution/resolve-combat ctx1 att-id attacker game)
            att-ally2 (xt/entity (xt/db node) att-ally-id)
            def-ally2 (xt/entity (xt/db node) def-ally-id)]
        (is (= 1 (count (:attacker-allies result))))
        (is (= 1 (count (:defender-allies result))))
        (is (= 1 (:player/incoming-seq att-ally2)))
        (is (= 1 (:player/incoming-seq def-ally2)))
        (is (= :attacking (:role (edn/read-string
                                   (first (:player/incoming-ally-battles att-ally2))))))
        (is (= :defending (:role (edn/read-string
                                   (first (:player/incoming-ally-battles def-ally2))))))
        ;; Each ally's stored :kind is read off their OWN real :alliance/kind — not
        ;; assumed from role — so a mix of kinds in the same battle is never mislabeled.
        (is (= :offence (:kind (edn/read-string
                                 (first (:player/incoming-ally-battles att-ally2))))))
        (is (= :defence (:kind (edn/read-string
                                 (first (:player/incoming-ally-battles def-ally2))))))))))

(deftest test-treaty-allies-reads-each-allys-real-alliance-kind
  (testing "treaty-allies' returned :kind comes from the actual :alliance/kind of each
            ally's own treaty, not an assumption from which capability set (kinds) was
            searched for — directly exercises the mechanism ally-update-tx relies on to
            avoid hard-coding :defence/:offence by name"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0    (get-ctx node)
            game-id (random-uuid)
            player-id (random-uuid)
            ally-id   (random-uuid)
            enemy-id  (random-uuid)
            _ (submit! ctx0 (merge helpers/player-defaults
                              {:xt/id ally-id :player/game game-id :player/empire-name "Ally"}))
            _ (submit! ctx0 (alliance-fixture
                              {:alliance/game game-id :alliance/kind :defence
                               :alliance/proposer player-id :alliance/recipient ally-id
                               :alliance/status :active :alliance/formed-at (java.util.Date.)}))
            db        (xt/db node)
            alliances (diplomacy/alliances-for-player db game-id player-id)
            allies    (#'resolution/treaty-allies db game-id alliances #{:defence :offence}
                                                   player-id enemy-id)]
        (is (= 1 (count allies)))
        (is (= :defence (:kind (first allies))))
        (is (= ally-id (:xt/id (:player (first allies)))))))))

;;;;
;;;; resolve-espionage — breaking a peace treaty by espionage (DB-backed)
;;;;
;;;; Mirrors the resolve-combat treaty tests above: a fresh espionage attempt against an
;;;; active treaty partner breaks the treaty atomically with the attempt, whether or not the
;;;; attempt itself succeeds — the hostile act is what ends it, not the outcome.
;;;;

(deftest test-espionage-on-active-treaty-partner-breaks-it-even-on-failure
  (testing "a failed spy attempt (attacker's agents are caught) against an active treaty
            partner still flags :treaty-broken? and breaks the alliance — merged into the
            SAME target update as the espionage-fail fields, not a second tx map"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0     (get-ctx node)
            game-id  (random-uuid)
            att-id   (random-uuid)
            def-id   (random-uuid)
            game     (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id                       att-id
                                           :player/game                  game-id
                                           :player/empire-name           "Spymaster"
                                           ;; Vastly outnumbered — guarantees a failed spy
                                           ;; attempt regardless of the random roll.
                                           :player/agents                1
                                           :player/pending-espionage     def-id
                                           :player/pending-espionage-op  "spy"}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id              def-id
                                           :player/game         game-id
                                           :player/empire-name  "Defender"
                                           :player/agents       10000}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game       game-id
                                      :alliance/proposer   att-id
                                      :alliance/recipient  def-id
                                      :alliance/status     :active
                                      :alliance/formed-at  (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result1 _ broken1] (#'resolution/resolve-espionage ctx1 att-id attacker game)
            defender (xt/entity (xt/db node) def-id)]
        (is (false? (:attacker-wins? result1)))
        (is (true? (:treaty-broken? result1)))
        (is (= :broken (:alliance/status broken1)))
        (is (= att-id (:alliance/ended-by broken1)))
        (is (= ["Spymaster"] (:player/incoming-espionage-treaty-broken defender)))
        (is (some? (:player/incoming-espionage-fails defender))
            "the fail notice and the treaty-broken notice land on the same target update")
        (is (= 2 (:player/incoming-seq defender)) "two alert rows: the fail, and the break")

        ;; Refresh: cache hit does neither a second time.
        (let [db2       (xt/db node)
              attacker2 (xt/entity db2 att-id)
              ctx2      (assoc ctx0 :biff/db db2)
              [result2 _ broken2] (#'resolution/resolve-espionage ctx2 att-id attacker2 game)]
          (is (= result1 result2) "the cached result is served unchanged")
          (is (nil? broken2) "no second break on a cache-hit refresh"))

        (let [alliances (biff/q (xt/db node) '{:find (pull a [*]) :where [[a :alliance/game _]]})]
          (is (= 1 (count alliances)))
          (is (= :broken (:alliance/status (first alliances)))))))))

(deftest test-successful-silent-spy-against-a-treaty-partner-still-breaks-it
  (testing "a successful spy op is normally silent (no trace left on the target), but a
            treaty break is never silent — it still writes the target's treaty-broken field
            and bumps :player/incoming-seq, even though no op-specific field fires"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0     (get-ctx node)
            game-id  (random-uuid)
            att-id   (random-uuid)
            def-id   (random-uuid)
            game     (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id                       att-id
                                           :player/game                  game-id
                                           :player/empire-name           "Spymaster"
                                           :player/agents                10000
                                           :player/pending-espionage     def-id
                                           :player/pending-espionage-op  "spy"}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id              def-id
                                           :player/game         game-id
                                           :player/empire-name  "Defender"
                                           :player/agents       0}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game       game-id
                                      :alliance/proposer   att-id
                                      :alliance/recipient  def-id
                                      :alliance/status     :active
                                      :alliance/formed-at  (java.util.Date.)}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ broken] (#'resolution/resolve-espionage ctx1 att-id attacker game)
            defender (xt/entity (xt/db node) def-id)]
        (is (true? (:attacker-wins? result)))
        (is (true? (:treaty-broken? result)))
        (is (= :broken (:alliance/status broken)))
        (is (= ["Spymaster"] (:player/incoming-espionage-treaty-broken defender)))
        (is (nil? (:player/incoming-espionage-fails defender))
            "a successful spy still leaves no fail trace")
        (is (= 1 (:player/incoming-seq defender)) "one alert row: just the treaty break")))))

(deftest test-espionage-on-proposed-treaty-does-not-break-it
  (testing "espionage against a merely :proposed treaty partner leaves it :proposed — it
            hasn't 'arrived' yet, same as resolve-combat"
    (with-open [node (test-xtdb-node [])]
      (let [ctx0     (get-ctx node)
            game-id  (random-uuid)
            att-id   (random-uuid)
            def-id   (random-uuid)
            game     (submit! ctx0 (merge helpers/game-defaults {:xt/id game-id}))
            attacker (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id                       att-id
                                           :player/game                  game-id
                                           :player/empire-name           "Spymaster"
                                           :player/agents                10000
                                           :player/pending-espionage     def-id
                                           :player/pending-espionage-op  "spy"}))
            _        (submit! ctx0 (merge helpers/player-defaults
                                          {:xt/id              def-id
                                           :player/game         game-id
                                           :player/empire-name  "Defender"
                                           :player/agents       0}))
            _        (submit! ctx0 (alliance-fixture
                                     {:alliance/game       game-id
                                      :alliance/proposer   att-id
                                      :alliance/recipient  def-id
                                      :alliance/status     :proposed}))
            ctx1     (assoc ctx0 :biff/db (xt/db node))
            [result _ broken] (#'resolution/resolve-espionage ctx1 att-id attacker game)]
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
