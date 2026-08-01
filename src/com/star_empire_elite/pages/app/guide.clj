;;;;;
;;;;; Guide - Game Reference Page
;;;;;
;;;;; A single scrollable reference documenting all game mechanics. Constants are read
;;;;; directly from the game entity so the page reflects the exact values in effect
;;;;; for this game instance (which can differ from the defaults in constants.clj).
;;;;;
;;;;; Sections are private fns returning hiccup, composed by guide-page at the bottom.
;;;;; Each section has an :id attribute so the table of contents can link to it.
;;;;;

(ns com.star-empire-elite.pages.app.guide
  (:require [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui        :as ui]))

;;;;
;;;; Helpers
;;;;

(defn- g
  "Read a constant from the game entity, falling back to a default.
  Game entity values take precedence because they are snapshotted at game-creation time
  and may differ from the current constants.clj defaults.

  [game game-map, k keyword, fallback any] -> any"
  [game k fallback]
  (if (contains? game k) (get game k) fallback))

(defn- v
  "Wrap a value in highlighted terminal-green text for inline constants.

  [x any] -> hiccup"
  [x]
  [:span.text-green-300.font-bold (str x)])

(defn- pct
  "Format a fraction as a percentage string, e.g. 0.15 -> \"15%\".

  [x number] -> str"
  [x]
  (str (Math/round (* 100.0 x)) "%"))

(defn- pct1
  "Format a fraction as a percentage string with one decimal place, e.g. 0.001 -> \"0.1%\".
  Use instead of pct when the value may be less than 0.5% and rounding to 0% would mislead.

  [x number] -> str"
  [x]
  (format "%.1f%%" (* 100.0 x)))

(defn- guide-table
  "Render a compact data table styled to match the terminal aesthetic.
  headers is a seq of column-header strings; rows is a seq of seqs of hiccup or strings.

  [headers [str], rows [[hiccup]]] -> hiccup"
  [headers rows]
  [:table.w-full.text-sm.font-mono
   {:class "border-collapse mt-1 mb-3"}
   [:thead
    (into [:tr]
          (map (fn [h]
                 [:th.text-left.pb-1
                  {:class "text-game-green-muted text-[11px] tracking-[0.08em] uppercase pr-4 border-b border-game-border"}
                  h])
               headers))]
   [:tbody
    (map-indexed
     (fn [i row]
       (into [:tr {:class (if (even? i) "bg-transparent" "bg-game-green-deep bg-opacity-20")}]
             (map (fn [cell]
                    [:td.py-1
                     {:class "text-game-green-soft pr-4 border-b border-game-border border-opacity-40"}
                     cell])
                  row)))
     rows)]])

(defn- prose
  "Render a paragraph of guide text.

  [& content hiccup] -> hiccup"
  [& content]
  (into [:p.text-sm.leading-relaxed.mb-2 {:class "text-game-green-soft"}] content))

(defn- section
  "Wrap section content with an anchor id and a section-label heading.

  [id str, title str, subtitle str-or-nil, & body hiccup] -> hiccup"
  [id title subtitle & body]
  (into [:div.mb-6 {:id id}
         (ui/section-label title subtitle)]
        body))

;;;;
;;;; Table of Contents
;;;;

(defn- toc []
  (let [entries [["quick-start"     "Quick Start"]
                 ["overview"        "Overview"]
                 ["starting-empire" "Starting Empire"]
                 ["income"          "Income"]
                 ["expenses"        "Expenses"]
                 ["exchange"        "Exchange"]
                 ["building"        "Building"]
                 ["combat"          "Combat"]
                 ["strikes"         "Strikes"]
                 ["espionage"       "Espionage"]
                 ["population"      "Population"]
                 ["stability"       "Stability"]
                 ["fate"            "Fate"]
                 ["scoring"         "Scoring"]]]
    [:div.mb-6
     (ui/section-label "CONTENTS")
     [:div.flex.flex-col.gap-0.5
      (map (fn [[id label]]
             [:a.text-sm.font-mono.no-underline
              {:href  (str "#" id)
               :class "text-green-400 hover:text-green-300"}
              (str "→ " label)])
           entries)]]))

