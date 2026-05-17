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
   :cmd-ships  (:player/cmd-ships  player)
   :stations   0})

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
     ;; If attacker, then 0 for stations; otherwise add stations to power
     (if attacker? 0 (* (:stations forces) (:game/station-power game)))))

(defn random-factor
  "Return a random multiplier in [1 - variance, 1 + variance].

  [] -> float"
  []
  (+ (- 1.0 const/combat-variance)
     (* (rand) (* 2 const/combat-variance))))

;;;;
;;;; Attack Resolution
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

(defn resolve-combat
  "Resolve a full combat engagement between attacker and defender. The `mode` parameter
  (:raid or :invade) determines the defender's effective defense multiplier and the cap
  on captured planets and resources. Returns a result map containing both sides' force
  counts, losses, rolls, captured planets, captured resources, and the combat mode.
  UUIDs stored as strings for safe pr-str round-trip.
  Passing nil for mode defaults to :invade for backward compatibility.

  [game game-map, attacker player-map, defender player-map, mode keyword] -> result-map"
  [game attacker defender mode]
  (let [mode        (or mode :invade)  ;; backward compatibility for games without combat-mode keys
        att-forces  (effective-forces attacker)
        def-forces  (effective-defending-forces defender)
        att-power   (base-power game att-forces true)
        def-mult    (case mode
                      :raid   (or (:game/raid-defense-multiplier   game) const/raid-defense-multiplier)
                      :invade (or (:game/invade-defense-multiplier game) const/invade-defense-multiplier))
        reward-mult (case mode
                      :raid   (or (:game/raid-reward-multiplier   game) const/raid-reward-multiplier)
                      :invade (or (:game/invade-reward-multiplier game) const/invade-reward-multiplier))
        def-power   (* (base-power game def-forces false) def-mult)
        att-roll    (* att-power (random-factor))
        def-roll    (* def-power (random-factor))
        att-wins?   (> att-roll def-roll)
        max-roll    (max att-roll def-roll)
        ;; Normalised relative difference (always between 0.0 and 1.0). Lower margin means rolls were
        ;; nearly identical; higher margin means one side overwhelmed the other.
        margin      (if (zero? max-roll) 0.0 (/ (Math/abs (- att-roll def-roll)) max-roll))
        ;; If margin is small, loser-rate is small (survives with most of their forces). Capped at 75%
        ;; as margin increases.
        loser-rate  (min margin 0.75)
        ;; Cap winner losses at 50% of loser's losses, ensuring that even in victory, there is some
        ;; cost to combat.
        winner-rate (/ loser-rate 2.0)
        def-total-planets (+ (:player/mil-planets  defender)
                              (:player/erg-planets  defender)
                              (:player/ore-planets  defender))
        ;; Use loser-rate (capped at 0.75) as the effective margin for captures, so that captures
        ;; scale with victory margin but are bounded by the same 75% cap applied to combat losses.
        planets-count       (if att-wins? (long (* loser-rate reward-mult def-total-planets)) 0)
        ;; Randomly select planets to be transferred to the attacker.
        planets-transferred (select-planets defender planets-count)
        ;; Resources captured scale by loser-rate × reward-mult; zero on defeat.
        resources-mult     (if att-wins? (* loser-rate reward-mult) 0.0)
        credits-captured   (long (* resources-mult (:player/credits defender)))
        food-captured      (long (* resources-mult (:player/food    defender)))
        fuel-captured      (long (* resources-mult (:player/fuel    defender)))]
    {:attacker-id     (str (:xt/id attacker))
     :attacker-name   (:player/empire-name attacker)
     :defender-id     (str (:xt/id defender))
     :defender-name   (:player/empire-name defender)
     :mode            mode
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
     :planets-transferred planets-transferred
     :resources-captured  {:credits credits-captured
                           :food    food-captured
                           :fuel    fuel-captured}}))

;;;;
;;;; Espionage Resolution
;;;;

(defn- espionage-roll
  "Compute attacker/defender agent rolls and determine the winner.
  Returns {:att-roll float, :def-roll float, :att-wins? bool, :agents-captured long|nil}.
  agents-captured is non-nil only on failure; it is the number of the attacker's agents taken.

  [attacker player-map, defender player-map] -> roll-map"
  [attacker defender]
  (let [att-roll  (* (:player/agents attacker) (random-factor))
        def-roll  (* (:player/agents defender)  (random-factor))
        att-wins? (> att-roll def-roll)]
    {:att-roll        att-roll
     :def-roll        def-roll
     :att-wins?       att-wins?
     :agents-captured (when-not att-wins?
                        (min (:player/agents attacker)
                             (max const/espionage-defection-min
                                  (long (* (:player/agents attacker)
                                           const/espionage-defection-rate)))))}))

(defn resolve-espionage
  "Resolve an espionage attempt. Agent counts are compared with ±variance random rolls. Returns a 
  result map including intel snapshot on success. UUIDs stored as strings for safe pr-str round-trip.

  [attacker player-map, defender player-map] -> result-map"
  [attacker defender]
  (let [{:keys [att-wins? agents-captured]} (espionage-roll attacker defender)]
    {:op             "spy"
     :attacker-id    (str (:xt/id attacker))
     :defender-id    (str (:xt/id defender))
     :defender-name  (:player/empire-name defender)
     :attacker-wins? att-wins?
     :agents-captured agents-captured
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

(defn resolve-incite
  "Resolve an incite operation. Agent counts are compared with ±variance random rolls. On success,
  the defender's stability is damaged. On failure, agents are captured. UUIDs stored as strings for
  safe pr-str round-trip.

  [attacker player-map, defender player-map] -> result-map"
  [attacker defender]
  (let [{:keys [att-wins? agents-captured]} (espionage-roll attacker defender)]
    {:op               "incite"
     :attacker-id      (str (:xt/id attacker))
     :defender-id      (str (:xt/id defender))
     :defender-name    (:player/empire-name defender)
     :attacker-wins?   att-wins?
     :stability-damage (when att-wins? const/incite-stability-damage)
     :agents-captured  agents-captured}))

(defn resolve-bomb
  "Resolve a bombing operation against the target empire's ground and space forces.
  On success, a fixed percentage of soldiers, transports, fighters, and carriers are destroyed.
  On failure, agents are captured. UUIDs stored as strings for safe pr-str round-trip.

  [attacker player-map, defender player-map] -> result-map"
  [attacker defender]
  (let [{:keys [att-wins? agents-captured]} (espionage-roll attacker defender)]
    {:op                   "bomb"
     :attacker-id          (str (:xt/id attacker))
     :defender-id          (str (:xt/id defender))
     :defender-name        (:player/empire-name defender)
     :attacker-wins?       att-wins?
     :soldiers-destroyed   (when att-wins? (long (* (:player/soldiers   defender) const/bomb-damage-rate)))
     :transports-destroyed (when att-wins? (long (* (:player/transports defender) const/bomb-damage-rate)))
     :fighters-destroyed   (when att-wins? (long (* (:player/fighters   defender) const/bomb-damage-rate)))
     :carriers-destroyed   (when att-wins? (long (* (:player/carriers   defender) const/bomb-damage-rate)))
     :agents-captured      agents-captured}))


