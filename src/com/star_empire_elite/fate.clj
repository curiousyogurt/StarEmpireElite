;;;;;
;;;;; Fate - Disaster and Boon Event Resolution
;;;;;
;;;;; Pure functions and catalog data for the fate system. Once per outcomes phase, a player
;;;;; may be struck by a disaster or granted a boon — a one-time stock delta applied to their
;;;;; resources. Fate resolves at outcomes, alongside breakaway and population growth.
;;;;;
;;;;; No DB, HTTP, or rendering concerns live here. All functions take plain maps and return
;;;;; plain maps. The catalog lives here, next to the logic that reads it, and stays fixed
;;;;; across games (type/label/targets/basis are structure, not balance). Its baked-in :lo/:hi
;;;;; are just the constants.clj defaults, though — roll-fate overlays each event's per-game
;;;;; :game/fate-<type>-lo/-hi (and :game/fate-uncollected-stability-penalty) on top when a
;;;;; game has customized them, via entry-lo-hi.
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
    :basis    :production
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
   {:type     :reactor-breach
    :label    "Reactor Breach"
    :polarity :disaster
    :targets  [:player/fuel]
    :basis    :production
    :lo       const/fate-reactor-breach-lo
    :hi       const/fate-reactor-breach-hi}
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
   {:type     :lottery-win
    :label    "Lottery Win"
    :polarity :boon
    :targets  [:player/credits]
    :basis    :production
    :lo       const/fate-lottery-win-lo
    :hi       const/fate-lottery-win-hi}
   {:type     :migration-wave
    :label    "Migration Wave"
    :polarity :boon
    :targets  [:player/population]
    :basis    :holdings
    :lo       const/fate-migration-wave-lo
    :hi       const/fate-migration-wave-hi}
   {:type     :reactor-surge
    :label    "Reactor Surge"
    :polarity :boon
    :targets  [:player/fuel]
    :basis    :production
    :lo       const/fate-reactor-surge-lo
    :hi       const/fate-reactor-surge-hi}])

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
  in income/calculate-income. Only :player/food, :player/credits, and :player/fuel have
  production bases.

  [target-key keyword, player player-map, game game-map] -> number"
  [target-key player game]
  (case target-key
    :player/food
    (* (:player/erg-planets player 0) (:game/erg-planet-food game 0))

    :player/credits
    (+ (* (:player/ore-planets player 0) (:game/ore-planet-credits game 0))
       (* (:player/population  player 0) (:game/population-tax-credits game 0)))

    :player/fuel
    (* (:player/erg-planets player 0) (:game/erg-planet-fuel game 0))

    ;; Fallback: unreachable for valid catalog entries, but safe.
    0))

(defn- entry-lo-hi
  "Resolve an entry's [lo hi] multiplier bounds. Prefers the game's per-game override
  (:game/fate-<type>-lo / -hi, e.g. :game/fate-drought-lo) and falls back to the catalog
  entry's own :lo/:hi (themselves sourced from constants.clj) for games that predate these
  fields being snapshotted. The catalog stays structural — only the magnitudes are overlaid.

  [game game-map, entry catalog-entry] -> [lo double, hi double]"
  [game entry]
  (let [type-name (name (:type entry))
        lo-key    (keyword "game" (str "fate-" type-name "-lo"))
        hi-key    (keyword "game" (str "fate-" type-name "-hi"))]
    [(lo-key game (:lo entry))
     (hi-key game (:hi entry))]))

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

;;; Credits, food, and fuel are the resources a player can spend down to ~0 before Outcomes
;;; resolves (via Expenses/Exchange/Building), which is exactly what entry-delta's holding
;;; cap above would otherwise let them dodge a disaster with. For those keys only, any
;;; portion of the disaster that couldn't be collected (because the holding was too low)
;;; spills into a stability penalty instead of simply being forgiven.
(def ^:private spillable-keys #{:player/credits :player/food :player/fuel})

(defn- entry-stability-spill
  "Compute the stability penalty for the portion of a credit/food/fuel disaster that couldn't be
  collected because the player had already spent the resource down.
  Zero for boons, for non-spillable targets, and when the disaster is fully collectable.
  Scales linearly from 0 (fully paid) to fate-uncollected-stability-penalty (fully dodged).

  [game game-map, polarity keyword, target-key keyword, basis number, mult double, holding number] -> long"
  [game polarity target-key basis mult holding]
  (if (or (not= polarity :disaster)
          (not (spillable-keys target-key))
          (<= basis 0))
    0
    (let [raw       (Math/round ^double (* mult (double basis)))
          collected (min raw (long holding))
          shortfall (- raw collected)]
      (if (or (zero? shortfall) (zero? raw))
        0
        (Math/round ^double (* (/ (double shortfall) (double raw))
                                (:game/fate-uncollected-stability-penalty
                                 game const/fate-uncollected-stability-penalty)))))))

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
    7. Compute a signed delta per target key (holdings- or production-based), plus any
       stability spill for credit/food/fuel targets the player couldn't fully pay.
    8. Return {:type :polarity :label :effect :stability-spill}.

  [player player-map, game game-map, active [player-map], rng fn] -> result-map | nil"
  [player game active rng]
  ;; Step 1 — gate: event occurs with probability fate-probability.
  (when (< (rng) (:game/fate-probability game const/fate-probability))
    (let [;; Step 2 — where does this player rank among active empires?
          r          (rank-fraction player active)
          ;; Step 3 — leaders draw disasters more; trailers draw boons more.
          p-disaster (clamp01 (+ 0.5 (* (- r 0.5) (:game/fate-rank-tilt game const/fate-rank-tilt))))
          ;; Step 4 — polarity.
          polarity   (if (< (rng) p-disaster) :disaster :boon)
          ;; Step 5 — pick uniformly from matching catalog entries.
          entries    (filterv #(= (:polarity %) polarity) fate-catalog)
          entry      (nth entries (int (* (rng) (count entries))))
          ;; Step 6 — multiplier drawn uniformly from [lo, hi] (per-game override, if set).
          [lo hi]    (entry-lo-hi game entry)
          mult       (+ lo (* (rng) (- hi lo)))
          ;; Step 7 — compute one signed delta (and any stability spill) per target key.
          bases      (into {}
                       (for [k (:targets entry)]
                         [k (if (= (:basis entry) :production)
                              (production-basis k player game)
                              (get player k 0))]))
          effect     (into {}
                       (for [k (:targets entry)]
                         [k (entry-delta polarity (get bases k) mult (get player k 0))]))
          spill      (reduce + 0
                       (for [k (:targets entry)]
                         (entry-stability-spill game polarity k (get bases k) mult (get player k 0))))]
      ;; Step 8 — assemble result map.
      {:type            (:type entry)
       :polarity        polarity
       :label           (:label entry)
       :effect          effect
       :stability-spill spill})))
