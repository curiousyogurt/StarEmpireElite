(ns com.star-empire-elite.pages.main.home
  (:require [com.star-empire-elite.ui :as ui]))

;;; Main landing page for Star Empire Elite: the first page visitors see. Uses the same retro terminal
;;; styling as the rest of the application to establish the game's visual language.

(defn- nav-link [href label & [attrs]]
  [:a (merge {:href  href
              :style {:padding "5px 16px" :border "1px solid #1e6e44" :background "transparent"
                      :color "#9adaaa" :border-radius "2px" :font-family "'Courier New', monospace"
                      :font-size "13px" :letter-spacing "0.04em" :text-decoration "none"}}
             attrs)
   label])

(defn- pillar [icon title body]
  [:div {:style {:border "1px solid #1e6e44" :padding "12px" :border-radius "3px"
                 :background "#0e1810"}}
   [:div {:style {:font-size "20px" :margin-bottom "6px"}} icon]
   [:div.font-bold {:style {:margin-bottom "4px" :color "#4ade80"}} title]
   [:p {:style {:font-size "12px" :color "#9adaaa" :margin 0}} body]])

(defn home [ctx]
  (ui/page
    {}
    [:div.text-base.w-full.max-w-3xl.mx-auto.overflow-hidden.relative
     {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
              :border-radius "4px" :color "#4ade80"
              :font-family "'Courier New', monospace"}}
     (ui/scanline-overlay)

     ;; Title header
     [:div.text-center
      {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "20px 14px"}}
      [:div {:style {:font-size "11px" :letter-spacing "0.3em" :color "#7ab88a"
                     :margin-bottom "6px" :text-transform "uppercase"}} "★ ★ ★"]
      [:div {:style {:font-size "28px" :font-weight "bold" :color "#4ade80"
                     :letter-spacing "0.08em"}} "STAR EMPIRE ELITE"]
      [:div.mt-2 {:style {:color "#9adaaa" :font-style "italic" :font-size "13px"}}
       "Rule the universe with your friends. Or instead of them."]]

     ;; Body
     [:div {:style {:padding "16px 20px"}}

      ;; Game description
      [:p.text-center.mb-6 {:style {:color "#9adaaa" :font-size "13px" :line-height "1.7"}}
       "Star Empire Elite is a turn-based strategy game where you build a galactic empire. "
       "Acquire planets, manage resources, conduct diplomacy, and use covert operations to chart your "
       "galactic destiny."]

      ;; Four game pillars in a 2x2 grid
      [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "10px"
                     :margin-bottom "20px"}}

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
      [:div {:style {:border-top "1px solid #1e6e44" :margin-bottom "16px"}}]

      ;; Call to action section
      [:div.text-center.mb-4
       [:div {:style {:font-size "13px" :font-weight "bold" :color "#4ade80"
                      :letter-spacing "0.1em" :margin-bottom "10px"}} "GET STARTED"]]
      [:div.flex.justify-center.gap-3.mb-6
       (nav-link "/signup" "Sign In / Sign Up")
       (nav-link "/about" "About" {:hx-boost "true"})]

      ;; Footer attribution
      [:div {:style {:border-top "1px solid #1a3020" :padding-top "10px"}}
       [:p.text-center {:style {:font-size "11px" :color "#4a6a58" :margin 0}}
        "Inspired by the classic BBS game Space Dynasty by Hollie Satterfield"]]]]))
