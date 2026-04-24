;;;;;
;;;;; Combat - Pure Combat and Espionage Resolution
;;;;;
;;;;; Pure functions only — no DB access, no side effects. All combat and espionage outcomes
;;;;; are computed here and returned as data maps. Callers (outcomes-handler in app.clj) are
;;;;; responsible for persisting results and applying stat changes.
;;;;;
;;;;; Combat model:
;;;;;  - Effective forces are capped by transport/carrier capacity and general/admiral command limits
;;;;;  - Each side's power is multiplied by a random factor (±combat-variance) to introduce variance
;;;;;  - The loser takes losses proportional to the power margin (capped at 75%); winner takes half
;;;;;  - Attacker captures planets proportional to margin when victorious
;;;;;

(ns com.star-empire-elite.combat
  (:require [com.star-empire-elite.constants :as const]))

;;;;
;;;; Force Calculations
;;;;

(defn effective-forces
  "Compute effective force counts, capped by transport and general capacity.

  [player player-map] -> force-map"
  [player]
  {:soldiers   (min (:player/soldiers player)
                    (* (:player/transports player) const/soldiers-per-transport)
                    (* (:player/generals   player) const/soldiers-per-general))
   :fighters   (min (:player/fighters player)
                    (* (:player/carriers player) const/fighters-per-carrier)
                    (* (:player/admirals player) const/fighters-per-admiral))
   :transports (:player/transports player)
   :generals   (:player/generals   player)
   :carriers   (:player/carriers   player)
   :admirals   (:player/admirals   player)
   :cmd-ships  (:player/cmd-ships  player)})

(defn effective-defending-forces
  "Compute effective force counts for a defender. Defenders have no transport cap (soliders and 
  fighters are are already where the need to be to defend against an attack), and stations are 
  included.

  [player player-map] -> force-map"
  [player]
  ;; Start with effective-forces for defending player, and modify :soldiers, :fighters calculation,
  ;; and add defence stations.
  (-> (effective-forces player)
      (assoc :soldiers  (min (:player/soldiers player)
                             (* (:player/generals player) const/soldiers-per-general))
             :fighters  (min (:player/fighters player)
                             (* (:player/admirals player) const/fighters-per-admiral))
             :stations  (:player/stations player))))

(defn base-power
  "Sum the raw power of a force set. Stations are counted only for defenders.

  [game game-map, forces force-map, attacker? bool] -> number"
  [game forces attacker?]
  (+ (* (:soldiers  forces) (:game/soldier-power  game))
     (* (:fighters  forces) (:game/fighter-power  game))
     (* (:cmd-ships forces) (:game/cmd-ship-power game))
     (* (:generals  forces) (:game/general-power  game))
     (* (:admirals  forces) (:game/admiral-power  game))
     (if attacker? 0
       (* (:stations forces) (:game/station-power game)))))

(defn random-factor
  "Return a random multiplier in [1 - variance, 1 + variance].

  [] -> float"
  []
  (+ (- 1.0 const/combat-variance)
     (* (rand) (* 2 const/combat-variance))))

;;;;
;;;; Resolution
;;;;

(defn- compute-losses
  "Apply a loss rate to each unit type in a force map, flooring at 0.

  [forces force-map, rate float] -> losses-map"
  [forces rate]
  {:soldiers-lost   (max 0 (long (* (:soldiers   forces) rate)))
   :transports-lost (max 0 (long (* (:transports forces) rate)))
   :generals-lost   (max 0 (long (* (:generals   forces) rate)))
   :fighters-lost   (max 0 (long (* (:fighters   forces) rate)))
   :carriers-lost   (max 0 (long (* (:carriers   forces) rate)))
   :admirals-lost   (max 0 (long (* (:admirals   forces) rate)))
   :cmd-ships-lost  (max 0 (long (* (:cmd-ships  forces) rate)))
   :stations-lost   (max 0 (long (* (:stations   forces) rate)))})

(defn- select-planets
  "Randomly select n planets from the defender's pool, returning a {:mil n :food n :ore n} map of how 
  many of each type are transferred.

  [defender player-map, n int] -> transfer-map"
  [defender n]
  (let [pool  (shuffle (concat (repeat (:player/mil-planets defender) :mil)
                               (repeat (:player/erg-planets defender) :food)
                               (repeat (:player/ore-planets defender) :ore)))
        taken (frequencies (take n pool))]
    {:mil  (get taken :mil  0)
     :food (get taken :food 0)
     :ore  (get taken :ore  0)}))

