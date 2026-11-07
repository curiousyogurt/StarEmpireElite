;;;;;
;;;;; Diplomacy - Alliance (Treaty) Rules
;;;;;
;;;;; Pure functions on plain :alliance maps (the shape is defined in schema.clj), plus the
;;;;; one query that loads them from the database. An alliance is shared by both players it
;;;;; involves — neither player's entity carries its own copy — so there is a single source of
;;;;; truth for whether a treaty exists.
;;;;;
;;;;; A pair holds at most one treaty at a time, of exactly one kind (see const/treaty-kinds —
;;;;; :peace, :defence, or :offence today). Proposing a different kind to an existing active
;;;;; partner is a *replacement*: accepting it supersedes the old treaty in the same move,
;;;;; rather than claiming a fresh slot (see replacement?, proposable-kinds).
;;;;;
;;;;; Lifecycle: :proposed -> :active (accepted) | :declined | :withdrawn;
;;;;;            :active   -> :ended (ended by either side, from the diplomacy phase)
;;;;;                       | :broken (ended by an attack or an espionage attempt)
;;;;;                       | :superseded (replaced by a different-kind treaty the same pair
;;;;;                         just accepted).
;;;;;
;;;;; Proposing, accepting, declining, and withdrawing only ever happen during the diplomacy
;;;;; phase (see pages/app/diplomacy.clj). Breaking a treaty by attacking or running espionage
;;;;; against its partner happens during turn resolution (resolution.clj's resolve-combat and
;;;;; resolve-espionage, both via the shared break-treaty-if-active) and can only happen to an
;;;;; :active treaty — a :proposed one is an offer that hasn't "arrived" yet, so a hostile
;;;;; action during that window leaves it pending.
;;;;;
;;;;; Public API: partner-id, involves?, for-player, only-live-partners, active-treaty-between,
;;;;;             has-relationship?, outgoing-proposals, incoming-proposals, active-treaties,
;;;;;             proposable-kinds, replacement?, partner-ids-of-kind, partner-ids-of-kinds,
;;;;;             slots-used, free-slots, check-propose, check-accept, propose, accept,
;;;;;             decline, withdraw, end, supersede, break-by-attack, resolve-decisions,
;;;;;             alliances-for-player
;;;;;

(ns com.star-empire-elite.diplomacy
  (:require [com.biffweb :refer [q]]
            [com.star-empire-elite.constants :as const]))

;;;;
;;;; Status Predicates
;;;;

