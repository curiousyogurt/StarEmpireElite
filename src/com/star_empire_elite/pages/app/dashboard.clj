;;;;;
;;;;; Dashboard - Game and Empire Overview
;;;;;
;;;;; The dashboard is the main landing page after login. It lists all games the current user has
;;;;; joined, one HUD strip per game, and available games the player has not yet joined. An
;;;;; admin-only create-game link appears at the bottom.
;;;;;

(ns com.star-empire-elite.pages.app.dashboard
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

;;;;
;;;; Calculations
;;;;

(defn format-turn-round
  "Format the turn/round status string for dashboard display.

  [player game] -> string"
  [player game]
  (let [{:keys [turn round]} (utils/display-turn-round player game)]
    (str "Turn " turn "/" (:game/turns-per-round game)
         " | Round " round "/" (:game/rounds-per-day game))))

;;;;
;;;; Data Fetching
;;;;

(defn get-user-games
  "Fetch all games the current user has joined, with rank, total player count,
  leader planet count, and leader score for each game.

  [db xtdb-db, uid uuid] -> [{:player player-map, :game game-map,
                               :total-players int, :leader-score int, :leader-planets int}]"
  [db uid]
  (let [players (filter #(not= (:player/status %) const/player-status-eliminated)
                        (q db
                           '{:find (pull player [*])
                             :in [user-id]
                             :where [[player :player/user user-id]]}
                           uid))]
    (for [player players]
      (let [game          (xt/entity db (:player/game player))
            game-players  (q db
                             '{:find (pull p [:player/score :player/ore-planets
                                              :player/erg-planets :player/mil-planets])
                               :in [game-id]
                               :where [[p :player/game game-id]]}
                             (:player/game player))
            game-scores   (map #(get % :player/score 0) game-players)
            game-planets  (map #(+ (get % :player/ore-planets 0)
                                   (get % :player/erg-planets 0)
                                   (get % :player/mil-planets 0))
                               game-players)
            player-score  (get player :player/score 0)
            rank          (inc (count (filter #(> % player-score) game-scores)))
            total-players (count game-players)]
        {:player         (assoc player :player/rank rank)
         :game           game
         :total-players  total-players
         :leader-score   (if (seq game-scores) (apply max game-scores) 0)
         :leader-planets (if (seq game-planets) (apply max game-planets) 0)}))))

(defn get-available-games
  "Fetch all games the current user has NOT joined, with player count for each.

  [db xtdb-db, uid uuid] -> [{:game game-map, :player-count int}]"
  [db uid]
  (let [games (q db
                 '{:find (pull game [*])
                   :in [user-id]
                   :where [[game :game/name _]
                           (not-join [game user-id]
                             [player :player/game game]
                             [player :player/user user-id])]}
                 uid)]
    (for [game games]
      (let [player-count (count (q db
                                   '{:find [player]
                                     :in [game-id]
                                     :where [[player :player/game game-id]]}
                                   (:xt/id game)))]
        {:game game
         :player-count player-count}))))

;;;;
;;;; UI Components
;;;;

(defn- delete-game-button
  "Render an admin-only delete button for a game, with a confirmation prompt.

  [game-id uuid] -> hiccup"
  [game-id]
  (biff/form
   {:action  (str "/app/delete-game/" game-id)
    :method  "post"
    :onsubmit "return confirm('Delete this game and all its players?')"
    :class   "m-0"}
   [:button
    {:type "submit"
     :class "py-px px-[10px] border border-game-green-muted bg-transparent text-game-green-soft rounded-sm font-mono cursor-pointer text-[26px]"}
    "✕"]))

(defn- bar-row
  "Render one metric bar row: label | progress track | value.
  When score? is true the value gets the green glow treatment and the fill always has a glow.

  [label str, pct 0-100, fill-color str, value-display str/hiccup, score? bool] -> hiccup"
  [label pct fill-color value-display score?]
  [:div.grid.items-center
   {:style {:grid-template-columns "90px 1fr 100px" :gap "14px"}}
   [:div.tracking-widest.uppercase.text-game-green-dim {:class "text-[10px]"} label]
   [:div.bg-game-divider.relative.overflow-hidden {:class "h-[6px] rounded-[1px]"}
    [:div.absolute.inset-y-0.left-0
     {:style (merge {:width (str pct "%") :background fill-color :transition "width 0.35s ease"}
                    (when score? {:box-shadow "0 0 8px rgba(61,220,132,0.4)"}))}]]
   [:div
    {:class (str "text-right font-bold text-xs " (if score? "text-green-400 text-sm" "text-game-green-soft"))
     :style (when score? {:text-shadow "0 0 6px rgba(61,220,132,0.4)"})}
    value-display]])

