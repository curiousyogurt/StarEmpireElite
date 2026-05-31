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

;;;;; Logical Structure:
;;;;;
;;;;; 1)  outcomes-page ← compute-summary
;;;;;     └─ ui/phase-shell
;;;;;        ├─ summary-strip
;;;;;        │  └─ delta-pill  (per non-zero delta)
;;;;;        ├─ incoming section
;;;;;        │  ├─ incoming-card  (per incoming attack)
;;;;;        │  │  └─ combat-card | strike-card
;;;;;        │  ├─ espionage-fail-card
;;;;;        │  ├─ incoming-espionage-card  (incite / defect)
;;;;;        │  └─ bomb-card
;;;;;        ├─ combat-card | strike-card  (outgoing battle)
;;;;;        ├─ spy-card | bomb-card  (outgoing espionage)
;;;;;        ├─ stability-card
;;;;;        ├─ elimination notice
;;;;;        ├─ growth-card
;;;;;        ├─ ui/snapshot-section
;;;;;        └─ ui/phase-action-bar
;;;;;           ├─ ui/action-bar-link "Pause"
;;;;;           └─ ui/submit-button "End the Current Round" | "Continue to Next Turn"
;;;;;
;;;;; 2)  apply-outcomes ← calculate-score, turn/round advancement

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

  [player] -> int"
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
;;;; UI Primitives
;;;; - Collapsible Card
;;;; - Result Summary
;;;; - Table Cells and Header
;;;;

;;;
;;; battle-unit-specs defines the rows for the 3-column combat table.
;;; :defender-only? true for stations — the defender fights with stations,
;;; the attacker does not.
;;;

(def ^:private battle-unit-specs
  [{:label "soldiers"   :unit-key :soldiers   :defender-only? false}
   {:label "transports" :unit-key :transports :defender-only? false}
   {:label "generals"   :unit-key :generals   :defender-only? false}
   {:label "fighters"   :unit-key :fighters   :defender-only? false}
   {:label "carriers"   :unit-key :carriers   :defender-only? false}
   {:label "admirals"   :unit-key :admirals   :defender-only? false}
   {:label "cmd ships"  :unit-key :cmd-ships  :defender-only? false}
   {:label "def stns"   :unit-key :stations   :defender-only? true}])

(def ^:private col-header-cls
  "text-[10px] tracking-widest text-game-green-dim uppercase font-normal py-1.5 px-3")

(defn- chevron
  "Small right-pointing chevron that rotates 90° when the parent <details> is open."
  []
  [:span.card-chevron.text-game-green-soft.text-xs.ml-auto "❯"])

(defn- collapsible-card
  "Render a <details>/<summary> card with consistent styling.
  badge, summary-content, and optional summary-extra appear in the summary row;
  body is the expandable content below.

  [opts {:keys [badge summary summary-extra open?]}, body hiccup] -> hiccup"
  [{:keys [badge summary summary-extra open?]} & body]
  [:details.rounded-game.bg-game-surface.overflow-hidden
   (cond-> {:class "border border-game-border"}
     open? (assoc :open true))
   [:summary.list-none.flex.items-center.py-2.px-3.gap-3.cursor-pointer
    badge
    [:span.text-sm.flex-1 summary]
    summary-extra
    (chevron)]
   (into [:<>] body)])

(defn- result-summary
  "Render the ATTACKER → ACTION → DEFENDER (RESULT) summary text.
  Result text is always from the attacker's perspective: (SUCCESS) if attacker won, (FAILURE) if not.
  Color reflects the viewer: green if you won, red if you lost.

  [verb str, opp-name str, attacker-wins? bool, you-are-attacker? bool] -> hiccup"
  [verb opp-name attacker-wins? you-are-attacker?]
  (let [you-win?   (if you-are-attacker? attacker-wins? (not attacker-wins?))
        result-cls (if you-win? "text-green-400" "text-red-400")
        result-txt (if attacker-wins? "(SUCCESS)" "(FAILURE)")]
    (if you-are-attacker?
      [:<> [:span.text-game-green-soft (str "You " verb " ")]
           [:span.text-green-300.font-bold opp-name]
           [:span {:class result-cls} (str " " result-txt)]]
      [:<> [:span.text-green-300.font-bold opp-name]
           [:span.text-game-green-soft (str " " verb " you")]
           [:span {:class result-cls} (str " " result-txt)]])))