;;;;
;;;; Quick Start
;;;;

(defn- quick-start-section []
  (section "quick-start" "QUICK START" "the short version"
    [:ul.text-sm.leading-relaxed.mb-2.list-none.pl-0
     {:class "text-game-green-soft space-y-1"}
     (for [item ["You own planets. Planets produce credits, food, fuel, soldiers, fighters, and defence stations each turn."
                 "Spend credits to build military units. Soldiers fight on the ground, fighters fight in space."
                 "Attack other players to capture their planets and steal their resources."
                 "Keep your empire stable. If stability drops too low, planets break away on their own."
                 "The player with the highest score (not the largest number of planets) wins."]]
       [:li [:span.text-green-400.mr-2 "•"] item])]
    (prose
      "Everything below explains the details. You don't need to read it all on day one; "
      "just play a few turns and come back when something surprises you.")))

;;;;
;;;; Overview
;;;;

(defn- overview-section [game]
  (section "overview" "OVERVIEW" nil
    (prose
      "Star Empire Elite is a multiplayer turn-based space-conquest game. "
      "Each round consists of " (v (g game :game/turns-per-round const/turns-per-round))
      " turns. There are " (v (g game :game/rounds-per-day const/rounds-per-day))
      " rounds per day, opening every "
      (v (g game :game/hours-between-rounds const/hours-between-rounds)) " hours.")
    (prose "Each turn you work through six sequential phases:")
    (guide-table
     ["#" "Phase" "What you decide"]
     [["1" "Income"    "Review resources received from your planets and population"]
      ["2" "Expenses"  "Pay upkeep for your military and infrastructure"]
      ["3" "Exchange"  "Buy or sell resources and units on the open market"]
      ["4" "Building"  "Purchase new units, ships, stations, and planets"]
      ["5" "Action"    "Raid, invade, or strike an enemy empire"]
      ["6" "Espionage" "Assign agents to spy on, destabilise, bomb, or infiltrate a rival"]])
    (prose "After all six phases, Outcomes resolves combat results and advances the game state.")
    (prose
      "The " [:span.italic "highlighted values"] " throughout this guide are specific to this galaxy: "
      "different galaxies may use different settings.")))

;;;;
;;;; Starting Empire
;;;;

(defn- starting-empire-section []
  (section "starting-empire" "STARTING EMPIRE" "values every new player begins with"
    (guide-table
     ["Resource / Unit" "Starting Amount"]
     [["Credits"         (v (ui/format-number const/starting-credits))]
      ["Food"            (v (ui/format-number const/starting-food))]
      ["Fuel"            (v (ui/format-number const/starting-fuel))]
      ["Population"      (v (str const/starting-population "M"))]
      ["Stability"       (v (str const/starting-stability "%"))]
      ["Erg planets"     (v const/starting-erg-planets)]
      ["Ore planets"     (v const/starting-ore-planets)]
      ["Mil planets"     (v const/starting-mil-planets)]
      ["Soldiers"        (v (ui/format-number const/starting-soldiers))]
      ["Transports"      (v const/starting-transports)]
      ["Generals"        (v const/starting-generals)]
      ["Fighters"        (v const/starting-fighters)]
      ["Carriers"        (v const/starting-carriers)]
      ["Admirals"        (v const/starting-admirals)]
      ["Stations"        (v const/starting-stations)]
      ["Command ships"   (v const/starting-cmd-ships)]
      ["Agents"          (v const/starting-agents)]])))

;;;;
;;;; Income
;;;;

