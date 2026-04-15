(ns com.star-empire-elite.simulation
  (:require [com.star-empire-elite.constants :as const]))

;; Default constants map — mirrors constants.clj, can be overridden per simulation
(def default-constants
  {;; Planet income
   :ore-planet-credits     const/ore-planet-credits
   :ore-planet-fuel        const/ore-planet-fuel
   :ore-planet-galaxars    const/ore-planet-galaxars
   :food-planet-food       const/food-planet-food
   :mil-planet-soldiers    const/mil-planet-soldiers
   :mil-planet-fighters    const/mil-planet-fighters
   :mil-planet-stations    const/mil-planet-stations
   :mil-planet-agents      const/mil-planet-agents

   ;; Planet upkeep
   :planet-upkeep-credits  const/planet-upkeep-credits
   :planet-upkeep-food     const/planet-upkeep-food

   ;; Unit upkeep (transports, generals, carriers, admirals, cmd-ships are free)
   :soldier-upkeep-credits const/soldier-upkeep-credits
   :soldier-upkeep-food    const/soldier-upkeep-food
   :fighter-upkeep-credits const/fighter-upkeep-credits
   :fighter-upkeep-fuel    const/fighter-upkeep-fuel
   :station-upkeep-credits const/station-upkeep-credits
   :station-upkeep-fuel    const/station-upkeep-fuel
   :agent-upkeep-food      const/agent-upkeep-food
   :agent-upkeep-fuel      const/agent-upkeep-fuel

   ;; Population upkeep (per population unit, where 1 unit = 1M people)
   :population-upkeep-food const/population-upkeep-food
   :population-upkeep-fuel const/population-upkeep-fuel

   ;; Exchange sell rates (for liquidation)
   :soldier-sell   const/soldier-sell
   :fighter-sell   const/fighter-sell
   :station-sell   const/station-sell
   :agent-sell     const/agent-sell

   ;; Planet costs (for expansion estimate)
   :ore-planet-cost  const/ore-planet-cost
   :food-planet-cost const/food-planet-cost
   :mil-planet-cost  const/mil-planet-cost})

