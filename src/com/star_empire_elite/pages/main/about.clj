(ns com.star-empire-elite.pages.main.about
  (:require [com.star-empire-elite.ui :as ui]))

;; :: about page
(defn about [ctx]
  (ui/page
    {}
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4
     [:h1.text-4xl.font-bold.mb-6 "About This Game"]
     
     [:h2.text-2xl.font-bold.mb-4 "The Legacy 💾"]
     [:p.text-center.w-full.mb-8.text-sm.max-w-3xl
      [:span.font-bold "Star Empire Elite "]
      "is a modern reimagining of the classic BBS door game "
      [:span.font-bold "Space Dynasty"] " (by Hollie Satterfield). Space Dynasty itself was "
      "inspired by "
      [:span.font-bold "Space Empire Elite"] " (by Jon Radoff) and "
      [:span.font-bold "Galactic Empire"] " (by Andromeda Software)."]
     
     [:h2.text-2xl.font-bold.mb-4 "The Original Games 💡"]
     [:p.text-center.w-full.mb-8.text-sm.max-w-3xl
      "These games defined a generation of BBS gaming in the late 1980s and early 1990s. Players "
      "would dial into bulletin board systems, play their turns (typically 4-6 per day), and compete "
      "asynchronously with other players on the same BBS."]
     
     [:h2.text-2xl.font-bold.mb-4 "Core Gameplay 🎲"]
     [:p.text-center.w-full.mb-8.text-sm.max-w-3xl
      "In Star Empire Elite, you command a galactic empire with the goal of becoming the largest "
      "power. Acquire planets through purchase or conquest, manage your economy, build military " 
      "fleets, form alliances, conduct diplomacy, and use covert operations to destabilize rival "
      "empires."]
     
     [:h2.text-2xl.font-bold.mb-4 "Turn-Based System 🎉"]
     [:p.text-center.w-full.mb-8.text-sm.max-w-3xl
      "The game operates on a turn-based system where each player receives a fixed number of turns "
      "per day. On each turn, you manage planet maintenance, buy and sell food, conduct covert "
      "operations, purchase military units, attack other empires, and send diplomatic messages."]
     
     [:h2.text-2xl.font-bold.mb-4 "The Radoffian Dynasty 👑"]
     [:p.text-center.w-full.mb-8.text-sm.max-w-3xl
      "Beware the computer-controlled Radoffian Dynasty! This powerful AI opponent has special "
      "abilities including seizing planets without attacking and hijacking fuel freighters."]
     
     [:h2.text-2xl.font-bold.mb-4 "Technology 🖥"]
     [:p.text-center.w-full.mb-8.text-sm.max-w-3xl
      "This remake is built entirely in "
      [:span.font-bold "Clojure"] ", a powerful Lisp dialect very much like "
      [:span.font-bold "Racket"] ", using a purely functional approach with modern web technologies."]
     
     [:div.flex.gap-4.mt-4
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href "/signup"} "Sign Up"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href "/signin"} "Sign In"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href "/" :hx-boost "true"} "Back to Home"]]]))
 
