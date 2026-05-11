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
  (let [th-style  {:color "#4ade80" :font-size "11px" :letter-spacing "0.08em"
                   :text-transform "uppercase"}
        td-border {:border-right "1px solid #253530" :padding "4px 12px" :color "#9adaaa"}
        td-right  (assoc td-border :text-align "right")]
    [:div.mt-2
     (ui/section-label "Players")
     [:div.overflow-x-auto
      [:table.w-full.text-sm
       {:style {:border "1px solid #253530" :border-collapse "collapse"}}
       [:thead
        [:tr {:style {:background "#151f1a" :border-bottom "1px solid #253530"}}
         [:th.text-left.px-3.py-1  {:style th-style} "Rank"]
         [:th.text-left.px-3.py-1  {:style th-style} "Empire"]
         [:th.text-right.px-3.py-1 {:style th-style} "Planets"]
         [:th.text-right.px-3.py-1 {:style th-style} "Score"]]]
       [:tbody
        (for [player players]
          [:tr {:style {:border-bottom "1px solid #1a2820" :background "#121a18"}}
           [:td {:style td-border} (:rank player)]
           [:td {:style td-border} (:player/empire-name player)]
           [:td {:style td-right}
            (ui/format-number (+ (:player/mil-planets player)
                                 (:player/erg-planets player)
                                 (:player/ore-planets player)))]
           [:td {:style td-right} (ui/format-number (:player/score player))]])]]]]))

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
       [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
        {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
                 :border-radius "4px" :color "#4ade80"
                 :font-family "'Courier New', monospace"}}
        (ui/scanline-overlay)
        (ui/phase-topbar player "GAME OVERVIEW")
        [:div.flex.flex-col.gap-2
         {:style {:padding "10px 14px"}}
         (ui/snapshot-section player)
         (players-table {:players players})]
        ;; Play button or cooldown message depending on round availability
        [:div.flex.gap-2
         {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
         (ui/action-bar-link "/app" "Games")
         (if cooldown-ms
           [:div {:style {:padding "5px 14px" :border "1px solid #b45309"
                          :color "#facc15" :border-radius "2px" :font-size "14px"
                          :font-family "'Courier New', monospace" :letter-spacing "0.05em"}}
            (if (utils/day-exhausted? player game)
              (str "Rounds reset in " (utils/format-cooldown-duration cooldown-ms))
              (str "Next round opens in " (utils/format-cooldown-duration cooldown-ms)))]
           (ui/action-bar-primary-link
             (get-phase-url (:xt/id player) (:player/current-phase player))
             "Play"))]]))))
