(ns com.star-empire-elite.pages.app.building
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: apply building - advance to phase 4
(defn apply-building [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; only allow building completion if player is in phase 3
      (if (not= (:player/current-phase player) 3)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (do
          ;; submit transaction to advance phase
          (biff/submit-tx ctx
            [{:db/doc-type :player
              :db/op :update
              :xt/id player-id
              :player/current-phase 4}])
          ;; redirect to action page
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/action")}})))))

;; :: building page - placeholder for phase 3
(defn building-page [{:keys [player game]}]
  (ui/page
   {}
   [:div.text-green-400.font-mono
    [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
    
    [:h2.text-xl.font-bold.mb-6 "PHASE 3: BUILDING"]
    
    [:p.mb-4 "This phase is not yet implemented."]
    
    [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
     [:h3.font-bold.mb-4 "Current Resources"]
     [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
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
       [:p.text-xs "Soldiers"]
       [:p.font-mono (:player/soldiers player)]]
      [:div
       [:p.text-xs "Fighters"]
       [:p.font-mono (:player/fighters player)]]
      [:div
       [:p.text-xs "Stations"]
       [:p.font-mono (:player/defence-stations player)]]
      [:div
       [:p.text-xs "Agents"]
       [:p.font-mono (:player/agents player)]]]]
    
    [:.h-6]
    (biff/form
     {:action (str "/app/game/" (:xt/id player) "/apply-building")
      :method "post"}
     [:div.flex.gap-4
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href (str "/app/game/" (:xt/id player))} "Back to Game"]
      [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
       {:type "submit"}
       "Continue to Action"]])]))
