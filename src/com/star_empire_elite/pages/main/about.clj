(ns com.star-empire-elite.pages.main.about
  (:require [com.star-empire-elite.ui :as ui]))

;;; Static about page providing game background, gameplay overview, and technology details. Uses the
;;; same retro terminal styling as the rest of the application.

(defn section [title & body]
  [:<>
   [:h2.text-2xl.font-bold.mb-4 title]
   [:p.text-center.w-full.mb-8.text-sm.max-w-3xl body]])

(defn nav-link [href label & [attrs]]
  [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
   (merge {:href href} attrs)
   label])

(defn about [ctx]
  (ui/page
    {}
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4
     [:h1.text-4xl.font-bold.mb-6 "About This Game"]

     ;; Game heritage section
     (section "The Legacy 💾"
       [:span.font-bold "Star Empire Elite "]
       "is a modern reimagining of the classic BBS door game "
       [:span.font-bold "Space Dynasty"] " (by Hollie Satterfield). Space Dynasty itself was "
       "inspired by "
       [:span.font-bold "Space Empire Elite"] " (by Jon Radoff) and "
       [:span.font-bold "Galactic Empire"] " (by Andromeda Software).")

     ;; Historical context
     (section "The Original Games 💡"
       "These games defined a generation of BBS gaming in the late 1980s and early 1990s. Players "
       "would dial into bulletin board systems, play their turns (typically 4-6 per day), and compete "
       "asynchronously with other players on the same BBS.")

     ;; Gameplay mechanics
     (section "Core Gameplay 🎲"
       "In Star Empire Elite, you command a galactic empire with the goal of becoming the largest "
       "power. Acquire planets through purchase or conquest, manage your economy, build military "
       "fleets, form alliances, conduct diplomacy, and use covert operations to destabilize rival "
       "empires.")

     ;; Turn system explanation
     (section "Turn-Based System 🎉"
       "The game operates on a turn-based system where each player receives a fixed number of turns "
       "per time interval. On each turn, you manage planet maintenance, buy and sell food, conduct "
       "covert operations, purchase military units, attack other empires, and send diplomatic "
       "messages.")

     ;; AI opponent description
     (section "The Radoffian Dynasty 👑"
       "Beware the computer-controlled Radoffian Dynasty! This powerful AI opponent has special "
       "abilities including seizing planets without attacking and hijacking fuel freighters.")

     ;; Technical implementation details
     (section "Technology 🖥"
       "This remake is built entirely in "
       [:span.font-bold "Clojure"] ", a powerful Lisp dialect very much like "
       [:span.font-bold "Racket"] ", using a purely functional approach with modern web technologies.")

     ;; Navigation links
     [:div.flex.gap-4.mt-4
      (nav-link "/signup" "Sign Up")
      (nav-link "/signin" "Sign In")
      (nav-link "/" "Home" {:hx-boost "true"})]]))
