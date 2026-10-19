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
  "Wrap a value in highlighted terminal-green text for inline constants. ui/format-number
  returns hiccup (not a plain string) for values >= 100K, to carry a hover tooltip with the
  exact number — pass that through as a child instead of stringifying it, or the literal
  hiccup form would render as visible text instead of the tooltip span.

  [x any] -> hiccup"
  [x]
  [:span.text-green-300.font-bold (if (vector? x) x (str x))])

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

(defn- phase-banner
  "Render a divider banner marking the start of a new turn phase in the page body, grouping
  the one or more sections that follow. Heavier than section-label (which headers each
  individual section) since it's marking a bigger structural break. id is optional — only
  needed when the Contents links to this banner as a single entry spanning more than one
  section (Action covers Combat and Strikes; Outcomes covers Population, Stability, Fate,
  and Scoring).

  [label str, id str-or-nil] -> hiccup"
  [label & [id]]
  [:div.mt-8.mb-3.pt-3
   (cond-> {:class "border-t border-game-border"} id (assoc :id id))
   [:span.text-sm.font-bold.uppercase.text-green-400 {:class "tracking-[0.15em]"} label]])

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
                 ["action"          "Action"]
                 ["espionage"       "Espionage"]
                 ["outcomes"        "Outcomes"]]]
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
      (v (g game :game/minutes-between-rounds const/minutes-between-rounds)) " minutes.")
    (prose "Each turn you work through six sequential phases:")
    (guide-table
     ["#"  "Phase"     "What you decide"]
     [["1"  "Income"    "Review resources received from your planets and population"]
      ["2"  "Expenses"  "Pay upkeep for your military and infrastructure"]
      ["2a" "Exchange"  "Buy or sell resources and units on the open market"]
      ["3"  "Building"  "Purchase new units, ships, stations, and planets"]
      ["4"  "Action"    "Raid, invade, or strike an enemy empire"]
      ["5"  "Espionage" "Assign agents to spy on, destabilise, bomb, or infiltrate a rival"]
      ["6"  "Outcomes"  "Nothing — combat, population, stability, and fate resolve automatically"]])
    (prose
      "The " [:span.italic "highlighted values"] " throughout this guide are specific to this galaxy: "
      "different galaxies may use different settings.")))

;;;;
;;;; Starting Empire
;;;;

(defn- starting-empire-section [game]
  (section "starting-empire" "STARTING EMPIRE" "values every new player begins with"
    (guide-table
     ["Resource / Unit" "Starting Amount"]
     [["Credits"         (v (ui/format-number (g game :game/starting-credits const/starting-credits)))]
      ["Food"            (v (ui/format-number (g game :game/starting-food const/starting-food)))]
      ["Fuel"            (v (ui/format-number (g game :game/starting-fuel const/starting-fuel)))]
      ["Population"      (v (str (g game :game/starting-population const/starting-population) "M"))]
      ["Stability"       (v (str (g game :game/starting-stability const/starting-stability) "%"))]
      ["Erg planets"     (v (g game :game/starting-erg-planets const/starting-erg-planets))]
      ["Ore planets"     (v (g game :game/starting-ore-planets const/starting-ore-planets))]
      ["Mil planets"     (v (g game :game/starting-mil-planets const/starting-mil-planets))]
      ["Soldiers"        (v (ui/format-number (g game :game/starting-soldiers const/starting-soldiers)))]
      ["Transports"      (v (g game :game/starting-transports const/starting-transports))]
      ["Generals"        (v (g game :game/starting-generals const/starting-generals))]
      ["Fighters"        (v (g game :game/starting-fighters const/starting-fighters))]
      ["Carriers"        (v (g game :game/starting-carriers const/starting-carriers))]
      ["Admirals"        (v (g game :game/starting-admirals const/starting-admirals))]
      ["Stations"        (v (g game :game/starting-stations const/starting-stations))]
      ["Command ships"   (v (g game :game/starting-cmd-ships const/starting-cmd-ships))]
      ["Agents"          (v (g game :game/starting-agents const/starting-agents))]])))

;;;;
;;;; Income
;;;;