(defn- income-section [game]
  (section "income" "INCOME" "resources generated automatically each turn"
    (prose
      "At the start of each turn your planets and population pay into your stockpiles. "
      "You cannot influence the amounts; income is automatic.")
    (guide-table
     ["Source"              "Credits"                                          "Food"                                          "Fuel"                                          "Soldiers"                                      "Fighters"                                      "Stations"]
     [["Each ore planet"    (v (ui/format-number (g game :game/ore-planet-credits const/ore-planet-credits))) "—"   "—"   "—"  "—"  "—"]
      ["Each erg planet"    "—"    (v (ui/format-number (g game :game/erg-planet-food const/erg-planet-food)))   (v (ui/format-number (g game :game/erg-planet-fuel const/erg-planet-fuel)))   "—"  "—"  "—"]
      ["Each mil planet"    "—"    "—"    "—"   (v (g game :game/mil-planet-soldiers const/mil-planet-soldiers))  (v (g game :game/mil-planet-fighters const/mil-planet-fighters))  (v (g game :game/mil-planet-stations const/mil-planet-stations))]
      ["Per million pop."   (v (ui/format-number (g game :game/population-tax-credits const/population-tax-credits))) "—" "—" "—" "—" "—"]])))

;;;;
;;;; Expenses
;;;;

(defn- expenses-section [game]
  (section "expenses" "EXPENSES" "mandatory upkeep paid every turn"
    (prose
      "After income, you must pay upkeep. You set what fraction you actually pay (0–100%). "
      "Paying less than full upkeep costs " (v (g game :game/expense-stability-penalty const/expense-stability-penalty))
      " stability per underpaid fraction; so skipping upkeep is dangerous (see Stability).")
    (guide-table
     ["Unit / Asset"  "Credits / turn"  "Food / turn"  "Fuel / turn"]
     [["Each planet"    (v (ui/format-number (g game :game/planet-upkeep-credits const/planet-upkeep-credits)))   (v (ui/format-number (g game :game/planet-upkeep-food const/planet-upkeep-food)))   "—"]
      ["Each soldier"   (v (ui/format-number (g game :game/soldier-upkeep-credits const/soldier-upkeep-credits)))  (v (ui/format-number (g game :game/soldier-upkeep-food const/soldier-upkeep-food)))   "—"]
      ["Each fighter"   (v (ui/format-number (g game :game/fighter-upkeep-credits const/fighter-upkeep-credits)))  "—"   (v (ui/format-number (g game :game/fighter-upkeep-fuel const/fighter-upkeep-fuel)))]
      ["Each station"   (v (ui/format-number (g game :game/station-upkeep-credits const/station-upkeep-credits)))  "—"   (v (ui/format-number (g game :game/station-upkeep-fuel const/station-upkeep-fuel)))]
      ["Each agent"     "—"   (v (g game :game/agent-upkeep-food const/agent-upkeep-food))   (v (g game :game/agent-upkeep-fuel const/agent-upkeep-fuel))]
      ["Per million pop." "—" (v (ui/format-number (g game :game/population-upkeep-food const/population-upkeep-food))) (v (ui/format-number (g game :game/population-upkeep-fuel const/population-upkeep-fuel)))]])
    (prose "Transports, carriers, generals, admirals, and command ships have no recurring upkeep cost.")))

;;;;
;;;; Building
;;;;

