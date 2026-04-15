(ns com.star-empire-elite.combat
  (:require [com.star-empire-elite.constants :as const]))

;;; Pure combat functions — no DB access, no side effects.

(defn effective-attacking-forces [player]
  {:soldiers   (min (:player/soldiers   player)
                    (* (:player/transports player) const/soldiers-per-transport)
                    (* (:player/generals  player) const/soldiers-per-general))
   :transports (:player/transports player)
   :generals   (:player/generals   player)
   :fighters   (min (:player/fighters player)
                    (* (:player/carriers player) const/fighters-per-carrier)
                    (* (:player/admirals player) const/fighters-per-admiral))
   :carriers   (:player/carriers   player)
   :admirals   (:player/admirals   player)
   :cmd-ships  (:player/cmd-ships  player)})

(defn effective-defending-forces [player]
  {:soldiers   (min (:player/soldiers player)
                    (* (:player/generals player) const/soldiers-per-general))
   :transports (:player/transports player)
   :generals   (:player/generals   player)
   :fighters   (min (:player/fighters player)
                    (* (:player/admirals player) const/fighters-per-admiral))
   :carriers   (:player/carriers   player)
   :admirals   (:player/admirals   player)
   :cmd-ships  (:player/cmd-ships  player)
   :stations   (:player/stations   player)})

(defn base-power [game forces attacker?]
  (+ (* (:soldiers  forces) (:game/soldier-power  game))
     (* (:fighters  forces) (:game/fighter-power  game))
     (* (:cmd-ships forces) (:game/cmd-ship-power game))
     (* (:generals  forces) (:game/general-power  game))
     (* (:admirals  forces) (:game/admiral-power  game))
     (if attacker? 0
       (* (:stations forces) (:game/station-power game)))))

(defn random-factor []
  (+ (- 1.0 const/combat-variance)
     (* (rand) (* 2 const/combat-variance))))

(defn- compute-losses [forces rate]
  {:soldiers-lost   (max 0 (long (* (:soldiers   forces) rate)))
   :transports-lost (max 0 (long (* (:transports forces) rate)))
   :generals-lost   (max 0 (long (* (:generals   forces) rate)))
   :fighters-lost   (max 0 (long (* (:fighters   forces) rate)))
   :carriers-lost   (max 0 (long (* (:carriers   forces) rate)))
   :admirals-lost   (max 0 (long (* (:admirals   forces) rate)))
   :cmd-ships-lost  (max 0 (long (* (:cmd-ships  forces) rate)))
   :stations-lost   (max 0 (long (* (or (:stations forces) 0) rate)))})

;;; Randomly select n planets from a pool built from the defender's planet counts.
;;; Returns a map {:mil n :food n :ore n} of how many of each type are transferred.
(defn- select-planets [defender n]
  (let [pool (shuffle (concat (repeat (:player/mil-planets  defender) :mil)
                              (repeat (:player/food-planets defender) :food)
                              (repeat (:player/ore-planets  defender) :ore)))
        taken (frequencies (take n pool))]
    {:mil  (get taken :mil  0)
     :food (get taken :food 0)
     :ore  (get taken :ore  0)}))

;;; Espionage resolution — agents vs agents with the same ±15% variance as combat.
;;; If attacker wins, returns a snapshot of the defender's military units as :intel.
(defn resolve-espionage [attacker defender]
  (let [att-roll (* (:player/agents attacker) (random-factor))
        def-roll (* (:player/agents defender)  (random-factor))
        att-wins? (> att-roll def-roll)]
    {:attacker-id   (str (:xt/id attacker))
     :defender-id   (str (:xt/id defender))
     :defender-name (:player/empire-name defender)
     :attacker-wins? att-wins?
     :intel (when att-wins?
              {:soldiers  (:player/soldiers  defender)
               :transports (:player/transports defender)
               :generals  (:player/generals  defender)
               :fighters  (:player/fighters  defender)
               :carriers  (:player/carriers  defender)
               :admirals  (:player/admirals  defender)
               :stations  (:player/stations  defender)
               :cmd-ships (:player/cmd-ships defender)
               :agents    (:player/agents    defender)})}))

;;; Returns the full battle result map. UUIDs stored as strings for safe pr-str round-trip.
(defn resolve-combat [game attacker defender]
  (let [att-forces (effective-attacking-forces attacker)
        def-forces (effective-defending-forces defender)
        att-power  (base-power game att-forces true)
        def-power  (base-power game def-forces false)
        att-roll   (* att-power (random-factor))
        def-roll   (* def-power (random-factor))
        att-wins?  (> att-roll def-roll)
        max-roll   (max att-roll def-roll)
        margin     (if (zero? max-roll) 0.0
                     (/ (Math/abs (- att-roll def-roll)) max-roll))
        loser-rate  (min margin 0.75)
        winner-rate (/ loser-rate 2.0)
        ;; planet transfer: only when attacker wins
        def-total-planets (+ (:player/mil-planets  defender)
                              (:player/food-planets defender)
                              (:player/ore-planets  defender))
        planets-count (if att-wins? (long (* margin def-total-planets)) 0)
        planets-transferred (select-planets defender planets-count)]
    {:attacker-id          (str (:xt/id attacker))
     :attacker-name        (:player/empire-name attacker)
     :defender-id          (str (:xt/id defender))
     :defender-name        (:player/empire-name defender)
     :attacker-counts      {:soldiers   (:player/soldiers   attacker)
                            :transports (:player/transports attacker)
                            :generals   (:player/generals   attacker)
                            :fighters   (:player/fighters   attacker)
                            :carriers   (:player/carriers   attacker)
                            :admirals   (:player/admirals   attacker)
                            :cmd-ships  (:player/cmd-ships  attacker)}
     :defender-counts      {:soldiers   (:player/soldiers   defender)
                            :transports (:player/transports defender)
                            :generals   (:player/generals   defender)
                            :fighters   (:player/fighters   defender)
                            :carriers   (:player/carriers   defender)
                            :admirals   (:player/admirals   defender)
                            :cmd-ships  (:player/cmd-ships  defender)
                            :stations   (:player/stations   defender)}
     :attacker-forces      att-forces
     :defender-forces      def-forces
     :attacker-roll        att-roll
     :defender-roll        def-roll
     :attacker-wins?       att-wins?
     :margin               margin
     :attacker-losses      (compute-losses att-forces (if att-wins? winner-rate loser-rate))
     :defender-losses      (compute-losses def-forces (if att-wins? loser-rate winner-rate))
     :planets-transferred  planets-transferred}))
