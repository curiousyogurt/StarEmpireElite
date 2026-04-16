(ns com.star-empire-elite.pages.app.outcomes
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [xtdb.api :as xt]))

(defn- calculate-score
  "Compute player score: planets (dominant) + military (power-weighted) + credits (tiebreaker).

  [player player-map] -> int"
  [player]
  (+ (* (:player/mil-planets  player) 500)
     (* (:player/food-planets player) 300)
     (* (:player/ore-planets  player) 200)
     (* (:player/soldiers     player) 1)
     (* (:player/fighters     player) 3)
     (* (:player/cmd-ships    player) 20)
     (* (:player/stations     player) 5)
     (* (:player/generals     player) 50)
     (* (:player/admirals     player) 100)
     (quot (max 0 (:player/credits player)) 1000)))

(defn apply-outcomes
  "Advance turn/round/phase and clear stored battle and espionage results.
  All stat changes were applied when the player loaded the outcomes page (GET);
  this POST only handles turn progression and cleanup.

  [ctx ring-ctx] -> ring-response (303 redirect to income)"
  [{:keys [path-params biff/db] :as ctx}]
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
              reset-used      (if end-round? 0 turns-used)]
          (biff/submit-tx ctx
            [(merge {:db/doc-type                     :player
                     :db/op                           :update
                     :xt/id                           player-id
                     :player/current-turn             next-turn
                     :player/current-round            next-round
                     :player/turns-used               reset-used
                     :player/current-phase            1
                     :player/last-turn-at             now
                     :player/last-battle-result        nil
                     :player/last-espionage-result     nil
                     :player/last-population-growth    nil
                     :player/pending-espionage         nil
                     :player/incoming-attacks         nil
                     :player/incoming-espionage-fails 0
                     :player/score                    (calculate-score player)}
                    (when end-round?
                      {:player/last-round-completed-at now}))])
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/income")}})))))

(defn- incoming-battle-row [label dc dl al unit-key loss-key def-only? separator-below?]
  [:tr {:class (if separator-below? "border-b border-green-400" "border-b border-green-400 border-opacity-30")}
   [:td.py-1 label]
   [:td.text-right.px-3 (get dc unit-key)]
   [:td.text-right.px-3 (get dl loss-key)]
   [:td.text-right (if def-only? "—" (get al loss-key))]])

(defn- incoming-planet-row [label def-losses separator-below?]
  [:tr {:class (if separator-below? "border-b border-green-400" "border-b border-green-400 border-opacity-30")}
   [:td.py-1 label]
   [:td.text-right.px-3 "—"]
   [:td.text-right.px-3 def-losses]
   [:td.text-right "—"]])

(defn- incoming-attack-section [result]
  (let [att-wins? (:attacker-wins? result)
        att-name  (:attacker-name result)
        dc        (:defender-counts result)
        al        (:attacker-losses result)
        dl        (:defender-losses result)
        pt        (:planets-transferred result)]
    [:div.border.border-green-400.p-4.mb-4
     [:h3.font-bold.mb-3
      (if att-wins?
        (str "Attacked by " att-name " — defeat")
        (str "Attacked by " att-name " — repelled"))]
     [:div.overflow-x-auto
      [:table.w-full.text-xs.table-fixed
       [:thead
        [:tr.border-b.border-green-400
         [:th.text-left.py-1  {:style {:width "10%"}} "Item"]
         [:th.text-right.py-1 {:style {:width "30%"}} "Your Forces"]
         [:th.text-right.py-1 {:style {:width "30%"}} "Your Losses"]
         [:th.text-right.py-1 {:style {:width "30%"}} (str att-name " Losses")]]]
       [:tbody
        (incoming-battle-row "Soldiers"   dc dl al :soldiers   :soldiers-lost   false false)
        (incoming-battle-row "Transports" dc dl al :transports :transports-lost false false)
        (incoming-battle-row "Generals"   dc dl al :generals   :generals-lost   false false)
        (incoming-battle-row "Fighters"   dc dl al :fighters   :fighters-lost   false false)
        (incoming-battle-row "Carriers"   dc dl al :carriers   :carriers-lost   false false)
        (incoming-battle-row "Admirals"   dc dl al :admirals   :admirals-lost   false false)
        (incoming-battle-row "Cmd Ships"  dc dl al :cmd-ships  :cmd-ships-lost  false false)
        (incoming-battle-row "Stations"   dc dl al :stations   :stations-lost   true  true)
        (incoming-planet-row "Ore"      (:ore  pt) false)
        (incoming-planet-row "Food"     (:food pt) false)
        (incoming-planet-row "Military" (:mil  pt) true)]]]]))

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

(defn outcomes-page
  "Show turn results (combat, espionage, population growth) and the advance button for the next turn.

  [{:keys [player game battle-result espionage-result pop-growth]}] -> hiccup"
  [{:keys [player game battle-result espionage-result pop-growth]}]
  (let [current-turn        (:player/current-turn player)
        turns-per-round     (:game/turns-per-round game)
        end-current-round?  (>= current-turn turns-per-round)]
    (ui/page
     {}
     [:div.mx-auto.max-w-4xl.w-full.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

      (ui/phase-header (:player/current-phase player) "OUTCOMES"
                       (str "Turn " (:player/current-turn player) " | Round " (:player/current-round player)))

      ;; Incoming attacks section (attacks received from other players this turn)
      (let [incoming (seq (:player/incoming-attacks player))
            esp-fails (or (:player/incoming-espionage-fails player) 0)]
        (when (or incoming (pos? esp-fails))
          [:div
           (for [r incoming]
             (incoming-attack-section (clojure.core/read-string r)))
           (when (pos? esp-fails)
             [:div.border.border-green-400.p-4.mb-4
              [:p.font-bold (str esp-fails " infiltration attempt(s) against your empire were discovered and neutralized.")]])]))

      ;; Battle result section (only shown when an attack was declared)
      (when battle-result
        (let [att-wins? (:attacker-wins? battle-result)
              ac        (:attacker-counts battle-result)
              al        (:attacker-losses battle-result)
              dl        (:defender-losses battle-result)
              def-name  (:defender-name battle-result)
              pt        (:planets-transferred battle-result)
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
              (battle-row "Soldiers"   ac al dl :soldiers   :soldiers-lost   false false)
              (battle-row "Transports" ac al dl :transports :transports-lost false false)
              (battle-row "Generals"   ac al dl :generals   :generals-lost   false false)
              (battle-row "Fighters"   ac al dl :fighters   :fighters-lost   false false)
              (battle-row "Carriers"   ac al dl :carriers   :carriers-lost   false false)
              (battle-row "Admirals"   ac al dl :admirals   :admirals-lost   false false)
              (battle-row "Cmd Ships"  ac al dl :cmd-ships  :cmd-ships-lost  false false)
              (battle-row "Stations"   ac al dl :stations   :stations-lost   true  true)
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

      ;; Population growth section (only shown at end of round)
      (when (some? pop-growth)
        [:div.border.border-green-400.p-4.mb-4
         (if (pos? pop-growth)
           [:p.font-bold (str "Population grew by " pop-growth " million this round.")]
           [:p.font-bold "Population held steady this round."])])

      (ui/extended-resource-display-grid player "Resources" false)

      (biff/form
       {:action (str "/app/game/" (:xt/id player) "/apply-outcomes")
        :method "post"}
       [:div.flex.gap-4.mt-6
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" (:xt/id player))} "Pause"]
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         (if end-current-round? "End the Current Round" "Continue to Next Turn")]])])))
