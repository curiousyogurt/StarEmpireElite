(ns com.star-empire-elite.constants)

;; Admin Accounts
;; Only these email addresses are permitted to create new games

(def admin-emails #{"darcyotto@bennington.edu"})

;; Access Control
;; Players must have a Bennington College email address, or appear in the whitelist below.

(def allowed-email-domain "bennington.edu")
(def whitelisted-emails   #{"darcyotto@gmail.com" "danher@unc.edu"})

;; Default Game Settings for Turns (per round) and Rounds (per day)

(def turns-per-round 6)
(def rounds-per-day 2)
(def hours-between-rounds 2)


;; Combat Power Values
;; Power contribution of each unit type per unit

(def soldier-power   1)
(def fighter-power   3)
(def cmd-ship-power  20)
(def station-power   5) 
(def general-power   5)
(def admiral-power   10)
(def combat-variance 0.15) ; ±15% random factor


;; Population Growth
;; Applied at the end of each round in outcomes

(def pop-growth-rate       0.02)  ; 2% of current population per round
(def pop-growth-per-planet 0.02)  ; +20,000 per planet per round (in millions)
(def pop-capacity-per-planet 10.0) ; max 10 million population per planet
(def pop-random-min        0.9)
(def pop-random-max        1.1)


;; Starting Empire Defaults
;; Resources and units a new player begins with

(def starting-credits  70000)
(def starting-food     5000)
(def starting-fuel     5000)
(def starting-galaxars 0)

(def starting-mil-planets  0)
(def starting-food-planets 1)
(def starting-ore-planets  1)

(def starting-population 6)   ; Stored in millions
(def starting-stability  100) ; Stored as %

(def starting-soldiers   100)
(def starting-transports 0)
(def starting-generals   2)
(def starting-fighters   0)
(def starting-carriers   1)
(def starting-admirals   0)
(def starting-stations   0)
(def starting-cmd-ships  0)
(def starting-agents     0)


;; Command / Transport Capacity Defaults

(def soldiers-per-general   1000)
(def soldiers-per-transport 100)
(def fighters-per-admiral   1000)
(def fighters-per-carrier   100)


;; Income Generation Defaults
;; Resources generated per planet type per round

(def ore-planet-credits  10000)
(def ore-planet-fuel     50)
(def food-planet-food    3500)

;; Military planets provide modest ongoing military support.
;; They should not out-compete ore planets as a source of sell value.
(def mil-planet-soldiers 5)
(def mil-planet-fighters 2)
(def mil-planet-stations 1)


;; :: Population / Tax Defaults
;; Population is stored in millions, so these are per-population-unit.

;; Tax revenue
(def population-tax-credits 2000) ; credits per million population per round

;; Civilian consumption
(def population-upkeep-food 250)
(def population-upkeep-fuel 10)


;; Upkeep / Expense Defaults
;; Cost to maintain units and infrastructure per round

(def planet-upkeep-credits    2500)
(def planet-upkeep-food       100)

(def soldier-upkeep-credits   25)
(def soldier-upkeep-food      10)

(def fighter-upkeep-credits   100)
(def fighter-upkeep-fuel      10)

(def station-upkeep-credits   100)
(def station-upkeep-fuel      10)

(def agent-upkeep-food        10)
(def agent-upkeep-fuel        10)


;; Building / Purchase Cost Defaults
;; Cost to purchase new units, ships, and planets

(def soldier-cost     1000)
(def transport-cost   5000)
(def general-cost     11000)

(def fighter-cost     1500)
(def carrier-cost     15000)
(def admiral-cost     15000)

(def station-cost     2000)
(def cmd-ship-cost    60000)

(def mil-planet-cost  17000)
(def food-planet-cost 11000)
(def ore-planet-cost  22000)

(def agent-cost       5000)


;; Rates for buying and selling assets in the exchange

(def soldier-sell     250)
(def transport-sell   2500)
(def general-sell     5500)

(def fighter-sell     375)
(def carrier-sell     7500)
(def admiral-sell     7500)

(def station-sell     500)
(def cmd-ship-sell    30000)

(def mil-planet-sell  1000)
(def food-planet-sell 5500)
(def ore-planet-sell  11000)

(def agent-sell       1000)

(def food-buy         6)
(def food-sell        2)
(def fuel-buy         6)
(def fuel-sell        3)
