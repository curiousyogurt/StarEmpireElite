(ns com.star-empire-elite.combat
  (:require [com.star-empire-elite.constants :as const]))

;;; Pure combat functions — no DB access, no side effects.

(defn effective-attacking-forces [player]
  {:soldiers  (min (:player/soldiers player)
                   (* (:player/transports player) const/soldiers-per-transport)
                   (* (:player/generals player) const/soldiers-per-general))
   :fighters  (min (:player/fighters player)
                   (* (:player/carriers player) const/fighters-per-carrier)
                   (* (:player/admirals player) const/fighters-per-admiral))
   :cmd-ships (:player/cmd-ships player)
   :generals  (:player/generals player)
   :admirals  (:player/admirals player)})

(defn effective-defending-forces [player]
  {:soldiers  (min (:player/soldiers player)
                   (* (:player/generals player) const/soldiers-per-general))
   :fighters  (min (:player/fighters player)
                   (* (:player/admirals player) const/fighters-per-admiral))
   :cmd-ships (:player/cmd-ships player)
   :generals  (:player/generals player)
   :admirals  (:player/admirals player)
   :stations  (:player/stations player)})

(defn base-power [game forces attacker?]
  (+ (* (:soldiers  forces) (get game :game/soldier-power  const/soldier-power))
     (* (:fighters  forces) (get game :game/fighter-power  const/fighter-power))
     (* (:cmd-ships forces) (get game :game/cmd-ship-power const/cmd-ship-power))
     (* (:generals  forces) (get game :game/general-power  const/general-power))
     (* (:admirals  forces) (get game :game/admiral-power  const/admiral-power))
     (if attacker? 0
       (* (or (:stations forces) 0)
          (get game :game/station-power const/station-power)))))

(defn- random-factor []
  (+ (- 1.0 const/combat-variance)
     (* (rand) (* 2 const/combat-variance))))

(defn- compute-losses [forces rate]
  {:soldiers-lost  (max 0 (long (* (:soldiers  forces) rate)))
   :fighters-lost  (max 0 (long (* (:fighters  forces) rate)))
   :cmd-ships-lost (max 0 (long (* (:cmd-ships forces) rate)))
   :generals-lost  (max 0 (long (* (:generals  forces) rate)))
   :admirals-lost  (max 0 (long (* (:admirals  forces) rate)))
   :stations-lost  (max 0 (long (* (or (:stations forces) 0) rate)))})

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
     :defender-id          (str (:xt/id defender))
     :defender-name        (:player/empire-name defender)
     :attacker-forces      att-forces
     :defender-forces      def-forces
     :attacker-roll        att-roll
     :defender-roll        def-roll
     :attacker-wins?       att-wins?
     :margin               margin
     :attacker-losses      (compute-losses att-forces (if att-wins? winner-rate loser-rate))
     :defender-losses      (compute-losses def-forces (if att-wins? loser-rate winner-rate))
     :planets-transferred  planets-transferred}))
