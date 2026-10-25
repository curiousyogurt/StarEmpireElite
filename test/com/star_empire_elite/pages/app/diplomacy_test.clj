(ns com.star-empire-elite.pages.app.diplomacy-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.pages.app.diplomacy :as page]
            [malli.generator :as mg]
            [xtdb.api :as xt]))

;;;;
;;;; Fixtures
;;;;

(defn- ctx-for
  "Build a fresh request ctx against the current db snapshot. opts can include :params
  and :session.

  [node xtdb-node, player-id uuid, opts? map] -> ctx-map"
  [node player-id & [{:keys [params session]}]]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts
   :path-params     {:player-id (str player-id)}
   :params          (or params {})
   :session         (or session {})})

(defn- make-game! [ctx overrides]
  (let [game (merge (mg/generate :game main/malli-opts)
                     {:xt/id (random-uuid) :game/alliance-slots 2}
                     overrides)]
    (biff/submit-tx ctx [(assoc game :db/doc-type :game)])
    game))

(defn- make-player! [ctx game-id overrides]
  (let [player (merge (mg/generate :player main/malli-opts)
                       {:xt/id         (random-uuid)
                        :player/game   game-id
                        :player/status const/player-status-active
                        :player/current-phase 7
                        ;; mg/generate fills every optional field with random garbage. The
                        ;; treaty-* fields are read-and-capped-onto by apply-diplomacy's
                        ;; decision-resolution; incoming-attacks and incoming-bomb-result are
                        ;; edn/read-string'd by diplomacy-page's alert banner
                        ;; (ui/incoming-alert-content) once something forces the
                        ;; (otherwise-lazy) hiccup tree, e.g. pr-str in a test. A random
                        ;; garbage string in either crashes that read, so all are started
                        ;; clean unless a test explicitly seeds one via overrides.
                        :player/incoming-treaty-proposed  nil
                        :player/incoming-treaty-accepted  nil
                        :player/incoming-treaty-withdrawn nil
                        :player/incoming-treaty-dissolved nil
                        :player/incoming-attacks          nil
                        :player/incoming-bomb-result      nil}
                       overrides)]
    (biff/submit-tx ctx [(assoc player :db/doc-type :player)])
    player))

(defn- make-alliance! [ctx overrides]
  (let [alliance (merge (mg/generate :alliance main/malli-opts)
                         {:xt/id (random-uuid)}
                         overrides)]
    (biff/submit-tx ctx [(assoc alliance :db/doc-type :alliance)])
    alliance))

(defn- alliances-in [db game-id]
  (biff/q db '{:find (pull a [*]) :in [game-id] :where [[a :alliance/game game-id]]} game-id))

(defn- events-in [db game-id]
  (biff/q db '{:find (pull e [*]) :in [game-id] :where [[e :event/game game-id]]} game-id))

;;; Builds the one param a decision-radio submits: {:treaty-<alliance-id> "accept"}, etc.
(defn- decision-params [alliance-id op]
  {(keyword (str "treaty-" alliance-id)) op})

;;;;
;;;; apply-diplomacy — ends the window, phase 7 -> 1
;;;;

(deftest test-apply-diplomacy-wrong-phase-redirects
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          player    (make-player! setup-ctx (:xt/id game) {:player/current-phase 3})
          result    (page/apply-diplomacy (ctx-for node (:xt/id player)))]
      (is (= 303 (:status result)))
      (is (= (str "/app/game/" (:xt/id player)) (get-in result [:headers "location"]))))))

(deftest test-apply-diplomacy-advances-to-income
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          player    (make-player! setup-ctx (:xt/id game) {})
          result    (page/apply-diplomacy (ctx-for node (:xt/id player)))
          reloaded  (xt/entity (xt/db node) (:xt/id player))]
      (is (= 303 (:status result)))
      (is (= (str "/app/game/" (:xt/id player) "/income") (get-in result [:headers "location"])))
      (is (= 1 (:player/current-phase reloaded))))))

