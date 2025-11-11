(ns com.star-empire-elite.pages.app.game
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: helper function to get the correct URL for a player's current phase
(defn get-phase-url [player-id current-phase]
  (case current-phase
    1 (str "/app/game/" player-id "/play")
    2 (str "/app/game/" player-id "/expenses")
    3 (str "/app/game/" player-id "/building")
    4 (str "/app/game/" player-id "/action")
    5 (str "/app/game/" player-id "/espionage")
    6 (str "/app/game/" player-id "/outcomes")
    ;; default to game overview if phase is invalid
    (str "/app/game/" player-id)))

;; :: game header showing key resources and status
(defn game-header [{:keys [player]}]
  [:div.grid.grid-cols-3.md:grid-cols-5.lg:grid-cols-9.gap-2.mb-6.pb-4.border-b.border-green-400
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
    [:p.text-xs "Planets"]
    [:p.font-mono (+ (:player/military-planets player)
                     (:player/food-planets player)
                     (:player/ore-planets player))]]
   [:div
    [:p.text-xs "Round"]
    [:p.font-mono (:player/current-round player)]]
   [:div
    [:p.text-xs "Phase"]
    [:p.font-mono (:player/current-phase player)]]])

(defn get-game-players [db game-id]
  (let [results (q db
                   '{:find (pull player [*])
                     :in [game-id]
                     :where [[player :player/game game-id]]}
                   game-id)
        ;; results is already a set of maps, just convert to seq
        player-list (seq results)
        sorted-players (sort-by :player/score > player-list)]
    (map-indexed (fn [idx player]
                   (assoc player :rank (inc idx)))
                 sorted-players)))

;; :: render players table
(defn players-table [{:keys [players]}]
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
          (+ (:player/military-planets player)
             (:player/food-planets player)
             (:player/ore-planets player))]
         [:td.px-3.py-2 (:player/score player)]])]]]])

;; :: main game view
(defn game-view [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)
        game (xt/entity db (:player/game player))
        players (get-game-players db (:xt/id game))]
    (if (nil? player)
      {:status 404
       :body "Game not found"}
      (ui/page
       {}
       [:div.text-green-400.font-mono
        [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
        (game-header {:player player})
        (players-table {:players players})
        [:.h-6]
        [:div.flex.gap-4
         [:a.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
          {:href (get-phase-url (:xt/id player) (:player/current-phase player))} "Play"]
         [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
          {:href "/app"} "Back"]
         ]]))))