(defn- building-section [game]
  (section "building" "BUILDING" "one-time purchase costs"
    (prose "Spend credits during the Building phase to purchase units and grow your empire.")
    (prose
      "The Projected Resources panel estimates your credits, food, and fuel at the "
      "start of next turn, after income and expenses are applied to whatever you build. "
      "It is an estimate that does not account for the outcomes of actions, espionage, "
      "or population growth this turn, because those numbers aren't determined yet.")
    (guide-table
     ["Unit / Asset"  "Purchase Cost"]
     [["Soldier"       (v (ui/format-number (g game :game/soldier-cost   const/soldier-cost)))]
      ["Transport"     (v (ui/format-number (g game :game/transport-cost  const/transport-cost)))]
      ["General"       (v (ui/format-number (g game :game/general-cost    const/general-cost)))]
      ["Fighter"       (v (ui/format-number (g game :game/fighter-cost    const/fighter-cost)))]
      ["Carrier"       (v (ui/format-number (g game :game/carrier-cost    const/carrier-cost)))]
      ["Admiral"       (v (ui/format-number (g game :game/admiral-cost    const/admiral-cost)))]
      ["Station"       (v (ui/format-number (g game :game/station-cost    const/station-cost)))]
      ["Command ship"  (v (ui/format-number (g game :game/cmd-ship-cost   const/cmd-ship-cost)))]
      ["Mil planet"    (v (ui/format-number (g game :game/mil-planet-cost const/mil-planet-cost)))]
      ["Erg planet"    (v (ui/format-number (g game :game/erg-planet-cost const/erg-planet-cost)))]
      ["Ore planet"    (v (ui/format-number (g game :game/ore-planet-cost const/ore-planet-cost)))]
      ["Agent"         (v (ui/format-number (g game :game/agent-cost      const/agent-cost)))]])))

;;;;
;;;; Exchange
;;;;

(defn- exchange-section [game]
  (section "exchange" "EXCHANGE" "buy and sell resources and assets"
    (prose
      "The exchange lets you convert between credits and other resources. "
      "Sell prices are always lower than buy prices; there is no arbitrage.")
    (guide-table
     ["Asset"          "Buy (credits)"  "Sell (credits)"]
     [["Soldier"       "—"              (v (g game :game/soldier-sell   const/soldier-sell))]
      ["Transport"     "—"              (v (g game :game/transport-sell  const/transport-sell))]
      ["General"       "—"              (v (g game :game/general-sell    const/general-sell))]
      ["Fighter"       "—"              (v (g game :game/fighter-sell    const/fighter-sell))]
      ["Carrier"       "—"              (v (g game :game/carrier-sell    const/carrier-sell))]
      ["Admiral"       "—"              (v (g game :game/admiral-sell    const/admiral-sell))]
      ["Station"       "—"              (v (g game :game/station-sell    const/station-sell))]
      ["Command ship"  "—"              (v (g game :game/cmd-ship-sell   const/cmd-ship-sell))]
      ["Mil planet"    "—"              (v (g game :game/mil-planet-sell const/mil-planet-sell))]
      ["Erg planet"    "—"              (v (g game :game/erg-planet-sell const/erg-planet-sell))]
      ["Ore planet"    "—"              (v (g game :game/ore-planet-sell const/ore-planet-sell))]
      ["Agent"         "—"              (v (g game :game/agent-sell      const/agent-sell))]
      ["Food"          (v (str (g game :game/food-buy const/food-buy) " / unit"))   (v (str (g game :game/food-sell const/food-sell) " / unit"))]
      ["Fuel"          (v (str (g game :game/fuel-buy const/fuel-buy) " / unit"))   (v (str (g game :game/fuel-sell const/fuel-sell) " / unit"))]])))

;;;;
;;;; Population
;;;;

(defn- population-section []
  (section "population" "POPULATION" "stored in millions"
    (prose
      "At the end of each round, population grows. Growth is additive: "
      (v (pct const/pop-growth-rate)) " of current population "
      "plus " (v (str (* const/pop-growth-per-planet 1000) "k")) " per planet you own, "
      "scaled by a random factor between "
      (v (pct const/pop-random-min)) " and " (v (pct const/pop-random-max)) ".")
    (prose
      "Population is capped at " (v (str (int const/pop-capacity-per-planet) "M")) " per planet. "
      "Population pays " (v (ui/format-number const/population-tax-credits))
      " credits per million per turn, but also consumes "
      (v (ui/format-number const/population-upkeep-food)) " food and "
      (v (ui/format-number const/population-upkeep-fuel)) " fuel per million per turn. "
      "A large population is powerful but expensive to feed.")))

