;;;;;
;;;;; Game Overview - Empire Status and Phase Navigation
;;;;;
;;;;; The game overview page is the central hub for a player's turn. It shows current resources,
;;;;; the full player leaderboard, and a button to continue to the current phase. When the player
;;;;; is waiting for a round or day reset, the button is replaced with a cooldown countdown.
;;;;;

(ns com.star-empire-elite.pages.app.game
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Routing
;;;;

(defn get-phase-url
  "Return the URL for the given phase number. Defaults to the game overview if the phase
  is unrecognised (e.g. 0 between turns).

  [player-id uuid, current-phase int] -> string"
  [player-id current-phase]
  (case current-phase
    1 (str "/app/game/" player-id "/income")
    2 (str "/app/game/" player-id "/expenses")
    3 (str "/app/game/" player-id "/building")
    4 (str "/app/game/" player-id "/action")
    5 (str "/app/game/" player-id "/espionage")
    6 (str "/app/game/" player-id "/outcomes")
    (str "/app/game/" player-id)))

;;;;
;;;; Data Fetching
;;;;

(defn get-game-players
  "Fetch all players in a game, sorted by score descending, with a :rank field added.

  [db xtdb-db, game-id uuid] -> [player-map]"
  [db game-id]
  (let [results        (filter #(not= (:player/status %) const/player-status-eliminated)
                              (q db '{:find (pull player [*])
                                       :in [game-id]
                                       :where [[player :player/game game-id]]}
                                   game-id))
        sorted-players (sort-by :player/score > (seq results))]
    (map-indexed (fn [idx player]
                   (assoc player :rank (inc idx)))
                 sorted-players)))

;;;;
;;;; UI Components
;;;;

(defn game-header
  "Render a responsive resource bar showing key empire stats.
  Adapts from 3 columns on mobile to 10 columns on large screens.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  [:div.grid.grid-cols-3.md:grid-cols-5.lg:grid-cols-9.gap-2.mb-6.pb-4.border-b.border-green-400

   ;; Economic resources
   [:div
    [:p.text-xs "Credits"]
    [:p.font-mono (ui/format-number (:player/credits player))]]
   [:div
    [:p.text-xs "Food"]
    [:p.font-mono (ui/format-number (:player/food player))]]
   [:div
    [:p.text-xs "Fuel"]
    [:p.font-mono (ui/format-number (:player/fuel player))]]
   [:div
    [:p.text-xs "Galaxars"]
    [:p.font-mono (ui/format-number (:player/galaxars player))]]

   ;; Empire metrics
   [:div
    [:p.text-xs "Population"]
    [:p.font-mono (str (:player/population player) "M")]]
   [:div
    [:p.text-xs "Stability"]
    [:p.font-mono (:player/stability player) "%"]]
   [:div
    [:p.text-xs "Planets"]
    [:p.font-mono (ui/format-number (+ (:player/mil-planets player)
                     (:player/erg-planets player)
                     (:player/ore-planets player)))]]

   ;; Turn progression
   (let [{:keys [turn round]} (utils/display-turn-round player game)]
     (list
      [:div
       [:p.text-xs "Turn"]
       [:p.font-mono turn]]
      [:div
       [:p.text-xs "Round"]
       [:p.font-mono round]]))])

(defn players-table
  "Render the leaderboard showing all players in the game ranked by score.

  [{:keys [players]}] -> hiccup"
  [{:keys [players]}]
  [:div.mt-8
   [:h2.text-xl.font-bold.mb-4 "Players"]
   [:div.overflow-x-auto
    [:table.w-full.text-sm.border.border-green-400

     [:thead
      [:tr.border-b.border-green-400
       [:th.border-r.border-green-400.px-3.py-2 "Rank"]
       [:th.border-r.border-green-400.px-3.py-2 "Empire"]
       [:th.border-r.border-green-400.px-3.py-2 "Planets"]
       [:th.px-3.py-2 "Score"]]]

     [:tbody
      (for [player players]
        [:tr.border-b.border-green-400
         [:td.border-r.border-green-400.px-3.py-2 (:rank player)]
         [:td.border-r.border-green-400.px-3.py-2 (:player/empire-name player)]
         [:td.border-r.border-green-400.px-3.py-2
          (ui/format-number (+ (:player/mil-planets player)
                               (:player/erg-planets player)
                               (:player/ore-planets player)))]
         [:td.px-3.py-2 (ui/format-number (:player/score player))]])]]]])

;;;;
;;;; Page
;;;;

(defn game-view
  "Render the game overview page. Returns 404 when the player entity does not exist.
  Shows a cooldown countdown when the player is waiting for the next round or day.

  [ctx ring-ctx] -> hiccup | ring-response"
  [{:keys [path-params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [players     (get-game-players (:biff/db ctx) (:xt/id game))
          ;; Mid-turn players (past income) have already committed to their turn
          ;; and must be allowed to finish it regardless of cooldown state.
          mid-turn?   (> (:player/current-phase player) 1)
          cooldown-ms (when-not mid-turn? (utils/round-cooldown-ms player game))]
      (ui/page
       {}
       [:div.text-green-400.font-mono
        [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

        (game-header {:player player :game game})
        (players-table {:players players})
        [:.h-6]

        ;; Play button or cooldown message depending on round availability
        [:div.flex.gap-4
         (if cooldown-ms
           [:div.border.border-yellow-400.px-6.py-2.text-yellow-400
            (if (utils/day-exhausted? player game)
              (str "Rounds reset in " (utils/format-cooldown-duration cooldown-ms))
              (str "Next round opens in " (utils/format-cooldown-duration cooldown-ms)))]
           [:a.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
            {:href (get-phase-url (:xt/id player) (:player/current-phase player))} "Play"])
         [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
          {:href "/app"} "Back to Games"]]]))))
