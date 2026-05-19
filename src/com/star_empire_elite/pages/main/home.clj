(ns com.star-empire-elite.pages.main.home
  (:require [com.star-empire-elite.ui :as ui]))

;;; Main landing page for Star Empire Elite: the first page visitors see. Uses the same retro terminal
;;; styling as the rest of the application to establish the game's visual language.

(defn- nav-link [href label & [attrs]]
  [:a (merge {:href  href
              :class "no-underline text-game-green-soft font-mono rounded-sm border border-game-green-border bg-transparent tracking-[0.04em] py-[5px] px-[16px] inline-block text-[13px]"}
             attrs)
   label])

(defn- pillar [icon title body]
  [:div.rounded-game.bg-game-bg.p-3 {:class "border border-game-green-border"}
   [:div.text-xl.mb-1.5 icon]
   [:div.font-bold.mb-1.text-green-400 title]
   [:p.text-xs.m-0.text-game-green-soft body]])

(defn home [ctx]
  (ui/page
    {}
    [:div.text-base.w-full.max-w-3xl.mx-auto.overflow-hidden.relative.bg-game-bg.text-green-400.font-mono
     {:class "border-[1.5px] border-game-green-border rounded"}
     (ui/scanline-overlay)

     ;; Title header
     [:div.text-center.bg-game-surface.border-b.border-game-green-border
      {:class "py-5 px-3.5"}
      [:div.text-game-green-muted.uppercase.mb-1.5 {:class "text-[11px] tracking-[0.3em]"} "★ ★ ★"]
      [:div.font-bold.text-green-400 {:class "text-[28px] tracking-[0.08em]"} "STAR EMPIRE ELITE"]
      [:div.mt-2.text-game-green-soft.italic {:class "text-[13px]"}
       "Rule the universe with your friends. Or instead of them."]]

     ;; Body
     [:div {:class "py-4 px-5"}

      ;; Game description
      [:p.text-center.mb-6.text-game-green-soft {:class "text-[13px] leading-[1.7]"}
       "Star Empire Elite is a turn-based strategy game where you build a galactic empire. "
       "Acquire planets, manage resources, conduct diplomacy, and use covert operations to chart your "
       "galactic destiny."]

      ;; Four game pillars in a 2x2 grid
      [:div.grid.grid-cols-2.mb-5 {:class "gap-[10px]"}

       ;; Empire building pillar
       (pillar "🪐" "Empire Building"
         "Acquire and manage planets, feed your people, and grow your economy.")

       ;; Military power pillar
       (pillar "🚀" "Military Power"
         "Build fleets, attack rivals, and defend your territory from invasion.")

       ;; Diplomacy pillar
       (pillar "🎁" "Diplomacy"
         "Form alliances, negotiate peace, and scheme against your enemies.")

       ;; Covert operations pillar
       (pillar "🔍" "Covert Ops"
         "Deploy agents to spy, sabotage, and destabilize rival empires.")]

      ;; Divider
      [:div.border-t.border-game-green-border.mb-4]

      ;; Call to action section
      [:div.text-center.mb-4
       [:div.font-bold.text-green-400 {:class "mb-[10px] text-[13px] tracking-widest"} "GET STARTED"]]
      [:div.flex.justify-center.gap-3.mb-6
       (nav-link "/signup" "Sign In / Sign Up")
       (nav-link "/about" "About" {:hx-boost "true"})]

      ;; Footer attribution
      [:div.border-t.border-game-divider {:class "pt-[10px]"}
       [:p.text-center.m-0.text-game-green-dim {:class "text-[11px]"}
        "Inspired by the classic BBS game Space Dynasty by Hollie Satterfield"]]]]))