(defn- gain-cell [n]
  [:span.text-green-400 (str "+" (ui/format-number n))])

(defn- opp-loss-cell [n]
  (if (pos? n)
    [:span.text-red-400 (str "−" (ui/format-number n))]
    [:span.text-game-green-dim "0"]))

(defn- combat-row [left center right]
  [:tr.bg-game-row.border-b.border-game-divider
   [:td.text-right.py-1.px-3.border-r.border-game-divider left]
   [:td.text-center.py-1.px-2.border-r.border-game-divider.text-game-green-muted center]
   [:td.text-left.py-1.px-3 right]])

(defn- combat-table-header
  "Render the 3-column table header for combat and strike cards."
  [opp-name]
  [:thead
   [:tr.bg-game-header.border-t.border-game-border.border-b.border-game-border
    [:th {:class (str col-header-cls " text-right")}  "◄ YOU"]
    [:th {:class (str col-header-cls " text-center")} "UNIT"]
    [:th {:class (str col-header-cls " text-left")}   (str (str/upper-case opp-name) " ►")]]])

;;;;
;;;; Summary Text Helpers
;;;; - destroyed-units-text
;;;; - non-trivial-losses?
;;;;

(defn- destroyed-units-text
  "One-clause summary of units destroyed, e.g. 'destroyed 200 soldiers, 30 fighters'.
  Takes a seq of [label count] pairs."
  [pairs]
  (let [parts (keep (fn [[label n]]
                      (when (and n (pos? n))
                        (str (ui/format-number-str n) " " label)))
                    pairs)]
    (when (seq parts) (str "destroyed " (str/join ", " parts)))))

(defn- non-trivial-losses?
  "True if any unit type suffered >5% losses relative to starting count.
  5% is the threshold where losses start to be tactically meaningful —
  below this, the card collapses to reduce visual noise on routine turns."
  [counts losses]
  (some (fn [{:keys [unit-key]}]
          (let [n (get counts unit-key 0)
                lost (get losses unit-key 0)]
            (and (pos? n) (> (/ (double lost) n) 0.05))))
        battle-unit-specs))

;;;;
;;;; Summary Strip
;;;; - compute-summary
;;;; - delta-pill
;;;; - summary-strip
;;;;

;;;
;;; The summary strip is a compact row of labeled delta pills showing net
;;; changes for the turn: units lost, planets, resources, stability, population,
;;; and agents. compute-summary aggregates all outcome data into a flat map of
;;; deltas; summary-strip renders the non-zero ones as colored pills.
;;;