(defn available-game-card
  "Render a card for a game the user has not yet joined, showing player count and a join link.
  Admin users also see a delete button.

  [{:keys [game player-count admin?]}] -> hiccup"
  [{:keys [game player-count admin?]}]
  [:div.rounded-game.bg-game-bg.mb-2
   {:class "border border-game-green-border py-3 px-3.5"}
   [:div.flex.justify-between.items-center
    [:div
     [:div.font-bold.text-green-400 {:class "text-[15px]"} (:game/name game)]
     [:div.text-xs.mt-px.text-game-green-muted (str player-count " player(s)")]]
    [:div.flex.items-center.gap-2
     (ui/action-bar-link (str "/app/join-game/" (:xt/id game)) "Join")
     (when admin?
       (delete-game-button (:xt/id game)))]]])

(defn game-card
  "Render a HUD-strip card for a game the player has joined.
  The entire card is a stretched link to the game page. Bars scale relative to each game's
  leader so the player can see at a glance how they're doing in each game.

  [{:keys [player game total-players leader-score leader-planets admin?]}] -> hiccup"
  [{:keys [player game total-players leader-score leader-planets admin?]}]
  (let [player-id      (:xt/id player)
        {:keys [turn round]} (utils/display-turn-round player game)
        turns-per-round (:game/turns-per-round game)
        rounds-per-day  (:game/rounds-per-day game)
        rank           (get player :player/rank 1)
        score          (get player :player/score 0)
        stability      (get player :player/stability 100)
        planets        (+ (get player :player/ore-planets 0)
                          (get player :player/erg-planets 0)
                          (get player :player/mil-planets 0))
        ;; Default missing leaderboard values to own stats (100% bars) for graceful degradation
        total-players  (or total-players 1)
        leader-score   (or leader-score (max 1 score))
        leader-planets (or leader-planets (max 1 planets))
        ;; Percentages (0-100)
        rank-pct   (if (> total-players 1)
                     (min 100 (max 0 (long (Math/round (* (/ (double (- total-players rank))
                                                             (- total-players 1))
                                                          100.0)))))
                     100)
        plan-pct   (if (pos? leader-planets)
                     (min 100 (long (Math/round (* (/ (double planets) leader-planets) 100.0))))
                     0)
        stab-pct   (min 100 (max 0 stability))
        score-pct  (if (pos? leader-score)
                     (min 100 (long (Math/round (* (/ (double score) leader-score) 100.0))))
                     0)
        turn-pct   (if (and turns-per-round (pos? turns-per-round))
                     (min 100 (long (Math/round (* (/ (double turn) turns-per-round) 100.0))))
                     0)
        ;; Bar fill colors
        plan-fill  (if (< plan-pct 50) "#d9a441" "#2a9058")
        stab-fill  (cond (< stability 30) "#d96a6a"
                         (< stability 50) "#d9a441"
                         :else            "#2a9058")
        score-fill (if (>= score-pct 50) "#4ade80" "#d9a441")
        is-leader? (= rank 1)]
    [:div.relative.grid.items-center.rounded-game.border.border-game-border.cursor-pointer
     {:class "mb-[14px]"
      :_ "on mouseenter set my.style.boxShadow to 'inset 0 0 0 9999px rgba(61,220,132,0.04)' on mouseleave set my.style.boxShadow to ''"
      :style {:grid-template-columns "260px 1fr"
              :gap                   "36px"
              :padding               "20px 24px"
              :border-left           (str "3px solid " (if is-leader? "#4ade80" "#2a9058"))
              :background            (if is-leader?
                                       "linear-gradient(to right, rgba(61,220,132,0.08), transparent 50%)"
                                       "linear-gradient(to right, rgba(30,110,68,0.06), transparent 40%)")
              :transition            "background 0.15s, border-color 0.15s"}}

     ;; Stretched link covers entire card
     [:a.absolute.inset-0 {:href (str "/app/game/" player-id)}]

     ;; ── Left column: Identity ────────────────────────────────────────
     [:div.flex.flex-col.gap-1
      [:div.font-bold.text-green-400 {:class "text-[22px] tracking-[0.04em]"}
       (:player/empire-name player)]
      [:div.text-game-green-soft {:class "text-[13px]"}
       (:game/name game)]
      ;; Rank badge
      [:div.inline-flex.items-baseline.rounded-sm.self-start.text-game-green-muted.uppercase
       {:class "gap-1.5 mt-[10px] text-[10px] tracking-[0.12em] py-1 px-[10px] border border-game-border bg-[rgba(30,110,68,0.08)]"}
       "Rank "
       [:b {:class (str "text-sm font-bold tracking-normal " (if (< rank-pct 50) "text-yellow-400" "text-green-400"))}
        (str "#" rank)]
       (str " of " total-players)]
      ;; Turn / round line
      [:div.text-game-green-muted.mt-3 {:class "text-[11px]"}
       (str "Turn " turn "/" turns-per-round " \u00b7 Round " round "/" rounds-per-day)]
      ;; Turn progress bar
      [:div.mt-1.5.bg-game-divider.relative.overflow-hidden {:class "w-[140px] h-[3px] rounded-[1px]"}
       [:div.absolute.inset-y-0.left-0
        {:style {:width (str turn-pct "%") :background "#4a7a5a"}}]]]

     ;; ── Right column: Bars ───────────────────────────────────────────
     [:div.flex.flex-col.gap-3
      (bar-row "Planets"   plan-pct  plan-fill  (str planets)           false)
      (bar-row "Stability" stab-pct  stab-fill  (str stability "%")     false)
      (bar-row "Score"     score-pct score-fill (ui/format-number score) true)]

     ;; Admin delete button — small discrete × in the top-right corner, above the stretched link
     (when admin?
       [:div.absolute.right-3.z-10 {:class "top-[10px]"}
        (biff/form
         {:action  (str "/app/delete-game/" (:xt/id game))
          :method  "post"
          :onsubmit "return confirm('Delete this game and all its players?')"
          :class   "m-0"}
         [:button
          {:type "submit"
           :class "px-1 border-none bg-transparent text-game-green-dim font-mono text-xl leading-none cursor-pointer"}
          "\u00d7"])])]))