(defn- income-section [game]
  (let [bonus-rate (g game :game/planet-synergy-bonus-rate const/planet-synergy-bonus-rate)]
    (section "income" "INCOME" "resources generated automatically each turn"
      (prose
        "At the start of each turn your planets and population pay into your stockpiles. "
        "You cannot influence the amounts; income is automatic. The income page groups these "
        "into four cards: Ore, Erg, Mil, and Econ (population tax plus the synergy bonus below).")
      (guide-table
       ["Source"                  "Credits"                                          "Food"                                          "Fuel"                                          "Soldiers"                                      "Fighters"                                      "Stations"]
       [["Ore — each planet"      (v (ui/format-number (g game :game/ore-planet-credits const/ore-planet-credits))) "—"   "—"   "—"  "—"  "—"]
        ["Erg — each planet"      "—"    (v (ui/format-number (g game :game/erg-planet-food const/erg-planet-food)))   (v (ui/format-number (g game :game/erg-planet-fuel const/erg-planet-fuel)))   "—"  "—"  "—"]
        ["Mil — each planet"      "—"    "—"    "—"   (v (g game :game/mil-planet-soldiers const/mil-planet-soldiers))  (v (g game :game/mil-planet-fighters const/mil-planet-fighters))  (v (g game :game/mil-planet-stations const/mil-planet-stations))]
        ["Econ — per million pop." (v (ui/format-number (g game :game/population-tax-credits const/population-tax-credits))) "—" "—" "—" "—" "—"]
        ["Econ — synergy bonus"   (v (str "+0–" (pct bonus-rate) " of ore credits")) "—" "—" "—" "—" "—"]])
      (prose
        "Synergy bonus: ore credits get a bonus for keeping your ore/erg/mil planet counts "
        "close to equal — full ("
        (v (pct bonus-rate))
        ") at an exact match, tapering linearly to zero once your smallest planet type falls "
        "more than "
        (v (pct (g game :game/planet-synergy-threshold const/planet-synergy-threshold)))
        " below your largest."))))

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
      ["Agent"         (v (ui/format-number (g game :game/agent-cost      const/agent-cost)))]])
    (prose
      "Planet costs above are the base (1x) rate. Past "
      (v (ui/format-number (g game :game/planet-cost-ramp-x const/planet-cost-ramp-x)))
      " planets of one type, each further one costs more — 2x base at "
      (v (ui/format-number (g game :game/planet-cost-ramp-y const/planet-cost-ramp-y)))
      " owned, climbing beyond that.")))

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

(defn- population-section [game]
  (section "population" "POPULATION" "stored in millions"
    (prose
      "At the end of each round, population grows: "
      (v (pct (g game :game/pop-growth-rate const/pop-growth-rate))) " of current population "
      "plus " (v (str (* (g game :game/pop-growth-per-planet const/pop-growth-per-planet) 1000) "k")) " per planet you own, "
      "then scaled by a random factor between "
      (v (pct (g game :game/pop-random-min const/pop-random-min))) " and "
      (v (pct (g game :game/pop-random-max const/pop-random-max))) ".")
    (prose
      "That growth also shrinks the closer you get to capacity — "
      (v (str (int (g game :game/pop-capacity-per-planet const/pop-capacity-per-planet)) "M")) " per planet — "
      "tapering toward zero rather than cutting off outright, so you creep up on the limit "
      "instead of slamming into it.")
    (prose
      "Population pays " (v (ui/format-number (g game :game/population-tax-credits const/population-tax-credits)))
      " credits per million per turn, but also consumes "
      (v (ui/format-number (g game :game/population-upkeep-food const/population-upkeep-food))) " food and "
      (v (ui/format-number (g game :game/population-upkeep-fuel const/population-upkeep-fuel))) " fuel per million per turn. "
      "A large population is powerful but expensive to feed.")))

;;;;
;;;; Stability
;;;;