;;;;
;;;; apply-diplomacy — Propose a Treaty (deferred, resolved via diplomacy/resolve-decisions —
;;;; see diplomacy.clj and apply-diplomacy's doc comments)
;;;;

(deftest test-propose-selection-creates-an-alliance-and-advances
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Proposer"})
          b         (make-player! setup-ctx (:xt/id game) {})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id a)
                        {:params {:treaty-proposal (str (:xt/id b) ":peace")}}))
          db        (xt/db node)
          [alliance] (alliances-in db (:xt/id game))]
      (is (= 303 (:status result)))
      (is (= (str "/app/game/" (:xt/id a) "/income") (get-in result [:headers "location"])))
      (is (= 1 (:player/current-phase (xt/entity db (:xt/id a)))))
      (is (= (:xt/id a) (:alliance/proposer alliance)))
      (is (= (:xt/id b) (:alliance/recipient alliance)))
      (is (= :proposed (:alliance/status alliance)))
      (is (empty? (events-in db (:xt/id game))) "a mere proposal is not news")
      ;; The target's alert banner picks this up even if they never revisit diplomacy.
      (is (= ["Proposer"] (:player/incoming-treaty-proposed (xt/entity db (:xt/id b))))))))

(deftest test-no-selection-advances-with-nothing-created
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          result    (page/apply-diplomacy (ctx-for node (:xt/id a)))
          db        (xt/db node)]
      (is (= 303 (:status result)))
      (is (= 1 (:player/current-phase (xt/entity db (:xt/id a)))))
      (is (empty? (alliances-in db (:xt/id game)))))))

(deftest test-propose-selection-of-self-is-silently-dropped
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id a)
                        {:params {:treaty-proposal (str (:xt/id a) ":peace")}}))
          db        (xt/db node)]
      (is (= 303 (:status result)))
      (is (= 1 (:player/current-phase (xt/entity db (:xt/id a)))))
      (is (empty? (alliances-in db (:xt/id game)))))))

(deftest test-propose-selection-of-eliminated-target-is-silently-dropped
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {:player/status const/player-status-eliminated})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id a)
                        {:params {:treaty-proposal (str (:xt/id b) ":peace")}}))
          db        (xt/db node)]
      (is (= 303 (:status result)))
      (is (= 1 (:player/current-phase (xt/entity db (:xt/id a)))))
      (is (empty? (alliances-in db (:xt/id game)))))))

(deftest test-propose-selection-with-no-free-slots-is-silently-dropped
  (testing "game was created with 2 alliance-slots; a third outgoing proposal is dropped"
    (with-open [node (test-xtdb-node [])]
      (let [setup-ctx (ctx-for node nil)
            game      (make-game! setup-ctx {})
            a         (make-player! setup-ctx (:xt/id game) {})
            b         (make-player! setup-ctx (:xt/id game) {})
            c         (make-player! setup-ctx (:xt/id game) {})
            d         (make-player! setup-ctx (:xt/id game) {})
            _ (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                         :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
            _ (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                         :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id c)})
            result (page/apply-diplomacy
                     (ctx-for node (:xt/id a)
                       {:params {:treaty-proposal (str (:xt/id d) ":peace")}}))
            db     (xt/db node)]
        (is (= 303 (:status result)))
        (is (= 1 (:player/current-phase (xt/entity db (:xt/id a)))))
        (is (= 2 (count (alliances-in db (:xt/id game)))))))))

;;;;
;;;; apply-diplomacy — Accept decision
;;;;

(deftest test-accept-decision-activates-and-records-a-public-event
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Proposer"})
          b         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Recipient"})
          proposal  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id b)
                        {:params (decision-params (:xt/id proposal) "accept")}))
          db        (xt/db node)
          [updated] (alliances-in db (:xt/id game))
          [evt]     (events-in db (:xt/id game))]
      (is (= 303 (:status result)))
      (is (= 1 (:player/current-phase (xt/entity db (:xt/id b)))))
      (is (= :active (:alliance/status updated)))
      (is (= :treaty-formed (:event/kind evt)))
      (is (= :public (:event/visibility evt)))
      (is (= (:xt/id a) (:event/attacker evt)))
      (is (= "Proposer" (:event/attacker-name evt)))
      (is (= (:xt/id b) (:event/defender evt)))
      (is (= "Recipient" (:event/defender-name evt)))
      ;; The proposer's alert banner picks this up even if they never revisit diplomacy.
      (is (= ["Recipient"] (:player/incoming-treaty-accepted (xt/entity db (:xt/id a))))))))