(defn resolve-espionage
  "Resolve an espionage attempt. Agent counts are compared with ±variance random rolls. Returns a 
  result map including intel snapshot on success. UUIDs stored as strings for safe pr-str round-trip.

  [attacker player-map, defender player-map] -> result-map"
  [attacker defender]
  (let [att-roll  (* (:player/agents attacker) (random-factor))
        def-roll  (* (:player/agents defender)  (random-factor))
        att-wins? (> att-roll def-roll)]
    {:attacker-id    (str (:xt/id attacker))
     :defender-id    (str (:xt/id defender))
     :defender-name  (:player/empire-name defender)
     :attacker-wins? att-wins?
     :intel (when att-wins?
              {:soldiers   (:player/soldiers   defender)
               :transports (:player/transports defender)
               :generals   (:player/generals   defender)
               :fighters   (:player/fighters   defender)
               :carriers   (:player/carriers   defender)
               :admirals   (:player/admirals   defender)
               :stations   (:player/stations   defender)
               :cmd-ships  (:player/cmd-ships  defender)
               :agents     (:player/agents     defender)})}))

(defn resolve-combat
  "Resolve a full combat engagement between attacker and defender. Returns a result map containing 
  both sides' force counts, losses, rolls, and any planets transferred. UUIDs stored as strings for 
  safe pr-str round-trip.

  [game game-map, attacker player-map, defender player-map] -> result-map"
  [game attacker defender]
  (let [att-forces (effective-forces attacker)
        def-forces (effective-defending-forces defender)
        att-power  (base-power game att-forces true)
        def-power  (base-power game def-forces false)
        att-roll   (* att-power (random-factor))
        def-roll   (* def-power (random-factor))
        att-wins?  (> att-roll def-roll)
        max-roll   (max att-roll def-roll)
        ;; Normalised relative difference (always between 0.0 and 1.0) Lower margin means rolls were 
        ;; nearly identical; higher margin means one side overwhelmed the other.
        margin     (if (zero? max-roll) 0.0
                     (/ (Math/abs (- att-roll def-roll)) max-roll))
        ;; If margin is small, loser-rate is small (survives with most of their forces.  Capped at 75% 
        ;; as margin increases.
        loser-rate  (min margin 0.75)
        ;; Cap winner losses at 50% of loser's losses, ensuring that even in victory, there is some 
        ;; cost to combat.
        winner-rate (/ loser-rate 2.0)
        def-total-planets (+ (:player/mil-planets  defender)
                              (:player/erg-planets defender)
                              (:player/ore-planets  defender))
        ;; The margin also determines whether any territory is gained.  A close battle will result in 
        ;; no new territory; but a crushing victory will allow the attacker to capture a large number 
        ;; of planets.
        planets-count       (if att-wins? (long (* margin def-total-planets)) 0)
        ;; Randomly select planets to be transferred to the attacker.
        planets-transferred (select-planets defender planets-count)]
    {:attacker-id     (str (:xt/id attacker))
     :attacker-name   (:player/empire-name attacker)
     :defender-id     (str (:xt/id defender))
     :defender-name   (:player/empire-name defender)
     :attacker-counts {:soldiers   (:player/soldiers   attacker)
                       :transports (:player/transports attacker)
                       :generals   (:player/generals   attacker)
                       :fighters   (:player/fighters   attacker)
                       :carriers   (:player/carriers   attacker)
                       :admirals   (:player/admirals   attacker)
                       :cmd-ships  (:player/cmd-ships  attacker)}
     :defender-counts {:soldiers   (:player/soldiers   defender)
                       :transports (:player/transports defender)
                       :generals   (:player/generals   defender)
                       :fighters   (:player/fighters   defender)
                       :carriers   (:player/carriers   defender)
                       :admirals   (:player/admirals   defender)
                       :cmd-ships  (:player/cmd-ships  defender)
                       :stations   (:player/stations   defender)}
     :attacker-forces att-forces
     :defender-forces def-forces
     :attacker-roll   att-roll
     :defender-roll   def-roll
     :attacker-wins?  att-wins?
     :margin          margin
     :attacker-losses (compute-losses att-forces (if att-wins? winner-rate loser-rate))
     :defender-losses (compute-losses def-forces (if att-wins? loser-rate winner-rate))
     :planets-transferred planets-transferred}))
