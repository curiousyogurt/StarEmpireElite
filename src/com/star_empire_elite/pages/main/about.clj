(ns com.star-empire-elite.pages.main.about
  (:require [com.star-empire-elite.ui :as ui]))

;;; About pages: game background section (default) and SEE genre essay section.
;;; Each tab is a distinct URL rendered as a full page so they are directly linkable.

;;;; Components

(defn- section [title & body]
  [:<>
   [:h2.font-bold.text-green-400 {:class "text-[16px] mb-2 tracking-[0.04em]"}
    title]
   [:p.text-left.text-game-green-soft {:class "text-[13px] mb-5 leading-[1.7] max-w-[48rem]"}
    body]])

(defn- nav-link [href label & [attrs]]
  [:a (merge {:href  href
              :class "no-underline text-game-green-soft font-mono rounded-sm border border-game-green-border bg-transparent tracking-[0.04em] py-[5px] px-4 inline-block text-[13px]"}
             attrs)
   label])

(defn- tab-button
  "Renders a tab button. active? controls filled vs. outline style."
  [href label active?]
  [:a {:href     href
       :hx-boost "true"
       :class    (if active?
                   "py-1 px-3.5 border border-green-400 bg-game-green-deep text-green-400 rounded-sm text-[13px] font-bold tracking-[0.04em] no-underline"
                   "py-1 px-3.5 border border-game-green-border bg-transparent text-game-green-soft rounded-sm text-[13px] tracking-[0.04em] no-underline")}
   label])

;;;; Content sections

(defn about-content []
  [:<>
   (section "The Legacy 💾"
     [:span.font-bold "Star Empire Elite "]
     "is a modern reimagining of the classic BBS door game "
     [:span.font-bold "Space Dynasty"] " (by Hollie Satterfield). Space Dynasty itself was "
     "inspired by "
     [:span.font-bold "Space Empire Elite"] " (by Jon Radoff) and "
     [:span.font-bold "Galactic Empire"] " (by Andromeda Software).")

   (section "The Original Games 💡"
     "These games defined a generation of BBS gaming in the late 1980s and early 1990s. Players "
     "would dial into bulletin board systems, play their turns (typically 4-6 per day), and compete "
     "asynchronously with other players on the same BBS.")

   (section "Core Gameplay 🎲"
     "In Star Empire Elite, you command a galactic empire with the goal of becoming the largest "
     "power. Acquire planets through purchase or conquest, manage your economy, build military "
     "fleets, form alliances, conduct diplomacy, and use covert operations to destabilize rival "
     "empires.")

   (section "Turn-Based System 🎉"
     "The game operates on a turn-based system where each player receives a fixed number of turns "
     "per time interval. On each turn, you manage planet maintenance, buy and sell food, conduct "
     "covert operations, purchase military units, attack other empires, and send diplomatic "
     "messages.")

   (section "The Radoffian Dynasty 👑"
     "Beware the computer-controlled Radoffian Dynasty! This powerful AI opponent has special "
     "abilities including seizing planets without attacking and hijacking fuel freighters.")

   (section "Technology 🖥"
     "This remake is built entirely in "
     [:span.font-bold "Clojure"] ", a powerful Lisp dialect very much like "
     [:span.font-bold "Racket"] ", using a purely functional approach with modern web technologies.")])