(deftest test-accept-decision-by-non-recipient-is-silently-dropped
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {})
          proposal  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          ;; a is the proposer, not the recipient — may not accept their own proposal
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id a)
                        {:params (decision-params (:xt/id proposal) "accept")}))]
      (is (= 303 (:status result)))
      (is (= :proposed (:alliance/status (first (alliances-in (xt/db node) (:xt/id game)))))))))

(deftest test-accept-decision-with-no-free-slots-is-silently-dropped
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {})
          c         (make-player! setup-ctx (:xt/id game) {})
          d         (make-player! setup-ctx (:xt/id game) {})
          proposal  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          _ (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                       :alliance/proposer (:xt/id b) :alliance/recipient (:xt/id c)})
          _ (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                       :alliance/proposer (:xt/id b) :alliance/recipient (:xt/id d)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id b)
                        {:params (decision-params (:xt/id proposal) "accept")}))]
      (is (= 303 (:status result)))
      (is (= :proposed (:alliance/status (first (filter #(= (:xt/id proposal) (:xt/id %))
                                                          (alliances-in (xt/db node) (:xt/id game))))))))))

(deftest test-two-incoming-accepts-with-one-free-slot-only-one-wins
  (testing "with only one slot free, two simultaneous Accept decisions can't both succeed —
            exactly which one wins is diplomacy/resolve-decisions's own concern (see its
            ordering test in diplomacy_test.clj, where the decisions order is controlled
            directly); this just confirms apply-diplomacy doesn't let both through"
    (with-open [node (test-xtdb-node [])]
      (let [setup-ctx (ctx-for node nil)
            game      (make-game! setup-ctx {})
            a         (make-player! setup-ctx (:xt/id game) {})
            b         (make-player! setup-ctx (:xt/id game) {})
            c         (make-player! setup-ctx (:xt/id game) {})
            d         (make-player! setup-ctx (:xt/id game) {})
            ;; a already has one active treaty, leaving exactly one of the two slots free.
            _  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                          :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id d)})
            p1 (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                          :alliance/proposer (:xt/id b) :alliance/recipient (:xt/id a)})
            p2 (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                          :alliance/proposer (:xt/id c) :alliance/recipient (:xt/id a)})
            result (page/apply-diplomacy
                     (ctx-for node (:xt/id a)
                       {:params (merge (decision-params (:xt/id p1) "accept")
                                       (decision-params (:xt/id p2) "accept"))}))
            db       (xt/db node)
            by-id    (into {} (map (juxt :xt/id identity)) (alliances-in db (:xt/id game)))
            statuses [(:alliance/status (by-id (:xt/id p1))) (:alliance/status (by-id (:xt/id p2)))]]
        (is (= 303 (:status result)))
        (is (= 1 (count (filter #{:active} statuses))) "exactly one of the two was accepted")
        (is (= 1 (count (filter #{:proposed} statuses))) "the other stayed pending")))))

;;;;
;;;; apply-diplomacy — Decline / Withdraw decisions (no event)
;;;;

(deftest test-decline-decision-produces-no-event
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {})
          proposal  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id b)
                        {:params (decision-params (:xt/id proposal) "decline")}))
          db        (xt/db node)]
      (is (= 303 (:status result)))
      (is (= :declined (:alliance/status (first (alliances-in db (:xt/id game))))))
      (is (empty? (events-in db (:xt/id game)))))))

(deftest test-decline-decision-by-non-recipient-is-silently-dropped
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {})
          proposal  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id a)
                        {:params (decision-params (:xt/id proposal) "decline")}))]
      (is (= 303 (:status result)))
      (is (= :proposed (:alliance/status (first (alliances-in (xt/db node) (:xt/id game)))))))))

