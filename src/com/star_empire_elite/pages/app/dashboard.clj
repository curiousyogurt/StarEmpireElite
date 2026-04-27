;;;;;
;;;;; Dashboard - Game and Empire Overview
;;;;;
;;;;; The dashboard is the main landing page after login. It lists all games the current user has
;;;;; joined, with a compact snapshot of each empire's resources, military, and turn status. It
;;;;; also shows available games the player has not yet joined, and an admin-only game creation
;;;;; link.
;;;;;

(ns com.star-empire-elite.pages.app.dashboard
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;;;;
;;;; Calculations
;;;;

(defn format-turn-round
  "Format the turn/round status string for dashboard display.
  Caps both values at their per-day/per-round maxes so that rolled-over values show the completed 
  state rather than the reset one. For example, a player who has used all turns and is waiting for the 
  next day sees 'Turn 6/6 | Round 2/2' rather than the misleading 'Turn 1 | Round 3'.

  [player game] -> string"
  [player game]
  (let [rounds-per-day  (:game/rounds-per-day game)
        turns-per-round (:game/turns-per-round game)
        current-round   (:player/current-round player)
        current-turn    (:player/current-turn player)
        ;; When current-round has rolled past the max, the day is complete and
        ;; all turns in the last round were used — display the maxes.
        day-complete?   (> current-round rounds-per-day)
        display-turn    (if day-complete? turns-per-round current-turn)
        display-round   (min current-round rounds-per-day)]
    (str "Turn " display-turn "/" turns-per-round
         " | Round " display-round "/" rounds-per-day)))

;;;;
;;;; Data Fetching
;;;;

(defn get-user-games
  "Fetch all games the current user has joined, with rank computed from scores.

  [db xtdb-db, uid uuid] -> [{:player player-map, :game game-map}]"
  [db uid]
  (let [players (filter #(not= (:player/status %) const/player-status-eliminated)
                        (q db
                           '{:find (pull player [*])
                             :in [user-id]
                             :where [[player :player/user user-id]]}
                           uid))]
    (for [player players]
      (let [game         (xt/entity db (:player/game player))
            game-scores  (map :player/score
                              (q db
                                 '{:find (pull p [:player/score])
                                   :in [game-id]
                                   :where [[p :player/game game-id]]}
                                 (:player/game player)))
            player-score (:player/score player)
            rank         (inc (count (filter #(> % player-score) game-scores)))]
        {:player (assoc player :player/rank rank)
         :game game}))))

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
    :onsubmit "return confirm('Delete this game and all its players?')"}
   [:button.border.border-green-400.text-green-400.px-2.py-1.text-sm.hover:border-yellow-400.hover:text-yellow-400.transition-colors
    {:type "submit"} "X"]))

(defn available-game-card
  "Render a card for a game the user has not yet joined, showing player count and a join link.
  Admin users also see a delete button.

  [{:keys [game player-count admin?]}] -> hiccup"
  [{:keys [game player-count admin?]}]
  [:div.border.border-green-400.p-4.mb-4.max-w-6xl
   [:div.flex.justify-between.items-center
    [:div
     [:h3.font-bold (:game/name game)]
     [:p.text-xs.text-green-400.text-opacity-75
      (str player-count " player(s)")]]
    [:div.flex.items-center.gap-2
     [:a.border.border-green-400.px-4.py-2.text-sm.transition-colors.hover:border-yellow-400.hover:text-yellow-400
      {:href (str "/app/join-game/" (:xt/id game))}
      "Join Game"]
     (when admin?
       (delete-game-button (:xt/id game)))]]])