;;; A "live" relationship is one that still occupies a slot or still has a chance of being
;;; acted on — a proposal awaiting a reply, or an accepted treaty. Everything past that
;;; (:declined, :withdrawn, :ended, :broken) is history.
(def ^:private live-statuses #{:proposed :active})

;;;;
;;;; Pure Queries over an Alliance List
;;;;
;;;; All functions below take the already-loaded seq of :alliance maps for one game (or one
;;;; player) and a player-id — no database access. Callers pass in alliances-for-player's
;;;; result.
;;;;

(defn partner-id
  "The other player in a two-sided alliance.

  [alliance alliance-map, player-id uuid] -> uuid"
  [alliance player-id]
  (if (= (:alliance/proposer alliance) player-id)
    (:alliance/recipient alliance)
    (:alliance/proposer alliance)))

(defn involves?
  "True if player-id is either side of alliance.

  [alliance alliance-map, player-id uuid] -> bool"
  [alliance player-id]
  (or (= (:alliance/proposer alliance) player-id)
      (= (:alliance/recipient alliance) player-id)))

(defn- between?
  "True if alliance is exactly the two-sided relationship between a-id and b-id, in either
  direction.

  [alliance alliance-map, a-id uuid, b-id uuid] -> bool"
  [alliance a-id b-id]
  (= #{a-id b-id} #{(:alliance/proposer alliance) (:alliance/recipient alliance)}))

(defn for-player
  "Every live (proposed or active) alliance that involves player-id.

  [alliances seq-of-alliance-map, player-id uuid] -> seq"
  [alliances player-id]
  (filter #(and (live-statuses (:alliance/status %)) (involves? % player-id)) alliances))

(defn only-live-partners
  "Filter alliances (already scoped to player-id, e.g. via for-player) down to those whose
  partner is in live-ids. An eliminated partner's alliance is dropped from both the slot
  count and the diplomacy page without needing any cleanup at elimination time — rejoin
  issues a brand-new player id, so a stale alliance can never apply to the wrong player.

  [alliances seq-of-alliance-map, player-id uuid, live-ids set-of-uuid] -> seq"
  [alliances player-id live-ids]
  (filter #(live-ids (partner-id % player-id)) alliances))

(defn active-treaty-between
  "The :active alliance between a-id and b-id, or nil if there isn't one.

  [alliances seq-of-alliance-map, a-id uuid, b-id uuid] -> alliance-map | nil"
  [alliances a-id b-id]
  (first (filter #(and (= (:alliance/status %) :active) (between? % a-id b-id)) alliances)))

(defn- relationship-between
  "Any live (proposed or active) alliance between a-id and b-id, or nil. A pair can briefly
  hold BOTH at once — a still-active treaty plus the new :proposed alliance switching it to
  a different kind (see replacement?) — since the active one isn't superseded until the
  replacement is actually accepted. When both exist, the :proposed one wins: it's the more
  restrictive state (proposable-kinds returns [] for it), and callers that need the active
  treaty specifically already go straight to active-treaty-between instead of here.

  [alliances seq-of-alliance-map, a-id uuid, b-id uuid] -> alliance-map | nil"
  [alliances a-id b-id]
  (let [live (filter #(and (live-statuses (:alliance/status %)) (between? % a-id b-id))
                      alliances)]
    (or (first (filter #(= :proposed (:alliance/status %)) live))
        (first live))))

(defn has-relationship?
  "True if a-id and b-id currently have any live relationship at all — an active treaty of
  either kind, or a pending proposal in either direction. Used to decide whether b-id
  belongs in a Propose a Treaty candidate list: an empire with a relationship already has
  its own options to offer elsewhere (see proposable-kinds), so it should never also show up
  there.

  [alliances seq-of-alliance-map, a-id uuid, b-id uuid] -> bool"
  [alliances a-id b-id]
  (some? (relationship-between alliances a-id b-id)))

(defn outgoing-proposals
  "Proposals player-id sent that are still awaiting a reply.

  [alliances seq-of-alliance-map, player-id uuid] -> seq"
  [alliances player-id]
  (filter #(and (= (:alliance/status %) :proposed) (= (:alliance/proposer %) player-id))
          alliances))

(defn incoming-proposals
  "Proposals sent to player-id that are still awaiting a reply.

  [alliances seq-of-alliance-map, player-id uuid] -> seq"
  [alliances player-id]
  (filter #(and (= (:alliance/status %) :proposed) (= (:alliance/recipient %) player-id))
          alliances))

(defn active-treaties
  "player-id's currently active treaties.

  [alliances seq-of-alliance-map, player-id uuid] -> seq"
  [alliances player-id]
  (filter #(and (= (:alliance/status %) :active) (involves? % player-id)) alliances))

(defn partner-ids-of-kind
  "The partners in player-id's active treaties of exactly kind — e.g. :defence for defence
  allies, or :offence for offence allies.

  [alliances seq-of-alliance-map, player-id uuid, kind keyword] -> set-of-uuid"
  [alliances player-id kind]
  (set (map #(partner-id % player-id)
            (filter #(= kind (:alliance/kind %)) (active-treaties alliances player-id)))))

(defn partner-ids-of-kinds
  "The partners in player-id's active treaties of any kind in kinds — e.g. (const/kinds-where
  :defends?) for every kind whose allies defend. Generalizes partner-ids-of-kind from one
  kind to a set, for callers (resolution/treaty-allies) that want allies by capability
  rather than by a single named kind — a future kind with more than one capability (e.g. a
  :full treaty) is then picked up automatically, with no call-site change.

  [alliances seq-of-alliance-map, player-id uuid, kinds set-of-keyword] -> set-of-uuid"
  [alliances player-id kinds]
  (set (map #(partner-id % player-id)
            (filter #(kinds (:alliance/kind %)) (active-treaties alliances player-id)))))

(defn- active-treaty-to-replace
  "The active treaty (of a different kind) that accepting proposal would replace, or nil —
  either because the pair holds no active treaty, or because check-propose already prevented
  proposing a kind the pair already holds (so this is a no-op, not an error, if that's ever
  bypassed).

  [alliances seq-of-alliance-map, proposal alliance-map] -> alliance-map | nil"
  [alliances proposal]
  (when-let [existing (active-treaty-between alliances
                                              (:alliance/proposer proposal)
                                              (:alliance/recipient proposal))]
    (when (not= (:alliance/kind existing) (:alliance/kind proposal))
      existing)))

(defn replacement?
  "True if alliance is a :proposed proposal that would replace an existing active treaty of
  a different kind, rather than needing a slot of its own — see active-treaty-to-replace.

  [alliances seq-of-alliance-map, alliance alliance-map] -> bool"
  [alliances alliance]
  (and (= :proposed (:alliance/status alliance))
       (some? (active-treaty-to-replace alliances alliance))))

(defn proposable-kinds
  "Which treaty kinds a-id may currently propose to b-id. Every kind, when there's no live
  relationship between them yet. Every kind except the one already held, when they hold an
  active treaty and neither side has a pending proposal — accepting one of these replaces
  the existing treaty (see replacement?). None, while a proposal in either direction is
  already pending — a pair holds at most one relationship at a time.

  [alliances seq-of-alliance-map, a-id uuid, b-id uuid] -> seq-of-keyword"
  [alliances a-id b-id]
  (let [live (relationship-between alliances a-id b-id)]
    (cond
      (nil? live)                           (map :kind const/treaty-kinds)
      (= :proposed (:alliance/status live)) []
      :else                                 (remove #{(:alliance/kind live)}
                                                      (map :kind const/treaty-kinds)))))

(defn slots-used
  "Number of slots player-id currently occupies: active treaties plus outgoing proposals,
  except a *replacement* proposal — one offering a different kind to an existing active
  partner (see replacement?) — which rides on the slot its target treaty already occupies
  instead of needing its own. If that active treaty ends before the replacement is decided,
  the proposal stops qualifying as a replacement and starts counting on its own, so the open-
  slot math never double-counts or drops a slot either way.
  An incoming proposal doesn't occupy a slot — only the sender reserves one, until it's
  accepted or the recipient's own slots are checked on acceptance.

  [alliances seq-of-alliance-map, player-id uuid] -> int"
  [alliances player-id]
  (+ (count (active-treaties alliances player-id))
     (count (remove #(replacement? alliances %) (outgoing-proposals alliances player-id)))))

(defn free-slots
  "How many open treaty slots player-id has left.

  [game game-map, alliances seq-of-alliance-map, player-id uuid] -> int"
  [game alliances player-id]
  (max 0 (- (:game/alliance-slots game) (slots-used alliances player-id))))

;;;;
;;;; Move Validation
;;;; Each check-* returns {:ok? bool :reason string-or-nil}, so the diplomacy-phase handler
;;;; can show the reason as a flash message when a move is rejected.
;;;;

(defn check-propose
  "Whether proposer-id may propose a kind treaty to target-id right now. A replacement (kind
  is one proposable-kinds offers because the pair already holds an active treaty of a
  different kind) needs no free slot — see slots-used.

  [game game-map, alliances seq-of-alliance-map, proposer-id uuid, target-id uuid,
   kind keyword] -> result-map"
  [game alliances proposer-id target-id kind]
  (cond
    (= proposer-id target-id)
    {:ok? false :reason "You cannot propose a treaty with yourself."}

    (not (some #{kind} (proposable-kinds alliances proposer-id target-id)))
    {:ok? false :reason "A treaty or proposal already exists with that empire."}

    (and (nil? (active-treaty-between alliances proposer-id target-id))
         (zero? (free-slots game alliances proposer-id)))
    {:ok? false :reason "No treaty slots available."}

    :else
    {:ok? true :reason nil}))

(defn check-accept
  "Whether alliance (a :proposed alliance) may be accepted by its recipient right now. A
  replacement (see replacement?) needs no free slot — it's riding on the slot the treaty it
  would supersede already occupies.

  [game game-map, alliances seq-of-alliance-map, alliance alliance-map] -> result-map"
  [game alliances alliance]
  (cond
    (not= (:alliance/status alliance) :proposed)
    {:ok? false :reason "That proposal is no longer pending."}

    (and (not (replacement? alliances alliance))
         (zero? (free-slots game alliances (:alliance/recipient alliance))))
    {:ok? false :reason "No treaty slots available."}

    :else
    {:ok? true :reason nil}))

;;;;
;;;; Transitions
;;;; Pure — each returns the updated alliance map. Callers are responsible for persisting it
;;;; (biff/submit-tx) and, where relevant, recording a game-event.
;;;;

(defn propose
  "Build a new :proposed alliance document, minus :xt/id — the caller assigns that
  (random-uuid) at submit time, the same split events/record-events! uses.

  [proposer-id uuid, recipient-id uuid, kind keyword, game-id uuid, now inst] -> alliance-map"
  [proposer-id recipient-id kind game-id now]
  {:db/doc-type          :alliance
   :alliance/game        game-id
   :alliance/kind        kind
   :alliance/proposer    proposer-id
   :alliance/recipient   recipient-id
   :alliance/status      :proposed
   :alliance/proposed-at now})

(defn accept
  "[alliance alliance-map, now inst] -> alliance-map"
  [alliance now]
  (assoc alliance :alliance/status :active :alliance/formed-at now))

(defn decline
  "[alliance alliance-map, now inst] -> alliance-map"
  [alliance now]
  (assoc alliance
         :alliance/status :declined
         :alliance/ended-at now
         :alliance/ended-by (:alliance/recipient alliance)))

(defn withdraw
  "[alliance alliance-map, now inst] -> alliance-map"
  [alliance now]
  (assoc alliance
         :alliance/status :withdrawn
         :alliance/ended-at now
         :alliance/ended-by (:alliance/proposer alliance)))

(defn end
  "End an active treaty peacefully. player-id is whichever side ended it.

  [alliance alliance-map, player-id uuid, now inst] -> alliance-map"
  [alliance player-id now]
  (assoc alliance
         :alliance/status :ended
         :alliance/ended-at now
         :alliance/ended-by player-id))

(defn supersede
  "End an active treaty because the pair just accepted a different-kind treaty, replacing
  this one. player-id is the recipient who accepted the replacement — the same accountable
  party accept itself is attributed to.

  [alliance alliance-map, player-id uuid, now inst] -> alliance-map"
  [alliance player-id now]
  (assoc alliance
         :alliance/status :superseded
         :alliance/ended-at now
         :alliance/ended-by player-id))

(defn break-by-attack
  "End an active treaty because attacker-id took a hostile action — an Action-phase attack
  or an espionage attempt — against their treaty partner.

  [alliance alliance-map, attacker-id uuid, now inst] -> alliance-map"
  [alliance attacker-id now]
  (assoc alliance
         :alliance/status :broken
         :alliance/ended-at now
         :alliance/ended-by attacker-id))

;;;;
;;;; Batch Resolution
;;;; Every diplomacy move is deferred in the UI (see pages/app/diplomacy.clj) — selecting one
;;;; only highlights it, and this function is where they all actually get applied, together,
;;;; when the player clicks End Current Round.
;;;;

(defn resolve-decisions
  "Apply every per-relationship decision (:accept/:decline/:withdraw/:end), plus an
  optional new Propose choice, against alliances in one pass. decisions is a seq of
  [alliance-id op] pairs — order matters when two decisions would compete for the same
  slot (see below), so callers pass them in a stable, meaningful order (e.g. page order).
  This function is pure; see apply-diplomacy for how its result gets persisted.

  Releases (:decline/:withdraw/:end) are applied first, since each only ever frees a
  slot or needs none — they never compete with each other. Accepts are applied next, each
  re-checked with check-accept against the slots freed by the releases above and by any
  earlier accept in this same list — so if two incoming proposals are both accepted but
  only one slot is free, the one earlier in decisions wins; the other falls back to
  :decline instead of staying :proposed, since an incoming proposal must always resolve one
  way or the other (see apply-diplomacy, which forces every incoming proposal to appear
  here as either :accept or :decline — never absent). Accepting a *replacement* (see
  replacement?) also supersedes the active treaty of the other kind it replaces, in the same
  step, so a later decision in this same pass sees both the acceptance and the now-
  :superseded treaty. The Propose choice (if any) is checked last of all, so it only claims
  a slot nothing above already took.

  A decision on an alliance missing from alliances, already resolved, or not currently valid
  for player-id to make (wrong side, wrong status) is silently dropped, not rejected.

  [game game-map, alliances seq-of-alliance-map (player-id's live relationships),
   player-id uuid, decisions seq-of-[uuid keyword],
   propose-choice [target-id kind]|nil, now inst]
  -> seq-of-{:op keyword, :alliance alliance-map}"
  [game alliances player-id decisions propose-choice now]
  (let [by-id0  (into {} (map (juxt :xt/id identity)) alliances)
        release (fn [op a]
                  (case op
                    :decline  (when (and (= (:alliance/recipient a) player-id)
                                         (= (:alliance/status a) :proposed))
                                (decline a now))
                    :withdraw (when (and (= (:alliance/proposer a) player-id)
                                         (= (:alliance/status a) :proposed))
                                (withdraw a now))
                    :end      (when (and (involves? a player-id)
                                         (= (:alliance/status a) :active))
                                (end a player-id now))
                    nil))
        ;; Pass 1: releases. Each one only ever needs its own current state, so order among
        ;; them doesn't matter.
        step1   (reduce
                  (fn [{:keys [by-id results]} [id op]]
                    (let [a (by-id id)
                          updated (and (#{:decline :withdraw :end} op) a (release op a))]
                      (if updated
                        {:by-id (assoc by-id id updated) :results (conj results {:op op :alliance updated})}
                        {:by-id by-id :results results})))
                  {:by-id by-id0 :results []}
                  decisions)
        ;; Pass 2: accepts, re-checked in order against the slot picture so far — including
        ;; releases from pass 1 and any accept earlier in this same pass. check-accept's
        ;; free-slots check is computed against the alliance's recipient — only correct when
        ;; player-id actually is the recipient, so that's verified here too, the same way
        ;; every release above checks it's the right side making the move.
        step2   (reduce
                  (fn [{:keys [by-id results]} [id op]]
                    (let [a (by-id id)]
                      (cond
                        (not (and (= op :accept) a (= (:alliance/recipient a) player-id)
                                  (= (:alliance/status a) :proposed)))
                        {:by-id by-id :results results}

                        (:ok? (check-accept game (vals by-id) a))
                        (let [accepted   (accept a now)
                              to-replace (active-treaty-to-replace (vals by-id) a)
                              superseded (when to-replace (supersede to-replace player-id now))]
                          {:by-id   (cond-> (assoc by-id id accepted)
                                      superseded (assoc (:xt/id to-replace) superseded))
                           :results (cond-> (conj results {:op :accept :alliance accepted})
                                      superseded (conj {:op :supersede :alliance superseded}))})

                        ;; No slot left for this accept by the time it's checked — an
                        ;; incoming proposal can never stay :proposed past this phase, so it
                        ;; falls back to :decline rather than being dropped.
                        :else
                        (let [updated (decline a now)]
                          {:by-id (assoc by-id id updated) :results (conj results {:op :decline :alliance updated})}))))
                  step1
                  decisions)
        {:keys [by-id results]} step2
        [propose-target propose-kind] propose-choice]
    ;; Pass 3: the one new Propose, checked last against everything above.
    (cond-> results
      (and propose-target
           (:ok? (check-propose game (vals by-id) player-id propose-target propose-kind)))
      (conj {:op :propose :alliance (propose player-id propose-target propose-kind (:xt/id game) now)}))))

;;;;
;;;; Persistence
;;;; The only database-touching function in this namespace.
;;;;

(defn alliances-for-player
  "Every alliance document (any status) that involves player-id in game-id, proposer or
  recipient. Includes ended ones — callers filter to live/active as needed via for-player,
  active-treaty-between, etc.

  [db xtdb-db, game-id uuid, player-id uuid] -> seq-of-alliance-map"
  [db game-id player-id]
  (distinct
    (concat
      (q db '{:find (pull alliance [*])
              :in [game-id player-id]
              :where [[alliance :alliance/game game-id]
                      [alliance :alliance/proposer player-id]]}
         game-id player-id)
      (q db '{:find (pull alliance [*])
              :in [game-id player-id]
              :where [[alliance :alliance/game game-id]
                      [alliance :alliance/recipient player-id]]}
         game-id player-id))))
