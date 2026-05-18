(ns com.star-empire-elite.pages.main.about
  (:require [com.star-empire-elite.ui :as ui]))

;;; About pages: game background section (default) and SEE genre essay section.
;;; Each tab is a distinct URL rendered as a full page so they are directly linkable.

;;;; Components

(defn- section [title & body]
  [:<>
   [:h2 {:style {:font-size "16px" :font-weight "bold" :margin-bottom "8px" :color "#4ade80"
                 :letter-spacing "0.04em"}}
    title]
   [:p {:style {:font-size "13px" :color "#9adaaa" :margin-bottom "20px" :text-align "left"
                :line-height "1.7" :max-width "48rem"}}
    body]])

(defn- nav-link [href label & [attrs]]
  [:a (merge {:href  href
              :style {:padding "5px 16px" :border "1px solid #1e6e44" :background "transparent"
                      :color "#9adaaa" :border-radius "2px" :font-family "'Courier New', monospace"
                      :font-size "13px" :letter-spacing "0.04em" :text-decoration "none"}}
             attrs)
   label])

(defn- tab-button
  "Renders a tab button. active? controls filled vs. outline style."
  [href label active?]
  [:a {:href      href
       :hx-boost  "true"
       :style     (if active?
                    {:padding "4px 14px" :border "1px solid #4ade80" :background "#1a3a28"
                     :color "#4ade80" :border-radius "2px" :font-size "13px"
                     :font-weight "bold" :letter-spacing "0.04em" :text-decoration "none"}
                    {:padding "4px 14px" :border "1px solid #1e6e44" :background "transparent"
                     :color "#9adaaa" :border-radius "2px" :font-size "13px"
                     :letter-spacing "0.04em" :text-decoration "none"})}
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
             [:p {:style {:font-size "13px" :margin-bottom "20px" :max-width "48rem"
                          :text-align "left" :line-height "1.7" :color "#9adaaa"}}
              body])]
    [:<>
     [:h2 {:style {:font-size "18px" :font-weight "bold" :margin-bottom "20px" :color "#4ade80"}}
      "From " [:em "The Sumerian Game"] " to " [:em "Star Empire Elite"]]

     (p "When people talk about the history of strategy games, they usually begin in the wrong place. "
        "They begin with the commercial successes of the 1990s, or with the better-known computer titles "
        "that helped define the modern genre. But the lineage is older than that, and also, I think, more "
        "conceptually interesting. One of the earliest ancestors of the empire-management game was "
        [:em "The Sumerian Game"] ", an educational simulation developed in the 1960s. That origin matters. "
        "The form did not begin as spectacle. It began as a model of stewardship. The player was asked to "
        "govern: to allocate grain, manage land, respond to uncertainty, and bear responsibility for the "
        "consequences. Long before the genre acquired its familiar space settings, military abstractions, "
        "and competitive multiplayer worlds, it was already organized around a surprisingly durable question: "
        "what does it mean to rule a system whose variables cannot be perfectly controlled?")

     (p "That question sits very near the centre of the empire game as such. What "
        [:em "The Sumerian Game"] " established was not simply a historical first, but a structure. The player "
        "governs a polity rather than inhabiting an avatar. Time advances in turns. Resources are scarce. "
        "Decisions have delayed effects. Randomness intervenes. Sometimes prudent choices fail, and sometimes "
        "a disaster arrives that cannot be prevented, only endured. That is already the shape of later "
        "strategy games. More importantly, it is a distinctly computational shape: a world described in terms "
        "of state, update, constraint, and consequence. The pleasure of the form lies not in motion or "
        "reaction, but in judgment under conditions of incomplete knowledge.")

     (p [:em "Hamurabi"] " was a simplified descendant of " [:em "The Sumerian Game"]
        ", with much of the educational framing stripped, and a decision loop exposed in austere form. "
        "The player buys and sells land, allocates grain, decides how much to plant, and waits to see what "
        "the next turn will bring. In one sense, this is a reduction. In another, it is a clarification. It "
        "reveals that the enduring appeal of these games does not depend on elaborate presentation. What "
        "matters is the logic of constrained rule: the sense that one is balancing subsistence, growth, and "
        "risk inside a system that is legible enough to reason about, but not so transparent as to become "
        "trivial. If there is a fundamental pleasure in the genre, it lies there.")

     (p "The BBS era transformed that pleasure by introducing other people. Once empire games became "
        "multiplayer, the problem changed. The player was no longer managing scarcity in relation to an "
        "impersonal simulation alone, but rather in the presence of rivals. That shift was decisive. "
        "A single-player empire game asks whether one can govern well. A multiplayer empire game asks whether "
        "one can govern well in a world populated by competitors, opportunists, allies, and enemies. The old "
        "economic logic remained, but its meaning changed. Resources became not just necessities but "
        "instruments of leverage. Populations, fleets, planets, and infrastructure were now embedded in a "
        "social field. Diplomacy, betrayal, retaliation, reputation, deterrence: all of these entered the "
        "genre not as ornament, but as a consequence of the fact that rule had become shared and contested.")

     (p "This is the context in which " [:em "Space Empire Elite"] " becomes important. It marks one of those "
        "moments in game history when an existing structure is not merely copied but reinterpreted through a "
        "new medium. The older ruler-simulation model survives, but it is reworked for the asynchronous "
        "social world of the bulletin board system. Because players connected one at a time, these games "
        "unfolded in turns spread across hours or days. That technical limitation produced a distinctive kind "
        "of political time. The empire did not exist only during a session. It persisted. One returned to it. "
        "Other players acted in one's absence. Plans ripened slowly; damage accumulated; grudges had time "
        "to harden. The result was a form of strategy game in which social memory mattered. The empire was "
        "no longer just a structure of resources; it was a position in an ongoing world.")

     (p "What " [:em "Space Empire Elite"] " helped crystallize, then, was the idea that empire games could "
        "become social systems. The player did not simply optimize production, but inhabited a strategic order. "
        "That made the genre richer. It pushed the game away from the dream of perfect control and toward "
        "something more interesting: a world in which rational planning remained necessary, but never "
        "sufficient. One needed resources, certainly. But one also needed to anticipate rivals, "
        "gauge intentions, manage exposure, and decide when to expand and when to remain quiet. In that "
        "respect, these games approach politics more closely than many later strategy titles do. They are not "
        "only about building. They are about flourishing among others who are building too.")

     (p "From there, the line through " [:em "Space Dynasty"] " and " [:em "Solar Realms Elite"]
        " is not difficult to see. These games extended and elaborated the form. They preserved the central "
        "logic of economic and military management, but they did so in increasingly expansive and socially "
        "complex settings. " [:em "Space Dynasty"] " helped carry the genre into the PC BBS world, widening "
        "its reach while preserving its core premise. " [:em "Solar Realms Elite"] " developed the tradition "
        "still further, showing just how much could be built atop the old foundation: larger empires, more "
        "intricate systems, more varied forms of conflict, and a deeper sense of competitive struggle. By "
        "this point the genre was an ecology, not a single line of descent. The family resemblance is "
        "unmistakable. The names, interfaces, and settings changed. The underlying problem did not.")

     (p "That continuity is worth dwelling on. Ancient Mesopotamia becomes interstellar space. Grain becomes "
        "food, fuel, or credits. Land becomes planets. Famine becomes collapse, invasion, or economic "
        "overextension. Yet the formal structure remains recognisable " [:em "across decades."] " The ruler "
        "must allocate scarce resources in time. Growth creates vulnerability as well as strength. Security "
        "requires investment, but investment imposes costs. Expansion is attractive, but it can outrun the "
        "economy that sustains it. The system is dynamic, partly knowable, and resistant to total mastery. "
        "These are not incidental design features. They are the essence of the form.")

     (p [:em "TradeWars"] " belongs to this wider history as well. It differs in emphasis, of course, but "
        "that is the point. By the BBS era, space-economic strategy games had become a substantial design "
        "family. Some titles leaned more heavily into trade, some into conquest, some into territorial "
        "control, some into diplomacy. What united them was not a single mechanic, but a shared conception of "
        "play: a player inhabits a persistent strategic world, manages resources under constraint, and seeks "
        "advantage in the presence of competing actors. In that sense, the empire game had become one of the "
        "most compelling ways computers could model not merely actions, but systems.")

     (p "That is why " [:em "Star Empire Elite"] " should be understood as more than a nostalgic exercise. "
        "To call it retro is not wrong, exactly, but it is insufficient. The deeper interest of such a "
        "project lies in the fact that it revives a historically important and conceptually rich form. From "
        [:em "The Sumerian Game"] " it inherits the premise that governance itself can be made playable. From "
        [:em "Hamurabi"] " it inherits the elegance of stripped-down numerical consequence. From the BBS "
        "empire tradition it inherits the recognition that systems become more interesting when they are "
        "inhabited by other minds: when scarcity, growth, and force are all mediated by rivalry, negotiation, "
        "and uncertainty about human intent. In that sense, " [:em "Star Empire Elite"] " is not merely "
        "reproducing an old aesthetic. Rather, it takes up an interesting discussion about what games can be.")

     (p "And that discussion, in essence, is that some of the deepest pleasures of computer games lie not in "
        "speed or spectacle, but in the experience of acting within a structured world whose rules can be "
        "studied but never fully mastered. Empire games are compelling because they establish a problem that is at "
        "once mathematical and political. They ask the player to think in terms of quantities, rates, "
        "constraints, and feedback loops; but also to confront the limits of planning in a world "
        "shared with others. They are games about administration and prudence; about growth and fragility; about "
        "dependence on systems that uncertainty built into the equation.")

     (p "That is the long thread that runs from " [:em "The Sumerian Game"] " to " [:em "Star Empire Elite"]
        ". Across sixty years, different platforms and communities have rediscovered the same insight: that "
        "rule itself can be a form of play. Not rule as fantasy omnipotence, but rule as a sequence of "
        "difficult decisions made under pressure, with incomplete information, and with consequences that "
        "cannot be fully contained. In that respect, " [:em "Star Empire Elite"] " stands in a much older "
        "tradition than its surface might suggest. Its true ancestors are not only the BBS door games that "
        "directly precede it, but the earliest simulations that recognized that to govern a world, even a "
        "small and abstract one, is to grapple with a web of tradeoffs. What makes the form durable is "
        "that it forcefully brings that setting to the table.")]))

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
     {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
              :border-radius "4px" :color "#4ade80"
              :font-family "'Courier New', monospace"}}
     (ui/scanline-overlay)

     ;; Page header
     [:div {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "7px 14px"}}
      [:div {:style {:font-size "22px" :font-weight "bold" :color "#4ade80"
                     :letter-spacing "0.05em"}} "ABOUT"]]

     [:div {:style {:padding "16px 20px"}}

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
      [:div {:style {:border-top "1px solid #1a3020" :padding-top "12px" :margin-top "8px"}}
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
