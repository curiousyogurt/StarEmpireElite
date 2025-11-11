(ns com.star-empire-elite.constants)

;; :: Default Game Constants
;; These are the default values used when creating a new game.
;; Each game stores its own values in the database, allowing for
;; customization per game instance.

;; :: Income Generation Defaults
;; Resources generated per planet type per round

(def ore-planet-credits 500)
(def ore-planet-fuel 200)
(def ore-planet-galaxars 100)

(def food-planet-food 1000)

(def military-planet-soldiers 50)
(def military-planet-fighters 25)
(def military-planet-stations 10)
(def military-planet-agents 5)

;; :: Upkeep/Expense Defaults
;; Cost to maintain units and infrastructure per round
;; Note: For soldiers, agents, and population, divide by 1000 when calculating actual costs

(def planet-upkeep-credits 10)
(def planet-upkeep-food 1)

(def soldier-upkeep-credits 1)
(def soldier-upkeep-food 1)

(def fighter-upkeep-credits 2)
(def fighter-upkeep-fuel 2)

(def station-upkeep-credits 3)
(def station-upkeep-fuel 3)

(def agent-upkeep-credits 2)
(def agent-upkeep-food 1)

(def population-upkeep-credits 1)
(def population-upkeep-food 1)