(deftest test-withdraw-decision-flags-the-recipients-alert-banner-and-produces-no-event
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Proposer"})
          b         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Recipient"})
          proposal  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id a)
                        {:params (decision-params (:xt/id proposal) "withdraw")}))
          db        (xt/db node)]
      (is (= 303 (:status result)))
      (is (= :withdrawn (:alliance/status (first (alliances-in db (:xt/id game))))))
      (is (empty? (events-in db (:xt/id game))) "withdraw stays private — no news event")
      ;; The recipient's alert banner picks this up even if they never revisit diplomacy.
      (is (= ["Proposer"] (:player/incoming-treaty-withdrawn (xt/entity db (:xt/id b))))))))

(deftest test-withdraw-decision-by-non-proposer-is-silently-dropped
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {})
          proposal  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id b)
                        {:params (decision-params (:xt/id proposal) "withdraw")}))]
      (is (= 303 (:status result)))
      (is (= :proposed (:alliance/status (first (alliances-in (xt/db node) (:xt/id game)))))))))

;;;;
;;;; apply-diplomacy — Dissolve decision (event, either side may do it)
;;;;

(deftest test-dissolve-decision-by-either-side-produces-a-public-event
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Dissolver"})
          treaty    (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id b)
                        {:params (decision-params (:xt/id treaty) "dissolve")}))
          db        (xt/db node)
          [evt]     (events-in db (:xt/id game))]
      (is (= 303 (:status result)))
      (is (= :dissolved (:alliance/status (first (alliances-in db (:xt/id game))))))
      (is (= :treaty-dissolved (:event/kind evt)))
      ;; Whoever dissolved it (b, "Dissolver") is the attacker side of the event, regardless
      ;; of who originally proposed the treaty.
      (is (= (:xt/id b) (:event/attacker evt)))
      (is (= "Dissolver" (:event/attacker-name evt)))
      ;; The other side's alert banner picks this up even if they never revisit diplomacy.
      (is (= ["Dissolver"] (:player/incoming-treaty-dissolved (xt/entity db (:xt/id a))))))))

(deftest test-dissolve-decision-by-an-uninvolved-player-is-silently-dropped
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {})
          c         (make-player! setup-ctx (:xt/id game) {})
          treaty    (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
          ;; c isn't a party to the treaty, so it isn't even in c's own live alliances —
          ;; apply-diplomacy only looks at decisions keyed to alliances it already loaded.
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id c)
                        {:params (decision-params (:xt/id treaty) "dissolve")}))]
      (is (= 303 (:status result)))
      (is (= :active (:alliance/status (first (alliances-in (xt/db node) (:xt/id game)))))))))

;;;;
;;;; apply-diplomacy — several decisions queued together in one submission
;;;;

(deftest test-several-decisions-in-one-submission-all-apply-together
  (with-open [node (test-xtdb-node [])]
    (let [setup-ctx (ctx-for node nil)
          game      (make-game! setup-ctx {})
          a         (make-player! setup-ctx (:xt/id game) {})
          b         (make-player! setup-ctx (:xt/id game) {})
          c         (make-player! setup-ctx (:xt/id game) {})
          d         (make-player! setup-ctx (:xt/id game) {})
          incoming  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                               :alliance/proposer (:xt/id b) :alliance/recipient (:xt/id a)})
          active    (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                               :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id c)})
          result    (page/apply-diplomacy
                      (ctx-for node (:xt/id a)
                        {:params (merge (decision-params (:xt/id incoming) "accept")
                                        (decision-params (:xt/id active) "dissolve")
                                        {:treaty-proposal (str (:xt/id d) ":peace")})}))
          db        (xt/db node)
          by-id     (into {} (map (juxt :xt/id identity)) (alliances-in db (:xt/id game)))
          new-alliance (first (remove #{(:xt/id incoming) (:xt/id active)} (map :xt/id (vals by-id))))]
      (is (= 303 (:status result)))
      (is (= :active    (:alliance/status (by-id (:xt/id incoming)))) "accepted")
      (is (= :dissolved  (:alliance/status (by-id (:xt/id active)))) "dissolved")
      (is (= :proposed   (:alliance/status (by-id new-alliance))) "a new Propose was also created")
      (is (= (:xt/id a) (:alliance/proposer (by-id new-alliance))))
      (is (= (:xt/id d) (:alliance/recipient (by-id new-alliance))))
      (is (= 2 (count (events-in db (:xt/id game)))) "accept and dissolve both record news"))))

