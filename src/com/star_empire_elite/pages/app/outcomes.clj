;;;;;
;;;;; Outcomes Phase - Turn Results and Advancement
;;;;;
;;;;; The outcomes page is the final phase of each turn. It displays the results of any combat and
;;;;; espionage operations resolved by resolution/resolve-turn!, along with population growth,
;;;;; stability events, and elimination. All stat changes are applied on the GET request and cached
;;;;; in the player entity so that a page refresh shows the same result without recomputing.
;;;;; The POST (apply-outcomes) only handles turn advancement and cleanup: incrementing turn/round
;;;;; counters, clearing pending state, and recording the score.
;;;;;

(ns com.star-empire-elite.pages.app.outcomes
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Score
;;;;

(defn- calculate-score
  "Compute player score: planets (dominant) + military (power-weighted) + credits (tiebreaker).

  [player player-map] -> int"
  [player]
  (+ (* (:player/mil-planets  player) 500)
     (* (:player/erg-planets  player) 300)
     (* (:player/ore-planets  player) 200)
     (* (:player/soldiers     player) 1)
     (* (:player/fighters     player) 3)
     (* (:player/cmd-ships    player) 20)
     (* (:player/stations     player) 5)
     (* (:player/generals     player) 50)
     (* (:player/admirals     player) 100)
     (quot (max 0 (:player/credits player)) 1000)))

;;;;
;;;; UI Components
;;;;

;; Unit row specs for the 3-column battle table.
;; :defender-only? true for stations — the defender fights with stations, the attacker does not.
(def ^:private battle-unit-specs
  [{:label "soldiers"   :unit-key :soldiers   :loss-key :soldiers-lost   :defender-only? false}
   {:label "transports" :unit-key :transports :loss-key :transports-lost :defender-only? false}
   {:label "generals"   :unit-key :generals   :loss-key :generals-lost   :defender-only? false}
   {:label "fighters"   :unit-key :fighters   :loss-key :fighters-lost   :defender-only? false}
   {:label "carriers"   :unit-key :carriers   :loss-key :carriers-lost   :defender-only? false}
   {:label "admirals"   :unit-key :admirals   :loss-key :admirals-lost   :defender-only? false}
   {:label "cmd ships"  :unit-key :cmd-ships  :loss-key :cmd-ships-lost  :defender-only? false}
   {:label "def stns"   :unit-key :stations   :loss-key :stations-lost   :defender-only? true}])

(defn- mode-badge [mode]
  (let [[text color] (case mode
                       :raid   ["RAID"         "#4ade80"]
                       :strike ["STRIKE"       "#4ade80"]
                       :bomb   ["BOMBING"      "#facc15"]
                       :growth ["GROWTH"       "#4ade80"]
                               ["INVASION"     "#4ade80"])]
    [:span {:style {:border (str "1px solid " color) :padding "2px 7px"
                    :font-size "11px" :letter-spacing "0.12em" :color color}}
     text]))

(defn- outcome-label [won?]
  [:span {:style {:font-size "12px" :letter-spacing "0.1em"
                  :color (if won? "#4ade80" "#f87171")}}
   (if won? "▲ VICTORY ▲" "▼ DEFEAT ▼")])

(defn- gain-cell [n]
  [:span {:style {:color "#4ade80"}} (str "+" (ui/format-number n))])

(defn- opp-loss-cell [n]
  (if (pos? (or n 0))
    [:span {:style {:color "#f87171"}} (str "−" (ui/format-number n))]
    [:span {:style {:color "#4a6a58"}} "0"]))

(defn- combat-row [left center right]
  [:tr {:style {:border-bottom "1px solid #1a2820" :background "#121a18"}}
   [:td {:style {:text-align "right" :padding "4px 12px"
                 :border-right "1px solid #1a2820"}} left]
   [:td {:style {:text-align "center" :padding "4px 8px"
                 :border-right "1px solid #1a2820" :color "#7ab88a"}} center]
   [:td {:style {:text-align "left" :padding "4px 12px"}} right]])

(defn- combat-card
  "Render a combat card with header, 3-col symmetric table, and optional footer.

  Left col: your unit count + inline red loss. Right col: opponent's loss delta.
  :stations-mine? true when you are the defender (you have stations).
  :plunder-label customises the footer label (outgoing vs incoming framing)."
  [{:keys [mode opp-name won? my-counts my-losses opp-losses
           stations-mine? resources-taken planets-transferred plunder-label]}]
  (let [col-header {:font-size "10px" :letter-spacing "0.1em"
                    :color "#4a6a58" :text-transform "uppercase"
                    :font-weight "normal" :padding "6px 12px"}
        dash       [:span {:style {:color "#4a6a58"}} "—"]
        make-left  (fn [unit-key loss-key defender-only?]
                     (if (and defender-only? (not stations-mine?))
                       dash
                       [:<>
                        [:span {:style {:color "#9adaaa"}}
                         (ui/format-number (get my-counts unit-key 0))]
                        (let [loss (get my-losses loss-key 0)]
                          (when (pos? loss)
                            [:span {:style {:color "#f87171" :margin-left "6px"}}
                             (str "−" (ui/format-number loss))]))]))
        make-right (fn [loss-key defender-only?]
                     (if (and defender-only? stations-mine?)
                       dash
                       (opp-loss-cell (get opp-losses loss-key 0))))
        credits    (or (:credits resources-taken) 0)
        food       (or (:food    resources-taken) 0)
        fuel       (or (:fuel    resources-taken) 0)
        ore-pt     (or (:ore planets-transferred) 0)
        erg-pt     (or (:erg planets-transferred) 0)
        mil-pt     (or (:mil planets-transferred) 0)
        ;; Both sides always show: attacker gain (+N, left) and defender loss (−N, right).
        ;; When stations-mine? the columns are swapped: your loss on left, attacker gain on right.
        transfer-row (fn [label n]
                       (when (pos? n)
                         (if stations-mine?
                           (combat-row (opp-loss-cell n) label (gain-cell n))
                           (combat-row (gain-cell n) label (opp-loss-cell n)))))]
    [:div {:style {:border "1px solid #253530" :border-radius "3px"
                   :background "#161616" :overflow "hidden"}}
     ;; Header: mode badge + opponent name on left, victory/defeat on right
     [:div.flex.justify-between.items-center
      {:style {:padding "8px 12px"}}
      [:div.flex.items-center {:style {:gap "12px"}}
       (mode-badge mode)
       [:span.text-xl.font-bold {:style {:color "#4ade80"}} opp-name]]
      (outcome-label won?)]
     ;; Table
     [:div.overflow-x-auto
      [:table.w-full.text-xs.table-fixed
       [:thead
        [:tr {:style {:background "#151f1a"
                      :border-top "1px solid #253530"
                      :border-bottom "1px solid #253530"}}
         [:th {:style (assoc col-header :text-align "right")}   "◄ YOU"]
         [:th {:style (assoc col-header :text-align "center")}  "UNIT"]
         [:th {:style (assoc col-header :text-align "left")}    (str (str/upper-case opp-name) " ►")]]]
       [:tbody
        ;; Military unit rows
        (for [{:keys [label unit-key loss-key defender-only?]} battle-unit-specs]
          (combat-row (make-left  unit-key loss-key defender-only?)
                      label
                      (make-right loss-key defender-only?)))
        ;; Planet rows — only when planets changed hands
        (transfer-row "ore planets"      ore-pt)
        (transfer-row "energy planets"   erg-pt)
        (transfer-row "military planets" mil-pt)
        ;; Plunder rows — only when resources were taken
        (transfer-row "credits" credits)
        (transfer-row "food"    food)
        (transfer-row "fuel"    fuel)]]]]))

(defn- strike-card
  "Render a strike result using the same 3-col table structure as combat-card.
  :stations-mine? true when you are the defender."
  [{:keys [opp-name stations-mine? dispatched intercepted units-destroyed defender-stations]}]
  (let [col-header {:font-size "10px" :letter-spacing "0.1em"
                    :color "#4a6a58" :text-transform "uppercase"
                    :font-weight "normal" :padding "6px 12px"}
        dash [:span {:style {:color "#4a6a58"}} "—"]
        att-cmd [:<>
                 [:span {:style {:color "#9adaaa"}} (ui/format-number dispatched)]
                 (when (pos? intercepted)
                   [:span {:style {:color "#f87171" :margin-left "6px"}}
                    (str "−" (ui/format-number intercepted))])]
        ud   (or units-destroyed {})
        rows [["cmd ships"  att-cmd                              dash]
              ["soldiers"   dash (opp-loss-cell (:soldiers   ud))]
              ["transports" dash (opp-loss-cell (:transports ud))]
              ["generals"   dash (opp-loss-cell (:generals   ud))]
              ["fighters"   dash (opp-loss-cell (:fighters   ud))]
              ["carriers"   dash (opp-loss-cell (:carriers   ud))]
              ["admirals"   dash (opp-loss-cell (:admirals   ud))]
              ["def stns"   dash           (opp-loss-cell (:stations ud))]]]
    [:div {:style {:border "1px solid #253530" :border-radius "3px"
                   :background "#161616" :overflow "hidden"}}
     [:div.flex.items-center {:style {:padding "8px 12px" :gap "12px"}}
      (mode-badge :strike)
      [:span.text-xl.font-bold {:style {:color "#4ade80"}} opp-name]]
     [:div.overflow-x-auto
      [:table.w-full.text-xs.table-fixed
       [:thead
        [:tr {:style {:background "#151f1a"
                      :border-top "1px solid #253530"
                      :border-bottom "1px solid #253530"}}
         [:th {:style (assoc col-header :text-align "right")}  "◄ YOU"]
         [:th {:style (assoc col-header :text-align "center")} "UNIT"]
         [:th {:style (assoc col-header :text-align "left")}   (str (str/upper-case opp-name) " ►")]]]
       [:tbody
        (for [[label att-cell def-cell] rows]
          (if stations-mine?
            (combat-row def-cell label att-cell)
            (combat-row att-cell label def-cell)))]]]]))

(defn- spy-badge [op won?]
  (let [[text color] (if (not won?)
                       ["FAILED"  "#f87171"]
                       (case op
                         "spy"    ["SPY"     "#facc15"]
                         "incite" ["INCITE"  "#facc15"]
                         "defect" ["DEFECT"  "#facc15"]
                                  ["COVERT"  "#facc15"]))]
    [:span {:style {:border (str "1px solid " color) :padding "2px 7px"
                    :font-size "11px" :letter-spacing "0.12em" :color color}}
     text]))

(defn- spy-card
  [{:keys [opp-name won? op spy lost stab agents-defected]}]
  [:div {:style {:border "1px solid #253530" :border-radius "3px"
                 :background "#161616" :overflow "hidden"}}
   [:div.flex.items-center {:style {:padding "8px 12px" :gap "12px"}}
    (spy-badge op won?)
    [:span.text-xl.font-bold {:style {:color "#4ade80"}} opp-name]]
   [:div {:style {:padding "6px 12px" :border-top "1px solid #1a2820"}}
    (cond
      (and won? (= op "spy"))
      (let [units [["Soldiers"   (:soldiers   spy)]
                   ["Transports" (:transports spy)]
                   ["Generals"   (:generals   spy)]
                   ["Fighters"   (:fighters   spy)]
                   ["Carriers"   (:carriers   spy)]
                   ["Admirals"   (:admirals   spy)]
                   ["Stations"   (:stations   spy)]
                   ["Cmd Ships"  (:cmd-ships  spy)]
                   ["Agents"     (:agents     spy)]]]
        [:div.grid.grid-cols-3 {:style {:gap "2px 16px"}}
         (for [[label val] units]
           [:div.flex.justify-between.items-center
            [:span {:style {:color "#7ab88a" :font-size "12px"}} label]
            [:span {:style {:color "#9adaaa"}} (ui/format-number (or val 0))]])])

      (and won? (= op "incite"))
      [:p.text-xs {:style {:color "#9adaaa"}}
       (str "Your agents successfully stirred unrest. "
            opp-name "'s stability was reduced by " stab ".")]

      (and won? (= op "defect"))
      [:p.text-xs {:style {:color "#9adaaa"}}
       (str (or agents-defected 0)
            " agent(s) defected from " opp-name " to your service.")]

      :else
      [:p.text-xs {:style {:color "#9adaaa"}}
       (str "Your agents were unable to complete their mission. "
            lost " agent(s) were captured by " opp-name ".")])]])

(defn- bomb-card
  "Render a bombing raid result card."
  [{:keys [opp-name won? soldiers transports fighters carriers]}]
  (let [units [["Soldiers"   (or soldiers   0)]
               ["Transports" (or transports 0)]
               ["Fighters"   (or fighters   0)]
               ["Carriers"   (or carriers   0)]]]
    [:div {:style {:border "1px solid #253530" :border-radius "3px"
                   :background "#161616" :overflow "hidden"}}
     [:div.flex.items-center {:style {:padding "8px 12px" :gap "12px"}}
      (mode-badge :bomb)
      [:span.text-xl.font-bold {:style {:color "#4ade80"}}
       (str opp-name " (" (if won? "succeeded" "failed") ")")]]
     (when won?
       [:div.grid.grid-cols-4 {:style {:padding "6px 12px" :border-top "1px solid #1a2820"}}
        (for [[label val] units]
          [:div.flex.justify-between.items-center {:style {:padding-right "16px"}}
           [:span {:style {:color "#7ab88a" :font-size "12px"}} label]
           [:span {:style {:color "#9adaaa"}} (ui/format-number val)]])])]))

(defn- growth-card [{:keys [pop-growth population]}]
  [:div {:style {:border "1px solid #253530" :border-radius "3px"
                 :background "#161616" :overflow "hidden"}}
   [:div.flex.items-center {:style {:padding "8px 12px" :gap "12px"}}
    (mode-badge :growth)
    (if (pos? pop-growth)
      [:span {:style {:color "#9adaaa" :font-size "14px"}}
       "Population grew by "
       [:span {:style {:color "#4ade80"}} (str "+" (ui/format-population pop-growth))]
       [:span {:style {:color "#4a6a58"}} " · "]
       "total "
       [:span {:style {:color "#4ade80"}} (ui/format-population population)]]
      [:span {:style {:color "#9adaaa" :font-size "14px"}}
       "Population held steady this round"
       [:span {:style {:color "#4a6a58"}} " · "]
       "total "
       [:span {:style {:color "#4ade80"}} (ui/format-population population)]])]])

(defn apply-outcomes
  "Advance turn/round/phase and clear stored battle and espionage results.
  All stat changes were applied when the player loaded the outcomes page (GET);
  this POST only handles turn progression and cleanup.

  [ctx ring-ctx] -> ring-response (303 redirect to income)"
  [{:keys [path-params] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 6 player-id)]
      redirect
      (if (= (:player/status player) const/player-status-eliminated)
        {:status 303 :headers {"location" (str "/app/game/" player-id "/eliminated")}}
        (let [turns-per-round (:game/turns-per-round game)
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
                   :player/last-battle-result              nil
                   :player/last-espionage-result           nil
                   :player/last-population-growth          nil
                   :player/last-expense-stability-penalty  nil
                   :player/last-stability-breakaway        nil
                   :player/last-stability-recovery         nil
                   :player/pending-espionage               nil
                   :player/incoming-attacks                 nil
                   :player/incoming-espionage-fails         0
                   :player/incoming-espionage-agents-gained nil
                   :player/incoming-incite-stability-lost   nil
                   :player/incoming-bomb-result             nil
                   :player/pending-espionage-op             nil
                   :player/score                    (calculate-score player)}
                  (when end-round?
                    {:player/last-round-completed-at now}))])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/income")}})))))

;;;;
;;;; Page
;;;;

(defn outcomes-page
  "Show turn results (combat, espionage, population growth, expense penalties, breakaway) and the advance button.

  [{:keys [player game battle-result espionage-result pop-growth expense-penalty breakaway-result recovery-result eliminated?]}] -> hiccup"
  [{:keys [player game battle-result espionage-result pop-growth expense-penalty breakaway-result recovery-result eliminated?]}]
  (let [current-turn       (:player/current-turn player)
        turns-per-round    (:game/turns-per-round game)
        end-current-round? (>= current-turn turns-per-round)
        card-style         {:border "1px solid #253530" :border-radius "3px" :background "#161616"
                            :padding "12px"}]
    (ui/page
     {}
     [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
      {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
               :border-radius "4px" :color "#4ade80"
               :font-family "'Courier New', monospace"}}
      (ui/scanline-overlay)
      (ui/phase-topbar player game "OUTCOMES PHASE")
      ;; Body
      [:div.flex.flex-col.gap-2
       {:style {:padding "10px 14px"}}

       ;; Incoming attacks section (attacks received from other players this turn)
       (let [incoming         (seq (:player/incoming-attacks player))
             esp-fails        (or (:player/incoming-espionage-fails player) 0)
             esp-agents       (or (:player/incoming-espionage-agents-gained player) 0)
             incite-stab-lost (or (:player/incoming-incite-stability-lost player) 0)
             bomb-result      (some-> (:player/incoming-bomb-result player) clojure.core/read-string)]
         (when (or incoming (pos? esp-fails) (pos? incite-stab-lost) bomb-result)
           [:div.flex.flex-col.gap-2
            (for [r incoming]
              (let [result (clojure.core/read-string r)]
                (if (= (:mode result) :strike)
                  ;; --- Incoming strike ---
                  (strike-card {:opp-name          (:attacker-name result)
                                :stations-mine?    true
                                :dispatched        (:cmd-ships-dispatched result)
                                :intercepted       (:cmd-ships-lost result)
                                :units-destroyed   (:units-destroyed result)
                                :defender-stations (:defender-stations result)})
                  ;; --- Incoming raid/invade ---
                  (let [att-wins? (:attacker-wins? result)]
                    (combat-card {:mode                (get result :mode :invade)
                                  :opp-name            (:attacker-name result)
                                  :won?                (not att-wins?)
                                  :my-counts           (:defender-counts result)
                                  :my-losses           (:defender-losses result)
                                  :opp-losses          (:attacker-losses result)
                                  :stations-mine?      true
                                  :resources-taken     (:resources-captured result)
                                  :planets-transferred (:planets-transferred result)
                                  :plunder-label       "SEIZED FROM YOUR TREASURY"})))))
            (when (pos? esp-fails)
              [:div {:style card-style}
               [:p.font-bold {:style {:color "#4ade80"}}
                (str esp-fails " covert operation(s) against your empire were discovered and neutralized.")
                (when (pos? esp-agents)
                  (str " " esp-agents " captured agent(s) joined your forces."))]])
            (when (pos? incite-stab-lost)
              [:div {:style card-style}
               [:p.font-bold {:style {:color "#facc15"}}
                (str "Enemy agents successfully incited unrest in your empire. Stability reduced by "
                     incite-stab-lost ".")]])
            (when bomb-result
              (bomb-card {:opp-name   (:attacker-name        bomb-result)
                          :won?       true
                          :soldiers   (:soldiers-destroyed   bomb-result)
                          :transports (:transports-destroyed bomb-result)
                          :fighters   (:fighters-destroyed   bomb-result)
                          :carriers   (:carriers-destroyed   bomb-result)}))]))

       ;; Battle result section (only shown when an attack was declared)
       (when battle-result
         (let [mode     (get battle-result :mode :invade)
               def-name (:defender-name battle-result)]
           (if (= mode :strike)
             ;; --- Strike result ---
             (strike-card {:opp-name          def-name
                           :stations-mine?    false
                           :dispatched        (:cmd-ships-dispatched battle-result)
                           :intercepted       (:cmd-ships-lost       battle-result)
                           :units-destroyed   (:units-destroyed      battle-result)
                           :defender-stations (:defender-stations    battle-result)})
             ;; --- Invade / Raid result ---
             (combat-card {:mode                mode
                           :opp-name            def-name
                           :won?                (:attacker-wins? battle-result)
                           :my-counts           (:attacker-counts battle-result)
                           :my-losses           (:attacker-losses battle-result)
                           :opp-losses          (:defender-losses battle-result)
                           :stations-mine?      false
                           :resources-taken     (:resources-captured battle-result)
                           :planets-transferred (:planets-transferred battle-result)
                           :plunder-label       "PLUNDERED FROM TREASURY"}))))

       ;; Espionage result section (only shown when an operation was declared)
       (when espionage-result
         (let [won?  (:attacker-wins? espionage-result)
               op    (get espionage-result :op "spy")
               intel (:intel espionage-result)
               lost  (or (:agents-captured espionage-result) 0)
               stab  (:stability-damage espionage-result)]
           (if (= op "bomb")
             (bomb-card {:opp-name   (:defender-name espionage-result)
                         :won?       won?
                         :soldiers   (:soldiers-destroyed   espionage-result)
                         :transports (:transports-destroyed espionage-result)
                         :fighters   (:fighters-destroyed   espionage-result)
                         :carriers   (:carriers-destroyed   espionage-result)})
             (spy-card {:opp-name        (:defender-name espionage-result)
                        :won?            won?
                        :op              op
                        :spy             intel
                        :lost            lost
                        :stab            stab
                        :agents-defected (:agents-defected espionage-result)}))))

       ;; Stability section — only shown when at least one stability event occurred
       (let [has-penalty?   (and (some? expense-penalty) (pos? expense-penalty))
             has-breakaway? (and (some? breakaway-result) (:triggered? breakaway-result))
             has-recovery?  (and (some? recovery-result) (:triggered? recovery-result))]
         (when (or has-penalty? has-breakaway? has-recovery?)
           [:div {:style card-style}
            [:h3.font-bold.mb-3 {:style {:color "#4ade80"}} "Stability"]
            (when has-penalty?
              [:p.text-xs.mb-2 {:style {:color "#facc15"}}
               (str "Empire stability decreases due to unpaid expenses: −" expense-penalty "%")])
            (when has-breakaway?
              (let [lost-str (clojure.string/join ", "
                               (keep identity
                                 [(when (pos? (:ore-lost breakaway-result))
                                    (str (:ore-lost breakaway-result) " ore planet(s)"))
                                  (when (pos? (:erg-lost breakaway-result))
                                    (str (:erg-lost breakaway-result) " energy planet(s)"))
                                  (when (pos? (:mil-lost breakaway-result))
                                    (str (:mil-lost breakaway-result) " military planet(s)"))]))]
                [:p.text-xs.mb-2 {:style {:color "#f87171"}}
                 (str "Empire instability prompts revolution. Lost: " lost-str)]))
            (when has-recovery?
              [:p.text-xs {:style {:color "#4ade80"}}
               (str "Empire stability increases due to paid expenses: +" (:amount recovery-result) "%")])]))

       ;; Elimination notice (only shown when player has 0 planets)
       (when eliminated?
         [:div {:style (assoc card-style :border "1px solid #f87171")}
          [:h3.font-bold.mb-2 {:style {:color "#f87171"}} "Empire Eliminated"]
          [:p.text-xs {:style {:color "#f87171"}}
           "Your empire has no planets remaining. You have been eliminated."]])

       ;; Population growth section (only shown at end of round)
       (when (some? pop-growth)
         (growth-card {:pop-growth pop-growth
                       :population (:player/population player)}))

       (ui/snapshot-section player)]

      ;; Action bar
      [:div.flex.gap-2
       {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
       (ui/action-bar-link (str "/app/game/" (:xt/id player)) "Pause")
       (biff/form
        {:action (str "/app/game/" (:xt/id player) "/apply-outcomes") :method "post"
         :style  {:margin 0}}
        (ui/submit-button true (if end-current-round? "End the Current Round" "Continue to Next Turn")))]])))
