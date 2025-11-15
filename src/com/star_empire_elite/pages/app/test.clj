(ns com.star-empire-elite.pages.app.exchange
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: exchange page - placeholder for asset/resource trading in phase 2
(defn exchange-page [{:keys [player game]}]
  (ui/page
   {}
   [:div.text-green-400.font-mono
    [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
    
    (ui/phase-header (:player/current-phase player) "EXCHANGE")
    
    [:p.mb-4 "This page is not yet implemented."]
    [:p.mb-4 "In this phase, you will be able to:"]
    [:ul.list-disc.pl-8.mb-4
     [:li "Sell assets for credits"]
     [:li "Buy resources with credits"]
     [:li "Exchange resources (credits for food/fuel)"]
     [:li "Liquidate military units if needed"]]
    
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
     {:action (str "/app/game/" (:xt/id player))
      :method "get"}
     [:div.flex.gap-4
      [:button.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:type "submit"}
       "Back to Expenses"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href (str "/app/game/" (:xt/id player))} "Back to Game"]])]))
