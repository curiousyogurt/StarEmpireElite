(ns com.star-empire-elite.pages.app.espionage
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: apply espionage - advance to phase 6
(defn apply-espionage [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; only allow espionage completion if player is in phase 5
      (if (not= (:player/current-phase player) 5)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (do
          ;; submit transaction to advance phase
          (biff/submit-tx ctx
            [{:db/doc-type :player
              :db/op :update
              :xt/id player-id
              :player/current-phase 6}])
          ;; redirect to outcomes page
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/outcomes")}})))))

;; :: espionage page - placeholder for phase 5
(defn espionage-page [{:keys [player game]}]
  (ui/page
   {}
   [:div.text-green-400.font-mono
    [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
    
    [:h2.text-xl.font-bold.mb-6 "PHASE 5: ESPIONAGE"]
    
    [:p.mb-4 "This phase is not yet implemented."]
    [:p.mb-4 "In this phase, you will be able to:"]
    [:ul.list-disc.pl-8.mb-4
     [:li "Deploy agents to spy on other empires"]
     [:li "Conduct sabotage operations"]
     [:li "Steal resources or technology"]
     [:li "Assassinate enemy leaders"]
     [:li "Counter-espionage to protect your empire"]]
    
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
     {:action (str "/app/game/" (:xt/id player) "/apply-espionage")
      :method "post"}
     [:div.flex.gap-4
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href (str "/app/game/" (:xt/id player))} "Back to Game"]
      [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
       {:type "submit"}
       "Continue to Outcomes"]])]))
