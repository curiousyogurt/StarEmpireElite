;;;;;
;;;;; Combat - Pure Combat and Espionage Resolution
;;;;;
;;;;; Pure functions only — no DB access, no side effects. All combat and espionage outcomes
;;;;; are computed here and returned as data maps. Callers (outcomes-handler in app.clj) are
;;;;; responsible for persisting results and applying stat changes.
;;;;;
;;;;; Combat model (two-phase):
;;;;;  - Effective forces are capped by transport/carrier capacity and general/admiral command limits
;;;;;  - Combat resolves in two sequential phases: Space then Ground
;;;;;  - Space phase: fighters, carriers, admirals, cmd-ships vs fighters, admirals, cmd-ships, stations
;;;;;  - Ground phase: soldiers, transports, generals, agents vs soldiers, generals, agents
;;;;;  - Space margin carries over as a bonus to the space winner's ground power
;;;;;  - Ground phase determines the battle winner, captures, and losses
;;;;;  - Generals/admirals/agents are capped multipliers, not raw power contributors
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
   :stations   0
   ;; Agents carry no base power and are not tracked by compute-losses.
   ;; They exist in the force map so the ground multiplier helper can read them.
   :agents     (:player/agents player)})

(defn effective-defending-forces
  "Compute effective force counts for a defender. Defenders have no transport cap (soldiers and
  fighters are already where they need to be to defend against an attack), and stations are
  included.

  [player player-map] -> force-map"
  [player]
  (-> (effective-forces player)
      (assoc :soldiers  (min (:player/soldiers player)
                             (* (:player/generals player) const/soldiers-per-general))
             :fighters  (min (:player/fighters player)
                             (* (:player/admirals player) const/fighters-per-admiral))
             :stations  (:player/stations player))))

;;;;
;;;; Phase Partitioning
;;;;

(defn- space-forces
  "Select the space-phase subset from a force map.

  [forces force-map] -> force-map"
  [forces]
  (select-keys forces [:fighters :carriers :admirals :cmd-ships :stations]))

(defn- ground-forces
  "Select the ground-phase subset from a force map.

  [forces force-map] -> force-map"
  [forces]
  (select-keys forces [:soldiers :transports :generals :agents]))

;;;;
;;;; Multiplier Helpers
;;;;

(defn- space-multiplier
  "Compute the space-phase power multiplier: 1.0 + capped admiral bonus.

  [game game-map, forces force-map] -> double"
  [game forces]
  (+ 1.0
     (min (:game/admiral-mult-cap game)
          (* (get forces :admirals 0) (:game/admiral-mult-rate game)))))

(defn- ground-multiplier
  "Compute the ground-phase power multiplier: 1.0 + capped general bonus + capped agent bonus.

  [game game-map, forces force-map] -> double"
  [game forces]
  (+ 1.0
     (min (:game/general-mult-cap game)
          (* (get forces :generals 0) (:game/general-mult-rate game)))
     (min (:game/agent-mult-cap game)
          (* (get forces :agents 0) (:game/agent-mult-rate game)))))

;;;;
;;;; Base Power
;;;;

(defn base-power
  "Sum the raw power of a force set. Command units (generals, admirals) no longer contribute
  raw power — they act as capped multipliers instead. Stations are counted only for defenders.

  [game game-map, forces force-map, attacker? bool] -> number"
  [game forces attacker?]
  (+ (* (get forces :soldiers  0) (:game/soldier-power  game))
     (* (get forces :fighters  0) (:game/fighter-power  game))
     (* (get forces :cmd-ships 0) (:game/cmd-ship-power game))
     (if attacker? 0 (* (get forces :stations 0) (:game/station-power game)))))

(defn random-factor
  "Return a random multiplier in [1 - variance, 1 + variance].

  [] -> float"
  []
  (+ (- 1.0 const/combat-variance)
     (* (rand) (* 2 const/combat-variance))))

;;;;
;;;; Phase Resolution
;;;;

(defn- resolve-phase
  "Resolve a single combat phase. Computes base-power × multiplier × random-factor for each
  side and returns the phase result.

  [game game-map, att-forces force-map, def-forces force-map,
   att-mult double, def-mult double, att-is-attacker? bool] -> phase-result"
  [game att-forces def-forces att-mult def-mult att-is-attacker?]
  (let [att-base  (base-power game att-forces att-is-attacker?)
        def-base  (base-power game def-forces false)
        att-roll  (* att-base att-mult (random-factor))
        def-roll  (* def-base def-mult (random-factor))
        att-wins? (> att-roll def-roll)
        max-roll  (max att-roll def-roll)
        margin    (if (zero? max-roll) 0.0 (/ (Math/abs (- att-roll def-roll)) max-roll))]
    {:att-wins? att-wins?
     :margin    margin
     :att-roll  att-roll
     :def-roll  def-roll}))

;;;;
;;;; Attack Resolution
;;;;

