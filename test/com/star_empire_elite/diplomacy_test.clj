(ns com.star-empire-elite.diplomacy-test
  (:require [clojure.test :refer :all]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.diplomacy :as diplomacy]
            [malli.generator :as mg]
            [xtdb.api :as xt])
  (:import (java.util Date UUID)))

;;;;
;;;; Fixtures
;;;;

(def a-id    (UUID/fromString "00000000-0000-0000-0000-00000000000a"))
(def b-id    (UUID/fromString "00000000-0000-0000-0000-00000000000b"))
(def c-id    (UUID/fromString "00000000-0000-0000-0000-00000000000c"))
(def d-id    (UUID/fromString "00000000-0000-0000-0000-00000000000d"))
(def game-id (UUID/fromString "00000000-0000-0000-0000-000000000099"))
(def now     (Date.))

(def game {:xt/id game-id :game/alliance-slots 3})

(defn- alliance
  ([status proposer recipient] (alliance status proposer recipient :peace))
  ([status proposer recipient kind]
   {:xt/id               (random-uuid)
    :alliance/game       game-id
    :alliance/kind       kind
    :alliance/proposer   proposer
    :alliance/recipient  recipient
    :alliance/status     status
    :alliance/proposed-at now}))

;;;;
;;;; partner-id / involves?
;;;;

(deftest test-partner-id
  (let [al (alliance :active a-id b-id)]
    (is (= b-id (diplomacy/partner-id al a-id)))
    (is (= a-id (diplomacy/partner-id al b-id)))))

(deftest test-involves?
  (let [al (alliance :active a-id b-id)]
    (is (true?  (diplomacy/involves? al a-id)))
    (is (true?  (diplomacy/involves? al b-id)))
    (is (false? (diplomacy/involves? al c-id)))))

;;;;
;;;; active-treaty-between
;;;;

(deftest test-active-treaty-between-finds-either-direction
  (let [al [(alliance :active a-id b-id)]]
    (is (some? (diplomacy/active-treaty-between al a-id b-id)))
    (is (some? (diplomacy/active-treaty-between al b-id a-id)))
    (is (nil?  (diplomacy/active-treaty-between al a-id c-id)))))

(deftest test-active-treaty-between-ignores-proposed
  (testing "a merely-proposed alliance is not an active treaty"
    (let [al [(alliance :proposed a-id b-id)]]
      (is (nil? (diplomacy/active-treaty-between al a-id b-id))))))

(deftest test-active-treaty-between-ignores-ended
  (testing "a broken/ended/declined/withdrawn alliance is not an active treaty"
    (doseq [status [:broken :ended :declined :withdrawn]]
      (let [al [(alliance status a-id b-id)]]
        (is (nil? (diplomacy/active-treaty-between al a-id b-id))
            (str status " should not count as active"))))))

;;;;
;;;; outgoing / incoming / active lists
;;;;

(deftest test-proposal-direction-lists
  (let [al [(alliance :proposed a-id b-id)]]
    (is (= 1 (count (diplomacy/outgoing-proposals al a-id))))
    (is (= 0 (count (diplomacy/outgoing-proposals al b-id))))
    (is (= 1 (count (diplomacy/incoming-proposals al b-id))))
    (is (= 0 (count (diplomacy/incoming-proposals al a-id))))))

;;;;
;;;; only-live-partners
;;;;

