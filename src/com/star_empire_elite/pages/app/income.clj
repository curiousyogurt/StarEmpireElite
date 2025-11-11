(ns com.star-empire-elite.pages.app.income
  (:require [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: income page - informational phase showing resource generation
(defn income-page [{:keys [player game]}]
  (let [ore-credits (* (:player/ore-planets player) (:game/ore-planet-credits game))
        ore-fuel (* (:player/ore-planets player) (:game/ore-planet-fuel game))
        ore-galaxars (* (:player/ore-planets player) (:game/ore-planet-galaxars game))
        food-food (* (:player/food-planets player) (:game/food-planet-food game))
        mil-soldiers (* (:player/military-planets player) (:game/military-planet-soldiers game))
        mil-fighters (* (:player/military-planets player) (:game/military-planet-fighters game))
        mil-stations (* (:player/military-planets player) (:game/military-planet-stations game))
        mil-agents (* (:player/military-planets player) (:game/military-planet-agents game))]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
      
      [:h2.text-xl.font-bold.mb-6 "PHASE 1: INCOME"]
      
      ;; :: current resources
      [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
       [:h3.font-bold.mb-4 "Resources Before Income"]
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
      
      ;; :: income cards - side by side on wide screens
      [:h3.font-bold.mb-4 "Income This Round"]
      [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4.mb-8
       
       ;; :: ore planets income card
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 (str "Ore Planets (" (:player/ore-planets player) ")")]
        [:div.space-y-2
         [:div
          [:p.text-xs "Credits"]
          [:p.text-lg.font-mono (str "+ " ore-credits)]]
         [:div
          [:p.text-xs "Fuel"]
          [:p.text-lg.font-mono (str "+ " ore-fuel)]]
         [:div
          [:p.text-xs "Galaxars"]
          [:p.text-lg.font-mono (str "+ " ore-galaxars)]]]]
       
       ;; :: food planets income card
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 (str "Food Planets (" (:player/food-planets player) ")")]
        [:div.space-y-2
         [:div
          [:p.text-xs "Food"]
          [:p.text-lg.font-mono (str "+ " food-food)]]]]
 
       ;; :: military planets income card
       [:div.border.border-green-400.p-4
        [:h4.font-bold.mb-3 (str "Military Planets (" (:player/military-planets player) ")")]
        [:div.space-y-2
         [:div
          [:p.text-xs "Soldiers"]
          [:p.text-lg.font-mono (str "+ " mil-soldiers)]]
         [:div
          [:p.text-xs "Fighters"]
          [:p.text-lg.font-mono (str "+ " mil-fighters)]]
         [:div
          [:p.text-xs "Stations"]
          [:p.text-lg.font-mono (str "+ " mil-stations)]]
         [:div
          [:p.text-xs "Agents"]
          [:p.text-lg.font-mono (str "+ " mil-agents)]]]]]

      ;; :: resources after income
      [:div.border.border-green-400.p-4.mb-8.bg-green-100.bg-opacity-5
       [:h3.font-bold.mb-4 "Resources After Income"]
       [:div.grid.grid-cols-3.md:grid-cols-6.lg:grid-cols-9.gap-2
        [:div
         [:p.text-xs "Credits"]
         [:p.font-mono (+ (:player/credits player) ore-credits)]]
        [:div
         [:p.text-xs "Food"]
         [:p.font-mono (+ (:player/food player) food-food)]]
        [:div
         [:p.text-xs "Fuel"]
         [:p.font-mono (+ (:player/fuel player) ore-fuel)]]
        [:div
         [:p.text-xs "Galaxars"]
         [:p.font-mono (+ (:player/galaxars player) ore-galaxars)]]
        [:div
         [:p.text-xs "Soldiers"]
         [:p.font-mono (+ (:player/soldiers player) mil-soldiers)]]
        [:div
         [:p.text-xs "Fighters"]
         [:p.font-mono (+ (:player/fighters player) mil-fighters)]]
        [:div
         [:p.text-xs "Stations"]
         [:p.font-mono (+ (:player/defence-stations player) mil-stations)]]
        [:div
         [:p.text-xs "Agents"]
         [:p.font-mono (+ (:player/agents player) mil-agents)]]]]

      [:.h-6]
      [:div.flex.gap-4
       [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
        {:href (str "/app/game/" (:xt/id player) "/expenses")} "Expenses"]]])))