(defn simulate-empire
  "Simulate one round of empire economics.

  empire map keys (all optional, default to 0 or starting constants):
    :ore-planets :food-planets :mil-planets
    :population :soldiers :generals :transports
    :fighters :admirals :carriers :stations :cmd-ships :agents
    :credits :food :fuel                  ; starting stockpiles
    :liquidate-military-output?           ; sell all mil-planet production each round

  Population is stored in millions (6 = 6M people).
  All other units are literal counts.

  const-overrides: map of any keys from default-constants to override."
  ([empire] (simulate-empire empire {}))
  ([empire const-overrides]
   (let [c (merge default-constants const-overrides)

         ore-planets   (get empire :ore-planets 0)
         food-planets  (get empire :food-planets 0)
         mil-planets   (get empire :mil-planets 0)
         total-planets (+ ore-planets food-planets mil-planets)

         population (get empire :population 0)
         soldiers   (get empire :soldiers 0)
         generals   (get empire :generals 0)
         transports (get empire :transports 0)
         fighters   (get empire :fighters 0)
         admirals   (get empire :admirals 0)
         carriers   (get empire :carriers 0)
         stations   (get empire :stations 0)
         cmd-ships  (get empire :cmd-ships 0)
         agents     (get empire :agents 0)

         starting-credits (get empire :credits const/starting-credits)
         starting-food    (get empire :food    const/starting-food)
         starting-fuel    (get empire :fuel    const/starting-fuel)
         liquidate?       (get empire :liquidate-military-output? false)

         ;; Military production (relevant for liquidation)
         mil-soldiers-produced (* mil-planets (:mil-planet-soldiers c))
         mil-fighters-produced (* mil-planets (:mil-planet-fighters c))
         mil-stations-produced (* mil-planets (:mil-planet-stations c))
         mil-agents-produced   (* mil-planets (:mil-planet-agents c))

         ;; Income
         credits-from-ore         (* ore-planets (:ore-planet-credits c))
         credits-from-liquidation (if liquidate?
                                    (+ (* mil-soldiers-produced (:soldier-sell c))
                                       (* mil-fighters-produced (:fighter-sell c))
                                       (* mil-stations-produced (:station-sell c))
                                       (* mil-agents-produced   (:agent-sell c)))
                                    0)
         credits-in (+ credits-from-ore credits-from-liquidation)

         food-in (* food-planets (:food-planet-food c))
         fuel-in (* ore-planets  (:ore-planet-fuel c))

         ;; Expenses — credits (transports, generals, carriers, admirals, cmd-ships are free)
         credits-out (+ (* total-planets (:planet-upkeep-credits c))
                        (* soldiers  (:soldier-upkeep-credits c))
                        (* fighters  (:fighter-upkeep-credits c))
                        (* stations  (:station-upkeep-credits c)))

         ;; Expenses — food
         food-out (+ (* total-planets (:planet-upkeep-food c))
                     (* soldiers   (:soldier-upkeep-food c))
                     (* agents     (:agent-upkeep-food c))
                     (* population (:population-upkeep-food c)))

         ;; Expenses — fuel (transports, carriers, cmd-ships are free)
         fuel-out (+ (* fighters  (:fighter-upkeep-fuel c))
                     (* stations  (:station-upkeep-fuel c))
                     (* agents    (:agent-upkeep-fuel c))
                     (* population (:population-upkeep-fuel c)))

         net-credits (- credits-in credits-out)
         net-food    (- food-in food-out)
         net-fuel    (- fuel-in fuel-out)

         ;; How many rounds until each stockpile runs out at current burn rate
         rounds-until-bankrupt
         (when (neg? net-credits)
           (long (Math/floor (/ starting-credits (Math/abs (double net-credits))))))

         rounds-until-food-shortage
         (when (neg? net-food)
           (long (Math/floor (/ starting-food (Math/abs (double net-food))))))

         rounds-until-fuel-shortage
         (when (neg? net-fuel)
           (long (Math/floor (/ starting-fuel (Math/abs (double net-fuel))))))

         ;; Balanced = no resource is running a deficit this round
         balanced? (and (>= net-credits 0) (>= net-food 0) (>= net-fuel 0))

         ;; Viable = balanced OR all deficits can be sustained from stockpiles
         ;; for at least one full day (rounds-per-day rounds)
         viable? (or balanced?
                     (and (or (nil? rounds-until-bankrupt)
                              (>= rounds-until-bankrupt const/rounds-per-day))
                          (or (nil? rounds-until-food-shortage)
                              (>= rounds-until-food-shortage const/rounds-per-day))
                          (or (nil? rounds-until-fuel-shortage)
                              (>= rounds-until-fuel-shortage const/rounds-per-day))))

         cheapest-planet (min (:ore-planet-cost c) (:food-planet-cost c) (:mil-planet-cost c))

         ;; How many rounds until the empire can afford the cheapest planet.
         ;; Accounts for credits already on hand — if already affordable, returns 0.
         ;; nil if net-credits <= 0 (can never save up) or if the empire is not viable
         ;; (reporting an expansion timeline when a shortage arrives first is misleading).
         rounds-until-expansion
         (when (and viable? (pos? net-credits))
           (let [shortfall (- cheapest-planet starting-credits)]
             (if (<= shortfall 0)
               0
               (long (Math/ceil (/ shortfall (double net-credits)))))))]

     {:credits-in                  credits-in
      :credits-from-ore            credits-from-ore
      :credits-from-liquidation    credits-from-liquidation
      :credits-out                 credits-out
      :food-in                     food-in
      :food-out                    food-out
      :fuel-in                     fuel-in
      :fuel-out                    fuel-out
      :net-credits                 net-credits
      :net-food                    net-food
      :net-fuel                    net-fuel
      :mil-soldiers-produced       mil-soldiers-produced
      :mil-fighters-produced       mil-fighters-produced
      :mil-stations-produced       mil-stations-produced
      :mil-agents-produced         mil-agents-produced
      :rounds-until-bankrupt       rounds-until-bankrupt
      :rounds-until-food-shortage  rounds-until-food-shortage
      :rounds-until-fuel-shortage  rounds-until-fuel-shortage
      :rounds-until-expansion      rounds-until-expansion
      :balanced?                   balanced?
      :viable?                     viable?})))

