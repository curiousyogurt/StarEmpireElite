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
;;;; Combat Modes
;;;; Raid is a limited engagement that targets a fraction of the defender's empire.
;;;; Invade is a full-scale assault. The engagement multiplier governs both the
;;;; defender's power participation (roll scaling) and loss exposure (fraction of
;;;; forces at risk) per phase.

(def raid-defense-multiplier    0.1)  ; defender engages 10% of forces in both space and ground phases
(def invade-defense-multiplier  1.0)  ; defender engages full forces during an invasion

;; Superseded by combat-loss and capture-cap constants below; kept for reference.
(def raid-reward-multiplier     0.1)
(def invade-reward-multiplier   1.0)

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
;;;; Combat Losses & Capture
;;;; The loser's loss rate rises with margin (close fight → floor, blowout → cap).
;;;; The winner's loss rate falls with margin — a decisive victory is cheap for the
;;;; winner. At margin 0 both curves meet at loss-floor ≈ winner-max: an even fight
;;;; is a meat grinder for both sides.
;;;;

(def combat-loss-floor  0.30)  ; both sides lose this fraction in an even fight
(def combat-loser-cap   0.70)  ; loser's maximum loss rate at a blowout
(def combat-winner-max  0.30)  ; winner's loss rate at margin 0, falling to ~0 at a blowout

;; Capture caps — per-mode maximum fraction of defender's planets/resources captured.
;; Actual capture = cap × ground-margin × defender-total, so narrow wins yield little.
(def invade-planet-capture-cap    0.25)
(def invade-resource-capture-cap  0.50)
(def raid-planet-capture-cap      0.05)  ; renamed from raid-planet-capture-rate
(def raid-resource-capture-cap    0.10)

;;;;
;;;; Combat Multipliers
;;;; Generals, admirals, and agents act as capped additive bonuses on top of a 1.0 base
;;;; multiplier. Each term is individually capped via min so it plateaus rather than
;;;; snowballing with empire size. The space-carryover constant controls how much a
;;;; decisive space victory tilts the subsequent ground fight.
;;;;

(def general-mult-rate  0.01)  ; +1% ground power per general
(def general-mult-cap   0.15)  ; max +15% (reached at 15 generals)
(def admiral-mult-rate  0.01)  ; +1% space power per admiral
(def admiral-mult-cap   0.15)  ; max +15% (reached at 15 admirals)
(def agent-mult-rate    0.005) ; +0.5% ground power per agent
(def agent-mult-cap     0.10)  ; max +10% (reached at 20 agents)
(def space-carryover    0.15)  ; winning space decisively adds up to +15% to ground power

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

(def starting-credits     70000)
(def starting-food        50000)
(def starting-fuel        50000)
(def starting-galaxars    0)

(def starting-mil-planets 0)
(def starting-erg-planets 1)
(def starting-ore-planets 1)

(def starting-population  6)    ; stored in millions
(def starting-stability   100)  ; stored as %

(def starting-soldiers    100)
(def starting-transports  1)
(def starting-generals    1)
(def starting-fighters    1)
(def starting-carriers    1)
(def starting-admirals    1)
(def starting-stations    1)
(def starting-cmd-ships   1)
(def starting-agents      2)

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
;;;; Population Tax
;;;; Population is stored in millions, so these are per-population-unit.

(def population-tax-credits  2000)  ; credits per million population per round

(def population-upkeep-food  1000)
(def population-upkeep-fuel  1000)

;;;;
;;;; Stability
;;;; Breakaway: if roll (1–100) > stability + threshold, planets break away.
;;;; Recovery:  if roll < max(stability, floor), stability recovers.

;;;;
;;;; Acquisition Penalty
;;;; Conquered worlds are restive; annexing territory faster than you can pacify
;;;; it costs stability. At 1 point/planet this only outpaces the +5 recovery
;;;; roll for captures of ~6+ planets; smaller conquests are clawed back.
;;;;

(def capture-stability-penalty-per-planet  1)  ; stability points lost per captured planet
(def capture-stability-penalty-cap         10) ; max stability points lost per attack