(defn essay-content []
  (let [p (fn [& body]
             [:p.text-left.text-game-green-soft {:class "text-[13px] mb-5 leading-[1.7] max-w-[48rem]"}
              body])]
    [:<>
     [:h2.font-bold.text-green-400 {:class "text-lg mb-5"}
      "From " [:em "The Sumerian Game"] " to " [:em "Star Empire Elite"]]

     (p "When people write about the history of strategy games, they tend to start too late: with the "
        "commercial successes of the 1990s, or with whichever computer titles helped define the modern "
        "genre. The lineage is older than that, and to my mind more interesting. One of the earliest "
        "ancestors of the empire game was " [:em "The Sumerian Game"] ", an educational simulation from the "
        "1960s, and the circumstances of that origin are worth noticing. The form began not as spectacle but "
        "as an exercise in stewardship: the player was asked to govern, to allocate grain and manage land, "
        "to respond to bad harvests and worse luck, and to answer for the consequences. Long before anyone "
        "gave the genre its spaceships and its multiplayer rivalries, it was already organized around the "
        "question that still animates it: what does it mean to rule a system whose variables you cannot "
        "fully control?")

     (p "What " [:em "The Sumerian Game"] " established was less a historical first than a structure, and "
        "the structure has proved remarkably stable. The player governs a polity rather than inhabiting an "
        "avatar; time advances in turns; resources are scarce; decisions take effect on a delay, and chance "
        "intervenes whenever it likes. Prudent choices sometimes fail. Some disasters cannot be prevented, "
        "only endured. Anyone who has played a later strategy game will recognize all of this, and it is "
        "striking how computational the shape is: a world described in terms of state, update, constraint, "
        "and consequence. The pleasure lies in judgment under incomplete knowledge, which is why the form "
        "never needed motion or reaction to hold anyone's attention.")

     (p [:em "Hamurabi"] ", its best-known descendant, stripped away most of the educational framing and "
        "left the decision loop standing in austere form. You buy and sell land, allocate grain, decide how "
        "much to plant, and wait to see what the year brings. Call that a reduction if you like; I would "
        "call it a clarification. What it showed is that the appeal of these games owes nothing to "
        "presentation. What matters is the logic of constrained rule: balancing subsistence, growth, and "
        "risk inside a system legible enough to reason about but never so transparent as to be trivial. If "
        "the genre has a fundamental pleasure, that is where it lives.")

     (p "The BBS era complicated that pleasure by adding other people. Once empire games went multiplayer, "
        "the problem itself changed: scarcity was no longer something you managed against an impersonal "
        "simulation but something you managed among rivals. A single-player empire game asks whether you "
        "can govern well. A multiplayer one asks whether you can govern well in the company of competitors, "
        "opportunists, allies, and enemies, which is a different question. The old economic logic survived, "
        "but its meaning shifted. Resources became leverage. Fleets, planets, and populations now sat inside "
        "a social field, and diplomacy, betrayal, retaliation, reputation, and deterrence entered the genre "
        "not as decoration but because rule had become shared and contested.")

     (p "This is where " [:em "Space Empire Elite"] " matters. It took the old ruler-simulation and "
        "rethought it for the bulletin board. Players connected one at a time, so the game unfolded in "
        "turns spread across hours or days, and that technical limitation produced something genuinely new: "
        "a kind of political time. The empire did not exist only while you were logged in. It persisted; "
        "you returned to it; other players acted in your absence. Plans ripened slowly, damage accumulated, "
        "grudges had time to harden. Social memory started to matter, and an empire stopped being merely a "
        "stock of resources; it was a position in an ongoing world.")

     (p "What crystallized here was the idea that an empire game could be a social system. You were not "
        "optimizing production so much as inhabiting a strategic order, one in which rational planning "
        "remained necessary but was never sufficient. Resources, certainly, but also the ability to "
        "anticipate rivals, gauge intentions, manage exposure, and judge when to expand and when to keep "
        "quiet. In this respect the BBS games come closer to politics than many later strategy titles do. "
        "They are about flourishing among others who are trying to flourish too.")

     (p "From there, the line through " [:em "Space Dynasty"] " and " [:em "Solar Realms Elite"]
        " is easy enough to trace. Both preserved the central logic of economic and military management "
        "while elaborating everything around it. " [:em "Space Dynasty"] " carried the genre into the PC "
        "BBS scene and widened its reach; " [:em "Solar Realms Elite"] " showed how much could be built on "
        "the old foundation: larger empires, more intricate systems, more varied conflict, a sharper sense "
        "of competitive struggle. By this point the genre was an ecology rather than a single line of "
        "descent, though the family resemblance is unmistakable. Names, interfaces, and settings changed; "
        "the underlying problem did not.")

     (p "The continuity deserves a moment's attention. Mesopotamia becomes interstellar space; grain "
        "becomes food, fuel, or credits; land becomes planets; famine becomes collapse or invasion or "
        "overextension. Yet across six decades the formal structure stays recognisable. The ruler allocates "
        "scarce resources in time. Growth creates vulnerability along with strength. Security requires "
        "investment, and investment imposes costs. Expansion tempts, and can outrun the economy that "
        "sustains it. None of this is incidental. It is the form.")

     (p [:em "TradeWars"] " belongs to this history too, and its differences are part of the point. By the "
        "BBS era, space-economic strategy had become a whole design family: some titles leaned into trade, "
        "some into conquest or territory or diplomacy. What united them was a conception of play rather "
        "than any particular mechanic: a persistent strategic world, resources managed under constraint, "
        "advantage sought in the presence of competing actors. The empire game had become one of the more "
        "compelling ways a computer could model a system rather than an action.")

     (p "All of which is why I resist calling " [:em "Star Empire Elite"] " a nostalgic exercise. Retro is "
        "not wrong as a description, but it stops short. The project revives a form that is historically "
        "important and conceptually rich: from " [:em "The Sumerian Game"] " it inherits the premise that "
        "governance can be made playable; from " [:em "Hamurabi"] ", the elegance of bare numerical "
        "consequence; from the BBS tradition, the recognition that systems grow more interesting when other "
        "minds inhabit them; and from the BBS era wat happens when scarcity, growth, and force are all mediated" 
        "by rivalry. " [:em "Star Empire Elite"] " is not reproducing an aesthetic. It is rejoining an "
        "argument about what games can be.")

     (p "The argument, roughly, is that the deepest pleasures of computer games have little to do with "
        "speed or spectacle. They lie in acting within a structured world whose rules can be studied but "
        "never mastered. Empire games pose a problem that is mathematical and political at once: they ask "
        "you to think in quantities, rates, constraints, and feedback loops, and at the same time to face "
        "the limits of planning in a world you share with others. They are games about administration and "
        "prudence, growth and fragility, and about depending on systems with uncertainty built into them.")

     (p "That is the thread running from " [:em "The Sumerian Game"] " to " [:em "Star Empire Elite"]
        ". For sixty years, on platform after platform, people have rediscovered the same insight: rule "
        "itself can be play. Not rule as omnipotence, but rule as a sequence of hard decisions made under "
        "pressure, with incomplete information, with consequences that cannot be fully contained. "
        [:em "Star Empire Elite"] " stands in an older tradition than its surface suggests. Its ancestors "
        "are not just the door games that immediately precede it, but the earliest simulations to grasp "
        "that governing a world, however small and abstract, means grappling with tradeoffs. The form "
        "endures because it turns that predicament into play.")]))

