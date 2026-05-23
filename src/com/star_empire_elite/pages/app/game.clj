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

(defn players-table
  "Render the leaderboard showing all players in the game ranked by score.

  [{:keys [players]}] -> hiccup"
  [{:keys [players]}]
  (let [th-cls       "text-green-400 text-[11px] tracking-[0.08em] uppercase"
        td-cls       "border-r border-game-border py-1 px-3 text-game-green-soft"
        td-right-cls "border-r border-game-border py-1 px-3 text-game-green-soft text-right"]
    [:div.mt-2
     (ui/section-label "Players")
     [:div.overflow-x-auto
      [:table.w-full.text-sm
       {:class "border border-game-border border-collapse"}
       [:thead
        [:tr.bg-game-header.border-b.border-game-border
         [:th.text-left.px-3.py-1  {:class th-cls} "Rank"]
         [:th.text-left.px-3.py-1  {:class th-cls} "Empire"]
         [:th.text-right.px-3.py-1 {:class th-cls} "Planets"]
         [:th.text-right.px-3.py-1 {:class th-cls} "Score"]]]
       [:tbody
        (for [player players]
          [:tr.bg-game-row.border-b.border-game-divider
           [:td {:class td-cls} (:rank player)]
           [:td {:class td-cls} (:player/empire-name player)]
           [:td {:class td-right-cls}
            (ui/format-number (+ (:player/mil-planets player)
                                 (:player/erg-planets player)
                                 (:player/ore-planets player)))]
           [:td {:class td-right-cls} (ui/format-number (:player/score player))]])]]]]))

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
      (ui/phase-shell player game "GAME OVERVIEW"
        [:div.flex.flex-col.gap-2
         {:class "py-2.5 px-3.5"}
         (ui/snapshot-section player)
         (players-table {:players players})]
        (ui/phase-action-bar
          (ui/action-bar-link "/app" "Back to Games")
          (ui/action-bar-link (str "/app/game/" (:xt/id player) "/news") "News")
          (if cooldown-ms
            [:div {:class "py-[5px] px-3.5 border border-yellow-700 text-yellow-400 rounded-sm text-sm font-mono tracking-wider"}
             (if (utils/day-exhausted? player game)
               (str "Rounds reset in " (utils/format-cooldown-duration cooldown-ms))
               (str "Next round opens in " (utils/format-cooldown-duration cooldown-ms)))]
            (ui/action-bar-primary-link
              (get-phase-url (:xt/id player) (:player/current-phase player))
              "Play")))))))
