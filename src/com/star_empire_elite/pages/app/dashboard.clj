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
     [:p.font-mono (:player/military-planets player)]]]
   
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
     [:p.text-xs "Com Shpps"]
     [:p.font-mono (:player/command-ships player)]]
    [:div
     [:p.text-xs "Agents"]
     [:p.font-mono (:player/agents player)]]]]])

;; :: dashboard page showing all games
(defn dashboard [{:keys [session biff/db] :as ctx}]
  (let [games (get-user-games db (:uid session))
        user (xt/entity db (:uid session))]
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
        "Create Test Game"])])))
