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
(def game-id (UUID/fromString "00000000-0000-0000-0000-000000000099"))
(def now     (Date.))

(def game {:xt/id game-id :game/alliance-slots 3})

(defn- alliance [status proposer recipient]
  {:xt/id               (random-uuid)
   :alliance/game       game-id
   :alliance/kind       :peace
   :alliance/proposer   proposer
   :alliance/recipient  recipient
   :alliance/status     status
   :alliance/proposed-at now})

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
  (testing "a broken/dissolved/declined/withdrawn alliance is not an active treaty"
    (doseq [status [:broken :dissolved :declined :withdrawn]]
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
;;;; check-propose
;;;;

(deftest test-check-propose-rejects-self
  (is (false? (:ok? (diplomacy/check-propose game [] a-id a-id)))))

(deftest test-check-propose-rejects-existing-relationship
  (let [al [(alliance :proposed a-id b-id)]]
    (is (false? (:ok? (diplomacy/check-propose game al a-id b-id))))
    (is (false? (:ok? (diplomacy/check-propose game al b-id a-id)))
        "existing relationship blocks the reverse direction too")))

(deftest test-check-propose-rejects-no-free-slots
  (let [al [(alliance :active   a-id b-id)
            (alliance :active   a-id c-id)
            (alliance :proposed a-id (UUID/randomUUID))]]
    (is (= 0 (diplomacy/free-slots game al a-id)))
    (is (false? (:ok? (diplomacy/check-propose game al a-id (UUID/randomUUID)))))))

(deftest test-check-propose-ok
  (is (true? (:ok? (diplomacy/check-propose game [] a-id b-id)))))

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
  (let [al (diplomacy/propose a-id b-id game-id now)]
    (is (= :proposed (:alliance/status al)))
    (is (= a-id (:alliance/proposer al)))
    (is (= b-id (:alliance/recipient al)))
    (is (= :peace (:alliance/kind al)))
    (is (not (contains? al :xt/id))
        "caller assigns the id at submit time, like events/record-events!")))

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

(deftest test-dissolve
  (let [al (diplomacy/dissolve (alliance :active a-id b-id) b-id now)]
    (is (= :dissolved (:alliance/status al)))
    (is (= b-id (:alliance/ended-by al)))))

;;;;
;;;; resolve-decisions
;;;;

(defn- by-op [resolved op]
  (:alliance (first (filter #(= op (:op %)) resolved))))

(deftest test-resolve-decisions-dissolve
  (let [treaty   (alliance :active a-id b-id)
        resolved (diplomacy/resolve-decisions game [treaty] a-id
                                               [[(:xt/id treaty) :dissolve]] nil now)]
    (is (= 1 (count resolved)))
    (is (= :dissolve (:op (first resolved))))
    (is (= :dissolved (:alliance/status (by-op resolved :dissolve))))))

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

(deftest test-resolve-decisions-dissolve-frees-a-slot-for-an-accept-in-the-same-batch
  (testing "releases are applied before accepts, so a same-round dissolve can free the slot
            an accept needs"
    (let [tight-game {:game/alliance-slots 1}
          active     (alliance :active a-id c-id)  ; a-id's one slot, already used
          proposal   (alliance :proposed b-id a-id)
          resolved   (diplomacy/resolve-decisions tight-game [active proposal] a-id
                                                   [[(:xt/id active) :dissolve]
                                                    [(:xt/id proposal) :accept]]
                                                   nil now)]
      (is (= :dissolved (:alliance/status (by-op resolved :dissolve))))
      (is (= :active (:alliance/status (by-op resolved :accept)))
          "freed by the dissolve above, in the same submission"))))

(deftest test-resolve-decisions-two-accepts-one-free-slot-keeps-the-first
  (testing "ties for the last slot break by decisions' order, not by chance"
    (let [tight-game {:game/alliance-slots 1}
          p1 (alliance :proposed b-id a-id)
          p2 (alliance :proposed c-id a-id)
          resolved (diplomacy/resolve-decisions tight-game [p1 p2] a-id
                                                 [[(:xt/id p1) :accept]
                                                  [(:xt/id p2) :accept]]
                                                 nil now)]
      (is (= :active (:alliance/status (:alliance (first (filter #(= (:xt/id p1) (:xt/id (:alliance %))) resolved))))))
      (is (empty? (filter #(= (:xt/id p2) (:xt/id (:alliance %))) resolved))
          "the second accept found no free slot left and was dropped"))))

(deftest test-resolve-decisions-propose-checked-last-after-accepts-use-the-slots
  (testing "Propose only claims a slot nothing above already took"
    (let [tight-game {:game/alliance-slots 1}
          proposal   (alliance :proposed b-id a-id)
          target-id  (random-uuid)
          resolved   (diplomacy/resolve-decisions tight-game [proposal] a-id
                                                   [[(:xt/id proposal) :accept]]
                                                   target-id now)]
      (is (= :active (:alliance/status (by-op resolved :accept))))
      (is (empty? (filter #(= :propose (:op %)) resolved))
          "the slot was already spent on the accept above"))))

(deftest test-resolve-decisions-propose-ok-when-a-slot-is-free
  (let [target-id (random-uuid)
        resolved  (diplomacy/resolve-decisions game [] a-id [] target-id now)]
    (is (= 1 (count resolved)))
    (is (= :propose (:op (first resolved))))
    (is (= a-id (:alliance/proposer (by-op resolved :propose))))
    (is (= target-id (:alliance/recipient (by-op resolved :propose))))
    (is (= game-id (:alliance/game (by-op resolved :propose))))
    (is (not (contains? (by-op resolved :propose) :xt/id))
        "caller assigns the id at submit time, same as diplomacy/propose alone")))

(deftest test-resolve-decisions-ignores-a-decision-on-an-unknown-alliance
  (testing "a decision keyed to an alliance-id not in alliances at all (e.g. the player
            isn't actually a party to it) is dropped, not an error"
    (is (empty? (diplomacy/resolve-decisions game [] a-id [[(random-uuid) :dissolve]] nil now)))))

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