;;;;
;;;; Page
;;;;

(defn dashboard
  "Render the main dashboard page showing the user's active games and available games to join.

  [ctx ring-ctx] -> hiccup"
  [{:keys [session biff/db] :as ctx}]
  (let [my-games        (get-user-games db (:uid session))
        available-games (get-available-games db (:uid session))
        user            (xt/entity db (:uid session))
        admin?          (boolean (const/admin-emails (:user/email user)))]
    (ui/page
     {}
     [:div.text-base.w-full.max-w-5xl.mx-auto.overflow-hidden.relative.bg-game-bg.text-green-400.font-mono.rounded
      (ui/scanline-overlay)

      [:div {:class "p-[22px]"}

       ;; Header bar
       [:div.flex.items-center.justify-between.rounded
        {:class "mb-[22px] border-[1.5px] border-game-green-border py-3.5 px-[18px]"}
        [:div
         [:div.text-base.font-bold.text-green-400 {:class "tracking-[0.06em]"} "YOUR GAMES"]
         [:div.text-xs.mt-1.text-game-green-muted (:user/email user)]]
        (biff/form
         {:action "/auth/signout" :method "post" :class "m-0"}
         [:button
          {:type "submit"
           :class "border border-game-green-border bg-transparent text-game-green-soft rounded-sm py-1.5 px-3.5 font-mono text-xs cursor-pointer"}
          "Sign Out"])]

       ;; Active games — one HUD strip per joined game
       (if (empty? my-games)
         [:p.text-sm.mb-4.text-game-green-soft
          "You are not currently in any games."]
         [:div.mb-4
          (map #(game-card (assoc % :admin? admin?)) my-games)])

       ;; Available games to join — only shown when any exist
       (when (seq available-games)
         [:div.mb-4
          (ui/section-label "Available Games")
          (map #(available-game-card (assoc % :admin? admin?)) available-games)])

       ;; Create game button — admin only
       (when admin?
         (ui/action-bar-primary-link "/app/create-game" "Create Game"))]])))
