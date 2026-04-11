(ns com.star-empire-elite.pages.app.outcomes
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: apply outcomes - advance to next round/turn and reset to phase 1
(defn apply-outcomes [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player (xt/entity db player-id)]
    (if (nil? player)
      {:status 404
       :body "Player not found"}
      ;; only allow outcomes completion if player is in phase 6
      (if (not= (:player/current-phase player) 6)
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}
        (let [;; determine if we're advancing turn or round
              current-turn (:player/current-turn player)
              current-round (:player/current-round player)
              game (xt/entity db (:player/game player))
              turns-per-round (:game/turns-per-round game)
              rounds-per-day (:game/rounds-per-day game)
              
              ;; calculate next turn and round
              turns-used (inc (:player/turns-used player))

              ;; check if we need to advance to next round
              should-advance-round (>= turns-used turns-per-round)
              next-turn (if should-advance-round 1 (inc current-turn))
              next-round (if should-advance-round (inc current-round) current-round)
              reset-turns-used (if should-advance-round 0 turns-used)]
          
          ;; submit transaction to advance turn/round and reset to phase 1
          (biff/submit-tx ctx
            [{:db/doc-type :player
              :db/op :update
              :xt/id player-id
              :player/current-turn next-turn
              :player/current-round next-round
              :player/turns-used reset-turns-used
              :player/current-phase 1
              :player/last-turn-at (java.util.Date.)}])
          
          ;; redirect to income page
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/income")}})))))

;; :: outcomes page - review turn results and advance to next turn
(defn outcomes-page [{:keys [player game]}]
  (let [current-turn (:player/current-turn player)
        current-round (:player/current-round player)
        turns-per-round (:game/turns-per-round game)
        rounds-per-day (:game/rounds-per-day game)
        will-advance-round (>= current-turn turns-per-round)]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

      (ui/phase-header (:player/current-phase player) "OUTCOMES")

      [:h3.font-bold.mb-2 "Summary"]
      [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
       [:div.space-y-2
        [:p (str "Turn: " current-turn " of " turns-per-round)]
        [:p (str "Round: " current-round " of " rounds-per-day)]
        (when will-advance-round
          [:p.text-yellow-400.font-bold "⚠ Completing this turn will advance you to the next round!"])]]
      
      (ui/resource-display-grid player "Resources")
      
      [:p.text-sm "Review your turn results above. When you're ready, begin your next turn."]
      
      [:.h-6]
      (biff/form
       {:action (str "/app/game/" (:xt/id player) "/apply-outcomes")
        :method "post"}
       [:div.flex.gap-4
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" (:xt/id player))} "Pause"]
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         (if will-advance-round "Continue to Next Round" "Continue to Next Turn")]])])))