(defn- compute-losses
  "Apply a loss rate to each unit type in a force map, flooring at 0.

  [forces force-map, rate float] -> losses-map"
  [forces rate]
  {:soldiers   (max 0 (long (* (:soldiers   forces) rate)))
   :transports (max 0 (long (* (:transports forces) rate)))
   :generals   (max 0 (long (* (:generals   forces) rate)))
   :fighters   (max 0 (long (* (:fighters   forces) rate)))
   :carriers   (max 0 (long (* (:carriers   forces) rate)))
   :admirals   (max 0 (long (* (:admirals   forces) rate)))
   :cmd-ships  (max 0 (long (* (:cmd-ships  forces) rate)))
   :stations   (max 0 (long (* (:stations   forces) rate)))})

(defn- select-planets
  "Randomly select n planets from the defender's pool, returning a {:mil n :erg n :ore n} map of how
  many of each type are transferred.

  [defender player-map, n int] -> transfer-map"
  [defender n]
  (let [pool  (shuffle (concat (repeat (:player/mil-planets defender) :mil)
                               (repeat (:player/erg-planets defender) :erg)
                               (repeat (:player/ore-planets defender) :ore)))
        taken (frequencies (take n pool))]
    {:mil (get taken :mil 0)
     :erg (get taken :erg 0)
     :ore (get taken :ore 0)}))

