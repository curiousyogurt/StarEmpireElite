;;;;;
;;;;; Diplomacy - Alliance (Treaty) Rules
;;;;;
;;;;; Pure functions on plain :alliance maps (the shape is defined in schema.clj), plus the
;;;;; one query that loads them from the database. An alliance is shared by both players it
;;;;; involves — neither player's entity carries its own copy — so there is a single source of
;;;;; truth for whether a treaty exists.
;;;;;
;;;;; Lifecycle: :proposed -> :active (accepted) | :declined | :withdrawn;
;;;;;            :active   -> :dissolved (ended by either side, from the diplomacy phase)
;;;;;                       | :broken (ended by an attack).
;;;;;
;;;;; Proposing, accepting, declining, and withdrawing only ever happen during the diplomacy
;;;;; phase (see pages/app/diplomacy.clj). Breaking a treaty by attack happens during combat
;;;;; resolution (resolution.clj) and can only happen to an :active treaty — a :proposed one
;;;;; is an offer that hasn't "arrived" yet, so an attack during that window leaves it pending.
;;;;;
;;;;; Public API: partner-id, involves?, for-player, only-live-partners, active-treaty-between,
;;;;;             outgoing-proposals, incoming-proposals, active-treaties, slots-used,
;;;;;             free-slots, check-propose, check-accept, propose, accept, decline, withdraw,
;;;;;             dissolve, break-by-attack, resolve-decisions, alliances-for-player
;;;;;

(ns com.star-empire-elite.diplomacy
  (:require [com.biffweb :refer [q]]))

;;;;
;;;; Status Predicates
;;;;

;;; A "live" relationship is one that still occupies a slot or still has a chance of being
;;; acted on — a proposal awaiting a reply, or an accepted treaty. Everything past that
;;; (:declined, :withdrawn, :dissolved, :broken) is history.
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
  "Any live (proposed or active) alliance between a-id and b-id, or nil.

  [alliances seq-of-alliance-map, a-id uuid, b-id uuid] -> alliance-map | nil"
  [alliances a-id b-id]
  (first (filter #(and (live-statuses (:alliance/status %)) (between? % a-id b-id)) alliances)))

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

(defn slots-used
  "Number of slots player-id currently occupies: active treaties plus proposals they sent.
  An incoming proposal doesn't occupy a slot — only the sender reserves one, until it's
  accepted or the recipient's own slots are checked on acceptance.

  [alliances seq-of-alliance-map, player-id uuid] -> int"
  [alliances player-id]
  (+ (count (active-treaties alliances player-id))
     (count (outgoing-proposals alliances player-id))))

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
  "Whether proposer-id may propose a treaty to target-id right now.

  [game game-map, alliances seq-of-alliance-map, proposer-id uuid, target-id uuid] -> result-map"
  [game alliances proposer-id target-id]
  (cond
    (= proposer-id target-id)
    {:ok? false :reason "You cannot propose a treaty with yourself."}

    (some? (relationship-between alliances proposer-id target-id))
    {:ok? false :reason "A treaty or proposal already exists with that empire."}

    (zero? (free-slots game alliances proposer-id))
    {:ok? false :reason "No treaty slots available."}

    :else
    {:ok? true :reason nil}))

(defn check-accept
  "Whether alliance (a :proposed alliance) may be accepted by its recipient right now.

  [game game-map, alliances seq-of-alliance-map, alliance alliance-map] -> result-map"
  [game alliances alliance]
  (cond
    (not= (:alliance/status alliance) :proposed)
    {:ok? false :reason "That proposal is no longer pending."}

    (zero? (free-slots game alliances (:alliance/recipient alliance)))
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

  [proposer-id uuid, recipient-id uuid, game-id uuid, now inst] -> alliance-map"
  [proposer-id recipient-id game-id now]
  {:db/doc-type          :alliance
   :alliance/game        game-id
   :alliance/kind        :peace
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

(defn dissolve
  "End an active treaty peacefully. player-id is whichever side ended it.

  [alliance alliance-map, player-id uuid, now inst] -> alliance-map"
  [alliance player-id now]
  (assoc alliance
         :alliance/status :dissolved
         :alliance/ended-at now
         :alliance/ended-by player-id))

(defn break-by-attack
  "End an active treaty because attacker-id attacked their treaty partner.

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
  "Apply every per-relationship decision (:accept/:decline/:withdraw/:dissolve), plus an
  optional new Propose target, against alliances in one pass. decisions is a seq of
  [alliance-id op] pairs — order matters when two decisions would compete for the same
  slot (see below), so callers pass them in a stable, meaningful order (e.g. page order).
  This function is pure; see apply-diplomacy for how its result gets persisted.

  Releases (:decline/:withdraw/:dissolve) are applied first, since each only ever frees a
  slot or needs none — they never compete with each other. Accepts are applied next, each
  re-checked with check-accept against the slots freed by the releases above and by any
  earlier accept in this same list — so if two incoming proposals are both accepted but
  only one slot is free, the one earlier in decisions wins and the other is silently
  dropped, the same way a single stale choice already was. The Propose target (if any) is
  checked last of all, so it only claims a slot nothing above already took.

  A decision on an alliance missing from alliances, already resolved, or not currently valid
  for player-id to make (wrong side, wrong status) is silently dropped, not rejected.

  [game game-map, alliances seq-of-alliance-map (player-id's live relationships),
   player-id uuid, decisions seq-of-[uuid keyword], propose-target uuid|nil, now inst]
  -> seq-of-{:op keyword, :alliance alliance-map}"
  [game alliances player-id decisions propose-target now]
  (let [by-id0  (into {} (map (juxt :xt/id identity)) alliances)
        release (fn [op a]
                  (case op
                    :decline  (when (and (= (:alliance/recipient a) player-id)
                                         (= (:alliance/status a) :proposed))
                                (decline a now))
                    :withdraw (when (and (= (:alliance/proposer a) player-id)
                                         (= (:alliance/status a) :proposed))
                                (withdraw a now))
                    :dissolve (when (and (involves? a player-id)
                                         (= (:alliance/status a) :active))
                                (dissolve a player-id now))
                    nil))
        ;; Pass 1: releases. Each one only ever needs its own current state, so order among
        ;; them doesn't matter.
        step1   (reduce
                  (fn [{:keys [by-id results]} [id op]]
                    (let [a (by-id id)
                          updated (and (#{:decline :withdraw :dissolve} op) a (release op a))]
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
                      (if (and (= op :accept) a (= (:alliance/recipient a) player-id)
                               (:ok? (check-accept game (vals by-id) a)))
                        (let [updated (accept a now)]
                          {:by-id (assoc by-id id updated) :results (conj results {:op :accept :alliance updated})})
                        {:by-id by-id :results results})))
                  step1
                  decisions)
        {:keys [by-id results]} step2]
    ;; Pass 3: the one new Propose, checked last against everything above.
    (cond-> results
      (and propose-target (:ok? (check-propose game (vals by-id) player-id propose-target)))
      (conj {:op :propose :alliance (propose player-id propose-target (:xt/id game) now)}))))

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