(defn- compute-summary
  "Compute net turn deltas for the summary strip from already-resolved outcomes.

  Military losses are an unweighted unit count total — the strip is a glance-level
  overview; per-unit breakdowns are in the individual cards. Planets and resources
  are net values (positive = gained, negative = lost).

  No new DB queries; all data comes from the cached outcomes map."
  [{:keys [player battle-result espionage-result pop-growth
           expense-penalty breakaway-result recovery-result]}]
  (let [sum-vals    (fn [m] (reduce + 0 (vals (or m {}))))
        ;; Helpers
        won-ground? (and battle-result
                         (:attacker-wins? battle-result)
                         (not= (:mode battle-result) :strike))
        incoming    (mapv clojure.edn/read-string (:player/incoming-attacks player []))
        in-wins     (fn [k] (reduce (fn [acc r]
                                      (if (and (:attacker-wins? r) (not= (:mode r) :strike))
                                        (merge-with + acc (k r {}))
                                        acc))
                                    {} incoming))
        ;; Unit losses — three sources, all as unit-count maps, merged and summed once
        out-losses  (cond
                      (nil? battle-result)                     {}
                      (= (:mode battle-result) :strike)        {:cmd-ships (:cmd-ships-lost battle-result 0)}
                      :else                                    (:attacker-losses battle-result {}))
        in-losses   (reduce (fn [acc r] (merge-with + acc (:defender-losses r {})))
                            {} incoming)
        bomb-losses (if-let [r (some-> (:player/incoming-bomb-result player) clojure.edn/read-string)]
                      (dissoc r :attacker-name) {})
        units-lost  (sum-vals (merge-with + out-losses in-losses bomb-losses))
        ;; Planet deltas: gained from outgoing ground wins, lost from incoming + breakaway
        out-pt      (if won-ground? (:planets-transferred battle-result {}) {})
        in-pt       (in-wins :planets-transferred)
        break-pt    (if (and breakaway-result (:triggered? breakaway-result))
                      {:ore (:ore-lost breakaway-result 0)
                       :erg (:erg-lost breakaway-result 0)
                       :mil (:mil-lost breakaway-result 0)}
                      {})
        ore (- (:ore out-pt 0) (:ore in-pt 0) (:ore break-pt 0))
        erg (- (:erg out-pt 0) (:erg in-pt 0) (:erg break-pt 0))
        mil (- (:mil out-pt 0) (:mil in-pt 0) (:mil break-pt 0))
        ;; Resource deltas: same pattern as planets
        out-res (if won-ground? (:resources-captured battle-result {}) {})
        in-res  (in-wins :resources-captured)
        credits (- (:credits out-res 0) (:credits in-res 0))
        food    (- (:food out-res 0)    (:food in-res 0))
        fuel    (- (:fuel out-res 0)    (:fuel in-res 0))
        ;; Stability delta
        stab-loss (+ (or expense-penalty 0)
                     (or (:player/incoming-incite-stability-lost player) 0))
        stab-gain (if (and recovery-result (:triggered? recovery-result))
                    (:amount recovery-result 0) 0)
        ;; Agents delta
        esp-lost    (if (and espionage-result (not (:attacker-wins? espionage-result)))
                      (:agents-captured espionage-result 0) 0)
        esp-gained  (or (:player/incoming-espionage-agents-gained player) 0)
        esp-defect  (if (and espionage-result (:attacker-wins? espionage-result)
                             (= (:op espionage-result) "defect"))
                      (:agents-defected espionage-result 0) 0)
        in-defected (or (:player/incoming-defect-agents-lost player) 0)]
    {:units-lost units-lost
     :ore ore :erg erg :mil mil
     :credits credits :food food :fuel fuel
     :stability (- stab-gain stab-loss)
     :population (or pop-growth 0)
     :agents (+ (- esp-lost) esp-gained esp-defect (- in-defected))}))

(defn- delta-pill
  "Render a labeled delta value, colored green for gains / red for losses.
  Returns nil when n is zero."
  [label n & [suffix]]
  (when-not (zero? n)
    [:span.inline-flex.items-center.gap-1
     [:span.text-game-green-muted label]
     [:span {:class (if (neg? n) "text-red-400" "text-green-400")}
      (str (if (pos? n) "+" "–") (ui/format-number-str (Math/abs n)) (or suffix ""))]]))

(defn- summary-strip
  "Compact strip showing net turn deltas. Only rendered when at least one delta
  is non-zero. Visually lighter than event cards — no border, subtle background."
  [summary]
  (let [pills (keep identity
                [(when (pos? (:units-lost summary))
                   [:span.inline-flex.items-center.gap-1
                    [:span.text-game-green-muted "UNITS"]
                    [:span.text-red-400 (str "−" (ui/format-number-str (:units-lost summary)))]])
                 (delta-pill "ORE"       (:ore       summary))
                 (delta-pill "ERG"       (:erg       summary))
                 (delta-pill "MIL"       (:mil       summary))
                 (delta-pill "CREDITS"   (:credits   summary))
                 (delta-pill "FOOD"      (:food      summary))
                 (delta-pill "FUEL"      (:fuel      summary))
                 (delta-pill "STABILITY" (:stability summary) "%")
                 (when (pos? (:population summary))
                   [:span.inline-flex.items-center.gap-1
                    [:span.text-game-green-muted "POP"]
                    [:span.text-green-400 (str "+" (ui/format-population (:population summary)))]])
                 (delta-pill "AGENTS" (:agents summary))])]
    (when (seq pills)
      [:div.flex.flex-wrap.gap-x-5.gap-y-1.text-xs.tracking-wide.py-2.px-3.rounded
       {:class "bg-[rgba(20,42,28,0.15)]"}
       pills])))

