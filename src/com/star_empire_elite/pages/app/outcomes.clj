;;;;;
;;;;; Outcomes Phase - Turn Results and Advancement
;;;;;
;;;;; The outcomes page is the final phase of each turn. It displays the results of any combat and
;;;;; espionage operations that were resolved when the page was first loaded (in outcomes-handler in
;;;;; app.clj), along with population growth at the end of a round. All stat changes — casualties,
;;;;; captured planets, population — are applied on the GET request and cached in the player entity
;;;;; so that a page refresh shows the same result without recomputing. The POST (apply-outcomes)
;;;;; only handles turn advancement and cleanup: incrementing turn/round counters, clearing pending
;;;;; state, and recording the score.
;;;;;

(ns com.star-empire-elite.pages.app.outcomes
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Calculations
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

(defn- distribute-breakaway-planets
  "Distribute n planet losses proportionally across ore/erg/mil counts.
  Uses largest-remainder method so the total always equals n exactly.

  [ore int, erg int, mil int, n int] -> {:ore int, :erg int, :mil int}"
  [ore erg mil n]
  (let [total (+ ore erg mil)]
    (if (zero? total)
      {:ore 0 :erg 0 :mil 0}
      (let [ore-frac (* n (/ (double ore) total))
            erg-frac (* n (/ (double erg) total))
            mil-frac (* n (/ (double mil) total))
            ore-base (long ore-frac)
            erg-base (long erg-frac)
            mil-base (long mil-frac)
            remainder (- n ore-base erg-base mil-base)
            ;; Distribute remainder to types with largest fractional parts
            by-frac  (sort-by second >
                       [[:ore (- ore-frac ore-base)]
                        [:erg (- erg-frac erg-base)]
                        [:mil (- mil-frac mil-base)]])
            extras   (set (map first (take remainder by-frac)))]
        {:ore (min ore (+ ore-base (if (extras :ore) 1 0)))
         :erg (min erg (+ erg-base (if (extras :erg) 1 0)))
         :mil (min mil (+ mil-base (if (extras :mil) 1 0)))}))))

(defn calculate-stability-breakaway
  "Roll for a stability breakaway event. Returns a result map whether or not
  a breakaway occurs; :triggered? indicates whether planets were lost.

  [player player-map, game game-map] -> result-map"
  [player game]
  (let [stability  (:player/stability player)
        threshold  (:game/stability-breakaway-threshold game)
        cap        (/ (:game/stability-breakaway-cap game) 100.0)
        roll       (inc (rand-int 100))
        triggered? (> roll (+ stability threshold))]
    (if-not triggered?
      {:roll roll :stability stability :triggered? false}
      (let [ore   (:player/ore-planets player)
            erg   (:player/erg-planets player)
            mil   (:player/mil-planets player)
            total (+ ore erg mil)
            fraction     (min cap (/ (double (- roll stability threshold)) 100.0))
            planets-lost (max 1 (long (Math/ceil (* fraction total))))
            lost         (distribute-breakaway-planets ore erg mil planets-lost)]
        {:roll        roll
         :stability   stability
         :triggered?  true
         :ore-lost    (:ore lost)
         :erg-lost    (:erg lost)
         :mil-lost    (:mil lost)
         :total-lost  planets-lost}))))

(defn calculate-stability-recovery
  "Roll for a stability recovery event. Only call when expenses were fully paid
  and stability is below 100. Triggers if roll < max(stability, 50), so higher
  stability means a greater chance of recovery, with a 50% floor.

  [player player-map, game game-map] -> result-map"
  [player game]
  (let [stability  (:player/stability player)
        amount     (:game/stability-recovery-amount game)
        floor      (:game/stability-recovery-floor game)
        roll       (inc (rand-int 100))
        target     (max stability floor)
        triggered? (< roll target)]
    {:roll       roll
     :stability  stability
     :triggered? triggered?
     :amount     (when triggered? (min amount (- 100 stability)))}))

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
  [:span {:style {:border "1px solid #4ade80" :padding "2px 7px"
                  :font-size "11px" :letter-spacing "0.12em" :color "#4ade80"}}
   (case mode :raid "RAID" :strike "STRIKE" "INVASION")])

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
        def-stns [:span {:style {:color "#9adaaa"}} (ui/format-number (or defender-stations 0))]
        ud   (or units-destroyed {})
        rows [["cmd ships"  att-cmd                              dash]
              ["soldiers"   dash (opp-loss-cell (:soldiers   ud))]
              ["transports" dash (opp-loss-cell (:transports ud))]
              ["generals"   dash (opp-loss-cell (:generals   ud))]
              ["fighters"   dash (opp-loss-cell (:fighters   ud))]
              ["carriers"   dash (opp-loss-cell (:carriers   ud))]
              ["admirals"   dash (opp-loss-cell (:admirals   ud))]
              ["def stns"   dash def-stns]]]
    [:div {:style {:border "1px solid #253530" :border-radius "3px"
                   :background "#161616" :overflow "hidden"}}
     [:div.flex.items-center {:style {:padding "8px 12px" :gap "12px"}}
      (mode-badge :strike)
      "by"
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
              [:div {:style card-style}
               [:p.font-bold.mb-2 {:style {:color "#facc15"}} "Enemy bombing raid struck your empire:"]
               [:div.grid.grid-cols-2.gap-x-8.gap-y-1
                [:p.text-xs {:style {:color "#9adaaa"}} (str "Soldiers lost:   " (:soldiers-destroyed   bomb-result))]
                [:p.text-xs {:style {:color "#9adaaa"}} (str "Transports lost: " (:transports-destroyed bomb-result))]
                [:p.text-xs {:style {:color "#9adaaa"}} (str "Fighters lost:   " (:fighters-destroyed   bomb-result))]
                [:p.text-xs {:style {:color "#9adaaa"}} (str "Carriers lost:   " (:carriers-destroyed   bomb-result))]]])]))

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
           [:div {:style card-style}
            [:h3.font-bold.mb-2 {:style {:color "#4ade80"}}
             (cond
               (and won? (= op "spy"))    (str "Spy operation against " (:defender-name espionage-result) " succeeded")
               (and won? (= op "incite")) (str "Incite operation against " (:defender-name espionage-result) " succeeded")
               (and won? (= op "bomb"))   (str "Bombing raid against " (:defender-name espionage-result) " succeeded")
               (and won? (= op "defect")) (str "Defection operation against " (:defender-name espionage-result) " succeeded")
               :else                      (str "Operation against " (:defender-name espionage-result) " failed — agents captured"))]
            (cond
              (and won? (= op "spy"))
              [:div.grid.grid-cols-2.gap-x-8.gap-y-1
               [:p.text-xs.font-bold.col-span-2.mb-1 {:style {:color "#4ade80"}}
                (str (:defender-name espionage-result) "'s military")]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Soldiers:   " (:soldiers   intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Transports: " (:transports intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Generals:   " (:generals   intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Fighters:   " (:fighters   intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Carriers:   " (:carriers   intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Admirals:   " (:admirals   intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Stations:   " (:stations   intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Cmd Ships:  " (:cmd-ships  intel))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Agents:     " (:agents     intel))]]

              (and won? (= op "incite"))
              [:p.text-xs {:style {:color "#9adaaa"}}
               (str "Your agents successfully stirred unrest. "
                    (:defender-name espionage-result) "'s stability was reduced by " stab ".")]

              (and won? (= op "bomb"))
              [:div.grid.grid-cols-2.gap-x-8.gap-y-1
               [:p.text-xs.font-bold.col-span-2.mb-1 {:style {:color "#4ade80"}}
                (str "Units destroyed in " (:defender-name espionage-result) ":")]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Soldiers:   " (or (:soldiers-destroyed   espionage-result) 0))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Transports: " (or (:transports-destroyed espionage-result) 0))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Fighters:   " (or (:fighters-destroyed   espionage-result) 0))]
               [:p.text-xs {:style {:color "#9adaaa"}} (str "Carriers:   " (or (:carriers-destroyed   espionage-result) 0))]]

              (and won? (= op "defect"))
              [:p.text-xs {:style {:color "#9adaaa"}}
               (str (or (:agents-defected espionage-result) 0)
                    " agent(s) defected from " (:defender-name espionage-result) " to your service.")]

              :else
              [:p.text-xs {:style {:color "#9adaaa"}}
               (str "Your agents were unable to complete their mission. "
                    lost " agent(s) were captured by " (:defender-name espionage-result) ".")])]))

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
         [:div {:style card-style}
          (if (pos? pop-growth)
            [:p.font-bold {:style {:color "#4ade80"}} (str "Population grew by " pop-growth " million this round.")]
            [:p.font-bold {:style {:color "#9adaaa"}} "Population held steady this round."])])

       (ui/snapshot-section player)]

      ;; Action bar
      [:div.flex.gap-2
       {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
       (ui/action-bar-link (str "/app/game/" (:xt/id player)) "Pause")
       (biff/form
        {:action (str "/app/game/" (:xt/id player) "/apply-outcomes") :method "post"
         :style  {:margin 0}}
        (ui/submit-button true (if end-current-round? "End the Current Round" "Continue to Next Turn")))]])))