;;;;
;;;; Stability
;;;;

(defn- stability-section [game]
  (section "stability" "STABILITY" "your empire's internal cohesion (0–100%)"
    (prose
      "Stability represents how firmly your planets are under your control. "
      "At the end of each round, the game rolls d100. "
      "If the roll exceeds your stability plus "
      (v (g game :game/stability-breakaway-threshold const/stability-breakaway-threshold))
      ", one or more planets break away, up to "
      (v (str (g game :game/stability-breakaway-cap const/stability-breakaway-cap) "%"))
      " of your total planet count.")
    (prose
      "The same roll can trigger recovery: if the result falls below "
      "max(your stability, "
      (v (g game :game/stability-recovery-floor const/stability-recovery-floor))
      "%), you gain " (v (g game :game/stability-recovery-amount const/stability-recovery-amount))
      " stability.")
    (guide-table
     ["Event"                            "Stability Change"]
     [["Planets captured from you"       (v (str "-" (g game :game/capture-stability-penalty-per-planet const/capture-stability-penalty-per-planet) " per planet (cap " (g game :game/capture-stability-penalty-cap const/capture-stability-penalty-cap) ")"))]
      ["Underpaying expenses"            (v (str "-" (g game :game/expense-stability-penalty const/expense-stability-penalty) " per underpaid fraction"))]
      ["Successful incite operation"     "−up to 8 pts (see Espionage)"]
      ["Recovery roll succeeds"          (v (str "+" (g game :game/stability-recovery-amount const/stability-recovery-amount) " pts"))]])
    (prose
      "Stability cannot be incited below "
      (v (str (g game :game/incite-stability-floor const/incite-stability-floor) "%"))
      " by espionage alone. This floor keeps rivals from using agents to trigger a breakaway cascade. "
      "But cumulative combat losses and unpaid expenses can push you there.")))

;;;;
;;;; Combat
;;;;

