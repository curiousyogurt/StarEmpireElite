(ns com.star-empire-elite.constants)

;; ============================================================
;; Space Dynasty - Default Constants
;; ============================================================
;;
;; Design notes
;;
;; - Population is stored internally in MILLIONS.
;;   Example: 6 means 6,000,000 population in the UI.
;;
;; - Food and fuel are stored in megatons.
;;
;; - Soldiers, fighters, transports, carriers, stations, agents,
;;   generals, admirals, and command ships are stored as literal units.
;;
;; - Ground attack capacity is limited by:
;;     soldiers
;;     generals   * soldiers-per-general
;;     transports * soldiers-per-transport
;;
;; - Fighter attack capacity is limited by:
;;     fighters
;;     admirals * fighters-per-admiral
;;     carriers * fighters-per-carrier
;;
;; ============================================================


;; :: Admin Accounts
;; Only these email addresses are permitted to create new games.

(def admin-emails #{"darcyotto@bennington.edu"})


;; :: Default Game Settings

(def turns-per-round 6)
(def rounds-per-day 2)
(def hours-between-rounds 2)


;; :: Starting Empire Defaults
;; Resources and units a new player begins with

(def starting-credits       70000)
(def starting-food          5000)
(def starting-fuel          5000)
(def starting-galaxars      0)

(def starting-mil-planets   0)
(def starting-food-planets  1)
(def starting-ore-planets   1)

;; Stored in millions
(def starting-population    6)

(def starting-stability     100)

(def starting-generals      2)
(def starting-admirals      0)

(def starting-soldiers      100)
(def starting-transports    0)
(def starting-stations      0)
(def starting-carriers      1)
(def starting-fighters      0)
(def starting-cmd-ships     0)
(def starting-agents        0)


;; :: Command / Transport Capacity Defaults

(def soldiers-per-general   1000)
(def soldiers-per-transport 100)

(def fighters-per-admiral   1000)
(def fighters-per-carrier   100)


;; :: Income Generation Defaults
;; Resources generated per planet type per round

(def ore-planet-credits     10000)
(def ore-planet-fuel        50)
(def ore-planet-galaxars    0)

(def food-planet-food       3500)

;; Military planets provide modest ongoing military support.
;; They should not out-compete ore planets as a source of sell value.
(def mil-planet-soldiers    5)
(def mil-planet-fighters    2)
(def mil-planet-stations    1)
(def mil-planet-agents      1)


;; :: Population / Tax Defaults
;; Population is stored in millions, so these are per-population-unit.

(def tax-per-population             250)
(def crime-upkeep-per-population     10)

;; Civilian consumption
(def population-upkeep-food         250)
(def population-upkeep-fuel          10)


;; :: Upkeep / Expense Defaults
;; Cost to maintain units and infrastructure per round

(def planet-upkeep-credits      2500)
(def planet-upkeep-food          100)

(def soldier-upkeep-credits       25)
(def soldier-upkeep-food          10)

(def transport-upkeep-credits     75)
(def transport-upkeep-fuel         8)

(def general-upkeep-credits       10)

(def fighter-upkeep-credits      100)
(def fighter-upkeep-fuel          10)

(def carrier-upkeep-credits      250)
(def carrier-upkeep-fuel          25)

(def admiral-upkeep-credits       20)

(def station-upkeep-credits      100)
(def station-upkeep-fuel          10)

(def cmd-ship-upkeep-credits     500)
(def cmd-ship-upkeep-fuel         50)

(def agent-upkeep-food            10)
(def agent-upkeep-fuel            10)


;; :: Building / Purchase Cost Defaults
;; Cost to purchase new units, ships, and planets

(def soldier-cost              1000)
(def transport-cost            5000)
(def general-cost             11000)

(def fighter-cost              1500)
(def carrier-cost             15000)
(def admiral-cost             15000)

(def station-cost              2000)
(def cmd-ship-cost            60000)

(def mil-planet-cost          17000)
(def food-planet-cost         11000)
(def ore-planet-cost          22000)

(def agent-cost               5000)


;; :: Exchange Rate Defaults
;; Rates for buying and selling resources and assets in the exchange

(def soldier-sell              250)
(def transport-sell           2500)
(def general-sell             5500)

(def fighter-sell              375)
(def carrier-sell             7500)
(def admiral-sell             7500)

(def station-sell              500)
(def cmd-ship-sell           30000)

(def mil-planet-sell         11000)
(def food-planet-sell         5500)
(def ore-planet-sell         11000)

(def agent-sell              1000)

(def food-buy                   6)
(def food-sell                  2)
(def fuel-buy                   6)
(def fuel-sell                  3)
