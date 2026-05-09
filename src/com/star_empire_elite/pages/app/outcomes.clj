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
  (:require [com.biffweb :as biff]
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
     (* (:player/erg-planets player) 300)
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

;; Specs for combat unit rows. :special? marks stations (defender-only): in incoming sections
;; it suppresses the attacker-losses column; in outgoing sections it suppresses your-forces and
;; your-losses columns. :separator? marks the last row before the planet rows.
(def ^:private battle-unit-specs
  [{:label "Soldiers"   :unit-key :soldiers   :loss-key :soldiers-lost   :special? false :separator? false}
   {:label "Transports" :unit-key :transports :loss-key :transports-lost :special? false :separator? false}
   {:label "Generals"   :unit-key :generals   :loss-key :generals-lost   :special? false :separator? false}
   {:label "Fighters"   :unit-key :fighters   :loss-key :fighters-lost   :special? false :separator? false}
   {:label "Carriers"   :unit-key :carriers   :loss-key :carriers-lost   :special? false :separator? false}
   {:label "Admirals"   :unit-key :admirals   :loss-key :admirals-lost   :special? false :separator? false}
   {:label "Cmd Ships"  :unit-key :cmd-ships  :loss-key :cmd-ships-lost  :special? false :separator? false}
   {:label "Stations"   :unit-key :stations   :loss-key :stations-lost   :special? true  :separator? true}])

(def ^:private planet-specs
  [{:label "Ore"      :pt-key :ore  :separator? false}
   {:label "Food"     :pt-key :food :separator? false}
   {:label "Military" :pt-key :mil  :separator? true}])

(defn- incoming-battle-row [label dc dl al unit-key loss-key special? separator-below?]
  [:tr {:style {:border-bottom (if separator-below? "1px solid #4ade80" "1px solid #253530")
                :background "#121a18"}}
   [:td {:style {:padding "4px 0" :color "#9adaaa"}} label]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} (get dc unit-key)]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} (get dl loss-key)]
   [:td {:style {:text-align "right" :color "#9adaaa"}} (if special? "—" (get al loss-key))]])

(defn- incoming-planet-row [label def-losses separator-below?]
  [:tr {:style {:border-bottom (if separator-below? "1px solid #4ade80" "1px solid #253530")
                :background "#121a18"}}
   [:td {:style {:padding "4px 0" :color "#9adaaa"}} label]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} "—"]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} def-losses]
   [:td {:style {:text-align "right" :color "#9adaaa"}} "—"]])

(defn- incoming-attack-section [result]
  (let [att-wins? (:attacker-wins? result)
        att-name  (:attacker-name result)
        dc        (:defender-counts result)
        al        (:attacker-losses result)
        dl        (:defender-losses result)
        pt        (:planets-transferred result)
        th-style  {:color "#4ade80"}]
    [:div {:style {:border "1px solid #253530" :border-radius "3px" :background "#161616"
                   :padding "12px"}}
     [:h3.font-bold.mb-3 {:style {:color "#4ade80"}}
      (if att-wins?
        (str "Attacked by " att-name " — defeat")
        (str "Attacked by " att-name " — repelled"))]
     [:div.overflow-x-auto
      [:table.w-full.text-xs.table-fixed
       [:thead
        [:tr {:style {:border-bottom "1px solid #4ade80"}}
         [:th.text-left.py-1  {:style (assoc th-style :width "10%")} "Item"]
         [:th.text-right.py-1 {:style (assoc th-style :width "30%")} "Your Forces"]
         [:th.text-right.py-1 {:style (assoc th-style :width "30%")} "Your Losses"]
         [:th.text-right.py-1 {:style (assoc th-style :width "30%")} (str att-name " Losses")]]]
       [:tbody
        (for [spec battle-unit-specs]
          (incoming-battle-row (:label spec) dc dl al (:unit-key spec) (:loss-key spec)
                               (:special? spec) (:separator? spec)))
        (for [spec planet-specs]
          (incoming-planet-row (:label spec) (get pt (:pt-key spec)) (:separator? spec)))]]]]))

(defn- battle-row [label af al dl att-key loss-key special? separator-below?]
  [:tr {:style {:border-bottom (if separator-below? "1px solid #4ade80" "1px solid #253530")
                :background "#121a18"}}
   [:td {:style {:padding "4px 0" :color "#9adaaa"}} label]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} (if special? "—" (get af att-key))]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} (if special? "—" (get al loss-key))]
   [:td {:style {:text-align "right" :color "#9adaaa"}} (get dl loss-key)]])

(defn- planet-row [label enemy-losses att-wins? separator-below?]
  [:tr {:style {:border-bottom (if separator-below? "1px solid #4ade80" "1px solid #253530")
                :background "#121a18"}}
   [:td {:style {:padding "4px 0" :color "#9adaaa"}} label]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} "—"]
   [:td {:style {:text-align "right" :padding "4px 12px" :color "#9adaaa"}} "—"]
   [:td {:style {:text-align "right" :color "#9adaaa"}} (if att-wins? enemy-losses "—")]])

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
                            :padding "12px"}
        th-style           {:color "#4ade80"}]
    (ui/page
     {}
     [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
      {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
               :border-radius "4px" :color "#4ade80"
               :font-family "'Courier New', monospace"}}
      (ui/scanline-overlay)
      (ui/phase-topbar player "OUTCOMES PHASE")
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
              (incoming-attack-section (clojure.core/read-string r)))
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
         (let [att-wins? (:attacker-wins? battle-result)
               ac        (:attacker-counts battle-result)
               al        (:attacker-losses battle-result)
               dl        (:defender-losses battle-result)
               def-name  (:defender-name battle-result)
               pt        (:planets-transferred battle-result)]
           [:div {:style card-style}
            [:h3.font-bold.mb-3 {:style {:color "#4ade80"}}
             (if att-wins? (str "Victory against " def-name) (str "Defeat by " def-name))]
            [:div.overflow-x-auto
             [:table.w-full.text-xs.table-fixed
              [:thead
               [:tr {:style {:border-bottom "1px solid #4ade80"}}
                [:th.text-left.py-1  {:style (assoc th-style :width "10%")} "Item"]
                [:th.text-right.py-1 {:style (assoc th-style :width "30%")} "Your Forces"]
                [:th.text-right.py-1 {:style (assoc th-style :width "30%")} "Your Losses"]
                [:th.text-right.py-1 {:style (assoc th-style :width "30%")} (str def-name " Losses")]]]
              [:tbody
               (for [spec battle-unit-specs]
                 (battle-row (:label spec) ac al dl (:unit-key spec) (:loss-key spec)
                             (:special? spec) (:separator? spec)))
               (for [spec planet-specs]
                 (planet-row (:label spec) (get pt (:pt-key spec)) att-wins? (:separator? spec)))]]]]))

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
        {:action (str "/app/game/" (:xt/id player) "/apply-outcomes")
         :method "post"
         :style  {:margin 0}}
        (ui/submit-button true (if end-current-round? "End the Current Round" "Continue to Next Turn")))]])))