;;
;; Named scenarios — run from REPL with (simulate-empire scenario-a) etc.
;;

(def scenario-a
  "Starting Empire — opening viability"
  {:ore-planets 1 :food-planets 1 :mil-planets 0
   :population 6 :soldiers 100 :generals 2 :carriers 1})

(def scenario-b
  "Ore-Spam Player — economic snowball test"
  {:ore-planets 4 :food-planets 1 :mil-planets 0
   :population 6 :soldiers 100 :generals 2 :carriers 1})

(def scenario-c
  "Military Buildup Player — war economy test"
  {:ore-planets 2 :food-planets 1 :mil-planets 2
   :population 6 :soldiers 300 :generals 4
   :transports 3 :fighters 50 :admirals 1 :carriers 1})

(def scenario-d
  "Food-Neglect Player — food pressure test"
  {:ore-planets 3 :food-planets 0 :mil-planets 0
   :population 6 :soldiers 100 :generals 2 :carriers 1})

(def scenario-e
  "Liquidation Exploit — military output as credit engine"
  {:ore-planets 0 :food-planets 1 :mil-planets 3
   :population 6
   :liquidate-military-output? true})

(defn print-report
  "Print a readable simulation report to stdout."
  [label empire]
  (let [r (simulate-empire empire)]
    (println (str "\n=== " label " ==="))
    (println (format "  Credits  in/out/net: %,d / %,d / %,d"
                     (:credits-in r) (:credits-out r) (:net-credits r)))
    (when (pos? (:credits-from-liquidation r))
      (println (format "    ore: %,d   liquidation: %,d"
                       (:credits-from-ore r) (:credits-from-liquidation r))))
    (println (format "  Food     in/out/net: %,d / %,d / %,d"
                     (:food-in r) (:food-out r) (:net-food r)))
    (println (format "  Fuel     in/out/net: %,d / %,d / %,d"
                     (:fuel-in r) (:fuel-out r) (:net-fuel r)))
    (when (pos? (+ (:mil-soldiers-produced r) (:mil-fighters-produced r)
                   (:mil-stations-produced r) (:mil-agents-produced r)))
      (println (format "  Mil output/round   : %,d soldiers  %,d fighters  %,d stations  %,d agents"
                       (:mil-soldiers-produced r) (:mil-fighters-produced r)
                       (:mil-stations-produced r) (:mil-agents-produced r))))
    (println (str   "  Balanced?          : " (:balanced? r)))
    (println (str   "  Viable?            : " (:viable? r)))
    (when (:rounds-until-bankrupt r)
      (println (str "  Bankrupt in         : " (:rounds-until-bankrupt r) " rounds")))
    (when (:rounds-until-food-shortage r)
      (println (str "  Food shortage in    : " (:rounds-until-food-shortage r) " rounds")))
    (when (:rounds-until-fuel-shortage r)
      (println (str "  Fuel shortage in    : " (:rounds-until-fuel-shortage r) " rounds")))
    (when (some? (:rounds-until-expansion r))
      (if (zero? (:rounds-until-expansion r))
        (println "  First expansion in  : now (credits on hand)")
        (println (str "  First expansion in  : " (:rounds-until-expansion r) " rounds"))))
    r))

(defn run-all-scenarios []
  (print-report "A — Starting Empire"       scenario-a)
  (print-report "B — Ore Spam"              scenario-b)
  (print-report "C — Military Buildup"      scenario-c)
  (print-report "D — Food Neglect"          scenario-d)
  (print-report "E — Liquidation Exploit"   scenario-e))
