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
    :onsubmit "return confirm('Delete this game and all its players?')"
    :style   {:margin 0}}
   [:button
    {:type "submit"
     :style {:padding "3px 10px" :border "1px solid #7ab88a" :background "transparent"
             :color "#9adaaa" :border-radius "2px" :font-family "'Courier New', monospace"
             :cursor "pointer" :font-size "26px"}}
    "✕"]))

(defn available-game-card
  "Render a card for a game the user has not yet joined, showing player count and a join link.
  Admin users also see a delete button.

  [{:keys [game player-count admin?]}] -> hiccup"
  [{:keys [game player-count admin?]}]
  [:div
   {:style {:border "1px solid #1e6e44" :padding "12px 14px" :margin-bottom "8px"
            :background "#0e1810" :border-radius "3px"}}
   [:div.flex.justify-between.items-center
    [:div
     [:div.font-bold {:style {:color "#4ade80" :font-size "15px"}} (:game/name game)]
     [:div.text-xs.mt-px {:style {:color "#7ab88a"}} (str player-count " player(s)")]]
    [:div.flex.items-center.gap-2
     (ui/action-bar-link (str "/app/join-game/" (:xt/id game)) "Join")
     (when admin?
       (delete-game-button (:xt/id game)))]]])

(defn- stat-cell
  "Render a single labeled stat cell for the dashboard game-card grids.

  [label str, val string|number] -> hiccup"
  [label val]
  [:div
   [:div {:style {:color "#4a6a58" :font-size "9px" :text-transform "uppercase"
                  :letter-spacing "0.04em" :overflow "hidden" :text-overflow "ellipsis"
                  :white-space "nowrap"}}
    label]
   [:div {:style {:color "#9adaaa" :font-size "13px" :font-weight "bold"}}
    (cond
      (string? val) val
      (number? val) (ui/format-number val)
      (nil? val)    "0"
      :else         (throw
                      (ex-info "stat-cell expected a string or number"
                               {:label label
                                :value val
                                :type  (type val)})))]])

(defn game-card
  "Render a full empire snapshot card for a game the player has joined.
  The entire card is a stretched link to the game page. Admin users also see a delete button.

  [{:keys [player game admin?]}] -> hiccup"
  [{:keys [player game admin?]}]
  [:div.relative
   {:style {:border "1px solid #1e6e44" :padding "12px 14px" :margin-bottom "8px"
            :background "#0e1810" :border-radius "3px" :cursor "pointer"}}

   ;; Stretched link covers the whole card
   [:a.absolute.inset-0 {:href (str "/app/game/" (:xt/id player))}]

   ;; Header row: game/empire info left, rank/score/delete right
   [:div.flex.justify-between.mb-3
    [:div
     [:div {:style {:font-size "18px" :font-weight "bold" :color "#4ade80"}}
      (:player/empire-name player)]
     [:div.text-sm {:style {:color "#9adaaa"}} (:game/name game)]
     [:div.text-xs {:style {:color "#7ab88a"}} (format-turn-round player game)]]
    [:div.flex.gap-6.items-start.relative.z-10
     [:div.text-right
      [:div.text-xs {:style {:color "#7ab88a"}} "Rank"]
      [:div {:style {:font-weight "bold" :color "#4ade80"}} (:player/rank player)]]
     [:div.text-right
      [:div.text-xs {:style {:color "#7ab88a"}} "Score"]
      [:div {:style {:font-weight "bold" :color "#4ade80"}}
       (ui/format-number (:player/score player))]]
     (when admin?
       (delete-game-button (:xt/id game)))]]

   ;; Row 1: currencies, population, and planets
   [:div {:style {:display "grid" :grid-template-columns "repeat(9, 1fr)" :gap "6px"
                  :margin-bottom "10px" :padding-bottom "10px"
                  :border-bottom "1px solid #1a3020"}}
    (stat-cell "Credits"    (:player/credits player))
    (stat-cell "Food"       (:player/food player))
    (stat-cell "Fuel"       (:player/fuel player))
    (stat-cell "Galaxars"   (:player/galaxars player))
    (stat-cell "Population" (str (:player/population player) "M"))
    (stat-cell "Stability"  (str (:player/stability player) "%"))
    (stat-cell "Ore Plts"   (:player/ore-planets player))
    (stat-cell "Erg Plts"   (:player/erg-planets player))
    (stat-cell "Mil Plts"   (:player/mil-planets player))]

   ;; Row 2: military units and leadership
   [:div {:style {:display "grid" :grid-template-columns "repeat(9, 1fr)" :gap "6px"}}
    (stat-cell "Generals"   (:player/generals player))
    (stat-cell "Soldiers"   (:player/soldiers player))
    (stat-cell "Transports" (:player/transports player))
    (stat-cell "Admirals"   (:player/admirals player))
    (stat-cell "Fighters"   (:player/fighters player))
    (stat-cell "Carriers"   (:player/carriers player))
    (stat-cell "Def Stns"   (:player/stations player))
    (stat-cell "Cmd Ships"  (:player/cmd-ships player))
    (stat-cell "Agents"     (:player/agents player))]])

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
     [:div.text-base.w-full.max-w-5xl.mx-auto.overflow-hidden.relative
      {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
               :border-radius "4px" :color "#4ade80"
               :font-family "'Courier New', monospace"}}
      (ui/scanline-overlay)

      ;; Header with user info and sign-out button
      [:div.flex.items-center.justify-between
       {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "7px 14px"}}
       [:div
        [:div {:style {:font-size "18px" :font-weight "bold" :color "#4ade80"
                       :letter-spacing "0.05em"}} "YOUR GAMES"]
        [:div.text-sm.mt-px {:style {:color "#9adaaa"}} (:user/email user)]]
       (biff/form
        {:action "/auth/signout" :method "post" :style {:margin 0}}
        [:button
         {:type "submit"
          :style {:padding "5px 14px" :border "1px solid #1e6e44" :background "transparent"
                  :color "#9adaaa" :border-radius "2px" :font-family "'Courier New', monospace"
                  :cursor "pointer" :font-size "13px" :letter-spacing "0.05em"}}
         "Sign Out"])]

      [:div {:style {:padding "10px 14px"}}

       ;; Active games — one card per joined game
       (if (empty? my-games)
         [:p.text-sm.mb-4 {:style {:color "#9adaaa"}}
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