(defn design-content []
  [:<>
   (section "What This Game Brings Together ✨"
     "A game like " [:em "Star Empire Elite"] " is not simply a piece of entertainment software. It is a meeting "
     "point for a number of different disciplines, each of which places its own demands on the project. "
     "To build a modern successor to a classic BBS empire game is to engage at once with programming, "
     "systems design, interface design, visual communication, historical reconstruction, and the shaping "
     "of player experience. The project is not reducible to any one of these. It is a technical artifact, "
     "certainly, but also a design problem, a historical conversation, and, in some sense, an experiment "
     "in reinterpretation.")

   (section "Software Implementation 🛠"
     "At the most basic level, the game is a software system. Its rules have to be represented in code; "
     "its data has to be organized coherently; player actions have to produce correct and intelligible "
     "results. That work is foundational. Without a sound implementation, the rest of the project "
     "cannot stand.")

   (section "Game Mechanics and Systems Design 🎲"
     "What gives a strategy game its interest is not simply theme, but structure. Turns, economies, "
     "combat systems, growth models, scarcity, and risk all have to be brought into conversation with "
     "one another. The challenge is not merely to include many moving parts, but to make them interact "
     "in a way that produces meaningful decisions rather than noise.")

   (section "User Interface and User Experience 📐"
     "A complex game must be legible. Players need to understand what is happening, what their "
     "options are, and what the likely consequences of their actions will be. Thus, interface design is "
     "not an afterthought. It is part of the game's intelligibility. A good interface allows strategic "
     "depth to appear as richness, rather than as confusion.")

   (section "Visual Language 🎨"
     "The visual dimension of the game matters not only for atmosphere, but for clarity. Typography, "
     "color, layout, iconography, and graphical style all contribute to the player's sense of the world "
     "and to the legibility of the information being presented. Visual language, at its best, is not "
     "decoration laid on top of a system. It is one of the means by which the system becomes perceptible.")

   (section "Economic and Strategic Balancing ⚖️"
     "Empire games depend on balance in a particularly demanding sense. Populations, planets, production, "
     "upkeep, military power, and expansion all have to be calibrated so that no single path trivializes "
     "the rest. This is partly a mathematical problem, partly an experimental one, and partly a question "
     "of judgment. The goal is not perfect equilibrium, but a strategic environment in which different "
     "choices remain live.")

   (section "Narrative Framing and Worldbuilding 🌌"
     "Even a heavily systems-driven game requires some world around its abstractions. Names, factions, "
     "descriptions, and thematic framing give context to mechanics that would otherwise remain merely "
     "numerical. Worldbuilding matters because it transforms a formal system into a setting that players "
     "can inhabit imaginatively.")

   (section "Multiplayer Dynamics 🤝"
     "Because " [:em "Star Empire Elite"] " belongs to a tradition of multiplayer empire games, player interaction "
     "is central to its design. Diplomacy, alliance, rivalry, deterrence, and betrayal are not secondary "
     "features. They are among the forms of play the game exists to make possible. Designing such a game "
     "therefore means thinking not only about systems, but about the social worlds those systems produce.")

   (section "Game History and Preservation 💾"
     "This project is also shaped by an interest in game history. " [:em "Star Empire Elite"] " draws on a lineage "
     "of BBS empire games that were once an important part of online play, but which are now less widely "
     "remembered than they deserve to be. Building a successor to that tradition is, in part, an act of "
     "preservation: not museum preservation, but the preservation that comes from continuing a form and "
     "allowing it to live again.")

   (section "Modern Reinterpretation 🚀"
     "A modern remake should not merely reproduce the past. It should ask what was valuable in the older "
     "form, what can be clarified or extended, and what it means to carry that form into a different "
     "technical and cultural setting. " [:em "Star Empire Elite"] " is not intended as a replica. It is an attempt "
     "to reinterpret a style of strategy game in a way that remains recognizably connected to its "
     "origins while still being fully a contemporary work.")])