;;;;
;;;; Card Components
;;;; - combat-card
;;;; - strike-card
;;;; - spy-card
;;;; - bomb-card
;;;; - growth-card
;;;; - stability-card
;;;; - incoming-card
;;;; - incoming-espionage-card
;;;; - espionage-fail-card
;;;;

;;;
;;; Each card is a collapsible <details>/<summary> element rendered via
;;; collapsible-card. Cards are split into two groups: battle cards
;;; (combat-card, strike-card) that use the 3-column table layout, and
;;; event cards (spy-card, bomb-card, growth-card, stability-card,
;;; incoming-espionage-card, espionage-fail-card) that use simpler body layouts.
;;; incoming-card dispatches to combat-card or strike-card based on mode.
;;;

(defn- combat-card
  "Render a collapsible combat card with header summary and 3-col symmetric table.

  Left col: your unit count + inline red loss. Right col: opponent's loss delta.
  :stations-mine? true when you are the defender (you have stations).
  :open? controls the initial expansion state."
  [{:keys [mode opp-name attacker-wins? my-counts my-losses opp-losses
           stations-mine? resources-taken planets-transferred open?]}]
  (let [dash           [:span.text-game-green-dim "—"]
        make-left      (fn [unit-key defender-only?]
                         (if (and defender-only? (not stations-mine?))
                           dash
                           [:<>
                            [:span.text-game-green-soft
                             (ui/format-number (get my-counts unit-key 0))]
                            (let [loss (get my-losses unit-key 0)]
                              (when (pos? loss)
                                [:span.text-red-400.ml-1.5
                                 (str "−" (ui/format-number loss))]))]))
        make-right     (fn [unit-key defender-only?]
                         (if (and defender-only? stations-mine?)
                           dash
                           (opp-loss-cell (get opp-losses unit-key 0))))
        credits        (:credits resources-taken 0)
        food           (:food    resources-taken 0)
        fuel           (:fuel    resources-taken 0)
        ore-pt         (:ore planets-transferred 0)
        erg-pt         (:erg planets-transferred 0)
        mil-pt         (:mil planets-transferred 0)
        transfer-row   (fn [label n]
                         (when (pos? n)
                           (if stations-mine?
                             (combat-row (opp-loss-cell n) label (gain-cell n))
                             (combat-row (gain-cell n) label (opp-loss-cell n)))))
        verb           (case mode :invade "invaded" :raid "raided" "attacked")]
    (collapsible-card
      {:badge   (ui/mode-badge mode)
       :summary (result-summary verb opp-name attacker-wins? (not stations-mine?))
       :open?   open?}
      [:div.overflow-x-auto
       [:table.w-full.text-xs.table-fixed
        (combat-table-header opp-name)
        [:tbody
         (for [{:keys [label unit-key defender-only?]} battle-unit-specs]
           (combat-row (make-left  unit-key defender-only?)
                       label
                       (make-right unit-key defender-only?)))
         (transfer-row "ore planets"      ore-pt)
         (transfer-row "energy planets"   erg-pt)
         (transfer-row "military planets" mil-pt)
         (transfer-row "credits" credits)
         (transfer-row "food"    food)
         (transfer-row "fuel"    fuel)]]])))