(defn resolve-combat
  "Resolve a full two-phase combat engagement between attacker and defender.
  Space resolves first; the space margin carries over to buff the space winner's ground power.
  Ground determines the battle winner: captures, resources, and losses.
  The `mode` parameter (:raid or :invade) determines defense/reward multipliers.
  UUIDs stored as strings for safe pr-str round-trip.

  [game game-map, attacker player-map, defender player-map, mode keyword] -> result-map"
  [game attacker defender mode]
  (let [att-forces  (effective-forces attacker)
        def-forces  (effective-defending-forces defender)

        ;; Phase partitions
        att-space   (space-forces att-forces)
        def-space   (space-forces def-forces)
        att-ground  (ground-forces att-forces)
        def-ground  (ground-forces def-forces)

        ;; Space phase
        att-space-mult (space-multiplier game att-space)
        def-space-mult (space-multiplier game def-space)
        space-result   (resolve-phase game att-space def-space att-space-mult def-space-mult true)

        ;; Mode multipliers
        def-mult    (case mode
                      :raid   (:game/raid-defense-multiplier   game)
                      :invade (:game/invade-defense-multiplier game))

        ;; Carryover: space margin × space-carryover constant
        carryover      (* (:margin space-result) (:game/space-carryover game))

        ;; Ground multipliers with space carryover applied to the space winner.
        ;; Defense multiplier (raid=10%, invade=100%) scales the defender's ground engagement.
        att-ground-mult (ground-multiplier game att-ground)
        def-ground-mult (* (ground-multiplier game def-ground) def-mult)
        att-ground-mult (if (:att-wins? space-result)
                          (* att-ground-mult (+ 1.0 carryover))
                          att-ground-mult)
        def-ground-mult (if (:att-wins? space-result)
                          def-ground-mult
                          (* def-ground-mult (+ 1.0 carryover)))

        ;; Ground phase — determines the battle outcome
        ground-result  (resolve-phase game att-ground def-ground att-ground-mult def-ground-mult true)

        att-wins?      (:att-wins? ground-result)
        margin         (:margin ground-result)
        reward-mult (case mode
                      :raid   (:game/raid-reward-multiplier   game)
                      :invade (:game/invade-reward-multiplier game))
        planet-mult (case mode
                      :raid   (:game/raid-planet-capture-rate game)
                      :invade (:game/invade-reward-multiplier game))

        ;; Loss model
        loser-rate  (min margin 0.75)
        winner-rate (/ loser-rate 2.0)

        ;; Planet capture (driven by ground margin)
        def-total-planets (+ (:player/mil-planets  defender)
                             (:player/erg-planets  defender)
                             (:player/ore-planets  defender))
        planets-count       (if att-wins? (long (* loser-rate planet-mult def-total-planets)) 0)
        planets-transferred (select-planets defender planets-count)

        ;; Resource capture
        resources-mult     (if att-wins? (* loser-rate reward-mult) 0.0)
        credits-captured   (long (* resources-mult (:player/credits defender)))
        food-captured      (long (* resources-mult (:player/food    defender)))
        fuel-captured      (long (* resources-mult (:player/fuel    defender)))

        ;; Defender engagement scaling for losses
        def-engaged (update-vals def-forces #(long (* reward-mult %)))]

    ;;;;
    ;;;; TODO: Per-phase loss model
    ;;;; Currently losses are computed from the ground margin applied to the full force maps.
    ;;;; A future session will split losses so space units take losses from the space margin
    ;;;; and ground units from the ground margin.
    ;;;;

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
     ;; Top-level roll/margin keys map to ground phase for backward compatibility
     :attacker-roll   (:att-roll ground-result)
     :defender-roll   (:def-roll ground-result)
     :attacker-wins?  att-wins?
     :margin          margin
     :attacker-losses (compute-losses att-forces  (if att-wins? winner-rate loser-rate))
     :defender-losses (compute-losses def-engaged (if att-wins? loser-rate winner-rate))
     :planets-transferred planets-transferred
     :resources-captured  {:credits credits-captured
                           :food    food-captured
                           :fuel    fuel-captured}
     ;; Phase sub-results for Phase 2 display
     :space-result  space-result
     :ground-result ground-result}))

;;;;
;;;; Strike Resolution
;;;;

(defn resolve-strike
  "Resolve a standoff strike: the attacker dispatches up to strike-max-dispatch cmd-ships, each
  destroying strike-damage-rate of the defender's non-station military. Dispatched ships may be
  intercepted independently by the defender's stations. No planet capture or resource theft.
  UUIDs stored as strings for safe pr-str round-trip.
  [game game-map, attacker player-map, defender player-map] -> result-map"
  [game attacker defender]
  (let [damage-rate-per-ship (:game/strike-damage-rate       game)
        max-dispatch         (:game/strike-max-dispatch      game)
        interception-rate    (:game/strike-interception-rate game)
        interception-cap     (:game/strike-interception-cap  game)
        committed            (:player/cmd-ships attacker)
        dispatched           (min max-dispatch committed)
        intercept-chance     (min interception-cap (* (:player/stations defender) interception-rate))
        ships-lost           (count (filter #(< % intercept-chance)
                                            (repeatedly dispatched #(Math/random))))
        ships-through        (- dispatched ships-lost)
        damage-rate          (* ships-through damage-rate-per-ship)]
    {:mode                :strike
     :attacker-id         (str (:xt/id attacker))
     :attacker-name       (:player/empire-name attacker)
     :defender-id         (str (:xt/id defender))
     :defender-name       (:player/empire-name defender)
     :defender-stations   (:player/stations defender)
     :cmd-ships-committed committed
     :cmd-ships-dispatched dispatched
     :cmd-ships-lost      ships-lost
     :damage-rate         damage-rate
     :defender-losses     (if (zero? committed)
                            {:soldiers 0 :transports 0 :generals 0
                             :fighters 0 :carriers 0  :admirals 0 :stations 0}
                            {:soldiers   (long (* damage-rate (:player/soldiers   defender)))
                             :transports (long (* damage-rate (:player/transports defender)))
                             :generals   (long (* damage-rate (:player/generals   defender)))
                             :fighters   (long (* damage-rate (:player/fighters   defender)))
                             :carriers   (long (* damage-rate (:player/carriers   defender)))
                             :admirals   (long (* damage-rate (:player/admirals   defender)))
                             :stations   (long (* damage-rate (:player/stations   defender)))})}))


;;;;
;;;; Espionage Resolution
;;;;

(defn- espionage-roll
  "Compute attacker/defender agent rolls and determine the winner.
  Returns {:att-roll float, :def-roll float, :att-wins? bool, :agents-captured long|nil}.
  agents-captured is non-nil only on failure; it is the number of the attacker's agents taken.
  The optional def-mult scales only the defender's effective agent count (default 1.0).

  [attacker player-map, defender player-map] -> roll-map
  [attacker player-map, defender player-map, def-mult float] -> roll-map"
  ([attacker defender] (espionage-roll attacker defender 1.0))
  ([attacker defender def-mult]
   (let [att-roll  (* (:player/agents attacker) (random-factor))
         def-roll  (* (:player/agents defender) def-mult (random-factor))
         att-wins? (> att-roll def-roll)]
     {:att-roll        att-roll
      :def-roll        def-roll
      :att-wins?       att-wins?
      :agents-captured (when-not att-wins?
                         (min (:player/agents attacker)
                              (max const/espionage-defection-min
                                   (long (* (:player/agents attacker)
                                            const/espionage-defection-rate)))))})))

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
     :soldiers             (when att-wins? (long (* (:player/soldiers   defender) const/bomb-damage-rate)))
     :transports           (when att-wins? (long (* (:player/transports defender) const/bomb-damage-rate)))
     :fighters             (when att-wins? (long (* (:player/fighters   defender) const/bomb-damage-rate)))
     :carriers             (when att-wins? (long (* (:player/carriers   defender) const/bomb-damage-rate)))
     :agents-captured      agents-captured}))

(defn resolve-defect
  "Resolve a defection operation against the target empire's agent pool.
  Only 10% of the defender's agents defend, making this viable against agent-massing opponents.
  On success, a fraction of the defender's agents transfer to the attacker (capped).
  On failure, agents are captured. UUIDs stored as strings for safe pr-str round-trip.
  [game game-map, attacker player-map, defender player-map] -> result-map"
  [game attacker defender]
  (let [def-mult  (:game/defect-defense-multiplier game)
        rate      (:game/defect-transfer-rate      game)
        cap       (:game/defect-transfer-cap       game)
        {:keys [att-wins? agents-captured]} (espionage-roll attacker defender def-mult)]
    {:op               "defect"
     :attacker-id      (str (:xt/id attacker))
     :defender-id      (str (:xt/id defender))
     :defender-name    (:player/empire-name defender)
     :attacker-wins?   att-wins?
     :agents-captured  agents-captured
     :agents-defected  (when att-wins?
                         (min cap (long (* rate (:player/agents defender)))))}))
