;;;;;
;;;;; Events - Game Event Construction and Persistence
;;;;;
;;;;; Pure event-constructor functions that turn resolution result maps into :game-event
;;;;; documents. Each constructor accepts the same result maps already computed by
;;;;; combat.clj / resolution.clj and returns plain maps — no DB or web plumbing.
;;;;;
;;;;; The single side-effecting function, record-events!, batches event maps into a
;;;;; biff/submit-tx call.
;;;;;
;;;;; Public API: event-of-battle-result, event-of-espionage-result, event-of-breakaway,
;;;;;             event-of-elimination, record-events!
;;;;;

(ns com.star-empire-elite.events
  (:require [com.biffweb :as biff])
  (:import (java.util UUID)))

;;;;
;;;; Helpers
;;;;

(defn- ->uuid
  "Parse a string UUID, returning nil if input is nil."
  [s]
  (when s (UUID/fromString s)))

(defn- base-event
  "Common fields shared by all game events.

  [meta {:keys [game-id turn round at]}, kind keyword, visibility keyword] -> map"
  [{:keys [game-id turn round at]} kind visibility]
  {:event/game       game-id
   :event/at         at
   :event/turn       turn
   :event/round      round
   :event/kind       kind
   :event/visibility visibility})

;;;;
;;;; Event Constructors — Pure Functions
;;;;

(defn event-of-battle-result
  "Construct a :public event from a combat result (invade, raid, or strike).

  The result map is the same shape produced by combat/resolve-combat or
  combat/resolve-strike and cached in :player/last-battle-result.

  [result battle-result-map, meta {:keys [game-id turn round at]}] -> event-map"
  [result meta]
  (merge (base-event meta (:mode result) :public)
         {:event/attacker      (->uuid (:attacker-id result))
          :event/attacker-name (:attacker-name result)
          :event/defender      (->uuid (:defender-id result))
          :event/defender-name (:defender-name result)
          :event/payload       (pr-str result)}))

(defn event-of-espionage-result
  "Construct an [attacker-event, defender-event] pair from an espionage result.

  The attacker event has :attacker-only visibility and includes full details.
  The defender event has :defender-only visibility with attacker identity redacted
  from both the event fields and the payload.

  bomb results use this constructor too — the :op field in the result map
  determines :event/kind.

  [result espionage-result-map,
   attacker-name string (empire name of the attacker),
   meta {:keys [game-id turn round at]}]
  -> [attacker-event defender-event]"
  [result attacker-name meta]
  (let [kind          (keyword (:op result))
        attacker-base (merge (base-event meta kind :attacker-only)
                             {:event/attacker      (->uuid (:attacker-id result))
                              :event/attacker-name attacker-name
                              :event/defender      (->uuid (:defender-id result))
                              :event/defender-name (:defender-name result)
                              :event/payload       (pr-str result)})
        redacted      (dissoc result :attacker-id)
        defender-base (merge (base-event meta kind :defender-only)
                             {:event/attacker      nil
                              :event/attacker-name nil
                              :event/defender      (->uuid (:defender-id result))
                              :event/defender-name (:defender-name result)
                              :event/payload       (pr-str redacted)})]
    [attacker-base defender-base]))

(defn event-of-breakaway
  "Construct a :public event from a stability breakaway result.

  Only call when (:triggered? result) is true — a non-triggered roll is not
  an event worth recording.

  [result breakaway-result-map, player-id uuid, empire-name string,
   meta {:keys [game-id turn round at]}] -> event-map"
  [result player-id empire-name meta]
  (merge (base-event meta :breakaway :public)
         {:event/attacker      nil
          :event/attacker-name nil
          :event/defender      player-id
          :event/defender-name empire-name
          :event/payload       (pr-str result)}))

(defn event-of-elimination
  "Construct a :public event when a player is eliminated (zero planets).

  [player-id uuid, empire-name string, meta {:keys [game-id turn round at]}]
  -> event-map"
  [player-id empire-name meta]
  (merge (base-event meta :elimination :public)
         {:event/attacker      nil
          :event/attacker-name nil
          :event/defender      player-id
          :event/defender-name empire-name
          :event/payload       (pr-str {})}))

;;;;
;;;; Persistence
;;;;

(defn record-events!
  "Persist a seq of event maps as :game-event documents.

  Each event is assigned a random UUID as :xt/id and tagged with
  :db/doc-type :game-event. Submits all events in a single transaction.

  [ctx biff-ctx, events seq-of-event-maps] -> nil"
  [ctx events]
  (when (seq events)
    (biff/submit-tx ctx
      (mapv (fn [evt]
              (assoc evt
                     :xt/id       (UUID/randomUUID)
                     :db/doc-type :game-event))
            events))))