(defn- stability-section [game]
  (section "stability" "STABILITY" "your empire's internal cohesion (0–100%)"
    (prose
      "Stability represents how firmly your planets are under your control. "
      "At the end of each round, the game rolls d100 against breakaway: if the roll exceeds "
      "your stability plus "
      (v (g game :game/stability-breakaway-threshold const/stability-breakaway-threshold))
      ", your empire fractures: you lose at least one planet, and that same percentage — "
      "scaling up to "
      (v (str (g game :game/stability-breakaway-cap const/stability-breakaway-cap) "%"))
      " the further the roll clears that threshold — is also stripped from your population, "
      "credits, food, fuel, and military units/leaders.")
    (prose
      "A separate d100 roll checks for recovery — but only on a round where you paid your "
      "expenses in full and didn't just suffer a breakaway. If that roll falls below "
      "max(your stability, "
      (v (g game :game/stability-recovery-floor const/stability-recovery-floor))
      "%), you gain " (v (g game :game/stability-recovery-amount const/stability-recovery-amount))
      " stability.")
    (guide-table
     ["Event"                            "Stability Change"]
     [["Planets captured from you"       (v (str "-" (g game :game/capture-stability-penalty-per-planet const/capture-stability-penalty-per-planet) " per planet (cap " (g game :game/capture-stability-penalty-cap const/capture-stability-penalty-cap) ")"))]
      ["Underpaying expenses"            (v (str "-" (g game :game/expense-stability-penalty const/expense-stability-penalty) " per underpaid fraction"))]
      ["Successful incite operation"     (v (str "−up to " (g game :game/incite-stability-damage const/incite-stability-damage) " pts (see Espionage)"))]
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
      ["Station"       "Space"    (v (str (g game :game/station-power const/station-power) " (defender only)"))]])
    (prose
      "Generals and admirals aren't on this table — they carry no power of their own. "
      "Their entire contribution is the capped multiplier below.")

    (ui/section-label "LEADERSHIP MULTIPLIERS" "additive bonus on top of base 1.0")
    (prose
      "Generals, admirals, agents, and command ships each add a percentage bonus to their "
      "respective phase. For command ships this stacks on top of the direct unit power they "
      "also contribute (see Unit Power above). Each bonus is individually capped; building "
      "beyond the cap gives no further advantage.")
    (guide-table
     ["Leader / Unit"  "Phase"   "Bonus per unit"                                                        "Cap"]
     [["General"        "Ground" (v (pct (g game :game/general-mult-rate const/general-mult-rate)))       (v (pct (g game :game/general-mult-cap const/general-mult-cap)))]
      ["Admiral"        "Space"  (v (pct (g game :game/admiral-mult-rate const/admiral-mult-rate)))       (v (pct (g game :game/admiral-mult-cap const/admiral-mult-cap)))]
      ["Agent"          "Ground" (v (pct (g game :game/agent-mult-rate   const/agent-mult-rate)))         (v (pct (g game :game/agent-mult-cap   const/agent-mult-cap)))]
      ["Command ship"   "Space"  (v (pct (g game :game/cmd-ship-mult-rate const/cmd-ship-mult-rate)))     (v (pct (g game :game/cmd-ship-mult-cap const/cmd-ship-mult-cap)))]])
    (prose
      "A decisive space victory carries over into the ground phase: winning space by a large margin "
      "adds up to " (v (pct (g game :game/space-carryover const/space-carryover)))
      " bonus ground power for the space victor.")

    (ui/section-label "COMMAND CAPACITY" "hard caps on army and fleet size")
    (prose
      "Generals and admirals cap how many soldiers and fighters can actually fight — anyone "
      "beyond the cap sits out the battle entirely, contributing nothing. As an attacker you're "
      "also capped by transports and carriers; defenders skip that cap since their forces are "
      "already home.")
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
      "Both sides suffer losses that scale with the margin of victory. At an even margin, "
      "the loser takes " (v (pct (g game :game/combat-loss-floor const/combat-loss-floor)))
      " and the winner takes " (v (pct (g game :game/combat-winner-max const/combat-winner-max)))
      " — two separately tunable rates that happen to match by default. As the margin widens, "
      "the loser's rate climbs toward "
      (v (pct (g game :game/combat-loser-cap const/combat-loser-cap)))
      " while the winner's falls toward zero.")
    (prose
      "Command ships are the exception: being the most expensive unit in the game and held "
      "in far smaller numbers, they take only "
      (v (pct (g game :game/cmd-ship-loss-mult const/cmd-ship-loss-mult)))
      " of the normal loss rate.")))

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
      " of each defender military unit type (soldiers, transports, generals, fighters, carriers, admirals, stations).")
    (prose
      "Agents are dug in and dispersed, so a strike destroys them at only "
      (v (pct (g game :game/strike-agent-mult const/strike-agent-mult)))
      " of the normal rate. A strike is the only military action that can touch agents at all — "
      "invasions and raids never do (see Espionage for how agents are normally lost).")
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
     [["Spy"       "Full military snapshot of target (units, agents)"                (v (pct (g game :game/spy-defection-rate const/spy-defection-rate)))]
      ["Incite"    (str "Reduce target stability up to " (g game :game/incite-stability-damage const/incite-stability-damage) " pts (floor " (g game :game/incite-stability-floor const/incite-stability-floor) "%)")  (v (pct (g game :game/incite-defection-rate const/incite-defection-rate)))]
      ["Bomb"      (str "Destroy " (pct (g game :game/bomb-damage-rate-lo const/bomb-damage-rate-lo)) "-" (pct (g game :game/bomb-damage-rate-hi const/bomb-damage-rate-hi)) " of soldiers, transports, fighters, carriers") (v (pct (g game :game/bomb-defection-rate const/bomb-defection-rate)))]
      ["Defect"    (str "Turn " (pct (g game :game/defect-transfer-rate const/defect-transfer-rate)) " of defender's agents (or yours, whichever is fewer)") (v (pct (g game :game/defect-defection-rate const/defect-defection-rate)))]])

    (prose
      "Failed bomb agents are destroyed outright. Failed spy/incite/defect agents "
      "defect to the defender.")

    (ui/section-label "DEFENSE — DIMINISHING RETURNS")
    (let [threshold (g game :game/espionage-defense-threshold const/espionage-defense-threshold)
          exponent  (g game :game/espionage-defense-exponent const/espionage-defense-exponent)
          ;; Mirrors combat/effective-defense-agents' formula, for a live worked example
          ;; instead of a number that would drift once these fields became per-game editable.
          worked    (long (Math/round (+ threshold (Math/pow (- 1000 threshold) exponent))))]
      (prose
        "Defenders do not benefit linearly from hoarding agents. Up to "
        (v threshold)
        " agents defend at full strength. Beyond that, surplus agents contribute with "
        "diminishing returns (effective = "
        (v threshold)
        " + surplus^" (v exponent) "). "
        "At 1,000 defending agents the effective count is roughly " (v worked) ", not 1,000."))

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
      (let [tilt (g game :game/fate-rank-tilt const/fate-rank-tilt)
            ;; Mirrors roll-fate's p-disaster formula at the r=1 (leader) and r=0 (trailer)
            ;; extremes — both work out to the same (1 - tilt) / 2, which is why the guide
            ;; quotes one number for both cases.
            extreme-pct (pct (/ (- 1.0 tilt) 2.0))]
        (prose
          "Which you receive (disaster or boon) depends on your standing. "
          "Leaders draw disasters more often; trailers draw boons more often. "
          "The tilt is not absolute: even the top-ranked empire has roughly a "
          (v extreme-pct) " chance of a boon, and the last-place empire still faces roughly a "
          (v extreme-pct) " chance of disaster."))
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
         [(fate-row "Drought"        "Food"       "one turn's food production"   (g game :game/fate-drought-lo        const/fate-drought-lo)        (g game :game/fate-drought-hi        const/fate-drought-hi))
          (fate-row "Pirate Raid"    "Credits"    "one turn's credit production" (g game :game/fate-pirate-raid-lo    const/fate-pirate-raid-lo)    (g game :game/fate-pirate-raid-hi    const/fate-pirate-raid-hi))
          (fate-row "Reactor Breach" "Fuel"       "one turn's fuel production"   (g game :game/fate-reactor-breach-lo const/fate-reactor-breach-lo) (g game :game/fate-reactor-breach-hi const/fate-reactor-breach-hi))
          (fate-row "Solar Flare"    "Units"      "each unit type's holdings"    (g game :game/fate-solar-flare-lo    const/fate-solar-flare-lo)    (g game :game/fate-solar-flare-hi    const/fate-solar-flare-hi))
          (fate-row "Plague"         "Population" "current population"           (g game :game/fate-plague-lo         const/fate-plague-lo)         (g game :game/fate-plague-hi         const/fate-plague-hi))])
        [:tr [:td {:colspan 3 :class sub-hdr-cls} "Boons"]]
        (data-rows
         [(fate-row "Bumper Harvest" "Food"       "one turn's food production"    (g game :game/fate-bumper-harvest-lo const/fate-bumper-harvest-lo) (g game :game/fate-bumper-harvest-hi const/fate-bumper-harvest-hi))
          (fate-row "Gold Rush"      "Credits"    "one turn's credit production"  (g game :game/fate-gold-rush-lo      const/fate-gold-rush-lo)      (g game :game/fate-gold-rush-hi      const/fate-gold-rush-hi))
          (fate-row "Lottery Win"    "Credits"    "one turn's credit production"  (g game :game/fate-lottery-win-lo    const/fate-lottery-win-lo)    (g game :game/fate-lottery-win-hi    const/fate-lottery-win-hi))
          (fate-row "Reactor Surge"  "Fuel"       "one turn's fuel production"    (g game :game/fate-reactor-surge-lo  const/fate-reactor-surge-lo)  (g game :game/fate-reactor-surge-hi  const/fate-reactor-surge-hi))
          (fate-row "Migration Wave" "Population" "current population"            (g game :game/fate-migration-wave-lo const/fate-migration-wave-lo) (g game :game/fate-migration-wave-hi const/fate-migration-wave-hi))])]]
      (prose
        "Drought, Pirate Raid, and Reactor Breach can never take more food, credits, or fuel "
        "than you actually have. But spending down before Outcomes doesn't dodge the disaster — "
        "whatever portion couldn't be collected instead costs you stability, up to "
        (v (str (long (g game :game/fate-uncollected-stability-penalty const/fate-uncollected-stability-penalty)) "%")) ".")
      (prose "Fate events are visible to all players in the News feed. "))))

