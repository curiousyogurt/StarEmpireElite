(ns com.star-empire-elite.pages.app.espionage
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: fetch all other players in the same game, sorted by score descending
(defn get-other-players [db game-id current-player-id]
  (let [players (q db '{:find (pull player [*])
                        :in [game-id current-player-id]
                        :where [[player :player/game game-id]
                                [(not= player current-player-id)]]}
                   game-id current-player-id)]
    (sort-by :player/score > (seq players))))

;; :: render a single row in the targets table with an intelligence button
(defn target-row [player current-player-id]
  (let [total-planets (+ (:player/mil-planets player)
                         (:player/food-planets player)
                         (:player/ore-planets player))]
    [:tr.border-b.border-green-400
     [:td.border-r.border-green-400.px-3.py-2.w-56 (:player/empire-name player)]
     [:td.border-r.border-green-400.px-3.py-2.text-right.w-36 total-planets]
     [:td.border-r.border-green-400.px-3.py-2.text-right.w-36 (:player/score player)]
     [:td.px-3.py-2
      (biff/form
       {:action (str "/app/game/" current-player-id "/apply-espionage")
        :method "post"}
       [:input {:type "hidden" :name "target-player-id" :value (str (:xt/id player))}]
       [:button.w-full.px-3.py-1.text-sm.font-bold.bg-black.text-green-400.border.border-green-400.hover:text-yellow-400.hover:border-yellow-400.transition-colors
        {:type "submit"}
        "Infiltrate"])]]))

;; :: espionage page - choose a target to infiltrate or skip espionage
(defn espionage-page [{:keys [player game db]}]
  (let [player-id (:xt/id player)
        other-players (get-other-players db (:player/game player) player-id)]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

      (ui/phase-header (:player/current-phase player) "ESPIONAGE")

      (if (empty? other-players)
        [:p.mb-6 "There are no other empires to infiltrate."]
        [:div.mb-6
         [:h2.text-xl.font-bold.mb-4 "Choose a Target"]
         [:p.text-sm.mb-4.text-green-400.text-opacity-75
          "Select an empire to infiltrate. Espionage results will be revealed in the Outcomes phase."]
         [:div.overflow-x-auto
          [:table.w-full.text-sm.border.border-green-400
           [:thead
            [:tr.border-b.border-green-400
             [:th.border-r.border-green-400.px-3.py-2.text-left.w-56 "Empire"]
             [:th.border-r.border-green-400.px-3.py-2.text-right.w-36 "Planets"]
             [:th.border-r.border-green-400.px-3.py-2.text-right.w-36 "Score"]
             [:th.px-3.py-2 ""]]]
           [:tbody
            (for [target other-players]
              (target-row target player-id))]]]])

      [:.h-6]
      (biff/form
       {:action (str "/app/game/" player-id "/apply-espionage")
        :method "post"}
       [:div.flex.gap-4
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" player-id)} "Back to Game"]
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         "Continue to Outcomes"]])])))

;; :: apply espionage - store pending espionage target (if any) and advance to phase 6
(defn apply-espionage [{:keys [path-params params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      (if (not= (:player/current-phase player) 5)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (let [target-str (:target-player-id params)
              target-id (when (and target-str (not (empty? target-str)))
                          (java.util.UUID/fromString target-str))
              tx-map (cond-> {:db/doc-type :player
                              :db/op :update
                              :xt/id player-id
                              :player/current-phase 6}
                       target-id (assoc :player/pending-espionage target-id))]
          (biff/submit-tx ctx [tx-map])
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/outcomes")}})))))
