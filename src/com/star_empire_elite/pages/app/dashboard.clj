(ns com.star-empire-elite.pages.app.dashboard
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: fetch all games for the current user
(defn get-user-games [db uid]
  (let [players (q db
                   '{:find (pull player [*])
                     :in [user-id]
                     :where [[player :player/user user-id]]}
                   uid)]
    (for [player players]
      (let [game (xt/entity db (:player/game player))]
        {:player player
         :game game}))))

;; :: fetch all games the current user has not yet joined
(defn get-open-games [db uid]
  (let [user-game-ids (->> (q db
                              '{:find [game-id]
                                :in [user-id]
                                :where [[player :player/user user-id]
                                        [player :player/game game-id]]}
                              uid)
                           (map first)
                           set)
        all-games (q db
                     '{:find (pull game [*])
                       :where [[game :game/name]]})]
    (for [game all-games
          :when (not (contains? user-game-ids (:xt/id game)))]
      (let [player-count (count (q db
                                   '{:find [player]
                                     :in [game-id]
                                     :where [[player :player/game game-id]]}
                                   (:xt/id game)))]
        {:game game
         :player-count player-count}))))

;; :: render a joinable game as a card with a join button
(defn join-game-card [{:keys [game player-count]}]
  [:div.border.border-green-400.p-4.mb-4.max-w-6xl
   [:div.flex.justify-between.items-center
    [:div
     [:h3.font-bold (:game/name game)]
     [:p.text-xs.text-green-400.text-opacity-75
      (str player-count " player" (when (not= player-count 1) "s") " joined")]]
    (biff/form
     {:action (str "/app/join-game/" (:xt/id game))
      :method "post"}
     [:button.bg-green-400.text-black.px-4.py-2.font-bold.hover:bg-green-300.transition-colors
      {:type "submit"}
      "Join"])]])

;; :: render a single game as a card with sections
(defn game-card [{:keys [player game]}]
  [:a {:href (str "/app/game/" (:xt/id player))
       :class "block"}
   [:div.border.border-green-400.p-4.mb-4.max-w-6xl.hover:bg-green-400.hover:bg-opacity-10.transition-colors.cursor-pointer
   
   ;; :: header row
   [:div.flex.justify-between.mb-3
    [:div
     [:h3.font-bold (:player/empire-name player)]
     [:p.text-xs.text-green-400.text-opacity-75 
      (str "Turn " (:player/current-turn player) 
           " | Round " (:player/current-round player) 
           " | Phase " (:player/current-phase player))]]
    [:div.flex.gap-4.text-right
     [:div
      [:p.text-xs "Rank"]
      [:p.font-bold (:player/rank player)]]
     [:div
      [:p.text-xs "Score"]
      [:p.text-lg.font-bold (:player/score player)]]]]
   
   ;; :: row 1: currencies and planets
   [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2.mb-3.pb-3.border-b.border-green-400
    [:div
     [:p.text-xs "Credits"]
     [:p.font-mono (:player/credits player)]]
    [:div
     [:p.text-xs "Food"]
     [:p.font-mono (:player/food player)]]
    [:div
     [:p.text-xs "Fuel"]
     [:p.font-mono (:player/fuel player)]]
    [:div
     [:p.text-xs "Galaxars"]
     [:p.font-mono (:player/galaxars player)]]
    [:div
     [:p.text-xs "Population"]
     [:p.font-mono (:player/population player)]]
    [:div
     [:p.text-xs "Stability"]
     [:p.font-mono (:player/stability player) "%"]]
    [:div
     [:p.text-xs "Ore Plts"]
     [:p.font-mono (:player/ore-planets player)]]
    [:div
     [:p.text-xs "Food Plts"]
     [:p.font-mono (:player/food-planets player)]]
    [:div
     [:p.text-xs "Mil Plts"]
     [:p.font-mono (:player/mil-planets player)]]]
   
   ;; :: row 2: military units and leadership
   [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2.mb-3.pb-3.border-b.border-green-400
    [:div
     [:p.text-xs "Generals"]
     [:p.font-mono (:player/generals player)]]
    [:div
     [:p.text-xs "Soldiers"]
     [:p.font-mono (:player/soldiers player)]]
    [:div
     [:p.text-xs "Transports"]
     [:p.font-mono (:player/transports player)]]
    [:div
     [:p.text-xs "Admirals"]
     [:p.font-mono (:player/admirals player)]]
    [:div
     [:p.text-xs "Fighters"]
     [:p.font-mono (:player/fighters player)]]
    [:div
     [:p.text-xs "Carriers"]
     [:p.font-mono (:player/carriers player)]]
    [:div
     [:p.text-xs "Def Stns"]
     [:p.font-mono (:player/stations player)]]
    [:div
     [:p.text-xs "Cmd Ships"]
     [:p.font-mono (:player/cmd-ships player)]]
    [:div
     [:p.text-xs "Agents"]
     [:p.font-mono (:player/agents player)]]]]])

;; :: dashboard page showing all games
(defn dashboard [{:keys [session biff/db params] :as ctx}]
  (let [uid (:uid session)
        games (get-user-games db uid)
        open-games (get-open-games db uid)
        user (xt/entity db uid)]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      ;; :: header with user info and sign out
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

      ;; :: error message if join was rejected
      (when-some [error (:error params)]
        [:p.mb-4.text-red-500
         (case error
           "already-joined" "You have already joined that game."
           "not-found"      "Game not found."
           "An error occurred.")])
      
      ;; :: games list or empty message
      (if (empty? games)
        [:p.mb-6 "You are not currently in any games."]
        [:div.mb-6
         (map game-card games)])
      
      ;; :: create test game button (always visible)
      (biff/form
       {:action "/app/create-test-game"
        :method "post"}
       [:button.bg-green-400.text-black.px-4.py-2.font-bold.hover:bg-green-300.transition-colors
        {:type "submit"}
        "Create Test Game"])

      ;; :: open games available to join
      (when (seq open-games)
        [:div.mt-8
         [:h2.text-2xl.font-bold.mb-4 "Open Games"]
         (map join-game-card open-games)])])))