(deftest test-only-live-partners-drops-eliminated-partner
  (testing "an alliance with a partner no longer in live-ids is dropped"
    (let [al       [(alliance :active a-id b-id) (alliance :proposed a-id c-id)]
          live-ids #{b-id}]  ; c-id eliminated / gone
      (is (= 1 (count (diplomacy/only-live-partners al a-id live-ids))))
      (is (= b-id (:alliance/recipient (first (diplomacy/only-live-partners al a-id live-ids))))))))

(deftest test-only-live-partners-keeps-all-when-all-live
  (let [al       [(alliance :active a-id b-id) (alliance :proposed a-id c-id)]
        live-ids #{b-id c-id}]
    (is (= 2 (count (diplomacy/only-live-partners al a-id live-ids))))))

;;;;
;;;; slots-used / free-slots
;;;;

(deftest test-slots-used-counts-active-and-outgoing-not-incoming
  (testing "an active treaty and an outgoing proposal each cost a slot; an incoming one doesn't"
    (let [al [(alliance :active   a-id b-id)
              (alliance :proposed a-id c-id)]]
      (is (= 2 (diplomacy/slots-used al a-id)) "proposer: 1 active + 1 outgoing")
      (is (= 1 (diplomacy/slots-used al b-id)) "active-treaty partner only")
      (is (= 0 (diplomacy/slots-used al c-id)) "incoming proposal costs the recipient nothing"))))

(deftest test-free-slots
  (let [al [(alliance :active a-id b-id)]]
    (is (= 2 (diplomacy/free-slots game al a-id)))
    (is (= 3 (diplomacy/free-slots game al c-id)))))

(deftest test-free-slots-never-negative
  (testing "more live relationships than the configured limit still floors at 0"
    (let [al [(alliance :active   a-id b-id)
              (alliance :proposed a-id c-id)]
          tight-game {:game/alliance-slots 1}]
      (is (= 0 (diplomacy/free-slots tight-game al a-id))))))

;;;;
;;;; proposable-kinds / replacement?
;;;;

(deftest test-proposable-kinds-no-relationship-offers-every-kind
  (is (= #{:peace :defence :offence} (set (diplomacy/proposable-kinds [] a-id b-id)))))

(deftest test-proposable-kinds-active-partner-offers-only-the-other-kinds
  (let [al [(alliance :active a-id b-id :peace)]]
    (is (= #{:defence :offence} (set (diplomacy/proposable-kinds al a-id b-id))))
    (is (= #{:defence :offence} (set (diplomacy/proposable-kinds al b-id a-id)))
        "either side sees the same offer"))
  (let [al [(alliance :active a-id b-id :defence)]]
    (is (= #{:peace :offence} (set (diplomacy/proposable-kinds al a-id b-id))))))

(deftest test-proposable-kinds-pending-proposal-offers-nothing
  (let [al [(alliance :proposed a-id b-id :peace)]]
    (is (empty? (diplomacy/proposable-kinds al a-id b-id))
        "a pair holds at most one relationship at a time")))

(deftest test-proposable-kinds-offers-nothing-while-a-replacement-is-pending
  (testing "an active treaty plus its own pending replacement proposal (see replacement?)
            must still offer nothing new — otherwise a second proposal of the replacement's
            kind could be queued, duplicating it"
    (let [existing    (alliance :active   a-id b-id :peace)
          replacement (alliance :proposed a-id b-id :defence)]
      (is (empty? (diplomacy/proposable-kinds [existing replacement] a-id b-id)))
      (is (empty? (diplomacy/proposable-kinds [replacement existing] a-id b-id))
          "order in the alliance seq must not matter"))))

(deftest test-replacement?-true-for-different-kind-against-active-treaty
  (let [existing (alliance :active a-id b-id :peace)
        proposal (alliance :proposed a-id b-id :defence)]
    (is (true? (diplomacy/replacement? [existing proposal] proposal)))))

(deftest test-replacement?-false-with-no-existing-treaty
  (let [proposal (alliance :proposed a-id b-id :peace)]
    (is (false? (diplomacy/replacement? [proposal] proposal)))))

(deftest test-replacement?-false-when-not-proposed
  (let [existing (alliance :active a-id b-id :peace)
        other    (alliance :active a-id b-id :defence)]
    (is (false? (diplomacy/replacement? [existing other] other))
        "only a :proposed alliance can be a pending replacement")))

;;;;
;;;; slots-used with a replacement pending
;;;;

(deftest test-slots-used-does-not-double-count-a-replacement
  (testing "a replacement proposal rides on the slot its target active treaty already holds"
    (let [existing    (alliance :active   a-id b-id :peace)
          replacement (alliance :proposed a-id b-id :defence)
          al          [existing replacement]]
      (is (= 1 (diplomacy/slots-used al a-id))
          "just the active treaty — the replacement proposal doesn't add a second slot"))))

(deftest test-slots-used-counts-a-stale-replacement-once-its-target-ends
  (testing "once the active treaty it would replace is gone, the proposal is an ordinary
            outgoing proposal and counts on its own"
    (let [ended       (alliance :ended a-id b-id :peace)
          replacement (alliance :proposed  a-id b-id :defence)
          al          [ended replacement]]
      (is (= 1 (diplomacy/slots-used al a-id))))))

;;;;
;;;; partner-ids-of-kinds
;;;;

(deftest test-partner-ids-of-kinds-unions-every-matching-kind
  (let [al [(alliance :active a-id b-id :peace)
            (alliance :active a-id c-id :defence)
            (alliance :active a-id d-id :offence)]]
    (is (= #{c-id d-id} (diplomacy/partner-ids-of-kinds al a-id #{:defence :offence})))
    (is (= #{c-id} (diplomacy/partner-ids-of-kinds al a-id #{:defence})))
    (is (= #{} (diplomacy/partner-ids-of-kinds al a-id #{}))
        "an empty kind set matches no treaty")))

;;;;
;;;; check-propose
;;;;

(deftest test-check-propose-rejects-self
  (is (false? (:ok? (diplomacy/check-propose game [] a-id a-id :peace)))))

(deftest test-check-propose-rejects-existing-relationship
  (let [al [(alliance :proposed a-id b-id)]]
    (is (false? (:ok? (diplomacy/check-propose game al a-id b-id :peace))))
    (is (false? (:ok? (diplomacy/check-propose game al b-id a-id :peace)))
        "existing relationship blocks the reverse direction too")))

(deftest test-check-propose-rejects-no-free-slots
  (let [al [(alliance :active   a-id b-id)
            (alliance :active   a-id c-id)
            (alliance :proposed a-id (UUID/randomUUID))]]
    (is (= 0 (diplomacy/free-slots game al a-id)))
    (is (false? (:ok? (diplomacy/check-propose game al a-id (UUID/randomUUID) :peace))))))

(deftest test-check-propose-ok
  (is (true? (:ok? (diplomacy/check-propose game [] a-id b-id :peace)))))

(deftest test-check-propose-rejects-same-kind-already-held
  (let [al [(alliance :active a-id b-id :peace)]]
    (is (false? (:ok? (diplomacy/check-propose game al a-id b-id :peace))))))

(deftest test-check-propose-replacement-ok-with-zero-free-slots
  (testing "proposing the other kind to an existing partner needs no free slot, in either
            direction (peace->defence and defence->peace)"
    (let [tight-game {:game/alliance-slots 1}]
      (let [al [(alliance :active a-id b-id :peace)
                (alliance :active a-id c-id :peace)]]
        (is (= 0 (diplomacy/free-slots tight-game al a-id)))
        (is (true? (:ok? (diplomacy/check-propose tight-game al a-id b-id :defence)))))
      (let [al [(alliance :active a-id b-id :defence)
                (alliance :active a-id c-id :peace)]]
        (is (true? (:ok? (diplomacy/check-propose tight-game al a-id b-id :peace))))))))

;;;;
;;;; check-accept
;;;;

(deftest test-check-accept-rejects-non-proposed
  (is (false? (:ok? (diplomacy/check-accept game [] (alliance :active a-id b-id))))))

(deftest test-check-accept-rejects-no-free-slots-for-recipient
  (let [proposal     (alliance :proposed a-id b-id)
        recipient-full [(alliance :active b-id c-id)
                         (alliance :active b-id (UUID/randomUUID))
                         (alliance :active b-id (UUID/randomUUID))]]
    (is (false? (:ok? (diplomacy/check-accept game recipient-full proposal))))))

(deftest test-check-accept-ok
  (is (true? (:ok? (diplomacy/check-accept game [] (alliance :proposed a-id b-id))))))

;;;;
;;;; Transitions
;;;;

(deftest test-propose-builds-pending-alliance
  (let [al (diplomacy/propose a-id b-id :peace game-id now)]
    (is (= :proposed (:alliance/status al)))
    (is (= a-id (:alliance/proposer al)))
    (is (= b-id (:alliance/recipient al)))
    (is (= :peace (:alliance/kind al)))
    (is (not (contains? al :xt/id))
        "caller assigns the id at submit time, like events/record-events!")))

(deftest test-propose-honors-kind
  (let [al (diplomacy/propose a-id b-id :defence game-id now)]
    (is (= :defence (:alliance/kind al)))))

(deftest test-accept-activates
  (let [al (diplomacy/accept (alliance :proposed a-id b-id) now)]
    (is (= :active (:alliance/status al)))
    (is (= now (:alliance/formed-at al)))))

(deftest test-decline-is-attributed-to-recipient
  (let [al (diplomacy/decline (alliance :proposed a-id b-id) now)]
    (is (= :declined (:alliance/status al)))
    (is (= b-id (:alliance/ended-by al)))))

(deftest test-withdraw-is-attributed-to-proposer
  (let [al (diplomacy/withdraw (alliance :proposed a-id b-id) now)]
    (is (= :withdrawn (:alliance/status al)))
    (is (= a-id (:alliance/ended-by al)))))

(deftest test-end
  (let [al (diplomacy/end (alliance :active a-id b-id) b-id now)]
    (is (= :ended (:alliance/status al)))
    (is (= b-id (:alliance/ended-by al)))))

(deftest test-supersede
  (let [al (diplomacy/supersede (alliance :active a-id b-id) b-id now)]
    (is (= :superseded (:alliance/status al)))
    (is (= b-id (:alliance/ended-by al)))
    (is (= now (:alliance/ended-at al)))))

;;;;
;;;; resolve-decisions
;;;;

(defn- by-op [resolved op]
  (:alliance (first (filter #(= op (:op %)) resolved))))

(deftest test-resolve-decisions-end
  (let [treaty   (alliance :active a-id b-id)
        resolved (diplomacy/resolve-decisions game [treaty] a-id
                                               [[(:xt/id treaty) :end]] nil now)]
    (is (= 1 (count resolved)))
    (is (= :end (:op (first resolved))))
    (is (= :ended (:alliance/status (by-op resolved :end))))))

(deftest test-resolve-decisions-decline-and-withdraw
  (let [incoming (alliance :proposed b-id a-id)
        outgoing (alliance :proposed a-id c-id)
        resolved (diplomacy/resolve-decisions game [incoming outgoing] a-id
                                               [[(:xt/id incoming) :decline]
                                                [(:xt/id outgoing) :withdraw]]
                                               nil now)]
    (is (= :declined  (:alliance/status (by-op resolved :decline))))
    (is (= :withdrawn (:alliance/status (by-op resolved :withdraw))))))

(deftest test-resolve-decisions-accept
  (let [proposal (alliance :proposed b-id a-id)
        resolved (diplomacy/resolve-decisions game [proposal] a-id
                                               [[(:xt/id proposal) :accept]] nil now)]
    (is (= :active (:alliance/status (by-op resolved :accept))))))

(deftest test-resolve-decisions-accept-by-non-recipient-is-dropped
  (testing "check-accept's free-slots math is only valid when player-id is the recipient —
            this guards against a caller passing a wrong-side accept through"
    (let [proposal (alliance :proposed a-id b-id)]  ; a-id is the proposer, not recipient
      (is (empty? (diplomacy/resolve-decisions game [proposal] a-id
                                                [[(:xt/id proposal) :accept]] nil now))))))

(deftest test-resolve-decisions-end-frees-a-slot-for-an-accept-in-the-same-batch
  (testing "releases are applied before accepts, so a same-round end can free the slot
            an accept needs"
    (let [tight-game {:game/alliance-slots 1}
          active     (alliance :active a-id c-id)  ; a-id's one slot, already used
          proposal   (alliance :proposed b-id a-id)
          resolved   (diplomacy/resolve-decisions tight-game [active proposal] a-id
                                                   [[(:xt/id active) :end]
                                                    [(:xt/id proposal) :accept]]
                                                   nil now)]
      (is (= :ended (:alliance/status (by-op resolved :end))))
      (is (= :active (:alliance/status (by-op resolved :accept)))
          "freed by the end above, in the same submission"))))

(deftest test-resolve-decisions-two-accepts-one-free-slot-keeps-the-first
  (testing "ties for the last slot break by decisions' order, not by chance. The loser falls
            back to :decline rather than being dropped — an incoming proposal can never stay
            :proposed past this phase (see apply-diplomacy, which always sends every incoming
            proposal through here as :accept or :decline)."
    (let [tight-game {:game/alliance-slots 1}
          p1 (alliance :proposed b-id a-id)
          p2 (alliance :proposed c-id a-id)
          resolved (diplomacy/resolve-decisions tight-game [p1 p2] a-id
                                                 [[(:xt/id p1) :accept]
                                                  [(:xt/id p2) :accept]]
                                                 nil now)]
      (is (= :active (:alliance/status (:alliance (first (filter #(= (:xt/id p1) (:xt/id (:alliance %))) resolved))))))
      (let [loser (first (filter #(= (:xt/id p2) (:xt/id (:alliance %))) resolved))]
        (is (= :decline (:op loser)))
        (is (= :declined (:alliance/status (:alliance loser)))
            "the second accept found no free slot left and fell back to decline")))))

(deftest test-resolve-decisions-accept-with-no-free-slot-falls-back-to-decline
  (testing "a lone Accept that fails check-accept (no free slot, no competing accept
            involved) still resolves to :decline rather than being dropped"
    (let [full-game {:game/alliance-slots 1}
          already   (alliance :active a-id c-id)     ; a-id's one slot already used
          proposal  (alliance :proposed b-id a-id)
          resolved  (diplomacy/resolve-decisions full-game [already proposal] a-id
                                                  [[(:xt/id proposal) :accept]] nil now)
          result    (by-op resolved :decline)]
      (is (= :declined (:alliance/status result)))
      (is (= (:xt/id proposal) (:xt/id result))))))

(deftest test-resolve-decisions-propose-checked-last-after-accepts-use-the-slots
  (testing "Propose only claims a slot nothing above already took"
    (let [tight-game {:game/alliance-slots 1}
          proposal   (alliance :proposed b-id a-id)
          target-id  (random-uuid)
          resolved   (diplomacy/resolve-decisions tight-game [proposal] a-id
                                                   [[(:xt/id proposal) :accept]]
                                                   [target-id :peace] now)]
      (is (= :active (:alliance/status (by-op resolved :accept))))
      (is (empty? (filter #(= :propose (:op %)) resolved))
          "the slot was already spent on the accept above"))))

(deftest test-resolve-decisions-propose-ok-when-a-slot-is-free
  (let [target-id (random-uuid)
        resolved  (diplomacy/resolve-decisions game [] a-id [] [target-id :peace] now)]
    (is (= 1 (count resolved)))
    (is (= :propose (:op (first resolved))))
    (is (= a-id (:alliance/proposer (by-op resolved :propose))))
    (is (= target-id (:alliance/recipient (by-op resolved :propose))))
    (is (= :peace (:alliance/kind (by-op resolved :propose))))
    (is (= game-id (:alliance/game (by-op resolved :propose))))
    (is (not (contains? (by-op resolved :propose) :xt/id))
        "caller assigns the id at submit time, same as diplomacy/propose alone")))

(deftest test-resolve-decisions-propose-defence-to-existing-peace-partner-needs-no-slot
  (testing "a replacement Propose succeeds even with zero free slots"
    (let [tight-game {:game/alliance-slots 1}
          existing   (alliance :active a-id b-id :peace)
          resolved   (diplomacy/resolve-decisions tight-game [existing] a-id []
                                                    [b-id :defence] now)]
      (is (= 0 (diplomacy/free-slots tight-game [existing] a-id)))
      (is (= :defence (:alliance/kind (by-op resolved :propose)))))))

(deftest test-resolve-decisions-accept-replacement-supersedes-the-existing-treaty
  (testing "accepting a replacement both activates the new treaty and supersedes the old one,
            in the same pass, without needing a free slot"
    (let [tight-game  {:game/alliance-slots 1}
          existing    (alliance :active   a-id b-id :peace)
          replacement (alliance :proposed a-id b-id :defence)  ; b-id is the recipient
          resolved    (diplomacy/resolve-decisions tight-game [existing replacement] b-id
                                                    [[(:xt/id replacement) :accept]] nil now)
          accepted    (by-op resolved :accept)
          superseded  (by-op resolved :supersede)]
      (is (= 2 (count resolved)))
      (is (= :active (:alliance/status accepted)))
      (is (= :defence (:alliance/kind accepted)))
      (is (= :superseded (:alliance/status superseded)))
      (is (= :peace (:alliance/kind superseded)))
      (is (= (:xt/id existing) (:xt/id superseded))))))

(deftest test-resolve-decisions-decline-replacement-leaves-existing-treaty-active
  (let [existing    (alliance :active   a-id b-id :peace)
        replacement (alliance :proposed a-id b-id :defence)
        resolved    (diplomacy/resolve-decisions game [existing replacement] b-id
                                                  [[(:xt/id replacement) :decline]] nil now)]
    (is (= :declined (:alliance/status (by-op resolved :decline))))
    (is (empty? (filter #(= :supersede (:op %)) resolved))
        "nothing is superseded unless the replacement is actually accepted")))

(deftest test-resolve-decisions-withdraw-replacement-leaves-existing-treaty-active
  (let [existing    (alliance :active   a-id b-id :peace)
        replacement (alliance :proposed a-id b-id :defence)
        resolved    (diplomacy/resolve-decisions game [existing replacement] a-id
                                                  [[(:xt/id replacement) :withdraw]] nil now)]
    (is (= :withdrawn (:alliance/status (by-op resolved :withdraw))))
    (is (empty? (filter #(= :supersede (:op %)) resolved)))))

(deftest test-resolve-decisions-ignores-a-decision-on-an-unknown-alliance
  (testing "a decision keyed to an alliance-id not in alliances at all (e.g. the player
            isn't actually a party to it) is dropped, not an error"
    (is (empty? (diplomacy/resolve-decisions game [] a-id [[(random-uuid) :end]] nil now)))))

;;;;
;;;; alliances-for-player (database-backed)
;;;;

(defn- get-ctx [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- make-alliance! [ctx overrides]
  (let [al (merge (mg/generate :alliance main/malli-opts)
                  {:xt/id (random-uuid)}
                  overrides)]
    (biff/submit-tx ctx [(assoc al :db/doc-type :alliance)])
    al))

(deftest test-alliances-for-player-finds-both-directions
  (testing "returns alliances whether player-id is the proposer or the recipient, scoped to the game"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-ctx node)
            as-proposer  (make-alliance! ctx {:alliance/game game-id
                                               :alliance/proposer a-id
                                               :alliance/recipient b-id
                                               :alliance/status :proposed})
            as-recipient (make-alliance! ctx {:alliance/game game-id
                                               :alliance/proposer c-id
                                               :alliance/recipient a-id
                                               :alliance/status :active})
            _other-game  (make-alliance! ctx {:alliance/game (UUID/randomUUID)
                                               :alliance/proposer a-id
                                               :alliance/recipient b-id
                                               :alliance/status :proposed})
            _unrelated   (make-alliance! ctx {:alliance/game game-id
                                               :alliance/proposer b-id
                                               :alliance/recipient c-id
                                               :alliance/status :proposed})
            db      (xt/db node)
            results (diplomacy/alliances-for-player db game-id a-id)
            ids     (set (map :xt/id results))]
        (is (= 2 (count results)))
        (is (contains? ids (:xt/id as-proposer)))
        (is (contains? ids (:xt/id as-recipient)))))))

;;;;
;;;; alliances-for-players (database-backed)
;;;;

(deftest test-alliances-for-players-batches-several-players-in-one-call
  (testing "returns every alliance touching ANY of the given player-ids — proposer or
            recipient, either side — same as calling alliances-for-player once per id and
            concatenating, but in one query pair instead of N"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-ctx node)
            b-as-proposer  (make-alliance! ctx {:alliance/game game-id
                                                :alliance/proposer b-id
                                                :alliance/recipient a-id
                                                :alliance/status :active})
            c-as-recipient (make-alliance! ctx {:alliance/game game-id
                                                :alliance/proposer a-id
                                                :alliance/recipient c-id
                                                :alliance/status :proposed})
            _unrelated     (make-alliance! ctx {:alliance/game game-id
                                                :alliance/proposer a-id
                                                :alliance/recipient d-id
                                                :alliance/status :proposed})
            db      (xt/db node)
            results (diplomacy/alliances-for-players db game-id #{b-id c-id})
            ids     (set (map :xt/id results))]
        (is (= 2 (count results)))
        (is (contains? ids (:xt/id b-as-proposer)))
        (is (contains? ids (:xt/id c-as-recipient)))))))