(defn- combat-section [game]
  (section "combat" "COMBAT" "raids, invasions, and the two-phase battle model"
    (prose
      "Combat resolves in two sequential phases: space (fighters vs. fighters) "
      "then ground (soldiers vs. soldiers). Each phase computes a power total, "
      "applies a ±" (v (pct (g game :game/combat-variance const/combat-variance)))
      " random variance, and the higher total wins.")

    (ui/section-label "UNIT POWER" "contribution per unit")
    (guide-table
     ["Unit"          "Phase"     "Power"]
     [["Soldier"       "Ground"   (v (g game :game/soldier-power   const/soldier-power))]
      ["Fighter"       "Space"    (v (g game :game/fighter-power    const/fighter-power))]
      ["Command ship"  "Space"    (v (g game :game/cmd-ship-power   const/cmd-ship-power))]
      ["Station"       "Space"    (v (str (g game :game/station-power const/station-power) " (defender only)"))]
      ["General"       "Ground"   (v (g game :game/general-power    const/general-power))]
      ["Admiral"       "Space"    (v (g game :game/admiral-power    const/admiral-power))]])

    (ui/section-label "LEADERSHIP MULTIPLIERS" "additive bonus on top of base 1.0")
    (prose
      "Generals, admirals, and agents each add a percentage bonus to their respective phase. "
      "Each bonus is individually capped; building beyond the cap gives no further advantage.")
    (guide-table
     ["Leader / Unit"  "Phase"   "Bonus per unit"                                                        "Cap"]
     [["General"        "Ground" (v (pct (g game :game/general-mult-rate const/general-mult-rate)))       (v (pct (g game :game/general-mult-cap const/general-mult-cap)))]
      ["Admiral"        "Space"  (v (pct (g game :game/admiral-mult-rate const/admiral-mult-rate)))       (v (pct (g game :game/admiral-mult-cap const/admiral-mult-cap)))]
      ["Agent"          "Ground" (v (pct (g game :game/agent-mult-rate   const/agent-mult-rate)))         (v (pct (g game :game/agent-mult-cap   const/agent-mult-cap)))]])
    (prose
      "A decisive space victory carries over into the ground phase: winning space by a large margin "
      "adds up to " (v (pct (g game :game/space-carryover const/space-carryover)))
      " bonus ground power for the space victor.")

    (ui/section-label "COMMAND CAPACITY" "soft caps on army and fleet size")
    (prose
      "Generals and admirals impose soft limits. Units beyond the cap still fight, "
      "but you lose the leadership multiplier benefit for them.")
    (guide-table
     ["Leader"     "Capacity"                                                                  "Transport"  "Transport capacity"]
     [["General"   (v (str (g game :game/soldiers-per-general const/soldiers-per-general) " soldiers"))   "Transport" (v (str (g game :game/soldiers-per-transport const/soldiers-per-transport) " soldiers"))]
      ["Admiral"   (v (str (g game :game/fighters-per-admiral const/fighters-per-admiral) " fighters"))   "Carrier"   (v (str (g game :game/fighters-per-carrier   const/fighters-per-carrier)  " fighters"))]])

    (ui/section-label "RAID VS. INVADE")
    (guide-table
     ["Mode"      "Defender engagement"                                                                "Planet capture cap"                                                                   "Resource capture cap"]
     [["Raid"     (v (pct (g game :game/raid-defense-multiplier   const/raid-defense-multiplier)))    (v (pct (g game :game/raid-planet-capture-cap    const/raid-planet-capture-cap)))       (v (pct (g game :game/raid-resource-capture-cap   const/raid-resource-capture-cap)))]
      ["Invade"   (v (pct (g game :game/invade-defense-multiplier const/invade-defense-multiplier)))  (v (pct (g game :game/invade-planet-capture-cap   const/invade-planet-capture-cap)))     (v (pct (g game :game/invade-resource-capture-cap const/invade-resource-capture-cap)))]])
    (prose
      "Raids hit only a fraction of the defender's forces, limiting risk and reward. "
      "Invasions are all-in: the defender commits everything, but so does the attacker. "
      "Actual planets and resources captured scale with the ground combat margin; "
      "a narrow win takes far less than the cap.")

    (ui/section-label "COMBAT LOSSES")
    (prose
      "Both sides suffer losses proportional to the margin of defeat. "
      "In an even fight both sides lose ~" (v (pct (g game :game/combat-loss-floor const/combat-loss-floor)))
      " of their forces. As the margin grows, the loser's losses climb toward "
      (v (pct (g game :game/combat-loser-cap const/combat-loser-cap)))
      " while the winner's losses fall toward zero.")))

;;;;
;;;; Strikes
;;;;

(defn- strikes-section [game]
  (section "strikes" "STRIKES" "standoff attacks using command ships"
    (prose
      "A strike is a ranged attack: no ground combat, no planet capture. "
      "You dispatch up to " (v (g game :game/strike-max-dispatch const/strike-max-dispatch))
      " command ships. Each ship destroys "
      (v (pct (g game :game/strike-damage-rate const/strike-damage-rate)))
      " of each defender military unit type (soldiers, transports, fighters, carriers, stations).")
    (prose
      "Defenders intercept with stations. Each station adds "
      (v (pct1 (g game :game/strike-interception-rate const/strike-interception-rate)))
      " interception chance per attacking command ship, up to a cap of "
      (v (pct (g game :game/strike-interception-cap const/strike-interception-cap)))
      " per ship. Intercepted ships deal no damage.")))

;;;;
;;;; Espionage
;;;;

