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

(defn- phase-losses
  "Apply a loss rate to each unit type in a phase force map, flooring at 0.
  Works with any subset of unit keys (space or ground phase).

  [forces force-map, rate double] -> losses-map"
  [forces rate]
  (into {} (map (fn [[k v]] [k (max 0 (long (* v rate)))]) forces)))

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
  Ground determines the battle winner, captures, and losses.

  Loss model (per phase): close fights are bloody for both sides; blowouts are bloody
  only for the loser. The loser's rate rises with margin; the winner's rate falls.
  Space units take losses from the space margin; ground units from the ground margin.

  The engagement multiplier (raid=0.1, invade=1.0) scales the defender's power
  participation AND loss exposure in both phases.

  UUIDs stored as strings for safe pr-str round-trip.

  [game game-map, attacker player-map, defender player-map, mode keyword] -> result-map"
  [game attacker defender mode]
  (let [att-forces  (effective-forces attacker)
        def-forces  (effective-defending-forces defender)

        ;; Engagement fraction: governs defender power participation and loss exposure
        engagement  (case mode
                      :raid   (:game/raid-defense-multiplier   game)
                      :invade (:game/invade-defense-multiplier game))

        ;; Phase partitions
        att-space   (space-forces att-forces)
        def-space   (space-forces def-forces)
        att-ground  (ground-forces att-forces)
        def-ground  (ground-forces def-forces)

        ;; Space phase — defender power scaled by engagement
        att-space-mult (space-multiplier game att-space)
        def-space-mult (* (space-multiplier game def-space) engagement)
        space-result   (resolve-phase game att-space def-space att-space-mult def-space-mult true)

        ;; Carryover: space margin × space-carryover constant
        carryover      (* (:margin space-result) (:game/space-carryover game))

        ;; Ground multipliers with engagement and space carryover
        att-ground-mult (ground-multiplier game att-ground)
        def-ground-mult (* (ground-multiplier game def-ground) engagement)
        att-ground-mult (if (:att-wins? space-result)
                          (* att-ground-mult (+ 1.0 carryover))
                          att-ground-mult)
        def-ground-mult (if (:att-wins? space-result)
                          def-ground-mult
                          (* def-ground-mult (+ 1.0 carryover)))

        ;; Ground phase — determines the battle outcome
        ground-result  (resolve-phase game att-ground def-ground att-ground-mult def-ground-mult true)

        att-wins?      (:att-wins? ground-result)
        space-margin   (:margin space-result)
        ground-margin  (:margin ground-result)

        ;; Loss curves
        loss-floor  (or (:game/combat-loss-floor game) const/combat-loss-floor)
        loser-cap   (or (:game/combat-loser-cap  game) const/combat-loser-cap)
        winner-max  (or (:game/combat-winner-max game) const/combat-winner-max)
        loser-rate  (fn [m] (min loser-cap (+ loss-floor (* (- loser-cap loss-floor) m))))
        winner-rate (fn [m] (* winner-max (- 1.0 m)))

        ;; Space-phase losses
        space-att-wins?    (:att-wins? space-result)
        att-space-rate     (if space-att-wins? (winner-rate space-margin) (loser-rate space-margin))
        def-space-rate     (if space-att-wins? (loser-rate space-margin) (winner-rate space-margin))
        def-space-engaged  (update-vals def-space #(long (* engagement %)))
        att-space-losses   (phase-losses att-space att-space-rate)
        def-space-losses   (phase-losses def-space-engaged def-space-rate)

        ;; Ground-phase losses (agents excluded — they don't take combat losses)
        att-ground-combat  (dissoc att-ground :agents)
        def-ground-combat  (dissoc def-ground :agents)
        att-ground-rate    (if att-wins? (winner-rate ground-margin) (loser-rate ground-margin))
        def-ground-rate    (if att-wins? (loser-rate ground-margin) (winner-rate ground-margin))
        def-ground-engaged (update-vals def-ground-combat #(long (* engagement %)))
        att-ground-losses  (phase-losses att-ground-combat att-ground-rate)
        def-ground-losses  (phase-losses def-ground-engaged def-ground-rate)

        ;; Merged losses across phases
        attacker-losses (merge att-space-losses att-ground-losses)
        defender-losses (merge def-space-losses def-ground-losses)

        ;; Capture model — driven off ground margin with per-mode caps
        planet-cap   (case mode
                       :raid   (or (:game/raid-planet-capture-cap   game) const/raid-planet-capture-cap)
                       :invade (or (:game/invade-planet-capture-cap game) const/invade-planet-capture-cap))
        resource-cap (case mode
                       :raid   (or (:game/raid-resource-capture-cap   game) const/raid-resource-capture-cap)
                       :invade (or (:game/invade-resource-capture-cap game) const/invade-resource-capture-cap))

        def-total-planets (+ (:player/mil-planets  defender)
                             (:player/erg-planets  defender)
                             (:player/ore-planets  defender))
        planets-count       (if att-wins? (long (* planet-cap ground-margin def-total-planets)) 0)
        planets-transferred (select-planets defender planets-count)

        resources-mult     (if att-wins? (* resource-cap ground-margin) 0.0)
        credits-captured   (long (* resources-mult (:player/credits defender)))
        food-captured      (long (* resources-mult (:player/food    defender)))
        fuel-captured      (long (* resources-mult (:player/fuel    defender)))

        ;; Acquisition penalty: annexing territory costs stability
        captured-total     (+ (:mil planets-transferred) (:erg planets-transferred) (:ore planets-transferred))
        penalty-per-planet (or (:game/capture-stability-penalty-per-planet game) const/capture-stability-penalty-per-planet)
        penalty-cap        (or (:game/capture-stability-penalty-cap game) const/capture-stability-penalty-cap)
        capture-penalty    (min penalty-cap (* penalty-per-planet captured-total))]

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
     :margin          ground-margin
     :attacker-losses attacker-losses
     :defender-losses defender-losses
     :planets-transferred planets-transferred
     :resources-captured  {:credits credits-captured
                           :food    food-captured
                           :fuel    fuel-captured}
     ;; Acquisition penalty (stability cost to attacker for captured planets)
     :attacker-stability-penalty capture-penalty
     ;; Phase sub-results for display
     :space-result    space-result
     :space-carryover carryover
     :ground-result   ground-result}))

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

(def ^:private op-failure-rates
  {:spy    const/spy-defection-rate
   :incite const/incite-defection-rate
   :bomb   const/bomb-defection-rate
   :defect const/defect-defection-rate})

(defn- effective-defense-agents
  "Apply the diminishing-returns soft cap to a defender's engaged agent count.
  Agents defend at face value up to the threshold T; surplus beyond T is raised to
  an exponent < 1, so each additional agent contributes less than the last.

  effective = agents                        when agents ≤ T
  effective = T + (agents − T)^exp          when agents > T

  [agents number] -> double"
  [agents]
  (let [t   (double const/espionage-defense-threshold)
        exp const/espionage-defense-exponent]
    (if (<= agents t)
      (double agents)
      (+ t (Math/pow (- agents t) exp)))))

(defn- espionage-roll
  "Compute attacker/defender agent rolls and determine the winner.
  Returns {:att-roll float, :def-roll float, :att-wins? bool,
           :agents-lost long|nil, :agents-captured long|nil}.
  Both loss keys are nil on success. On failure:
    :agents-lost    — agents the attacker loses (always present, all ops).
    :agents-captured — agents the defender gains (= agents-lost for transfer ops;
                       0 for bomb, whose failed agents are destroyed, not captured).
  The optional def-mult scales the defender's agent count before the soft cap is applied.

  [attacker player-map, defender player-map, op keyword] -> roll-map
  [attacker player-map, defender player-map, op keyword, def-mult float] -> roll-map"
  ([attacker defender op] (espionage-roll attacker defender op 1.0))
  ([attacker defender op def-mult]
   (let [att-agents (:player/agents attacker)
         def-engaged (effective-defense-agents (* (:player/agents defender) def-mult))
         att-roll    (* att-agents (random-factor))
         def-roll    (* def-engaged (random-factor))
         att-wins?   (> att-roll def-roll)
         rate        (get op-failure-rates op const/spy-defection-rate)
         transfer?   (not= op :bomb)]
     {:att-roll        att-roll
      :def-roll        def-roll
      :att-wins?       att-wins?
      :agents-lost     (when-not att-wins?
                         (min att-agents
                              (max const/espionage-defection-min
                                   (long (* att-agents rate)))))
      :agents-captured (when-not att-wins?
                         (if transfer?
                           (min att-agents
                                (max const/espionage-defection-min
                                     (long (* att-agents rate))))
                           0))})))

(defn resolve-espionage
  "Resolve an espionage attempt. Agent counts are compared with ±variance random rolls. Returns a
  result map including intel snapshot on success. UUIDs stored as strings for safe pr-str round-trip.

  [attacker player-map, defender player-map] -> result-map"
  [attacker defender]
  (let [{:keys [att-wins? agents-lost agents-captured]} (espionage-roll attacker defender :spy)]
    {:op             "spy"
     :attacker-id    (str (:xt/id attacker))
     :defender-id    (str (:xt/id defender))
     :defender-name  (:player/empire-name defender)
     :attacker-wins? att-wins?
     :agents-lost    agents-lost
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

(defn- incite-damage
  "Compute the stability damage a successful incite inflicts on a target.
  Two mechanisms limit the damage:
    1. Diminishing returns — damage scales with (stability / 100), so a healthy empire
       takes the full base hit while an already-shaky one barely moves.
    2. Hard floor — incite alone cannot push stability below incite-stability-floor (70).
       This keeps incited victims out of the breakaway-danger zone (~80%/turn planet loss
       at stability 0) and turns incite into sustained pressure rather than a kill.

  [target-stability number] -> long"
  [target-stability]
  (let [raw  (* const/incite-stability-damage (/ (double target-stability) 100.0))
        room (max 0 (- target-stability const/incite-stability-floor))]
    (long (Math/round (double (min raw room))))))

(defn resolve-incite
  "Resolve an incite operation. Agent counts are compared with ±variance random rolls. On success,
  the defender's stability is damaged (diminishing with current stability, floored at 70).
  On failure, agents are captured. UUIDs stored as strings for safe pr-str round-trip.

  [attacker player-map, defender player-map] -> result-map"
  [attacker defender]
  (let [{:keys [att-wins? agents-lost agents-captured]} (espionage-roll attacker defender :incite)]
    {:op               "incite"
     :attacker-id      (str (:xt/id attacker))
     :defender-id      (str (:xt/id defender))
     :defender-name    (:player/empire-name defender)
     :attacker-wins?   att-wins?
     :stability-damage (when att-wins? (incite-damage (:player/stability defender)))
     :agents-lost      agents-lost
     :agents-captured  agents-captured}))

(defn resolve-bomb
  "Resolve a bombing operation against the target empire's ground and space forces.
  On success, a fixed percentage of soldiers, transports, fighters, and carriers are destroyed.
  On failure, agents are captured. UUIDs stored as strings for safe pr-str round-trip.

  [attacker player-map, defender player-map] -> result-map"
  [attacker defender]
  (let [{:keys [att-wins? agents-lost agents-captured]} (espionage-roll attacker defender :bomb)]
    {:op                   "bomb"
     :attacker-id          (str (:xt/id attacker))
     :defender-id          (str (:xt/id defender))
     :defender-name        (:player/empire-name defender)
     :attacker-wins?       att-wins?
     :soldiers             (when att-wins? (long (* (:player/soldiers   defender) const/bomb-damage-rate)))
     :transports           (when att-wins? (long (* (:player/transports defender) const/bomb-damage-rate)))
     :fighters             (when att-wins? (long (* (:player/fighters   defender) const/bomb-damage-rate)))
     :carriers             (when att-wins? (long (* (:player/carriers   defender) const/bomb-damage-rate)))
     :agents-lost          agents-lost
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
        {:keys [att-wins? agents-lost agents-captured]} (espionage-roll attacker defender :defect def-mult)]
    {:op               "defect"
     :attacker-id      (str (:xt/id attacker))
     :defender-id      (str (:xt/id defender))
     :defender-name    (:player/empire-name defender)
     :attacker-wins?   att-wins?
     :agents-lost      agents-lost
     :agents-captured  agents-captured
     :agents-defected  (when att-wins?
                         (min cap (long (* rate (:player/agents defender)))))}))