(def stability-breakaway-threshold 20)
(def stability-breakaway-cap       25) ; stored as %, i.e. 25 = 25%
(def stability-recovery-amount     5)  ; flat stability points gained on recovery
(def stability-recovery-floor      50) ; minimum effective stability for recovery rolls
(def expense-stability-penalty     3)  ; stability points lost per underpaid expense fraction

;;;;
;;;; Player Status
;;;;

(def player-status-active     0)
(def player-status-eliminated 1)

;;;;
;;;; Upkeep / Expenses
;;;; Cost to maintain units and infrastructure per round.

(def planet-upkeep-credits  1000)
(def planet-upkeep-food     300)

(def soldier-upkeep-credits 25)
(def soldier-upkeep-food    20)

(def fighter-upkeep-credits 100)
(def fighter-upkeep-fuel    20)

(def station-upkeep-credits 100)
(def station-upkeep-fuel    20)

(def agent-upkeep-food      20)
(def agent-upkeep-fuel      20)

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
(def erg-planet-cost 20000)
(def ore-planet-cost 22000)

(def agent-cost      5000)

;;;;
;;;; Espionage
;;;; Agent defection on a failed covert mission.

;;;; Per-op failure rates — supersede the old flat espionage-defection-rate.
;;;; Each op risks a different fraction of the attacker's agent pool on failure.
;;;; Spy is light recon (low exposure); bomb is a physical operation (high exposure).
(def spy-defection-rate    0.05)
(def incite-defection-rate 0.10)
(def bomb-defection-rate   0.15)
(def defect-defection-rate 0.12)

(def espionage-defection-min  1)     ; minimum agents lost on any failed mission

;;;; Diminishing returns on defensive agent massing.
;;;; Agents defend at full value up to the threshold T. Beyond T, the surplus
;;;; contributes with diminishing returns: effective = T + (surplus)^exp.
;;;; This is a concave power curve — the first agents past T still help, but each
;;;; successive agent adds less than the one before. At exp = 0.75 and T = 100,
;;;; a 1000-agent defender has ~264 effective agents, not 1000.
(def espionage-defense-threshold 100)
(def espionage-defense-exponent  0.75)

(def incite-stability-damage  10)    ; stability points lost by the target on a successful incite
(def bomb-damage-rate         0.10)  ; fraction of soldiers/transports/fighters/carriers destroyed on a successful bomb

;;;;
;;;; Strike Operation
;;;; Standoff attack using command ships to damage the defender's military. No planet capture.

(def strike-damage-rate       0.01)  ; fraction of each unit type destroyed per dispatched cmd-ship
(def strike-max-dispatch      15)    ; max cmd-ships dispatched per strike; rest stay in reserve
(def strike-interception-rate 0.001) ; per-station contribution to interception chance per cmd-ship
(def strike-interception-cap  0.20)  ; maximum per-cmd-ship interception chance regardless of stations

;;;;
;;;; Defect Operation
;;;; Targets the defender's agent pool — success flips a fraction of their agents to the attacker.

(def defect-defense-multiplier 0.10) ; only 10% of defender's agents defend against defection
(def defect-transfer-rate      0.10) ; fraction of defender's agents that defect on success
(def defect-transfer-cap       50)   ; maximum agents transferred per successful defect

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

(def mil-planet-sell  9000)
(def erg-planet-sell  10000)
(def ore-planet-sell  11000)

(def agent-sell 1000)

(def food-buy  6)
(def food-sell 2)
(def fuel-buy  6)
(def fuel-sell 2)

;;;;
;;;; Score Weights
;;;; Points contributed per unit/planet/resource to a player's score.

(def score-population 700)
(def score-mil-planet 500)
(def score-erg-planet 300)
(def score-ore-planet 200)
(def score-soldier    2)
(def score-transport  10)
(def score-general    20)
(def score-fighter    3)
(def score-carrier    15)
(def score-admiral    30)
(def score-cmd-ship   20)
(def score-station    5)
(def score-agent      10)