;;;;
;;;; diplomacy-page — Active Treaties marker
;;;;

(deftest test-active-treaties-table-shows-the-peace-marker
  (testing "an active :peace treaty's row carries the boxed treaty-indicator"
    (with-open [node (test-xtdb-node [])]
      (let [setup-ctx (ctx-for node nil)
            game      (make-game! setup-ctx {})
            a         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Ironwall"})
            b         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Crimson Hand"})
            _         (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/kind :peace
                                                 :alliance/status :active
                                                 :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
            db        (xt/db node)
            html      (pr-str (page/diplomacy-page {:player a :game game :db db}))]
        (is (str/includes? html "Crimson Hand"))
        (is (str/includes? html "Active peace treaty"))))))

(deftest test-incoming-proposals-table-has-no-peace-marker
  (testing "a mere proposal isn't a treaty yet, so its row carries no marker"
    (with-open [node (test-xtdb-node [])]
      (let [setup-ctx (ctx-for node nil)
            game      (make-game! setup-ctx {})
            a         (make-player! setup-ctx (:xt/id game) {})
            b         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Ironwall"})
            _         (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                                 :alliance/proposer (:xt/id b) :alliance/recipient (:xt/id a)})
            db        (xt/db node)
            html      (pr-str (page/diplomacy-page {:player a :game game :db db}))]
        (is (str/includes? html "Ironwall"))
        (is (not (str/includes? html "Active peace treaty")))))))

;;;;
;;;; diplomacy-page — every relationship row renders a deferred radio, not an instant form
;;;;

(deftest test-propose-candidate-row-renders-a-radio-not-a-submit-button
  (testing "Propose is deferred like Invade/Raid/Strike — a radio input, resolved only on
            End Current Round (apply-diplomacy), not its own instant-submit form"
    (with-open [node (test-xtdb-node [])]
      (let [setup-ctx (ctx-for node nil)
            game      (make-game! setup-ctx {})
            a         (make-player! setup-ctx (:xt/id game) {})
            b         (make-player! setup-ctx (:xt/id game) {:player/empire-name "Ironwall"})
            db        (xt/db node)
            html      (pr-str (page/diplomacy-page {:player a :game game :db db}))]
        (is (str/includes? html "Ironwall"))
        (is (str/includes? html "treaty-proposal"))
        (is (str/includes? html (str (:xt/id b) ":peace")))))))

(deftest test-active-and-incoming-rows-render-deferred-radios-with-no-instant-form-left
  (testing "Dissolve/Accept/Decline/Withdraw are now treaty-<id> radios, like Propose's —
            there's no more /diplomacy-op form anywhere on the page"
    (with-open [node (test-xtdb-node [])]
      (let [setup-ctx (ctx-for node nil)
            game      (make-game! setup-ctx {})
            a         (make-player! setup-ctx (:xt/id game) {})
            b         (make-player! setup-ctx (:xt/id game) {})
            c         (make-player! setup-ctx (:xt/id game) {})
            treaty    (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :active
                                                 :alliance/proposer (:xt/id a) :alliance/recipient (:xt/id b)})
            incoming  (make-alliance! setup-ctx {:alliance/game (:xt/id game) :alliance/status :proposed
                                                 :alliance/proposer (:xt/id c) :alliance/recipient (:xt/id a)})
            db        (xt/db node)
            html      (pr-str (page/diplomacy-page {:player a :game game :db db}))]
        (is (str/includes? html (str "treaty-" (:xt/id treaty))))
        (is (str/includes? html (str "treaty-" (:xt/id incoming))))
        (is (not (str/includes? html "diplomacy-op")))))))