(defn game-card
  "Render a full empire snapshot card for a game the player has joined.
  The entire card is a stretched link to the game page. Admin users also see a delete button.

  [{:keys [player game admin?]}] -> hiccup"
  [{:keys [player game admin?]}]
  [:div.border.border-green-400.p-4.mb-4.max-w-6xl.relative.hover:bg-green-400.hover:bg-opacity-10.transition-colors.cursor-pointer

   ;; Stretched link covers the whole card
   [:a.absolute.inset-0 {:href (str "/app/game/" (:xt/id player))}]

   ;; Header row: game/empire info left, rank/score/delete right
   [:div.flex.justify-between.mb-3
    [:div
     [:h3.text-2xl.font-bold (:player/empire-name player)]
     [:p.text-sm (:game/name game)]
     [:p.text-xs.text-green-400.text-opacity-75
      (format-turn-round player game)]]
    [:div.flex.gap-8.items-start.relative.z-10
     [:div.text-right
      [:p.text-xs "Rank"]
      [:p.font-bold (:player/rank player)]]
     [:div.text-right
      [:p.text-xs "Score"]
      [:p.font-bold (ui/format-number (:player/score player))]]
     (when admin?
       (delete-game-button (:xt/id game)))]]

   ;; Row 1: currencies, population, and planets
   [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2.mb-3.pb-3.border-b.border-green-400
    [:div [:p.text-xs "Credits"]    [:p.font-mono (ui/format-number (:player/credits player))]]
    [:div [:p.text-xs "Food"]       [:p.font-mono (ui/format-number (:player/food player))]]
    [:div [:p.text-xs "Fuel"]       [:p.font-mono (ui/format-number (:player/fuel player))]]
    [:div [:p.text-xs "Galaxars"]   [:p.font-mono (ui/format-number (:player/galaxars player))]]
    [:div [:p.text-xs "Population"] [:p.font-mono (str (:player/population player) "M")]]
    [:div [:p.text-xs "Stability"]  [:p.font-mono (:player/stability player) "%"]]
    [:div [:p.text-xs "Ore Plts"]   [:p.font-mono (ui/format-number (:player/ore-planets player))]]
    [:div [:p.text-xs "Erg Plts"]  [:p.font-mono (ui/format-number (:player/erg-planets player))]]
    [:div [:p.text-xs "Mil Plts"]   [:p.font-mono (ui/format-number (:player/mil-planets player))]]]

   ;; Row 2: military units and leadership
   [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2.mb-3.pb-3.border-b.border-green-400
    [:div [:p.text-xs "Generals"]   [:p.font-mono (ui/format-number (:player/generals player))]]
    [:div [:p.text-xs "Soldiers"]   [:p.font-mono (ui/format-number (:player/soldiers player))]]
    [:div [:p.text-xs "Transports"] [:p.font-mono (ui/format-number (:player/transports player))]]
    [:div [:p.text-xs "Admirals"]   [:p.font-mono (ui/format-number (:player/admirals player))]]
    [:div [:p.text-xs "Fighters"]   [:p.font-mono (ui/format-number (:player/fighters player))]]
    [:div [:p.text-xs "Carriers"]   [:p.font-mono (ui/format-number (:player/carriers player))]]
    [:div [:p.text-xs "Def Stns"]   [:p.font-mono (ui/format-number (:player/stations player))]]
    [:div [:p.text-xs "Cmd Ships"]  [:p.font-mono (ui/format-number (:player/cmd-ships player))]]
    [:div [:p.text-xs "Agents"]     [:p.font-mono (ui/format-number (:player/agents player))]]]])

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
     [:div.text-green-400.font-mono

      ;; Header with user info and sign-out button
      [:div.flex.justify-between.items-center.mb-6
       [:h1.text-3xl.font-bold "Your Games"]
       [:div.flex.items-center.gap-4
        [:span.text-sm (:user/email user)]
        (biff/form
         {:action "/auth/signout"
          :method "post"}
         [:button.border.border-green-400.px-4.py-2.text-sm.hover:bg-green-400.hover:bg-opacity-10.transition-colors
          {:type "submit"}
          "Sign Out"])]]

      ;; Active games — one card per joined game
      (if (empty? my-games)
        [:p.mb-6 "You are not currently in any games."]
        [:div.mb-6
         (map #(game-card (assoc % :admin? admin?)) my-games)])

      ;; Available games to join — only shown when any exist
      (when (seq available-games)
        [:div.mb-6
         [:h2.text-xl.font-bold.mb-4 "Available Games"]
         (map #(available-game-card (assoc % :admin? admin?)) available-games)])

      ;; Create game button — admin only
      (when admin?
        [:a.bg-green-400.text-black.px-4.py-2.font-bold.hover:bg-green-300.transition-colors.inline-block
         {:href "/app/create-game"}
         "Create Game"])])))
