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
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

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
  Also attaches each player's :user/email, looked up via xt/entity on :player/user — this
  codebase has no nested pull-join syntax, so refs are resolved with a second lookup.

  [db xtdb-db, game-id uuid] -> [player-map]"
  [db game-id]
  (let [results        (filter #(not= (:player/status %) const/player-status-eliminated)
                               (q db '{:find (pull player [*])
                                        :in [game-id]
                                        :where [[player :player/game game-id]]}
                                    game-id))
        sorted-players (sort-by :player/score > (seq results))]
    (map-indexed (fn [idx player]
                   (assoc player
                          :rank (inc idx)
                          :user/email (:user/email (xt/entity db (:player/user player)))))
                 sorted-players)))

;;;;
;;;; UI Components
;;;;

(defn- remove-player-button
  "Render an admin-only remove button for a single player, with a confirmation prompt.
  Styled to match the discrete delete-game × on the dashboard's game-card.

  [player-id uuid] -> hiccup"
  [player-id]
  (biff/form
   {:action   (str "/app/remove-player/" player-id)
    :method   "post"
    :hx-boost "true"
    :class    "m-0"}
   [:button
    {:type       "submit"
     :hx-confirm "Remove this player from the game?"
     :class      "px-1 border-none bg-transparent text-game-green-dim font-mono text-xl leading-none cursor-pointer"}
    "×"]))

(defn- player-info-tooltip
  "Render an admin-only info icon for a player; hovering or focusing it reveals a tooltip
  with the player's email. CSS-only (:peer + peer-hover/peer-focus), so it also opens on
  keyboard focus and touch-tap, not just mouse hover. More fields can be added to the
  tooltip body later without changing this trigger.

  The tooltip opens upward (bottom-full) rather than down: the players table sits in an
  overflow-x-auto wrapper, which makes browsers treat its overflow-y as auto too, so a
  downward tooltip on the table's last row — the one admins check most — would get clipped.

  [email string] -> hiccup"
  [email]
  [:span.relative.inline-block
   [:button.peer
    {:type      "button"
     :aria-label (str "Player info: " email)
     :class     "px-1 border-none bg-transparent text-game-green-dim font-mono text-sm leading-none cursor-pointer focus:outline-none"}
    "ⓘ"]
   [:div
    {:class "pointer-events-none absolute right-0 bottom-full z-10 mb-1 hidden whitespace-nowrap rounded-sm border border-game-border bg-game-header px-2 py-1 text-xs text-game-green-soft peer-hover:block peer-focus:block"}
    email]])

(defn players-table
  "Render the leaderboard showing all players in the game ranked by score.
  Admin users also see a remove button per player.

  [{:keys [players admin?]}] -> hiccup"
  [{:keys [players admin?]}]
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
         [:th.text-right.px-3.py-1 {:class th-cls} "Score"]
         (when admin?
           [:th.w-px.whitespace-nowrap.px-1.py-1 {:class th-cls} ""])]]
       [:tbody
        (for [player players]
          [:tr.bg-game-row.border-b.border-game-divider
           [:td {:class td-cls} (:rank player)]
           [:td {:class td-cls} (:player/empire-name player)]
           [:td {:class td-right-cls}
            (ui/format-number (utils/total-planets player))]
           [:td {:class td-right-cls} (ui/format-number (:player/score player))]
           (when admin?
             [:td.w-px.whitespace-nowrap.text-right.px-1
              (player-info-tooltip (:user/email player))
              (remove-player-button (:xt/id player))])])]]]]))

;;;;
;;;; Page
;;;;

(defn cooldown-bar
  "Render the action-bar cooldown widget for the game overview. When in cooldown, returns a
  polling div that refreshes itself every 30 seconds via HTMX; when cooldown has cleared,
  returns the Play link so the player can start their next round immediately.

  Uses outerHTML swap so the element replaces itself entirely — when the cooldown expires
  the Play link takes over and polling stops automatically.

  [player-id uuid, cooldown-ms int-or-nil, day-exhausted? bool, phase-url str] -> hiccup"
  [player-id cooldown-ms day-exhausted? phase-url]
  (if cooldown-ms
    ;; Cooldown active: polling div that updates its own text every 30s.
    [:div#cooldown-bar
     {:class       "py-[5px] px-3.5 border border-yellow-700 text-yellow-400 rounded-sm text-sm font-mono tracking-wider"
      :hx-get      (str "/app/game/" player-id "/cooldown")
      :hx-trigger  "every 60s"
      :hx-swap     "outerHTML"}
     (if day-exhausted?
       (str "Rounds reset in " (utils/format-cooldown-duration cooldown-ms))
       (str "Next round opens in " (utils/format-cooldown-duration cooldown-ms)))]
    ;; Cooldown over: replace with the Play button (no HTMX, polling stops).
    (ui/action-bar-primary-link phase-url "Play")))

(defn game-view
  "Render the game overview page. Returns 404 when the player entity does not exist.
  Shows a cooldown countdown when the player is waiting for the next round or day.

  [ctx ring-ctx] -> hiccup | ring-response"
  [{:keys [session path-params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [players     (get-game-players (:biff/db ctx) (:xt/id game))
          user        (xt/entity db (:uid session))
          admin?      (boolean (const/admin-emails (:user/email user)))
          ;; Mid-turn players (past income) have already committed to their turn
          ;; and must be allowed to finish it regardless of cooldown state.
          mid-turn?   (> (:player/current-phase player) 1)
          cooldown-ms (when-not mid-turn? (utils/round-cooldown-ms player game))]
      (ui/phase-shell player game "GAME OVERVIEW"
        [:div.flex.flex-col.gap-2
         {:class "py-2.5 px-3.5"}
         (ui/incoming-alert player "every 60s")
         (ui/snapshot-section player)
         (players-table {:players players :admin? admin?})]
        (ui/phase-action-bar
          (ui/action-bar-link "/app" "Back to Games")
          (ui/action-bar-link (str "/app/game/" (:xt/id player) "/news") "News")
          (ui/action-bar-link (str "/app/game/" (:xt/id player) "/guide") "Guide")
          (cooldown-bar (:xt/id player) cooldown-ms
                        (utils/day-exhausted? player game)
                        (get-phase-url (:xt/id player) (:player/current-phase player))))))))