(defn- strike-card
  "Render a collapsible strike result using the same 3-col table structure as combat-card.
  :stations-mine? true when you are the defender."
  [{:keys [opp-name stations-mine? dispatched intercepted defender-losses open?]}]
  (let [dash           [:span.text-game-green-dim "—"]
        att-cmd        [:<>
                        [:span.text-game-green-soft (ui/format-number dispatched)]
                        (when (pos? intercepted)
                          [:span.text-red-400.ml-1.5
                           (str "−" (ui/format-number intercepted))])]
        dl             (or defender-losses {})
        unit-pairs     [["soldiers" (:soldiers dl)] ["transports" (:transports dl)]
                        ["fighters" (:fighters dl)] ["stations"   (:stations   dl)]]
        summary-text   (let [destroyed (destroyed-units-text unit-pairs)]
                         (if stations-mine?
                           destroyed
                           (str/join " · " (keep identity
                                            [destroyed
                                             (when (pos? intercepted)
                                               (str "lost " (ui/format-number-str intercepted) " cmd ships"))]))))
        rows           [["cmd ships"  att-cmd                            dash]
                        ["soldiers"   dash (opp-loss-cell (:soldiers   dl))]
                        ["transports" dash (opp-loss-cell (:transports dl))]
                        ["generals"   dash (opp-loss-cell (:generals   dl))]
                        ["fighters"   dash (opp-loss-cell (:fighters   dl))]
                        ["carriers"   dash (opp-loss-cell (:carriers   dl))]
                        ["admirals"   dash (opp-loss-cell (:admirals   dl))]
                        ["def stns"   dash (opp-loss-cell (:stations   dl))]]]
    (collapsible-card
      {:badge         (ui/mode-badge :strike)
       :summary       (if stations-mine?
                        [:<> [:span.text-green-300.font-bold opp-name]
                             [:span.text-game-green-soft " launched a missile strike on you"]]
                        [:<> [:span.text-game-green-soft "You launched a missile strike on "]
                             [:span.text-green-300.font-bold opp-name]])
       :summary-extra (when summary-text [:span.text-xs.text-game-green-dim summary-text])
       :open?         open?}
      [:div.overflow-x-auto
       [:table.w-full.text-xs.table-fixed
        (combat-table-header opp-name)
        [:tbody
         (for [[label att-cell def-cell] rows]
           (if stations-mine?
             (combat-row def-cell label att-cell)
             (combat-row att-cell label def-cell)))]]])))

(defn- spy-card
  "Render a collapsible espionage result card."
  [{:keys [opp-name won? op spy lost stab agents-defected open?]}]
  (let [verb (case op
               "spy"    (if won? "spied on" "attempted to spy on")
               "incite" (if won? "incited unrest within" "attempted to stir unrest within")
               "defect" (if won? "subverted agents from" "attempted to subvert from")
                        (if won? "operated against" "attempted to operate against"))]
    (collapsible-card
      {:badge   (ui/mode-badge :spy)
       :summary (result-summary verb opp-name won? true)
       :open?   open?}
      [:div.py-1.5.px-3.border-t.border-game-divider
       (cond
         (and won? (= op "spy"))
         [:div.grid.grid-cols-3.gap-x-4.gap-y-0.5
          (for [[label val] [["Soldiers"  (:soldiers  spy 0)] ["Transports" (:transports spy 0)]
                             ["Generals"  (:generals  spy 0)] ["Fighters"   (:fighters   spy 0)]
                             ["Carriers"  (:carriers  spy 0)] ["Admirals"   (:admirals   spy 0)]
                             ["Stations"  (:stations  spy 0)] ["Cmd Ships"  (:cmd-ships  spy 0)]
                             ["Agents"    (:agents    spy 0)]]]
            [:div.flex.justify-between.items-center
             [:span.text-game-green-muted.text-xs label]
             [:span.text-game-green-soft (ui/format-number val)]])]

         (and won? (= op "incite"))
         [:p.text-xs.text-game-green-soft
          (str "Your agents successfully stirred unrest. "
               "The stability of " opp-name " was reduced by " stab "%.")]

         (and won? (= op "defect"))
         [:p.text-xs.text-game-green-soft
          (str agents-defected " agent(s) defected from " opp-name " to your service.")]

         :else
         [:p.text-xs.text-game-green-soft
          (str "Your agents were unable to complete their mission. "
               lost " agent(s) were captured by " opp-name ".")])])))

