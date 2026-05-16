;;;;;
;;;;; Constants - Game-Wide Configuration Values
;;;;;
;;;;; All tuneable values for the game live here. Game entities are seeded from these constants
;;;;; at creation time so values are snapshotted per-game. Player-facing balance (costs, rates,
;;;;; combat power) can be adjusted here without touching logic code.
;;;;;

(ns com.star-empire-elite.constants)

;;;;
;;;; Access Control
;;;;

(def admin-emails #{"darcyotto@bennington.edu"})

(def allowed-email-domain "bennington.edu")
(def whitelisted-emails   #{"darcyotto@gmail.com" "danher@unc.edu"})

;;;;
;;;; Logging
;;;;

(def enable-game-logging false)

;;;;
;;;; Turn Structure
;;;;

(def turns-per-round      6)
(def rounds-per-day       2)
(def hours-between-rounds 2)

;;;;
;;;; Combat Power
;;;; Power contribution of each unit type per unit.

(def soldier-power   1)
(def fighter-power   3)
(def cmd-ship-power  20)
(def station-power   5)  ; defender only
(def general-power   5)
(def admiral-power   10)
(def combat-variance 0.15) ; ±15% random factor

;;;;
;;;; Command / Transport Capacity
;;;;

(def soldiers-per-general   1000)
(def soldiers-per-transport 100)
(def fighters-per-admiral   1000)
(def fighters-per-carrier   100)

;;;;
;;;; Population Growth
;;;; Applied at the end of each round in outcomes.

(def pop-growth-rate         0.02)  ; 2% of current population per round
(def pop-growth-per-planet   0.02)  ; +20,000 per planet per round (in millions)
(def pop-capacity-per-planet 10.0)  ; max 10 million population per planet
(def pop-random-min          0.9)
(def pop-random-max          1.1)

;;;;
;;;; Starting Empire Defaults
;;;; Resources and units a new player begins with.

(def starting-credits  70000)
(def starting-food     5000)
(def starting-fuel     5000)
(def starting-galaxars 0)

(def starting-mil-planets 0)
(def starting-erg-planets 1)
(def starting-ore-planets 1)

(def starting-population 6)    ; stored in millions
(def starting-stability  100)  ; stored as %

(def starting-soldiers   100)
(def starting-transports 1)
(def starting-generals   1)
(def starting-fighters   0)
(def starting-carriers   1)
(def starting-admirals   1)
(def starting-stations   0)
(def starting-cmd-ships  0)
(def starting-agents     2)
(def starting-advisors   2)

;;;;
;;;; Income Generation
;;;; Resources generated per planet type per turn.

(def ore-planet-credits 10000)
(def erg-planet-food    3000)
(def erg-planet-fuel    2000)

;; Military planets provide modest ongoing military support.
;; They should not out-compete ore planets as a source of sell value.
(def mil-planet-soldiers 5)
(def mil-planet-fighters 2)
(def mil-planet-stations 1)

;;;;
;;;; Industrial Synergy
;;;; Bonus output for paired ore/erg planets. The first min(ore, erg) planets
;;;; on each side contribute a Treasury credit and fuel bonus, representing
;;;; efficiency gains from coordinated industrial and energy operations.

(def synergy-credits-per-paired 1000)
(def synergy-fuel-per-paired     200)

;;;;
;;;; Population Tax
;;;; Population is stored in millions, so these are per-population-unit.

(def population-tax-credits  2000)  ; credits per million population per round

(def population-upkeep-food  250)
(def population-upkeep-fuel  10)

;;;;
;;;; Stability
;;;; Breakaway: if roll (1–100) > stability + threshold, planets break away.
;;;; Recovery:  if roll < max(stability, floor), stability recovers.

(def stability-breakaway-threshold 20)
(def stability-breakaway-cap       25)  ; stored as %, i.e. 25 = 25%
(def stability-recovery-amount      5)  ; flat stability points gained on recovery
(def stability-recovery-floor      50)  ; minimum effective stability for recovery rolls

(def expense-stability-penalty 3)  ; stability points lost per underpaid expense fraction

;;;;
;;;; Player Status
;;;;

(def player-status-active    0)
(def player-status-eliminated 1)

;;;;
;;;; Upkeep / Expenses
;;;; Cost to maintain units and infrastructure per round.

(def planet-upkeep-credits  2500)
(def planet-upkeep-food     100)

(def soldier-upkeep-credits 25)
(def soldier-upkeep-food    10)

(def fighter-upkeep-credits 100)
(def fighter-upkeep-fuel    10)

(def station-upkeep-credits 100)
(def station-upkeep-fuel    10)

(def agent-upkeep-food 10)
(def agent-upkeep-fuel 10)

;;;;
;;;; Building Costs
;;;; Cost to purchase new units, ships, and planets.

(def soldier-cost    1000)
(def transport-cost  5000)
(def general-cost    11000)

(def fighter-cost    1500)
(def carrier-cost    15000)
(def admiral-cost    15000)

(def station-cost    2000)
(def cmd-ship-cost   60000)

(def mil-planet-cost 17000)
(def erg-planet-cost 22000)
(def ore-planet-cost 22000)

(def agent-cost    5000)
(def advisor-cost  15000)

;;;;
;;;; Espionage
;;;; Agent defection on a failed covert mission.

(def espionage-defection-rate 0.10)  ; fraction of attacker's agents captured on any failed mission
(def espionage-defection-min  1)     ; minimum agents captured on any failed mission

(def incite-stability-damage  10)    ; stability points lost by the target on a successful incite
(def bomb-damage-rate         0.10)  ; fraction of soldiers/transports/fighters/carriers destroyed on a successful bomb

;;;;
;;;; Exchange Rates
;;;; Rates for buying and selling assets in the exchange.

(def soldier-sell     250)
(def transport-sell   2500)
(def general-sell     5500)

(def fighter-sell     375)
(def carrier-sell     7500)
(def admiral-sell     7500)

(def station-sell     500)
(def cmd-ship-sell    30000)

(def mil-planet-sell  1000)
(def erg-planet-sell  5500)
(def ore-planet-sell  11000)

(def agent-sell 1000)

(def food-buy  6)
(def food-sell 2)
(def fuel-buy  6)
(def fuel-sell 2)