;;;;
;;;; Pages
;;;;

(defn about-page
  "Renders the full about page. tab is :about, :essay, or :design."
  [tab]
  (ui/page
    {}
    [:div.text-base.w-full.max-w-3xl.mx-auto.overflow-hidden.relative
     {:class "border-[1.5px] border-game-green-border rounded bg-game-bg text-green-400 font-mono"}
     (ui/scanline-overlay)

     ;; Page header
     [:div.bg-game-surface.border-b.border-game-green-border
      {:class "py-[7px] px-3.5"}
      [:div.font-bold.text-green-400 {:class "text-[22px] tracking-wider"} "ABOUT"]]

     [:div {:class "py-4 px-5"}

      ;; Tab bar
      [:div.flex.gap-2.mb-6
       (tab-button "/about"        "Overview" (= tab :about))
       (tab-button "/about/essay"  "History"  (= tab :essay))
       (tab-button "/about/design" "Design"   (= tab :design))]

      ;; Tab content
      (case tab
        :about  (about-content)
        :essay  (essay-content)
        :design (design-content))

      ;; Navigation links
      [:div.border-t.border-game-green-border {:class "pt-3 mt-2"}
       [:div.flex.gap-3
        (nav-link "/signup" "Sign In / Sign Up")
        (nav-link "/" "Home" {:hx-boost "true"})]]]]))

;;;;
;;;; Handlers
;;;;

(defn about [_ctx]
  (about-page :about))

(defn essay [_ctx]
  (about-page :essay))

(defn design [_ctx]
  (about-page :design))
