;;;;;
;;;;; Game Config - Admin-Editable Constant Specs
;;;;;
;;;;; Single source of truth for every constant an admin can tune when creating a game.
;;;;; field-specs is an ordered vector of plain maps; game-defaults (the snapshot merged onto
;;;;; a new :game entity), the create-game form, and server-side validation are all derived
;;;;; from it, so adding a new tunable constant means adding one row here — not editing the
;;;;; form, the defaults map, and a validator separately.
;;;;;

(ns com.star-empire-elite.game-config
  (:require [clojure.string]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.utils     :as utils]))

;;;;
;;;; Field Specs
;;;; Each spec: :key (the :game/... keyword stored on the entity and the schema),
;;;; :label (form/guide display text), :category (grouping key, see category-order below),
;;;; :type (:int or :double, drives parsing), :min/:max (legal range, inclusive),
;;;; :step (HTML5 number input step), :default (value copied from constants.clj).
;;;;

(def field-specs
  [;; Game — length and turn structure, kept together at the top since these are the settings
   ;; an admin is most likely to want to adjust.
   {:key :game/game-duration-days :label "Game length (days)" :category :game
    :type :int :min 1 :max 365 :step 1 :default const/game-duration-days}
   {:key :game/turns-per-round :label "Turns per round" :category :game
    :type :int :min 1 :max 100 :step 1 :default const/turns-per-round}
   {:key :game/rounds-per-day :label "Rounds per day" :category :game
    :type :int :min 1 :max 48 :step 1 :default const/rounds-per-day}
   {:key :game/minutes-between-rounds :label "Minutes between rounds" :category :game
    :type :int :min 1 :max 10080 :step 1 :default const/minutes-between-rounds}

   ;; Combat Modes — raid/invade-reward-multiplier are NOT included here: constants.clj marks
   ;; them "superseded by combat-loss and capture-cap constants ... kept for reference," and no
   ;; code path reads them, so exposing them as editable would be a dead control that does
   ;; nothing when an admin changes it.
   {:key :game/raid-defense-multiplier :label "Raid defense multiplier" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/raid-defense-multiplier}
   {:key :game/invade-defense-multiplier :label "Invade defense multiplier" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/invade-defense-multiplier}

   ;; Combat Power
   {:key :game/soldier-power :label "Soldier power" :category :action
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/soldier-power}
   {:key :game/fighter-power :label "Fighter power" :category :action
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/fighter-power}
   {:key :game/cmd-ship-power :label "Command ship power" :category :action
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/cmd-ship-power}
   {:key :game/station-power :label "Station power (defender only)" :category :action
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/station-power}
   {:key :game/general-power :label "General power" :category :action
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/general-power}
   {:key :game/admiral-power :label "Admiral power" :category :action
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/admiral-power}
   {:key :game/combat-variance :label "Combat variance (± random factor)" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/combat-variance}

   ;; Combat Losses & Capture
   {:key :game/combat-loss-floor :label "Combat loss floor" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/combat-loss-floor}
   {:key :game/combat-loser-cap :label "Combat loser cap" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/combat-loser-cap}
   {:key :game/combat-winner-max :label "Combat winner max loss" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/combat-winner-max}
   {:key :game/cmd-ship-loss-mult :label "Command ship loss multiplier" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/cmd-ship-loss-mult}
   {:key :game/invade-planet-capture-cap :label "Invade planet capture cap" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/invade-planet-capture-cap}
   {:key :game/invade-resource-capture-cap :label "Invade resource capture cap" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/invade-resource-capture-cap}
   {:key :game/raid-planet-capture-cap :label "Raid planet capture cap" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/raid-planet-capture-cap}
   {:key :game/raid-resource-capture-cap :label "Raid resource capture cap" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/raid-resource-capture-cap}

   ;; Combat Multipliers
   {:key :game/general-mult-rate :label "General multiplier rate" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.001 :default const/general-mult-rate}
   {:key :game/general-mult-cap :label "General multiplier cap" :category :action
    :type :double :min 0.0 :max 5.0 :step 0.01 :default const/general-mult-cap}
   {:key :game/admiral-mult-rate :label "Admiral multiplier rate" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.001 :default const/admiral-mult-rate}
   {:key :game/admiral-mult-cap :label "Admiral multiplier cap" :category :action
    :type :double :min 0.0 :max 5.0 :step 0.01 :default const/admiral-mult-cap}
   {:key :game/agent-mult-rate :label "Agent multiplier rate" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.001 :default const/agent-mult-rate}
   {:key :game/agent-mult-cap :label "Agent multiplier cap" :category :action
    :type :double :min 0.0 :max 5.0 :step 0.01 :default const/agent-mult-cap}
   {:key :game/cmd-ship-mult-rate :label "Command ship multiplier rate" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.001 :default const/cmd-ship-mult-rate}
   {:key :game/cmd-ship-mult-cap :label "Command ship multiplier cap" :category :action
    :type :double :min 0.0 :max 5.0 :step 0.01 :default const/cmd-ship-mult-cap}
   {:key :game/space-carryover :label "Space carryover" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/space-carryover}

   ;; Command / Transport Capacity — divisors, so must be at least 1
   {:key :game/soldiers-per-general :label "Soldiers per general" :category :action
    :type :int :min 1 :max 100000 :step 1 :default const/soldiers-per-general}
   {:key :game/soldiers-per-transport :label "Soldiers per transport" :category :action
    :type :int :min 1 :max 100000 :step 1 :default const/soldiers-per-transport}
   {:key :game/fighters-per-admiral :label "Fighters per admiral" :category :action
    :type :int :min 1 :max 100000 :step 1 :default const/fighters-per-admiral}
   {:key :game/fighters-per-carrier :label "Fighters per carrier" :category :action
    :type :int :min 1 :max 100000 :step 1 :default const/fighters-per-carrier}

   ;; Population Growth
   {:key :game/pop-growth-rate :label "Population growth rate" :category :outcomes
    :type :double :min 0.0 :max 1.0 :step 0.001 :default const/pop-growth-rate}
   {:key :game/pop-growth-per-planet :label "Population growth per planet" :category :outcomes
    :type :double :min 0.0 :max 1.0 :step 0.001 :default const/pop-growth-per-planet}
   {:key :game/pop-capacity-per-planet :label "Population capacity per planet" :category :outcomes
    :type :double :min 0.0 :max 1000.0 :step 0.5 :default const/pop-capacity-per-planet}
   {:key :game/pop-random-min :label "Population growth random min" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.01 :default const/pop-random-min}
   {:key :game/pop-random-max :label "Population growth random max" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.01 :default const/pop-random-max}

   ;; Starting Empire Defaults
   {:key :game/starting-credits :label "Starting credits" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-credits}
   {:key :game/starting-food :label "Starting food" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-food}
   {:key :game/starting-fuel :label "Starting fuel" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-fuel}
   {:key :game/starting-galaxars :label "Starting galaxars" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-galaxars}
   {:key :game/starting-mil-planets :label "Starting military planets" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-mil-planets}
   {:key :game/starting-erg-planets :label "Starting energy planets" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-erg-planets}
   {:key :game/starting-ore-planets :label "Starting ore planets" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-ore-planets}
   {:key :game/starting-population :label "Starting population (millions)" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-population}
   {:key :game/starting-stability :label "Starting stability (%)" :category :starting
    :type :int :min 0 :max 100 :step 1 :default const/starting-stability}
   {:key :game/starting-soldiers :label "Starting soldiers" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-soldiers}
   {:key :game/starting-transports :label "Starting transports" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-transports}
   {:key :game/starting-generals :label "Starting generals" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-generals}
   {:key :game/starting-fighters :label "Starting fighters" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-fighters}
   {:key :game/starting-carriers :label "Starting carriers" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-carriers}
   {:key :game/starting-admirals :label "Starting admirals" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-admirals}
   {:key :game/starting-stations :label "Starting stations" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-stations}
   {:key :game/starting-cmd-ships :label "Starting command ships" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-cmd-ships}
   {:key :game/starting-agents :label "Starting agents" :category :starting
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/starting-agents}

   ;; Income Generation
   {:key :game/ore-planet-credits :label "Ore planet credits" :category :income
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/ore-planet-credits}
   {:key :game/planet-synergy-threshold :label "Planet synergy threshold" :category :income
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/planet-synergy-threshold}
   {:key :game/planet-synergy-bonus-rate :label "Planet synergy bonus rate" :category :income
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/planet-synergy-bonus-rate}
   {:key :game/erg-planet-food :label "Energy planet food" :category :income
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/erg-planet-food}
   {:key :game/erg-planet-fuel :label "Energy planet fuel" :category :income
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/erg-planet-fuel}
   {:key :game/mil-planet-soldiers :label "Military planet soldiers" :category :income
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/mil-planet-soldiers}
   {:key :game/mil-planet-fighters :label "Military planet fighters" :category :income
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/mil-planet-fighters}
   {:key :game/mil-planet-stations :label "Military planet stations" :category :income
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/mil-planet-stations}

   ;; Population Tax — credits are earned at Income; food/fuel are paid at Expenses
   {:key :game/population-tax-credits :label "Population tax credits" :category :income
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/population-tax-credits}
   {:key :game/population-upkeep-food :label "Population upkeep food" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/population-upkeep-food}
   {:key :game/population-upkeep-fuel :label "Population upkeep fuel" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/population-upkeep-fuel}

   ;; Stability — grouped by the phase that triggers each change, not by topic
   {:key :game/capture-stability-penalty-per-planet :label "Capture stability penalty per planet" :category :action
    :type :int :min 0 :max 100 :step 1 :default const/capture-stability-penalty-per-planet}
   {:key :game/capture-stability-penalty-cap :label "Capture stability penalty cap" :category :action
    :type :int :min 0 :max 100 :step 1 :default const/capture-stability-penalty-cap}
   {:key :game/stability-breakaway-threshold :label "Stability breakaway threshold" :category :outcomes
    :type :int :min 0 :max 100 :step 1 :default const/stability-breakaway-threshold}
   {:key :game/stability-breakaway-cap :label "Stability breakaway cap (%)" :category :outcomes
    :type :int :min 0 :max 100 :step 1 :default const/stability-breakaway-cap}
   {:key :game/stability-recovery-amount :label "Stability recovery amount" :category :outcomes
    :type :int :min 0 :max 100 :step 1 :default const/stability-recovery-amount}
   {:key :game/stability-recovery-floor :label "Stability recovery floor" :category :outcomes
    :type :int :min 0 :max 100 :step 1 :default const/stability-recovery-floor}
   {:key :game/expense-stability-penalty :label "Expense stability penalty" :category :expenses
    :type :int :min 0 :max 100 :step 1 :default const/expense-stability-penalty}

   ;; Upkeep / Expenses
   {:key :game/planet-upkeep-credits :label "Planet upkeep credits" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/planet-upkeep-credits}
   {:key :game/planet-upkeep-food :label "Planet upkeep food" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/planet-upkeep-food}
   {:key :game/soldier-upkeep-credits :label "Soldier upkeep credits" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/soldier-upkeep-credits}
   {:key :game/soldier-upkeep-food :label "Soldier upkeep food" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/soldier-upkeep-food}
   {:key :game/fighter-upkeep-credits :label "Fighter upkeep credits" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/fighter-upkeep-credits}
   {:key :game/fighter-upkeep-fuel :label "Fighter upkeep fuel" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/fighter-upkeep-fuel}
   {:key :game/station-upkeep-credits :label "Station upkeep credits" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/station-upkeep-credits}
   {:key :game/station-upkeep-fuel :label "Station upkeep fuel" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/station-upkeep-fuel}
   {:key :game/agent-upkeep-food :label "Agent upkeep food" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/agent-upkeep-food}
   {:key :game/agent-upkeep-fuel :label "Agent upkeep fuel" :category :expenses
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/agent-upkeep-fuel}

   ;; Building Costs
   {:key :game/soldier-cost :label "Soldier cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/soldier-cost}
   {:key :game/transport-cost :label "Transport cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/transport-cost}
   {:key :game/general-cost :label "General cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/general-cost}
   {:key :game/fighter-cost :label "Fighter cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/fighter-cost}
   {:key :game/carrier-cost :label "Carrier cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/carrier-cost}
   {:key :game/admiral-cost :label "Admiral cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/admiral-cost}
   {:key :game/station-cost :label "Station cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/station-cost}
   {:key :game/cmd-ship-cost :label "Command ship cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/cmd-ship-cost}
   {:key :game/agent-cost :label "Agent cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/agent-cost}
   {:key :game/mil-planet-cost :label "Military planet cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/mil-planet-cost}
   {:key :game/erg-planet-cost :label "Energy planet cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/erg-planet-cost}
   {:key :game/ore-planet-cost :label "Ore planet cost" :category :building
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/ore-planet-cost}
   {:key :game/planet-cost-ramp-x :label "Planet cost ramp start (x)" :category :building
    :type :int :min 0 :max utils/max-unit-quantity :step 1 :default const/planet-cost-ramp-x}
   {:key :game/planet-cost-ramp-y :label "Planet cost ramp 2x point (y)" :category :building
    :type :int :min 0 :max utils/max-unit-quantity :step 1 :default const/planet-cost-ramp-y}

   ;; Espionage
   {:key :game/spy-defection-rate :label "Spy defection rate" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/spy-defection-rate}
   {:key :game/incite-defection-rate :label "Incite defection rate" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/incite-defection-rate}
   {:key :game/bomb-defection-rate :label "Bomb defection rate" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/bomb-defection-rate}
   {:key :game/defect-defection-rate :label "Defect defection rate" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/defect-defection-rate}
   {:key :game/espionage-defection-min :label "Minimum agents lost on failed mission" :category :espionage
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/espionage-defection-min}
   {:key :game/espionage-defense-threshold :label "Espionage defense threshold" :category :espionage
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/espionage-defense-threshold}
   {:key :game/espionage-defense-exponent :label "Espionage defense exponent" :category :espionage
    :type :double :min 0.0 :max 5.0 :step 0.01 :default const/espionage-defense-exponent}
   {:key :game/incite-stability-damage :label "Incite stability damage" :category :espionage
    :type :int :min 0 :max 100 :step 1 :default const/incite-stability-damage}
   {:key :game/incite-stability-floor :label "Incite stability floor" :category :espionage
    :type :int :min 0 :max 100 :step 1 :default const/incite-stability-floor}
   {:key :game/bomb-damage-rate-lo :label "Bomb damage rate (low)" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/bomb-damage-rate-lo}
   {:key :game/bomb-damage-rate-hi :label "Bomb damage rate (high)" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/bomb-damage-rate-hi}

   ;; Strike Operation
   {:key :game/strike-damage-rate :label "Strike damage rate" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/strike-damage-rate}
   {:key :game/strike-max-dispatch :label "Strike max dispatch" :category :action
    :type :int :min 1 :max 1000 :step 1 :default const/strike-max-dispatch}
   {:key :game/strike-interception-rate :label "Strike interception rate" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.0001 :default const/strike-interception-rate}
   {:key :game/strike-interception-cap :label "Strike interception cap" :category :action
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/strike-interception-cap}
   {:key :game/strike-agent-mult :label "Strike agent multiplier" :category :action
    :type :double :min 0.0 :max 2.0 :step 0.01 :default const/strike-agent-mult}

   ;; Defect Operation
   {:key :game/defect-defense-multiplier :label "Defect defense multiplier" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/defect-defense-multiplier}
   {:key :game/defect-transfer-rate :label "Defect transfer rate" :category :espionage
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/defect-transfer-rate}

   ;; Exchange Rates
   {:key :game/soldier-sell :label "Soldier sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/soldier-sell}
   {:key :game/transport-sell :label "Transport sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/transport-sell}
   {:key :game/general-sell :label "General sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/general-sell}
   {:key :game/fighter-sell :label "Fighter sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/fighter-sell}
   {:key :game/carrier-sell :label "Carrier sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/carrier-sell}
   {:key :game/admiral-sell :label "Admiral sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/admiral-sell}
   {:key :game/station-sell :label "Station sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/station-sell}
   {:key :game/cmd-ship-sell :label "Command ship sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/cmd-ship-sell}
   {:key :game/agent-sell :label "Agent sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/agent-sell}
   {:key :game/mil-planet-sell :label "Military planet sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/mil-planet-sell}
   {:key :game/erg-planet-sell :label "Energy planet sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/erg-planet-sell}
   {:key :game/ore-planet-sell :label "Ore planet sell" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/ore-planet-sell}
   {:key :game/food-buy :label "Food buy price" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/food-buy}
   {:key :game/food-sell :label "Food sell price" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/food-sell}
   {:key :game/fuel-buy :label "Fuel buy price" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/fuel-buy}
   {:key :game/fuel-sell :label "Fuel sell price" :category :exchange
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/fuel-sell}

   ;; Fate
   {:key :game/fate-probability :label "Fate probability" :category :outcomes
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/fate-probability}
   {:key :game/fate-rank-tilt :label "Fate rank tilt" :category :outcomes
    :type :double :min 0.0 :max 1.0 :step 0.01 :default const/fate-rank-tilt}
   {:key :game/fate-drought-lo :label "Drought low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-drought-lo}
   {:key :game/fate-drought-hi :label "Drought high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-drought-hi}
   {:key :game/fate-pirate-raid-lo :label "Pirate raid low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-pirate-raid-lo}
   {:key :game/fate-pirate-raid-hi :label "Pirate raid high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-pirate-raid-hi}
   {:key :game/fate-solar-flare-lo :label "Solar flare low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-solar-flare-lo}
   {:key :game/fate-solar-flare-hi :label "Solar flare high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-solar-flare-hi}
   {:key :game/fate-plague-lo :label "Plague low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-plague-lo}
   {:key :game/fate-plague-hi :label "Plague high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-plague-hi}
   {:key :game/fate-reactor-breach-lo :label "Reactor breach low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-reactor-breach-lo}
   {:key :game/fate-reactor-breach-hi :label "Reactor breach high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-reactor-breach-hi}
   {:key :game/fate-bumper-harvest-lo :label "Bumper harvest low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-bumper-harvest-lo}
   {:key :game/fate-bumper-harvest-hi :label "Bumper harvest high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-bumper-harvest-hi}
   {:key :game/fate-gold-rush-lo :label "Gold rush low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-gold-rush-lo}
   {:key :game/fate-gold-rush-hi :label "Gold rush high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-gold-rush-hi}
   {:key :game/fate-lottery-win-lo :label "Lottery win low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-lottery-win-lo}
   {:key :game/fate-lottery-win-hi :label "Lottery win high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-lottery-win-hi}
   {:key :game/fate-migration-wave-lo :label "Migration wave low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-migration-wave-lo}
   {:key :game/fate-migration-wave-hi :label "Migration wave high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-migration-wave-hi}
   {:key :game/fate-reactor-surge-lo :label "Reactor surge low" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-reactor-surge-lo}
   {:key :game/fate-reactor-surge-hi :label "Reactor surge high" :category :outcomes
    :type :double :min 0.0 :max 5.0 :step 0.05 :default const/fate-reactor-surge-hi}
   {:key :game/fate-uncollected-stability-penalty :label "Fate uncollected stability penalty" :category :outcomes
    :type :int :min 0 :max 100 :step 1 :default const/fate-uncollected-stability-penalty}

   ;; Score Weights
   {:key :game/score-population :label "Score: population" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-population}
   {:key :game/score-mil-planet :label "Score: military planet" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-mil-planet}
   {:key :game/score-erg-planet :label "Score: energy planet" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-erg-planet}
   {:key :game/score-ore-planet :label "Score: ore planet" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-ore-planet}
   {:key :game/score-soldier :label "Score: soldier" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-soldier}
   {:key :game/score-transport :label "Score: transport" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-transport}
   {:key :game/score-general :label "Score: general" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-general}
   {:key :game/score-fighter :label "Score: fighter" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-fighter}
   {:key :game/score-carrier :label "Score: carrier" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-carrier}
   {:key :game/score-admiral :label "Score: admiral" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-admiral}
   {:key :game/score-cmd-ship :label "Score: command ship" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-cmd-ship}
   {:key :game/score-station :label "Score: station" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-station}
   {:key :game/score-agent :label "Score: agent" :category :scoring
    :type :int :min 0 :max utils/max-config-value :step 1 :default const/score-agent}])

;;;;
;;;; Category Order
;;;; :game (length and turn structure), :starting (player seed values), and :scoring (point
;;;; weights) lead, since none is tied to a turn phase and all three are settings an admin is
;;;; likely to want up front. The rest map to the game's turn phases (see the app.clj module
;;;; doc-comment: Income -> Expenses -> Exchange -> Building -> Action -> Espionage), plus
;;;; Outcomes for the mechanics that resolve there (population growth, stability
;;;; breakaway/recovery, fate). A field is filed under whichever phase's resolution logic
;;;; actually reads it, not the phase where a player takes the related action — e.g.
;;;; combat-power lives under Action even though damage is applied at Outcomes, because Action
;;;; is where the player experiences it (choosing to raid/invade/strike).
;;;;
;;;; Drives the order collapsible sections render in on the create-game form.
;;;;

(def category-order
  [[:game       "Game"]
   [:starting   "Start"]
   [:scoring    "Scoring"]
   [:income     "Income"]
   [:expenses   "Expenses"]
   [:exchange   "Exchange"]
   [:building   "Building"]
   [:action     "Action"]
   [:espionage  "Espionage"]
   [:outcomes   "Outcomes"]])

(defn fields-in-category
  "All field-specs belonging to category, in field-specs order.

  [category keyword] -> [spec-map, ...]"
  [category]
  (filterv #(= category (:category %)) field-specs))

;;;;
;;;; Defaults
;;;;

(def game-defaults
  "Every field-spec's default value, keyed by its :game/... keyword. Merged onto a new :game
  entity at creation time and overridden by whatever the admin submits on the create-game form."
  (into {} (map (juxt :key :default)) field-specs))

;;;;
;;;; Form Field Names
;;;; A :game/soldier-cost spec is submitted by an HTML input named \"soldier-cost\" (no
;;;; namespace); Biff parses that into a plain (un-namespaced) keyword param.
;;;;

(defn param-key
  "The un-namespaced keyword a spec's :game/x key is submitted under in form params.

  [spec-key keyword] -> keyword"
  [spec-key]
  (keyword (name spec-key)))

;;;;
;;;; Coercion
;;;;

(defn- coerce-one
  "Parse one submitted value against its spec, clamped to [min, max]. Blank, missing, or
  unparseable input silently falls back to the field's default rather than rejecting the
  whole form — the same philosophy as utils/parse-numeric-input, extended to doubles and to
  each field's own custom [min, max] range rather than one of parse-numeric-input's shared caps.

  [spec map, raw-value any] -> number"
  [{:keys [type min max default]} raw-value]
  (let [s (clojure.string/trim (str raw-value))]
    (if (clojure.string/blank? s)
      default
      (let [parsed (try
                     (case type
                       :int    (Long/parseLong s)
                       :double (Double/parseDouble s))
                     (catch NumberFormatException _ nil))]
        (if (nil? parsed)
          default
          (let [clamped (-> parsed (clojure.core/max min) (clojure.core/min max))]
            (if (= type :int) (long clamped) (double clamped))))))))

(defn coerce-config
  "Parse and clamp every field-spec against submitted create-game form params. Missing/blank/
  non-numeric fields fall back to their default.

  [params {keyword any}] -> {:game/x number, ...}"
  [params]
  (into {}
        (map (fn [spec]
               [(:key spec) (coerce-one spec (get params (param-key (:key spec))))]))
        field-specs))

;;;;
;;;; Cross-Field Invariants
;;;; A handful of constant pairs must stay ordered for the game to make sense — e.g. selling an
;;;; asset for more than it costs to build lets a player mint credits by buying and reselling in
;;;; a loop. enforce-invariants tightens the exploitable side of each pair after per-field
;;;; clamping rather than rejecting the submission outright.
;;;;

(def ^:private sell-cost-pairs
  [[:game/soldier-sell    :game/soldier-cost]
   [:game/transport-sell  :game/transport-cost]
   [:game/general-sell    :game/general-cost]
   [:game/fighter-sell    :game/fighter-cost]
   [:game/carrier-sell    :game/carrier-cost]
   [:game/admiral-sell    :game/admiral-cost]
   [:game/station-sell    :game/station-cost]
   [:game/cmd-ship-sell   :game/cmd-ship-cost]
   [:game/agent-sell      :game/agent-cost]
   [:game/mil-planet-sell :game/mil-planet-cost]
   [:game/erg-planet-sell :game/erg-planet-cost]
   [:game/ore-planet-sell :game/ore-planet-cost]])

(def ^:private fate-lo-hi-pairs
  [[:game/fate-drought-lo        :game/fate-drought-hi]
   [:game/fate-pirate-raid-lo    :game/fate-pirate-raid-hi]
   [:game/fate-solar-flare-lo    :game/fate-solar-flare-hi]
   [:game/fate-plague-lo         :game/fate-plague-hi]
   [:game/fate-reactor-breach-lo :game/fate-reactor-breach-hi]
   [:game/fate-bumper-harvest-lo :game/fate-bumper-harvest-hi]
   [:game/fate-gold-rush-lo      :game/fate-gold-rush-hi]
   [:game/fate-lottery-win-lo    :game/fate-lottery-win-hi]
   [:game/fate-migration-wave-lo :game/fate-migration-wave-hi]
   [:game/fate-reactor-surge-lo  :game/fate-reactor-surge-hi]])

(defn- cap-sell-prices
  "Lower each sell price to at most its matching cost/buy price, for every pair in pairs.

  [config map, pairs [[sell-key cost-key], ...]] -> map"
  [config pairs]
  (reduce (fn [c [sell-key cost-key]]
            (update c sell-key min (get c cost-key)))
          config
          pairs))

(defn- order-lo-hi-pairs
  "Swap lo/hi within each pair in pairs so lo never exceeds hi.

  [config map, pairs [[lo-key hi-key], ...]] -> map"
  [config pairs]
  (reduce (fn [c [lo-key hi-key]]
            (let [lo (get c lo-key) hi (get c hi-key)]
              (if (> lo hi)
                (assoc c lo-key hi hi-key lo)
                c)))
          config
          pairs))

(defn enforce-invariants
  "Adjust a coerced config map to satisfy known cross-field invariants: the loser's combat-loss
  cap can't sit below the even-fight floor, no asset can sell for more than it costs to build,
  food/fuel can't sell above its buy price, and every fate lo/hi pair, the bomb-damage-rate
  lo/hi pair, the planet-cost-ramp x/y pair, and the population-growth random range must be
  ordered lo <= hi.

  [config {:game/x number}] -> {:game/x number}"
  [config]
  (-> config
      (update :game/combat-loser-cap max (:game/combat-loss-floor config))
      (cap-sell-prices sell-cost-pairs)
      (update :game/food-sell min (:game/food-buy config))
      (update :game/fuel-sell min (:game/fuel-buy config))
      (order-lo-hi-pairs fate-lo-hi-pairs)
      (order-lo-hi-pairs [[:game/pop-random-min :game/pop-random-max]
                           [:game/bomb-damage-rate-lo :game/bomb-damage-rate-hi]
                           [:game/planet-cost-ramp-x :game/planet-cost-ramp-y]])))
