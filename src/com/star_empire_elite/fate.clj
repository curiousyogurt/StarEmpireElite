;;;;;
;;;;; Fate - Disaster and Boon Event Resolution
;;;;;
;;;;; Pure functions and catalog data for the fate system. Once per outcomes phase, a player
;;;;; may be struck by a disaster or granted a boon — a one-time stock delta applied to their
;;;;; resources. Fate resolves at outcomes, alongside breakaway and population growth.
;;;;;
;;;;; No DB, HTTP, or rendering concerns live here. All functions take plain maps and return
;;;;; plain maps. The catalog lives here, next to the logic that reads it; it is not seeded
;;;;; onto the game entity (fate entries are structure, not per-game balance numbers).
;;;;;
;;;;; Public API: fate-catalog, clamp01, rank-fraction, roll-fate
;;;;;

(ns com.star-empire-elite.fate
  (:require [com.star-empire-elite.constants :as const]))

;;;;
;;;; Catalog
;;;;

;;; Each entry describes one possible fate event.
;;; :targets  - vector of fully-qualified :player/* keys; one delta is computed per key.
;;; :basis    - :holdings uses the player's current stock; :production uses one turn's output.
;;; :lo / :hi - the multiplier is drawn uniformly from [lo, hi].

(def fate-catalog
  "The eight fate events. Four disasters remove resources; four boons add them."
  [{:type     :drought
    :label    "Drought"
    :polarity :disaster
    :targets  [:player/food]
    :basis    :production
    :lo       const/fate-drought-lo
    :hi       const/fate-drought-hi}
   {:type     :pirate-raid
    :label    "Pirate Raid"
    :polarity :disaster
    :targets  [:player/credits]
    :basis    :holdings
    :lo       const/fate-pirate-raid-lo
    :hi       const/fate-pirate-raid-hi}
   {:type     :solar-flare
    :label    "Solar Flare"
    :polarity :disaster
    :targets  [:player/soldiers :player/transports :player/fighters :player/carriers]
    :basis    :holdings
    :lo       const/fate-solar-flare-lo
    :hi       const/fate-solar-flare-hi}
   {:type     :plague
    :label    "Plague"
    :polarity :disaster
    :targets  [:player/population]
    :basis    :holdings
    :lo       const/fate-plague-lo
    :hi       const/fate-plague-hi}
   {:type     :bumper-harvest
    :label    "Bumper Harvest"
    :polarity :boon
    :targets  [:player/food]
    :basis    :production
    :lo       const/fate-bumper-harvest-lo
    :hi       const/fate-bumper-harvest-hi}
   {:type     :gold-rush
    :label    "Gold Rush"
    :polarity :boon
    :targets  [:player/credits]
    :basis    :production
    :lo       const/fate-gold-rush-lo
    :hi       const/fate-gold-rush-hi}
   {:type     :foreign-aid
    :label    "Foreign Aid"
    :polarity :boon
    :targets  [:player/credits]
    :basis    :production
    :lo       const/fate-foreign-aid-lo
    :hi       const/fate-foreign-aid-hi}
   {:type     :migration-wave
    :label    "Migration Wave"
    :polarity :boon
    :targets  [:player/population]
    :basis    :holdings
    :lo       const/fate-migration-wave-lo
    :hi       const/fate-migration-wave-hi}])

;;;;
;;;; Pure Helpers
;;;;

(defn clamp01
  "Clamp x to [0.0, 1.0].

  [x double] -> double"
  [x]
  (min 1.0 (max 0.0 x)))

(defn rank-fraction
  "Compute r in [0.0, 1.0], where 0.0 = last place and 1.0 = first place.

  r = (players with score strictly below mine) / (max 1 (dec active-count)).
  A solo player — the only active empire in the game — returns 0.5 so they draw
  disasters and boons with equal probability regardless of their absolute score.

  [player player-map, active [player-map]] -> double"
  [player active]
  (if (= 1 (count active))
    0.5
    (let [my-score (:player/score player 0)
          below-me (count (filter #(< (:player/score % 0) my-score) active))
          denom    (max 1 (dec (count active)))]
      (/ (double below-me) denom))))

(defn- production-basis
  "Compute one turn's production for the given resource key, mirroring the arithmetic
  in income/calculate-income. Only :player/food and :player/credits have production bases.

  [target-key keyword, player player-map, game game-map] -> number"
  [target-key player game]
  (case target-key
    :player/food
    (* (:player/erg-planets player 0) (:game/erg-planet-food game 0))

    :player/credits
    (+ (* (:player/ore-planets player 0) (:game/ore-planet-credits game 0))
       (* (:player/population  player 0) (:game/population-tax-credits game 0)))

    ;; Fallback: unreachable for valid catalog entries, but safe.
    0))

(defn- entry-delta
  "Compute the signed delta for one target key.
  disaster: negative, capped so the player cannot go below zero.
  boon:     positive (Math/round returns a long, compatible with :int schema fields).

  [polarity keyword, basis number, mult double, holding number] -> long"
  [polarity basis mult holding]
  (let [raw (Math/round ^double (* mult (double basis)))]
    (case polarity
      :disaster (- (min raw (long holding)))
      :boon     (+ raw))))

;;;;
;;;; Main Roll
;;;;

(defn roll-fate
  "Roll for a fate event this turn. Returns a result map or nil (no event this turn).

  rng is a 0-arg function returning a double in [0, 1). Pass rand at the call site;
  inject a deterministic stub in tests for reproducible results.

  Steps:
    1. Gate check — skip (return nil) unless draw < fate-probability.
    2. Compute rank fraction r among all active (non-eliminated) players in the game.
    3. Derive p-disaster: biased so leaders (r near 1) draw disasters more often.
    4. Draw polarity: :disaster if draw < p-disaster, else :boon.
    5. Pick a catalog entry uniformly from entries of that polarity.
    6. Draw a multiplier uniformly from [lo, hi].
    7. Compute a signed delta per target key (holdings- or production-based).
    8. Return {:type :polarity :label :effect}.

  [player player-map, game game-map, active [player-map], rng fn] -> result-map | nil"
  [player game active rng]
  ;; Step 1 — gate: event occurs with probability fate-probability.
  (when (< (rng) (:game/fate-probability game 0.15))
    (let [;; Step 2 — where does this player rank among active empires?
          r          (rank-fraction player active)
          ;; Step 3 — leaders draw disasters more; trailers draw boons more.
          p-disaster (clamp01 (+ 0.5 (* (- r 0.5) (:game/fate-rank-tilt game 0.4))))
          ;; Step 4 — polarity.
          polarity   (if (< (rng) p-disaster) :disaster :boon)
          ;; Step 5 — pick uniformly from matching catalog entries.
          entries    (filterv #(= (:polarity %) polarity) fate-catalog)
          entry      (nth entries (int (* (rng) (count entries))))
          ;; Step 6 — multiplier drawn uniformly from [lo, hi].
          mult       (+ (:lo entry) (* (rng) (- (:hi entry) (:lo entry))))
          ;; Step 7 — compute one signed delta per target key.
          effect     (into {}
                       (for [k (:targets entry)]
                         (let [basis (if (= (:basis entry) :production)
                                       (production-basis k player game)
                                       (get player k 0))
                               delta (entry-delta polarity basis mult (get player k 0))]
                           [k delta])))]
      ;; Step 8 — assemble result map.
      {:type     (:type entry)
       :polarity polarity
       :label    (:label entry)
       :effect   effect})))
