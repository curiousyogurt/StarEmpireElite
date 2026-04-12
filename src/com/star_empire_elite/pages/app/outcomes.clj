(ns com.star-empire-elite.pages.app.outcomes
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

;; :: apply outcomes - advance to next round/turn, apply combat casualties, reset to phase 1
(defn apply-outcomes [{:keys [path-params biff/db] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player    (xt/entity db player-id)]
    (if (nil? player)
      {:status 404 :body "Player not found"}
      (if (not= (:player/current-phase player) 6)
        {:status 303 :headers {"location" (str "/app/game/" player-id)}}
        (let [game            (xt/entity db (:player/game player))
              turns-per-round (:game/turns-per-round game)
              now             (java.util.Date.)
              turns-used      (inc (:player/turns-used player))
              end-round?      (>= turns-used turns-per-round)
              next-turn       (if end-round? 1 (inc (:player/current-turn player)))
              next-round      (if end-round? (inc (:player/current-round player)) (:player/current-round player))
              reset-used      (if end-round? 0 turns-used)

              ;; read stored battle result (if any)
              stored-str    (:player/last-battle-result player)
              battle-result (when stored-str (clojure.core/read-string stored-str))
              al            (when battle-result (:attacker-losses battle-result))

              ;; load defender now so we can cap planet transfers against live counts
              defender      (when battle-result
                              (xt/entity db (java.util.UUID/fromString (:defender-id battle-result))))
              dl            (when battle-result (:defender-losses battle-result))

              ;; planet transfers — cap each type at what defender currently holds
              raw-pt        (when battle-result
                              (or (:planets-transferred battle-result) {:mil 0 :food 0 :ore 0}))
              pt-mil        (when raw-pt (min (:mil  raw-pt) (:player/mil-planets  defender)))
              pt-food       (when raw-pt (min (:food raw-pt) (:player/food-planets defender)))
              pt-ore        (when raw-pt (min (:ore  raw-pt) (:player/ore-planets  defender)))

              ;; attacker unit counts after casualties
              att-soldiers  (if al (max 0 (- (:player/soldiers  player) (:soldiers-lost  al)))
                              (:player/soldiers  player))
              att-fighters  (if al (max 0 (- (:player/fighters  player) (:fighters-lost  al)))
                              (:player/fighters  player))
              att-cmd-ships (if al (max 0 (- (:player/cmd-ships player) (:cmd-ships-lost al)))
                              (:player/cmd-ships player))
              att-generals  (if al (max 0 (- (:player/generals  player) (:generals-lost  al)))
                              (:player/generals  player))
              att-admirals  (if al (max 0 (- (:player/admirals  player) (:admirals-lost  al)))
                              (:player/admirals  player))

              ;; attacker planet counts after captures
              att-mil-planets  (+ (:player/mil-planets  player) (or pt-mil  0))
              att-food-planets (+ (:player/food-planets player) (or pt-food 0))
              att-ore-planets  (+ (:player/ore-planets  player) (or pt-ore  0))

              ;; attacker transaction — always includes all modified fields
              attacker-tx (merge {:db/doc-type                  :player
                                  :db/op                        :update
                                  :xt/id                        player-id
                                  :player/current-turn          next-turn
                                  :player/current-round         next-round
                                  :player/turns-used            reset-used
                                  :player/current-phase         1
                                  :player/last-turn-at          now
                                  :player/last-battle-result    nil
                                  :player/last-espionage-result nil
                                  :player/pending-espionage     nil
                                  :player/soldiers            att-soldiers
                                  :player/fighters            att-fighters
                                  :player/cmd-ships           att-cmd-ships
                                  :player/generals            att-generals
                                  :player/admirals            att-admirals
                                  :player/mil-planets         att-mil-planets
                                  :player/food-planets        att-food-planets
                                  :player/ore-planets         att-ore-planets}
                                 (when end-round?
                                   {:player/last-round-completed-at now}))

              ;; defender transaction (only if battle happened)
              defender-tx (when battle-result
                            {:db/doc-type         :player
                             :db/op               :update
                             :xt/id               (:xt/id defender)
                             :player/soldiers     (max 0 (- (:player/soldiers  defender) (:soldiers-lost  dl)))
                             :player/fighters     (max 0 (- (:player/fighters  defender) (:fighters-lost  dl)))
                             :player/cmd-ships    (max 0 (- (:player/cmd-ships defender) (:cmd-ships-lost dl)))
                             :player/generals     (max 0 (- (:player/generals  defender) (:generals-lost  dl)))
                             :player/admirals     (max 0 (- (:player/admirals  defender) (:admirals-lost  dl)))
                             :player/stations     (max 0 (- (:player/stations  defender) (:stations-lost  dl)))
                             :player/mil-planets  (max 0 (- (:player/mil-planets  defender) (or pt-mil  0)))
                             :player/food-planets (max 0 (- (:player/food-planets defender) (or pt-food 0)))
                             :player/ore-planets  (max 0 (- (:player/ore-planets  defender) (or pt-ore  0)))})]

          ;; submit everything atomically
          (biff/submit-tx ctx (remove nil? [attacker-tx defender-tx]))

          {:status 303
           :headers {"location" (str "/app/game/" player-id "/income")}})))))

(defn- battle-row [label af al dl att-key loss-key att-only? separator-below?]
  [:tr {:class (if separator-below? "border-b border-green-400" "border-b border-green-400 border-opacity-30")}
   [:td.py-1 label]
   [:td.text-right.px-3 (if att-only? "—" (get af att-key))]
   [:td.text-right.px-3 (if att-only? "—" (get al loss-key))]
   [:td.text-right (get dl loss-key)]])

(defn- planet-row [label enemy-losses att-wins? separator-below?]
  [:tr {:class (if separator-below? "border-b border-green-400" "border-b border-green-400 border-opacity-30")}
   [:td.py-1 label]
   [:td.text-right.px-3 "—"]
   [:td.text-right.px-3 "—"]
   [:td.text-right (if att-wins? enemy-losses "—")]])

;; :: outcomes page - review turn results and advance to next turn
(defn outcomes-page [{:keys [player game battle-result espionage-result]}]
  (let [current-turn    (:player/current-turn player)
        current-round   (:player/current-round player)
        turns-per-round (:game/turns-per-round game)
        rounds-per-day  (:game/rounds-per-day game)
        end-current-round?  (>= current-turn turns-per-round)
        one-turn-left?      (= current-turn (dec turns-per-round))]
    (ui/page
     {}
     [:div.mx-auto.max-w-4xl.w-full.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

      (ui/phase-header (:player/current-phase player) "OUTCOMES")

      [:h3.font-bold.mb-2 "Summary"]
      [:div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5
       [:div.space-y-2
        [:p (str "Turn: " current-turn " of " turns-per-round)]
        [:p (str "Round: " current-round " of " rounds-per-day)]
        (when one-turn-left?
          [:p.font-bold "Warning: One turn remaining this round."])
        (when end-current-round?
          [:p.font-bold "Warning: Completing this turn will end the current round!"])]]

      ;; Battle result section (only shown when an attack was declared)
      (when battle-result
        (let [att-wins? (:attacker-wins? battle-result)
              af        (:attacker-forces battle-result)
              df        (:defender-forces battle-result)
              al        (:attacker-losses battle-result)
              dl        (:defender-losses battle-result)
              def-name  (:defender-name battle-result)
              pt        (or (:planets-transferred battle-result) {:mil 0 :food 0 :ore 0})
              pt-total  (+ (:mil pt) (:food pt) (:ore pt))]
          [:div.border.border-green-400.p-4.mb-4
           [:h3.font-bold.mb-3
            (if att-wins? (str "Victory against " def-name) (str "Defeat by " def-name))]
           [:div.overflow-x-auto
            [:table.w-full.text-xs.table-fixed
             [:thead
              [:tr.border-b.border-green-400
               [:th.text-left.py-1   {:style {:width "10%"}} "Item"]
               [:th.text-right.py-1  {:style {:width "30%"}} "Your Forces"]
               [:th.text-right.py-1  {:style {:width "30%"}} "Your Losses"]
               [:th.text-right.py-1  {:style {:width "30%"}} (str def-name " Losses")]]]
             [:tbody
              (battle-row "Soldiers"  af al dl :soldiers  :soldiers-lost  false false)
              (battle-row "Fighters"  af al dl :fighters  :fighters-lost  false false)
              (battle-row "Cmd Ships" af al dl :cmd-ships :cmd-ships-lost false false)
              (battle-row "Generals"  af al dl :generals  :generals-lost  false false)
              (battle-row "Admirals"  af al dl :admirals  :admirals-lost  false false)
              (battle-row "Stations"  af al dl :stations  :stations-lost  true  true)
              (planet-row "Ore"       (:ore  pt) att-wins? false)
              (planet-row "Food"      (:food pt) att-wins? false)
              (planet-row "Military"  (:mil  pt) att-wins? true)]]]]
        ))
      ;; Espionage result section (only shown when an infiltration was declared)
      (when espionage-result
        (let [won?  (:attacker-wins? espionage-result)
              intel (:intel espionage-result)]
          [:div.border.border-green-400.p-4.mb-4
           [:h3.font-bold.mb-2
            (if won?
              (str "Infiltration of " (:defender-name espionage-result) " succeeded")
              (str "Infiltration of " (:defender-name espionage-result) " failed — agents discovered"))]
           (if won?
             [:div.grid.grid-cols-2.gap-x-8.gap-y-1
              [:p.text-xs.font-bold.col-span-2.mb-1 (str (:defender-name espionage-result) "'s military")]
              [:p.text-xs (str "Soldiers:   " (:soldiers   intel))]
              [:p.text-xs (str "Transports: " (:transports intel))]
              [:p.text-xs (str "Generals:   " (:generals   intel))]
              [:p.text-xs (str "Fighters:   " (:fighters   intel))]
              [:p.text-xs (str "Carriers:   " (:carriers   intel))]
              [:p.text-xs (str "Admirals:   " (:admirals   intel))]
              [:p.text-xs (str "Stations:   " (:stations   intel))]
              [:p.text-xs (str "Cmd Ships:  " (:cmd-ships  intel))]
              [:p.text-xs (str "Agents:     " (:agents     intel))]]
             [:p.text-xs "Your agents were unable to obtain useful intelligence."])]))

      (ui/resource-display-grid player "Resources")

      [:p.text-sm "Review your turn results above. When you're ready, continue to your next turn."]

      [:.h-6]
      (biff/form
       {:action (str "/app/game/" (:xt/id player) "/apply-outcomes")
        :method "post"}
       [:div.flex.gap-4
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" (:xt/id player))} "Pause"]
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         (if end-current-round? "End the Current Round" "Continue to Next Turn")]])])))