(defn- espionage-section [game]
  (section "espionage" "ESPIONAGE" "covert operations via agents"
    (prose
      "If you have at least one agent, you can assign them to one operation against "
      "a target empire each turn. Resolution is a contested roll: your agents (±"
      (v (pct (g game :game/combat-variance const/combat-variance)))
      ") vs the defender's effective agents (±"
      (v (pct (g game :game/combat-variance const/combat-variance)))
      "). On failure, a fraction of your agents are lost.")

    (ui/section-label "OPERATIONS")
    (guide-table
     ["Operation"  "On Success"                                                       "Agents lost on failure (min 1)"]
     [["Spy"       "Full military snapshot of target (units, agents)"                (v (pct const/spy-defection-rate))]
      ["Incite"    (str "Reduce target stability up to " const/incite-stability-damage " pts (floor " const/incite-stability-floor "%)")  (v (pct const/incite-defection-rate))]
      ["Bomb"      (str "Destroy " (pct const/bomb-damage-rate) " of soldiers, transports, fighters, carriers") (v (pct const/bomb-defection-rate))]
      ["Defect"    (str "Turn " (pct const/defect-transfer-rate) " of defender's agents (or yours, whichever is fewer)") (v (pct const/defect-defection-rate))]])

    (prose
      "Failed bomb agents are destroyed outright. Failed spy/incite/defect agents "
      "defect to the defender.")

    (ui/section-label "DEFENSE — DIMINISHING RETURNS")
    (prose
      "Defenders do not benefit linearly from hoarding agents. Up to "
      (v const/espionage-defense-threshold)
      " agents defend at full strength. Beyond that, surplus agents contribute with "
      "diminishing returns (effective = "
      (v const/espionage-defense-threshold)
      " + surplus^" (v const/espionage-defense-exponent) "). "
      "At 1,000 defending agents the effective count is roughly 364, not 1,000.")

    (ui/section-label "DEFECT — SPECIAL RULE")
    (prose
      "For the Defect operation only, the defender commits just "
      (v (pct (g game :game/defect-defense-multiplier const/defect-defense-multiplier)))
      " of their agents to defense. This makes Defect viable against agent-heavy empires. "
      "On success, you receive "
      (v (pct (g game :game/defect-transfer-rate const/defect-transfer-rate)))
      " of the smaller of your agent count and the defender's; "
      "a big spy network is required to absorb a big defection.")))

;;;;
;;;; Fate
;;;;

(defn- fate-row
  "One row in the fate table: event name, what it affects, and its lo–hi multiplier range.
  basis-label is a short phrase like \"one turn's food production\" or \"current population\".

  [label str, affects str, basis-label str, lo number, hi number] -> [hiccup hiccup hiccup]"
  [label affects basis-label lo hi]
  [label affects [:span (v (pct lo)) "–" (v (pct hi)) " of " basis-label]])