(defn- bomb-card
  "Render a collapsible bombing raid result card."
  [{:keys [opp-name attacker-wins? you-are-attacker? soldiers transports fighters carriers open?]}]
  (let [units        [["Soldiers"   soldiers]
                      ["Transports" transports]
                      ["Fighters"   fighters]
                      ["Carriers"   carriers]]
        summary-text (when attacker-wins?
                       (destroyed-units-text
                         [["soldiers" soldiers] ["transports" transports]
                          ["fighters" fighters] ["carriers"   carriers]]))]
    (collapsible-card
      {:badge         (ui/mode-badge :bomb)
       :summary       (result-summary "bombed" opp-name attacker-wins? you-are-attacker?)
       :summary-extra (when summary-text [:span.text-xs.text-game-green-dim (str "— " summary-text)])
       :open?         open?}
      (when attacker-wins?
        [:div.grid.grid-cols-4.py-1.5.px-3.border-t.border-game-divider
         (for [[label val] units]
           [:div.flex.justify-between.items-center.pr-4
            [:span.text-game-green-muted.text-xs label]
            [:span.text-game-green-soft (ui/format-number val)]])]))))

(defn- growth-card [{:keys [pop-growth]}]
  [:div.rounded-game.bg-game-surface.overflow-hidden
   {:class "border border-game-border"}
   [:div.flex.items-center.py-2.px-3.gap-3
    (ui/mode-badge :growth)
    (if (pos? pop-growth)
      [:span.text-game-green-soft.text-sm
       "Population grew by "
       [:span.text-green-400 (str "+" (ui/format-population pop-growth))]]
      [:span.text-game-green-soft.text-sm
       "Population held steady this round"])]])

(defn- stability-card
  "Render a collapsible stability card combining expense penalty, breakaway, and recovery."
  [{:keys [expense-penalty breakaway-result recovery-result]}]
  (let [has-penalty?   (and (some? expense-penalty) (pos? expense-penalty))
        has-breakaway? (and (some? breakaway-result) (:triggered? breakaway-result))
        has-recovery?  (and (some? recovery-result) (:triggered? recovery-result))
        summary-text   (cond
                         has-breakaway? (str "empire fractured — "
                                             (:total-lost breakaway-result) " planets lost")
                         has-penalty?   (str "−" expense-penalty "%")
                         has-recovery?  (str "+" (:amount recovery-result) "% recovery")
                         :else          "")]
    (when (or has-penalty? has-breakaway? has-recovery?)
      (collapsible-card
        {:badge (ui/mode-badge :stability)
         :summary [:span.font-bold {:class (if has-breakaway? "text-red-400" "text-yellow-400")}
                   summary-text]
         :open?  (or has-penalty? has-breakaway?)}
        [:div.p-3.border-t.border-game-divider
         (when has-penalty?
           [:p.text-xs.mb-2.text-yellow-400
            (str "Empire stability decreases due to unpaid expenses: −" expense-penalty "%")])
         (when has-breakaway?
           (let [lost-str (str/join ", "
                            (keep identity
                              [(when (pos? (:ore-lost breakaway-result))
                                 (str (:ore-lost breakaway-result) " ore planet(s)"))
                               (when (pos? (:erg-lost breakaway-result))
                                 (str (:erg-lost breakaway-result) " energy planet(s)"))
                               (when (pos? (:mil-lost breakaway-result))
                                 (str (:mil-lost breakaway-result) " military planet(s)"))]))]
             [:p.text-xs.mb-2.text-red-400
              (str "Empire instability prompts revolution. Lost: " lost-str)]))
         (when has-recovery?
           [:p.text-xs.text-green-400
            (str "Empire stability increases due to paid expenses: +" (:amount recovery-result) "%")])]))))

(defn- incoming-card
  "Render a single incoming attack card (combat or strike)."
  [r]
  (let [result (read-string r)]
    (if (= (:mode result) :strike)
      (strike-card {:opp-name          (:attacker-name result)
                    :stations-mine?    true
                    :dispatched        (:cmd-ships-dispatched result)
                    :intercepted       (:cmd-ships-lost result)
                    :defender-losses   (:defender-losses result)
                    :open?             true})
      (combat-card {:mode                (get result :mode :invade)
                    :opp-name            (:attacker-name result)
                    :attacker-wins?      (:attacker-wins? result)
                    :my-counts           (:defender-counts result)
                    :my-losses           (:defender-losses result)
                    :opp-losses          (:attacker-losses result)
                    :stations-mine?      true
                    :resources-taken     (:resources-captured result)
                    :planets-transferred (:planets-transferred result)
                    :open?               true}))))

