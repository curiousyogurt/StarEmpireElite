(ns com.star-empire-elite.pages.main.home
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]))

;; :: home page - the landing page for the game
;; displays the title, game description, and four core game pillars
(defn home [ctx]
  (ui/page
    {}
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4

     ;; :: title section with decorative stars
     [:div.text-center.mb-8
      [:span.star.text-2xl "★ "]
      [:h1.text-5xl.font-bold.glow "STAR EMPIRE ELITE"]
      [:span.star.text-2xl " ★"]]
     
     ;; :: subtitle
     [:p.text-lg.italic.mb-8 "Shape destiny. Write history. Become legend."]
     
     ;; :: divider line
     [:div.w-96.border-t.border-green-400.mb-8]
     
     ;; :: welcome greeting
     [:h2.text-3xl.font-bold.mb-8 "Welcome, Player One"]
     
     ;; :: game description
     [:p.text-center.w-full.mb-12.text-sm.leading-relaxed.max-w-3xl
      "Star Empire Elite is a turn-based strategy game where you found and manage a galactic empire. "
      "Acquire planets, manage resources, conduct diplomacy, and use covert operations to create a "
      "galactic dynasty."]
     
     ;; :: four game pillars in a 2x2 grid
     [:div.grid.grid-cols-2.gap-6.mb-12.w-full.max-w-2xl
      
      ;; :: empire building pillar
      [:div.border.border-green-400.p-4.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       [:div.text-2xl.mb-2 "🪐"]
       [:h3.font-bold.mb-2 "Empire Building"]
       [:p.text-xs "Acquire and manage planets, feed your people, and grow your economy."]]
      
      ;; :: military power pillar
      [:div.border.border-green-400.p-4.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       [:div.text-2xl.mb-2 "🚀"]
       [:h3.font-bold.mb-2 "Military Power"]
       [:p.text-xs "Build fleets, attack rivals, and defend your territory from invasion."]]
      
      ;; :: diplomacy pillar
      [:div.border.border-green-400.p-4.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       [:div.text-2xl.mb-2 "🎁"]
       [:h3.font-bold.mb-2 "Diplomacy"]
       [:p.text-xs "Form alliances, negotiate peace, and scheme against your enemies."]]
      
      ;; :: covert operations pillar
      [:div.border.border-green-400.p-4.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       [:div.text-2xl.mb-2 "🔍"]
       [:h3.font-bold.mb-2 "Covert Ops"]
       [:p.text-xs "Deploy agents to spy, sabotage, and destabilize rival empires."]]]
     
     ;; :: call to action section
     [:h3.text-2xl.font-bold.mb-4 "Get Started"]
     [:div.flex.gap-4
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href "/signup"} "Sign Up"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href "/signin"} "Sign In"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href "/about" :hx-boost "true"} "About"]]
     
     ;; :: footer divider
     [:div.w-96.border-t.border-green-400.mt-12.mb-4]
     
     ;; :: footer attribution
     [:p.text-xs.text-green-400.text-opacity-75
      "Inspired by the classic BBS game Space Dynasty by Hollie Satterfield"]]))