(defn- fate-section [game]
  (let [;; Shared cell and sub-header styles, inlined because this is the only table
        ;; that needs disaster/boon group rows interspersed with data rows.
        cell-cls    "text-game-green-soft pr-4 border-b border-game-border border-opacity-40"
        sub-hdr-cls "text-game-green-dim text-[11px] tracking-[0.08em] uppercase pt-3 pb-1"
        data-rows   (fn [rows]
                      (map-indexed
                       (fn [i row]
                         (into [:tr {:class (if (even? i) "bg-transparent" "bg-game-green-deep bg-opacity-20")}]
                               (map #(vector :td.py-1 {:class cell-cls} %) row)))
                       rows))]
    (section "fate" "FATE" "random disasters and boons resolved at outcomes"
      (prose
        "At the end of each turn, fate may intervene. There is a "
        (v (pct (g game :game/fate-probability const/fate-probability)))
        " chance that your empire is struck by a disaster or granted a boon. "
        "The result is applied immediately and appears in your Outcomes summary.")
      (prose
        "Which you receive (disaster or boon) depends on your standing. "
        "Leaders draw disasters more often; trailers draw boons more often. "
        "The tilt is not absolute: even the top-ranked empire has roughly a "
        (v "30%") " chance of a boon, and the last-place empire still faces roughly a "
        (v "30%") " chance of disaster.")
      ;; Single table keeps Event / Affects / Magnitude columns aligned across both groups.
      [:table.w-full.text-sm.font-mono
       {:class "border-collapse mt-1 mb-3"}
       [:thead
        [:tr
         (for [h ["Event" "Affects" "Magnitude"]]
           [:th.text-left.pb-1
            {:class "text-game-green-muted text-[11px] tracking-[0.08em] uppercase pr-4 border-b border-game-border"}
            h])]]
       [:tbody
        [:tr [:td {:colspan 3 :class sub-hdr-cls} "Disasters"]]
        (data-rows
         [(fate-row "Drought"     "Food"       "one turn's food production" const/fate-drought-lo       const/fate-drought-hi)
          (fate-row "Pirate Raid" "Credits"    "current credit holdings"    const/fate-pirate-raid-lo   const/fate-pirate-raid-hi)
          (fate-row "Solar Flare" "Units"      "each unit type's holdings"  const/fate-solar-flare-lo   const/fate-solar-flare-hi)
          (fate-row "Plague"      "Population" "current population"         const/fate-plague-lo        const/fate-plague-hi)])
        [:tr [:td {:colspan 3 :class sub-hdr-cls} "Boons"]]
        (data-rows
         [(fate-row "Bumper Harvest" "Food"       "one turn's food production"    const/fate-bumper-harvest-lo  const/fate-bumper-harvest-hi)
          (fate-row "Gold Rush"      "Credits"    "one turn's credit production"  const/fate-gold-rush-lo       const/fate-gold-rush-hi)
          (fate-row "Foreign Aid"    "Credits"    "one turn's credit production"  const/fate-foreign-aid-lo     const/fate-foreign-aid-hi)
          (fate-row "Migration Wave" "Population" "current population"            const/fate-migration-wave-lo  const/fate-migration-wave-hi)])]]
      (prose "Fate events are visible to all players in the News feed. "))))

;;;;
;;;; Scoring
;;;;

(defn- scoring-section []
  (section "scoring" "SCORING" "how your empire score is calculated"
    (prose
      "Your score is the sum of all assets multiplied by their point weights. "
      "It updates each turn and determines leaderboard rank.")
    (guide-table
     ["Asset"         "Points"]
     [["Population"   (v (str const/score-population " / million"))]
      ["Mil planet"   (v const/score-mil-planet)]
      ["Erg planet"   (v const/score-erg-planet)]
      ["Ore planet"   (v const/score-ore-planet)]
      ["Soldier"      (v const/score-soldier)]
      ["Transport"    (v const/score-transport)]
      ["General"      (v const/score-general)]
      ["Fighter"      (v const/score-fighter)]
      ["Carrier"      (v const/score-carrier)]
      ["Admiral"      (v const/score-admiral)]
      ["Command ship" (v const/score-cmd-ship)]
      ["Station"      (v const/score-station)]
      ["Agent"        (v const/score-agent)]])))

;;;;
;;;; Public Page
;;;;

(defn guide-page
  "Render the game guide: a single scrollable reference page with live game constants.

  [player player-map, game game-map] -> hiccup"
  [player game]
  (ui/phase-shell player game "GAME GUIDE"
    [:div.flex.flex-col
     {:class "py-2.5 px-3.5"}
     (toc)
     (quick-start-section)
     (overview-section game)
     (starting-empire-section)
     (income-section game)
     (expenses-section game)
     (building-section game)
     (exchange-section game)
     (population-section)
     (stability-section game)
     (combat-section game)
     (strikes-section game)
     (espionage-section game)
     (fate-section game)
     (scoring-section)]
    (ui/phase-action-bar
      (ui/action-bar-link (str "/app/game/" (:xt/id player)) "Back to Game"))))