(defn- incoming-espionage-card
  "Render a collapsible card for a successful incoming espionage operation (incite or defect).

  [mode keyword, summary-text string, body-text string] -> hiccup"
  [mode summary-text body-text]
  (collapsible-card
    {:badge   (ui/mode-badge mode)
     :summary [:<> [:span.text-game-green-soft summary-text]
                   [:span.text-red-400 " (SUCCESS)"]]
     :open?   true}
    [:div.py-2.px-3.border-t.border-game-divider
     [:p.text-xs.text-yellow-400 body-text]]))

(defn- espionage-fail-card
  "Render a collapsible card for incoming espionage operations that failed."
  [esp-fails esp-agents]
  (let [op-verb  (fn [op] (case op
                            "spy"    "spied on you"
                            "incite" "attempted to incite unrest"
                            "defect" "attempted to turn your agents"
                            "bomb"   "attempted to bomb your forces"
                                     "operated against you"))
        summary  (if (= 1 (count esp-fails))
                   (str "An empire " (op-verb (first esp-fails)))
                   (str (count esp-fails) " empires operated against you"))]
    (collapsible-card
      {:badge   (ui/mode-badge :spy)
       :summary [:<> [:span.text-game-green-soft summary]
                     [:span.text-green-400 " (FAILURE)"]]
       :open?   false}
      (when (pos? esp-agents)
        [:div.py-2.px-3.border-t.border-game-divider
         [:p.text-xs.text-green-400
          (str esp-agents " captured agent(s) joined your forces.")]]))))


;;;;
;;;; Actions
;;;;

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
              next-round      (if end-round?
                                (inc (:player/current-round player))
                                (:player/current-round player))
              reset-used      (if end-round? 0 turns-used)]
          (biff/submit-tx ctx
            [(merge {:db/doc-type                            :player
                     :db/op                                  :update
                     :xt/id                                  player-id
                     :player/current-turn                    next-turn
                     :player/current-round                   next-round
                     :player/turns-used                      reset-used
                     :player/current-phase                   1
                     :player/last-turn-at                    now
                     :player/last-battle-result              nil
                     :player/last-espionage-result           nil
                     :player/last-population-growth          nil
                     :player/last-expense-stability-penalty  nil
                     :player/last-stability-breakaway        nil
                     :player/last-stability-recovery         nil
                     :player/pending-espionage               nil
                     :player/incoming-attacks                nil
                     :player/incoming-espionage-fails        nil
                     :player/incoming-espionage-agents-gained nil
                     :player/incoming-incite-stability-lost  nil
                     :player/incoming-bomb-result            nil
                     :player/incoming-defect-agents-lost    nil
                     :player/pending-espionage-op            nil
                     :player/score                           (calculate-score player)}
                    (when end-round?
                      {:player/last-round-completed-at now}))])
          {:status 303
           :headers {"location" (str "/app/game/" player-id "/income")}})))))

;;;;
;;;; Page
;;;;