;;;;
;;;; Scoring
;;;;

(defn- scoring-section [game]
  (section "scoring" "SCORING" "how your empire score is calculated"
    (prose
      "Your score is the sum of all assets multiplied by their point weights. "
      "It updates each turn and determines leaderboard rank.")
    (guide-table
     ["Asset"         "Points"]
     [["Population"   (v (str (g game :game/score-population const/score-population) " / million"))]
      ["Mil planet"   (v (g game :game/score-mil-planet const/score-mil-planet))]
      ["Erg planet"   (v (g game :game/score-erg-planet const/score-erg-planet))]
      ["Ore planet"   (v (g game :game/score-ore-planet const/score-ore-planet))]
      ["Soldier"      (v (g game :game/score-soldier const/score-soldier))]
      ["Transport"    (v (g game :game/score-transport const/score-transport))]
      ["General"      (v (g game :game/score-general const/score-general))]
      ["Fighter"      (v (g game :game/score-fighter const/score-fighter))]
      ["Carrier"      (v (g game :game/score-carrier const/score-carrier))]
      ["Admiral"      (v (g game :game/score-admiral const/score-admiral))]
      ["Command ship" (v (g game :game/score-cmd-ship const/score-cmd-ship))]
      ["Station"      (v (g game :game/score-station const/score-station))]
      ["Agent"        (v (g game :game/score-agent const/score-agent))]])))

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
     (starting-empire-section game)
     (phase-banner "Phase 1 — Income")
     (income-section game)
     (phase-banner "Phase 2 — Expenses")
     (expenses-section game)
     (phase-banner "Phase 2a — Exchange")
     (exchange-section game)
     (phase-banner "Phase 3 — Building")
     (building-section game)
     (phase-banner "Phase 4 — Action" "action")
     (combat-section game)
     (strikes-section game)
     (phase-banner "Phase 5 — Espionage")
     (espionage-section game)
     (phase-banner "Phase 6 — Outcomes" "outcomes")
     (population-section game)
     (stability-section game)
     (fate-section game)
     (scoring-section game)]
    (ui/phase-action-bar
      (ui/action-bar-link (str "/app/game/" (:xt/id player)) "Back to Game"))))