(defn outcomes-page
  "Show turn results with a summary strip, collapsible event cards, and the advance button.

  Cards use native <details>/<summary> for collapse. Smart defaults expand events
  that matter (incoming attacks, defeats, non-trivial losses) and collapse routine
  successes and steady-state events.

  [{:keys [player game battle-result espionage-result pop-growth expense-penalty
           breakaway-result recovery-result eliminated?]}] -> hiccup"
  [{:keys [player game battle-result espionage-result pop-growth expense-penalty
           breakaway-result recovery-result eliminated?] :as outcomes}]
  (let [player-id       (:xt/id player)
        current-turn    (:player/current-turn player)
        turns-per-round (:game/turns-per-round game)
        end-round?      (>= current-turn turns-per-round)]
    (ui/phase-shell 
      player 
      game "Outcomes Phase"
      [:div.flex.flex-col.gap-2
       {:class "py-2.5 px-3.5"}

       (summary-strip (compute-summary outcomes))

       ;; Incoming section — attacks and espionage received this turn
       (let [incoming           (seq (:player/incoming-attacks player))
             esp-fails          (:player/incoming-espionage-fails player [])
             esp-agents         (or (:player/incoming-espionage-agents-gained player) 0)
             incite-stab-lost   (or (:player/incoming-incite-stability-lost player) 0)
             bomb-result        (some-> (:player/incoming-bomb-result player) read-string)
             defect-agents-lost (or (:player/incoming-defect-agents-lost player) 0)]
         (when (or incoming (seq esp-fails) (pos? incite-stab-lost) bomb-result (pos? defect-agents-lost))
           [:div.flex.flex-col.gap-2
            (map incoming-card incoming)
            (when (seq esp-fails)
              (espionage-fail-card esp-fails esp-agents))
            (when (pos? incite-stab-lost)
              (incoming-espionage-card :incite
                "An empire incited unrest in your empire"
                (str "Enemy agents stirred unrest. Stability reduced by " incite-stab-lost "%.")))
            (when (pos? defect-agents-lost)
              (incoming-espionage-card :defect
                "An empire subverted your agents"
                (str defect-agents-lost " agent(s) defected to the enemy.")))
            (when bomb-result
              (bomb-card {:opp-name          (:attacker-name        bomb-result)
                          :attacker-wins?    true
                          :you-are-attacker? false
                          :soldiers          (:soldiers   bomb-result)
                          :transports        (:transports bomb-result)
                          :fighters          (:fighters   bomb-result)
                          :carriers          (:carriers   bomb-result)
                          :open?             true}))]))

       ;; Outgoing battle result
       (when battle-result
         (let [mode     (get battle-result :mode :invade)
               def-name (:defender-name battle-result)]
           (if (= mode :strike)
             (let [committed (:cmd-ships-committed battle-result 0)
                   lost      (:cmd-ships-lost battle-result 0)]
               (strike-card {:opp-name        def-name
                             :stations-mine?  false
                             :dispatched      (:cmd-ships-dispatched battle-result)
                             :intercepted     lost
                             :defender-losses (:defender-losses      battle-result)
                             :open?           (and (pos? committed)
                                                   (> (/ (double lost) committed) 0.05))}))
             (combat-card {:mode                mode
                           :opp-name            def-name
                           :attacker-wins?      (:attacker-wins? battle-result)
                           :my-counts           (:attacker-counts battle-result)
                           :my-losses           (:attacker-losses battle-result)
                           :opp-losses          (:defender-losses battle-result)
                           :stations-mine?      false
                           :resources-taken     (:resources-captured battle-result)
                           :planets-transferred (:planets-transferred battle-result)
                           :open?               (or (not (:attacker-wins? battle-result))
                                                    (non-trivial-losses?
                                                      (:attacker-counts battle-result)
                                                      (:attacker-losses battle-result)))}))))

       ;; Outgoing espionage result
       (when espionage-result
         (let [won? (:attacker-wins? espionage-result)
               op   (get espionage-result :op "spy")]
           (if (= op "bomb")
             (bomb-card {:opp-name          (:defender-name espionage-result)
                         :attacker-wins?    won?
                         :you-are-attacker? true
                         :soldiers          (:soldiers   espionage-result)
                         :transports        (:transports espionage-result)
                         :fighters          (:fighters   espionage-result)
                         :carriers          (:carriers   espionage-result)
                         :open?             (not won?)})
             (spy-card {:opp-name        (:defender-name espionage-result)
                        :won?            won?
                        :op              op
                        :spy             (:intel espionage-result)
                        :lost            (:agents-captured espionage-result 0)
                        :stab            (:stability-damage espionage-result)
                        :agents-defected (:agents-defected espionage-result)
                        :open?           (not won?)}))))

       (stability-card {:expense-penalty  expense-penalty
                        :breakaway-result breakaway-result
                        :recovery-result  recovery-result})

       (when eliminated?
         [:div.rounded-game.bg-game-surface.border.border-red-400.p-3
          [:h3.font-bold.mb-2.text-red-400 "Empire Eliminated"]
          [:p.text-xs.text-red-400
           "Your empire has no planets remaining. You have been eliminated."]])

       (when (some? pop-growth)
         (growth-card {:pop-growth pop-growth}))

       (ui/snapshot-section player)]

      (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (biff/form
          {:action (str "/app/game/" player-id "/apply-outcomes") :method "post"
           :class  "m-0"}
          (ui/submit-button true (if end-round? "End the Current Round" "Continue to Next Turn")))))))

