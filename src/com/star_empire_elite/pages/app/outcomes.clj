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
;;;;; A proposed/accepted/withdrawn/declined/ended treaty is deliberately NOT shown here
;;;;; — those are already covered by the status-line alert chip's "instant messages" (see
;;;;; alerts.clj).
;;;;; The only diplomacy news this page ever carries is a treaty BREAKING as the direct
;;;;; consequence of this turn's own attack or espionage attempt — see treaty-broken-card below.
;;;;; A treaty ally's forces joining a battle shows up two ways: alliance-line, a third (and
;;;;; possibly fourth) result line on the battle card itself — one per side that had help,
;;;;; right after Orbit and Ground, for both sides' perspectives — and ally-battle-card,
;;;;; this player's own card when THEY were the ally who joined someone else's fight, on
;;;;; either side.
;;;;;

;;;;; Logical Structure:
;;;;;
;;;;; 1)  outcomes-page ← compute-summary
;;;;;     └─ ui/phase-shell
;;;;;        ├─ summary-strip
;;;;;        │  └─ delta-pill  (per non-zero delta)
;;;;;        ├─ incoming section
;;;;;        │  ├─ incoming-card  (per incoming attack)
;;;;;        │  │  ├─ combat-card | strike-card  (combat-card's Alliance line(s) name any
;;;;;        │  │  │  defence/offence allies who joined — see alliance-line, below Orbit/Ground)
;;;;;        │  │  └─ treaty-broken-card  (if this attack broke an active treaty)
;;;;;        │  ├─ ally-battle-card  (per battle this player's own forces joined as an ally)
;;;;;        │  ├─ espionage-fail-card
;;;;;        │  ├─ incoming-espionage-card  (incite / defect)
;;;;;        │  ├─ bomb-card
;;;;;        │  └─ treaty-broken-card  (per treaty broken by incoming espionage)
;;;;;        ├─ combat-card | strike-card | treaty-broken-card  (outgoing battle)
;;;;;        ├─ spy-card | bomb-card | treaty-broken-card  (outgoing espionage)
;;;;;        ├─ stability-card
;;;;;        ├─ elimination notice
;;;;;        ├─ growth-card
;;;;;        ├─ ui/snapshot-section
;;;;;        └─ ui/phase-action-bar
;;;;;           ├─ ui/action-bar-link "Pause"
;;;;;           └─ ui/submit-button "Continue to Diplomacy" | "Continue to Next Turn"
;;;;;
;;;;; 2)  apply-outcomes ← utils/calculate-score, turn/round advancement

(ns com.star-empire-elite.pages.app.outcomes
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

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

;;; The six unit types a defence or offence ally can contribute — see
;;; combat/contributable-units — as the same label+order battle-unit-specs already uses for
;;; the main combat table, so ally-battle-card's table reads as the same kind of report.
(def ^:private ally-unit-specs
  (filter #(#{:soldiers :transports :generals :fighters :carriers :admirals} (:unit-key %))
          battle-unit-specs))

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
  [:span.text-green-400 "+" (ui/format-number n)])

(defn- opp-loss-cell [n]
  (if (pos? n)
    [:span.text-red-400 "−" (ui/format-number n)]
    [:span.text-game-green-dim "0"]))

(defn- combat-row [left center right]
  [:tr.bg-game-row.border-b.border-game-divider
   [:td.text-right.py-1.px-3.border-r.border-game-divider left]
   [:td.text-center.py-1.px-2.border-r.border-game-divider.text-game-green-muted center]
   [:td.text-left.py-1.px-3 right]])

(defn- combat-table-header
  "Render the 3-column table header for combat and strike cards. you-label overrides the
  left header's text (default \"YOU\") — e.g. \"YOU (ALLIES)\" when this player had treaty
  allies whose own lumped contribution/loss the row below also reports, alongside (not
  instead of) this player's own figure. opp-allied? appends the same \" (ALLIES)\" suffix to
  the right header, when the OPPONENT had treaty allies of their own — the two are
  independent: either side, both, or neither may have brought allies to this fight.

  [opp-name string, you-label string|nil, opp-allied? bool|nil] -> hiccup"
  [opp-name & [you-label opp-allied?]]
  [:thead
   [:tr.bg-game-header.border-t.border-game-border.border-b.border-game-border
    [:th {:class (str col-header-cls " text-right")}  (str "◄ " (or you-label "YOU"))]
    [:th {:class (str col-header-cls " text-center")} "UNIT"]
    [:th {:class (str col-header-cls " text-left")}
     (str (str/upper-case opp-name) (when opp-allied? " (ALLIES)") " ►")]]])

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
           expense-penalty capture-penalty breakaway-result recovery-result fate-result]}]
  (let [sum-vals    (fn [m] (reduce + 0 (vals (or m {}))))
        ;; Helpers
        won-ground? (and battle-result
                         (:attacker-wins? battle-result)
                         (not= (:mode battle-result) :strike))
        incoming    (mapv edn/read-string (utils/take-recent (:player/incoming-attacks player)))
        in-wins     (fn [k] (reduce (fn [acc r]
                                      (if (and (:attacker-wins? r) (not= (:mode r) :strike))
                                        (merge-with + acc (k r {}))
                                        acc))
                                    {} incoming))
        ;; Unit losses — four sources, all as unit-count maps, merged and summed once.
        ;; out-losses/in-losses read :attacker-own-losses/:defender-own-losses, not the plain
        ;; :attacker-losses/:defender-losses — the latter are POOLED totals a side took
        ;; (that side plus any treaty allies), so using them here would overstate THIS
        ;; player's own losses by whatever an ally absorbed instead (see combat/resolve-
        ;; combat). Each falls back to the pooled key for a result stored before the -own-
        ;; field existed, when the two were always identical anyway.
        out-losses  (cond
                      (nil? battle-result)                     {}
                      (= (:mode battle-result) :strike)        {:cmd-ships (:cmd-ships-lost battle-result 0)}
                      :else                                    (or (:attacker-own-losses battle-result)
                                                                     (:attacker-losses battle-result {})))
        in-losses   (reduce (fn [acc r]
                              (merge-with + acc (or (:defender-own-losses r) (:defender-losses r) {})))
                            {} incoming)
        bomb-losses (if-let [r (some-> (:player/incoming-bomb-result player) edn/read-string)]
                      (dissoc r :attacker-name) {})
        ;; A turn this player's own treaty forces spent helping someone else fight, on
        ;; either side.
        ally-losses (reduce (fn [acc r] (merge-with + acc (:losses r {})))
                            {} (map edn/read-string (utils/take-recent (:player/incoming-ally-battles player))))
        units-lost  (sum-vals (merge-with + out-losses in-losses bomb-losses ally-losses))
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
        ;; Breakaway also draws down population, stockpiles, and military/leader units by
        ;; the same fraction as planets (resolution/calculate-stability-breakaway).
        break-res   (:resources-lost breakaway-result {})
        break-pop   (get break-res :player/population 0)
        break-units (reduce + 0 (vals (select-keys break-res
                                         [:player/soldiers :player/transports :player/fighters
                                          :player/carriers :player/cmd-ships :player/stations
                                          :player/generals :player/admirals])))
        credits (- (:credits out-res 0) (:credits in-res 0) (get break-res :player/credits 0))
        food    (- (:food out-res 0)    (:food in-res 0)    (get break-res :player/food    0))
        fuel    (- (:fuel out-res 0)    (:fuel in-res 0)    (get break-res :player/fuel    0))
        ;; Stability delta
        stab-loss (+ (or expense-penalty 0)
                     (or capture-penalty 0)
                     (or (:player/incoming-incite-stability-lost player) 0))
        stab-gain (if (and recovery-result (:triggered? recovery-result))
                    (:amount recovery-result 0) 0)
        ;; Agents delta
        esp-lost    (if (and espionage-result (not (:attacker-wins? espionage-result)))
                      (:agents-lost espionage-result 0) 0)
        esp-gained  (or (:player/incoming-espionage-agents-gained player) 0)
        esp-defect  (if (and espionage-result (:attacker-wins? espionage-result)
                             (= (:op espionage-result) "defect"))
                      (:agents-defected espionage-result 0) 0)
        in-defected (or (:player/incoming-defect-agents-lost player) 0)
        ;; Fate effect deltas: map :player/* keys onto summary fields.
        ;; Unit keys (solar flare) are absolute losses; resources/population are signed.
        fate-effect (or (:effect fate-result) {})
        unit-keys   #{:player/soldiers :player/transports :player/fighters :player/carriers}
        fate-units  (reduce (fn [acc [k v]] (if (unit-keys k) (+ acc (Math/abs (long v))) acc))
                            0 fate-effect)
        fate-food   (get fate-effect :player/food       0)
        fate-credits(get fate-effect :player/credits    0)
        fate-fuel   (get fate-effect :player/fuel       0)
        fate-pop    (get fate-effect :player/population 0)]
    {:units-lost (+ units-lost fate-units break-units)
     :ore ore :erg erg :mil mil
     :credits (+ credits fate-credits) :food (+ food fate-food) :fuel (+ fuel fate-fuel)
     :stability (- stab-gain stab-loss)
     :population (- (+ (or pop-growth 0) fate-pop) break-pop)
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
                 (let [pop (:population summary)]
                   (when-not (zero? pop)
                     [:span.inline-flex.items-center.gap-1
                      [:span.text-game-green-muted "POP"]
                      [:span {:class (if (neg? pop) "text-red-400" "text-green-400")}
                       (str (if (pos? pop) "+" "–") (ui/format-population (Math/abs pop)) "M")]]))

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

(defn- orbit-line
  "Render the space-phase result line for the combat card.
  Returns nil when space-result data is not available (backward compat).

  [space-att-wins? bool, space-buff double, opp-name str, you-are-attacker? bool] -> hiccup|nil"
  [space-att-wins? space-buff opp-name you-are-attacker?]
  (when (some? space-att-wins?)
    (let [you-won-space? (if you-are-attacker? space-att-wins? (not space-att-wins?))
          pct            (when space-buff (long (* space-buff 100)))
          significant?   (and pct (>= pct 1))
          buff-text      (if significant?
                           (str " (" pct "% to ground power)")
                           " (marginal edge)")]
      [:div.text-xs.px-3.border-t.border-game-divider
       {:class (str "py-1.5 " (if you-won-space? "text-green-400" "text-yellow-400"))}
       (if you-are-attacker?
         (if you-won-space?
           (str "» ORBIT: Your fleet achieved a secure orbit" buff-text)
           (str "» ORBIT: Your fleet failed to achieve a secure orbit" buff-text))
         (if you-won-space?
           (str "» ORBIT: Your fleet prevented the enemy achieving a secure orbit" buff-text)
           (str "» ORBIT: Your fleet failed to prevent the enemy achieving a secure orbit" buff-text)))])))

(defn- ground-line
  "Render the ground-phase result line for the combat card.
  Returns nil when space-result data is not available (backward compat — only show
  ground line when the two-phase model is active).

  [space-att-wins? bool, attacker-wins? bool, you-are-attacker? bool] -> hiccup|nil"
  [space-att-wins? attacker-wins? you-are-attacker?]
  (when (some? space-att-wins?)
    (let [you-won-ground? (if you-are-attacker? attacker-wins? (not attacker-wins?))]
      [:div.text-xs.px-3.border-t.border-game-divider
       {:class (str "py-1.5 " (if you-won-ground? "text-green-400" "text-yellow-400"))}
       (if you-are-attacker?
         (if you-won-ground?
           "» GROUND: Your ground forces overcame planetary defences"
           "» GROUND: Your ground forces failed to overcome planetary defences")
         (if you-won-ground?
           "» GROUND: Your ground forces prevented the enemy overcoming planetary defences"
           "» GROUND: Your ground forces failed to prevent the enemy overcoming planetary defences"))])))

(defn- alliance-line
  "Render an Alliance result line for the combat card, naming the treaty allies of one kind
  (label, e.g. \"DEFENCE\" or \"OFFENCE\") who joined this battle — same one-line pattern as
  orbit-line/ground-line above it, so each reads as one connected report instead of a
  separate notice bolted on afterward. Phrased neutrally (\"Joined by X\", not \"your\" or
  \"their\") so the exact same text is correct whichever side's card renders it — the label
  alone says which side the named allies reinforced. Returns nil when no ally joined.

  [label str, allies seq-of-{:ally-name str, ...}] -> hiccup|nil"
  [label allies]
  (when (seq allies)
    ;; Same green as a won Orbit/Ground line — reinforcement is never the bad outcome.
    [:div.text-xs.px-3.border-t.border-game-divider {:class "py-1.5 text-green-400"}
     (str "» " label ": Joined by " (str/join ", " (map :ally-name allies)) ".")]))

(defn- combat-card
  "Render a collapsible combat card with header summary and 3-col symmetric table.

  Left col: your unit count + inline red loss. Right col: opponent's loss delta.
  :stations-mine? true when you are the defender (you have stations).
  :defender-allies/:attacker-allies, when given, list the defence/offence allies who joined
  the defending/attacking side respectively (see alliance-line) — both are present
  identically on the attacker's outgoing card (who they really fought, and who helped them)
  and the defender's incoming card (same fight, same allies, from the other side).
  :you-label, when given, overrides the left header's \"YOU\" text (see combat-table-header)
  — the caller passes this when their side had treaty allies, so the header reads \"YOU
  (ALLIES)\" even though my-counts/my-losses themselves stay this player's own stock alone
  (never pooled with allies — see combat/resolve-combat's :attacker-own-counts/-losses and
  :defender-own-counts/-losses). Whenever :you-label is set, each row also appends the
  allies' own lumped contribution/loss, in muted-green parentheses, after this player's own
  figure — e.g. \"375.5K−42.3K (500.1K−256.1K)\" — summed across however many allies joined
  (see defender-allies/attacker-allies above), not broken out per ally. Independently of
  :you-label, whenever the OPPONENT's side had treaty allies, the right header gets its own
  \" (ALLIES)\" suffix, and each row appends the opponent allies' own lumped loss the same
  way — e.g. \"−189K (−35.6K)\" — mirroring the left column: opp-losses itself stays the
  opponent's OWN loss alone, never pooled with their allies, same principle as my-counts/
  my-losses; the right column never shows a count, so there's no contributed figure to pair
  with the loss there. Either side, both, or neither may have brought help.
  :open? controls the initial expansion state.

  [opts {:keys [mode opp-name attacker-wins? my-counts my-losses opp-losses stations-mine?
                resources-taken planets-transferred space-att-wins? space-buff
                defender-allies attacker-allies you-label open?]}] -> hiccup"
  [{:keys [mode opp-name attacker-wins? my-counts my-losses opp-losses
           stations-mine? resources-taken planets-transferred
           space-att-wins? space-buff defender-allies attacker-allies you-label open?]}]
  (let [dash           [:span.text-game-green-dim "—"]
        ;; The allies folded into my-counts/my-losses above — only set when the caller
        ;; signaled a pooled "(ALLIES)" display (see you-label), and only the allies that
        ;; actually joined MY side on this card, not the opponent's.
        my-allies      (when you-label (if stations-mine? defender-allies attacker-allies))
        ;; The opponent's own allies — the other side of the same coin. Drives both the
        ;; right header's "(ALLIES)" suffix and the right column's own ally-loss breakdown
        ;; below (opp-losses is already pooled regardless of either).
        opp-allies     (if stations-mine? attacker-allies defender-allies)
        ally-sum       (fn [allies unit-key k] (reduce + 0 (map #(get-in % [k unit-key] 0) allies)))
        make-left      (fn [unit-key defender-only?]
                         (if (and defender-only? (not stations-mine?))
                           dash
                           (let [ally-contrib (ally-sum my-allies unit-key :contributed)
                                 ally-loss    (ally-sum my-allies unit-key :losses)]
                             [:<>
                              [:span.text-game-green-soft
                               (ui/format-number (get my-counts unit-key 0))]
                              (let [loss (get my-losses unit-key 0)]
                                (when (pos? loss)
                                  [:span.text-red-400 {:class "ml-1.5"}
                                   "−" (ui/format-number loss)]))
                              ;; Allies' own lumped share, muted so it reads as secondary
                              ;; to the pooled total above — contribution and loss both the
                              ;; same muted green, not red, since this isn't a loss *to you*.
                              ;; Only shown for a unit type the allies actually contributed —
                              ;; cmd-ships/stations never appear here, since allies never
                              ;; contribute those.
                              (when (pos? ally-contrib)
                                [:span.text-game-green-muted {:class "ml-1.5"}
                                 " (" (ui/format-number ally-contrib)
                                 (when (pos? ally-loss)
                                   [:<> "−" (ui/format-number ally-loss)])
                                 ")"])])))
        make-right     (fn [unit-key defender-only?]
                         (if (and defender-only? stations-mine?)
                           dash
                           (let [opp-ally-loss (ally-sum opp-allies unit-key :losses)]
                             [:<>
                              (opp-loss-cell (get opp-losses unit-key 0))
                              ;; How much of the opponent's pooled loss their own allies
                              ;; absorbed — same muted-green parenthetical as the left
                              ;; column, mirrored. The right column never shows a count (see
                              ;; opp-loss-cell), so there's no "contributed" figure to pair
                              ;; it with here, only the loss share.
                              (when (pos? opp-ally-loss)
                                [:span.text-game-green-muted {:class "ml-1.5"}
                                 " (" "−" (ui/format-number opp-ally-loss) ")"])])))
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
    (ui/collapsible-card
      {:badge   (ui/mode-badge mode)
       :summary (result-summary verb opp-name attacker-wins? (not stations-mine?))
       :open?   open?}
      (orbit-line space-att-wins? space-buff opp-name (not stations-mine?))
      (ground-line space-att-wins? attacker-wins? (not stations-mine?))
      (alliance-line "DEFENCE" defender-allies)
      (alliance-line "OFFENCE" attacker-allies)
      [:div.overflow-x-auto
       [:table.w-full.text-xs.table-fixed
        (combat-table-header opp-name you-label (seq opp-allies))
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
                          [:span.text-red-400 {:class "ml-1.5"}
                           "−" (ui/format-number intercepted)])]
        dl             (or defender-losses {})
        unit-pairs     [["soldiers" (:soldiers dl)] ["transports" (:transports dl)]
                        ["fighters" (:fighters dl)] ["stations"   (:stations   dl)]
                        ["agents"   (:agents   dl)]]
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
                        ["def stns"   dash (opp-loss-cell (:stations   dl))]
                        ["agents"     dash (opp-loss-cell (:agents     dl))]]]
    (ui/collapsible-card
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
    (ui/collapsible-card
      {:badge   (ui/mode-badge :spy)
       :summary (result-summary verb opp-name won? true)
       :open?   open?}
      [:div.px-3.border-t.border-game-divider {:class "py-1.5"}
       (cond
         (and won? (= op "spy"))
         [:div.grid.grid-cols-3.gap-x-4 {:class "gap-y-0.5"}
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
          (if (and stab (pos? stab))
            (str "Your agents successfully stirred unrest. "
                 "The stability of " opp-name " was reduced by " stab "%.")
            (str "Your agents stirred unrest, but " opp-name
                 "'s population is already at peak instability. Stability was unaffected."))]

         (and won? (= op "defect"))
         [:p.text-xs.text-game-green-soft
          (str agents-defected " agent(s) defected from " opp-name " to your service.")]

         :else
         [:p.text-xs.text-game-green-soft
          (str "Your agents were unable to complete their mission. "
               lost " agent(s) were captured by " opp-name ".")])])))

(defn- bomb-card
  "Render a collapsible bombing raid result card."
  [{:keys [opp-name attacker-wins? you-are-attacker? soldiers transports fighters carriers
           agents-lost open?]}]
  (let [units        [["Soldiers"   soldiers]
                      ["Transports" transports]
                      ["Fighters"   fighters]
                      ["Carriers"   carriers]]
        summary-text (when attacker-wins?
                       (destroyed-units-text
                         [["soldiers" soldiers] ["transports" transports]
                          ["fighters" fighters] ["carriers"   carriers]]))]
    (ui/collapsible-card
      {:badge         (ui/mode-badge :bomb)
       :summary       (result-summary "bombed" opp-name attacker-wins? you-are-attacker?)
       :summary-extra (when summary-text [:span.text-xs.text-game-green-dim (str "— " summary-text)])
       :open?         open?}
      (if attacker-wins?
        [:div.grid.grid-cols-4.px-3.border-t.border-game-divider {:class "py-1.5"}
         (for [[label val] units]
           [:div.flex.justify-between.items-center.pr-4
            [:span.text-game-green-muted.text-xs label]
            [:span.text-game-green-soft (ui/format-number val)]])]
        (when (and you-are-attacker? agents-lost (pos? agents-lost))
          [:div.px-3.border-t.border-game-divider {:class "py-1.5"}
           [:p.text-xs.text-game-green-soft
            (str agents-lost " agent(s) were lost in the operation.")]])))))

(defn- fate-card
  "Render a collapsible card for a fate event (disaster or boon).
  Defaults open for disasters (player needs to see bad news); collapsed for boons.
  Returns nil when there's nothing to show — a boon with no resource moved, or a disaster
  that neither collected any resource nor spilled into stability.

  [fate-result map] -> hiccup | nil"
  [fate-result]
  (let [polarity  (:polarity fate-result)
        label     (:label    fate-result)
        effect    (:effect   fate-result)
        spill     (:stability-spill fate-result 0)
        disaster? (= polarity :disaster)
        ;; Sum absolute deltas for the summary line; label the resource.
        total     (reduce (fn [acc [_k v]] (+ acc (Math/abs (long v)))) 0 effect)]
    (when (or (pos? total) (pos? spill))
      (let [res-name (case (ffirst effect)
                       :player/food       "food"
                       :player/credits    "credits"
                       :player/fuel       "fuel"
                       :player/population "population"
                       "units")
            ;; A disaster the player already spent down still costs them — in stability
            ;; instead of the resource, which is already gone.
            summary  (cond
                       (and disaster? (zero? total))
                       [:span.text-red-400
                        label " — vault empty, cost " (ui/format-number spill) "% stability instead"]

                       ;; Partially dodged: some of the resource was there to take, but not
                       ;; enough to cover the full raid — the shortfall spills into stability
                       ;; too. Same event, described cause → effect, not a second penalty.
                       (and disaster? (pos? spill))
                       [:span.text-red-400
                        label " — lost " (ui/format-number total) " " res-name
                        " — the rest cost " (ui/format-number spill) "% stability"]

                       disaster?
                       [:span.text-red-400
                        label " — lost " (ui/format-number total) " " res-name]

                       :else
                       [:span.text-green-400
                        label " — gained " (ui/format-number total) " " res-name])]
        ;; Solar Flare hits multiple unit types — expand to show the per-unit breakdown.
        ;; All other fates have one target, so the detail would just repeat the summary.
        (if (> (count effect) 1)
          (ui/collapsible-card
            {:badge   (ui/mode-badge polarity)
             :summary summary
             :open?   disaster?}
            [:div.px-3.border-t.border-game-divider {:class "py-1.5"}
             [:div.grid.gap-x-4 {:class "grid-cols-2 gap-y-0.5"}
              (for [[k delta] effect
                    :when (not (zero? delta))]
                (let [res (name k)  ; strip :player/ namespace for display
                      cls (if disaster? "text-red-400" "text-green-400")]
                  [:div.flex.justify-between.items-center
                   [:span.text-game-green-muted.text-xs res]
                   [:span {:class cls}
                    (if disaster?
                      (str "−" (ui/format-number-str (Math/abs (long delta))))
                      (str "+" (ui/format-number-str delta)))]]))]])
          [:div.rounded-game.bg-game-surface.overflow-hidden
           {:class "border border-game-border"}
           [:div.flex.items-center.py-2.px-3.gap-3
            (ui/mode-badge polarity)
            [:span.text-sm.flex-1 summary]]])))))

(defn- treaty-broken-card
  "Render a one-line notice when this battle broke an active treaty between the two
  empires. you-are-attacker? controls perspective: true for the outgoing battle card (you
  attacked), false for an incoming one (they attacked you). kind names which treaty kind was
  broken (:peace, :defence, …) when known; omit it for a kind-neutral 'a treaty' when it
  isn't — e.g. a treaty broken by someone else's espionage against you, whose history field
  only ever stored an empire name, never the kind.

  [opp-name str, you-are-attacker? bool, & [kind keyword|nil]] -> hiccup"
  [opp-name you-are-attacker? & [kind]]
  (let [label (when kind (str (name kind) " "))]
    [:div.rounded-game.bg-game-surface.overflow-hidden
     {:class "border border-game-border"}
     [:div.flex.items-center.py-2.px-3.gap-3
      (ui/mode-badge :treaty-broken)
      [:span.text-sm.text-game-green-soft
       (if you-are-attacker?
         (str "You broke your " label "treaty with " opp-name ".")
         (str opp-name " broke their " label "treaty with you."))]]]))

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
  "Render a stability card with a single info line summarising all stability changes this turn.
  Each non-zero component is rendered as a colored fragment: red for losses, green for gains.
  Breakaway gets an additional detail line below."
  [{:keys [expense-penalty capture-penalty incite-penalty fate-spill breakaway-result recovery-result]}]
  (let [has-breakaway? (and (some? breakaway-result) (:triggered? breakaway-result))
        fragments (keep identity
                    [(when (and (some? expense-penalty) (pos? expense-penalty))
                       [(str "−" (ui/format-number-str expense-penalty) "% unpaid expenses") "text-red-400"])
                     (when (and (some? capture-penalty) (pos? capture-penalty))
                       [(str "−" (ui/format-number-str capture-penalty) "% integrating captured planets") "text-yellow-400"])
                     (when (and (some? incite-penalty) (pos? incite-penalty))
                       [(str "−" (ui/format-number-str incite-penalty) "% enemy incited unrest") "text-red-400"])
                     (when (and (some? fate-spill) (pos? fate-spill))
                       [(str "−" (ui/format-number-str fate-spill) "% unabsorbed disaster") "text-red-400"])
                     (when (and (some? recovery-result) (:triggered? recovery-result))
                       [(str "+" (ui/format-number-str (:amount recovery-result)) "% all expenses covered") "text-green-400"])])]
    (when (or (seq fragments) has-breakaway?)
      [:div.rounded-game.bg-game-surface.overflow-hidden
       {:class "border border-game-border"}
       [:div.flex.items-center.py-2.px-3.gap-3
        (ui/mode-badge :stability)
        [:span.text-sm
         (if (seq fragments)
           (interpose
             [:span.text-game-green-dim "  ·  "]
             (for [[text cls] fragments]
               [:span {:class cls} text]))
           [:span.text-game-green-soft "No stability changes"])]]
       (when has-breakaway?
         [:div.px-3.border-t.border-game-divider {:class "py-1.5"}
          [:p.text-xs.text-red-400
           ;; Same :fraction that sized the planet loss also drained population, stockpiles,
           ;; and forces (:resources-lost) — both appear in one combined "Lost:" list.
           (let [res (:resources-lost breakaway-result {})
                 unit-total (reduce + 0 (vals (select-keys res
                                                 [:player/soldiers :player/transports :player/fighters
                                                  :player/carriers :player/cmd-ships :player/stations
                                                  :player/generals :player/admirals])))
                 lost-str (str/join ", "
                            (keep identity
                              [(when (pos? (:ore-lost breakaway-result))
                                 (str (ui/format-number-str (:ore-lost breakaway-result)) " ore planet(s)"))
                               (when (pos? (:erg-lost breakaway-result))
                                 (str (ui/format-number-str (:erg-lost breakaway-result)) " energy planet(s)"))
                               (when (pos? (:mil-lost breakaway-result))
                                 (str (ui/format-number-str (:mil-lost breakaway-result)) " military planet(s)"))
                               (when (pos? (get res :player/population 0))
                                 (str (ui/format-population (get res :player/population 0)) " population"))
                               (when (pos? (get res :player/credits 0))
                                 (str (ui/format-number-str (get res :player/credits 0)) " credits"))
                               (when (pos? (get res :player/food 0))
                                 (str (ui/format-number-str (get res :player/food 0)) " food"))
                               (when (pos? (get res :player/fuel 0))
                                 (str (ui/format-number-str (get res :player/fuel 0)) " fuel"))
                               (when (pos? unit-total)
                                 (str (ui/format-number-str unit-total) " units"))]))]
             (str "Empire instability prompts revolution. Lost: " lost-str))]])])))

(defn- incoming-card
  "Render a single incoming attack card (combat or strike), plus a treaty-broken notice
  when this attack broke an active treaty between the two empires. A treaty ally's forces
  joining the fight, on either side, shows up as the card's own Alliance line(s) (see
  alliance-line), not a separate notice. :opp-losses is the attacker's OWN loss alone — never
  pooled with their offence allies — matching :my-counts/:my-losses below; the attacker's
  allies' own lumped loss is shown separately, in combat-card's own right-column
  parenthetical."
  [r]
  (let [result (edn/read-string r)
        card   (if (= (:mode result) :strike)
                 (strike-card {:opp-name          (:attacker-name result)
                               :stations-mine?    true
                               :dispatched        (:cmd-ships-dispatched result)
                               :intercepted       (:cmd-ships-lost result)
                               :defender-losses   (:defender-losses result)
                               :open?             true})
                 (let [space-r (:space-result result)
                       ;; "YOU" is always this player's own stock alone — never pooled with
                       ;; allies (see combat/resolve-combat's :defender-own-counts/-losses) —
                       ;; the allies' own lumped contribution/loss is shown separately, in
                       ;; the parenthetical combat-card adds next to each row (see my-allies
                       ;; there). Falls back to the plain :defender-counts/-losses keys for a
                       ;; result stored before defence treaties existed, when the two were
                       ;; always identical anyway.
                       allied?   (seq (:defender-allies result))
                       my-counts (or (:defender-own-counts result) (:defender-counts result))
                       my-losses (or (:defender-own-losses result) (:defender-losses result))]
                   (combat-card {:mode                (get result :mode :invade)
                                 :opp-name            (:attacker-name result)
                                 :attacker-wins?      (:attacker-wins? result)
                                 :my-counts           my-counts
                                 :my-losses           my-losses
                                 :opp-losses          (or (:attacker-own-losses result)
                                                           (:attacker-losses result))
                                 :stations-mine?      true
                                 :resources-taken     (:resources-captured result)
                                 :planets-transferred (:planets-transferred result)
                                 :space-att-wins?     (:att-wins? space-r)
                                 :space-buff          (:space-carryover result)
                                 :defender-allies     (:defender-allies result)
                                 :attacker-allies     (:attacker-allies result)
                                 :you-label           (when allied? "YOU (ALLIES)")
                                 :open?               true})))]
    [:<> card
     (when (:treaty-broken? result)
       (treaty-broken-card (:attacker-name result) false (:treaty-broken-kind result)))]))

(defn- ally-battle-card
  "Render a card for a battle this player's own treaty forces joined on someone else's
  behalf (see resolution/ally-update-tx) — who they helped, who they fought, the outcome,
  and a 3-column table in the same shape as the real attacker/defender's own combat card
  (see combat-table-header/combat-row). role is :defending (joined the defender against the
  attacker) or :attacking (joined the attacker against the defender) — each has its own
  wording, success condition, and opponent.

  Left col ('◄ YOU (ALLIES)'): this player's own contributed units with any loss shown
  inline, same as combat-card's make-left, followed by a muted-green parenthetical for the
  player's TEAMMATES — the real combatant they joined, plus any co-allies, all lumped
  together — found by subtracting this player's own :contributed/:losses from the whole
  side's pooled :attacker-counts/:attacker-losses (or :defender-counts/:defender-losses for
  a defending ally).
  Right col: the OPPONENT's own loss alone for that unit (:attacker-own-losses/
  :defender-own-losses — never pooled with the opponent's own allies, same own-vs-pooled
  principle as combat-card), with its own muted-green parenthetical for the opponent's
  allies' lumped loss share, found the same way — subtracting their own loss from their
  side's pooled :attacker-losses/:defender-losses — and the header gets its own '(ALLIES)'
  suffix when that opponent-ally share is non-zero, exactly mirroring combat-card's
  opp-allied?.
  Every pooled/own field above falls back to nil when absent (a record stored before it
  existed), which omits the corresponding parenthetical — or, for -own-losses specifically,
  falls back to the pooled figure so the right column still shows a number — rather than
  showing a bogus or missing one.

  [r string (pr-str'd summary)] -> hiccup"
  [r]
  (let [{:keys [role attacker-name defender-name mode attacker-wins? contributed losses
                attacker-losses defender-losses attacker-own-losses defender-own-losses
                attacker-counts defender-counts]}
        (edn/read-string r)
        defending?  (= role :defending)
        ;; Joining the defence succeeds when the attacker loses; joining the offence
        ;; succeeds when the attacker wins — same reasoning as alerts/ally-battle-messages.
        won?        (if defending? (not attacker-wins?) attacker-wins?)
        ;; The opponent is whichever side this ally didn't join — the attacker for a
        ;; defending ally, the defender for an attacking one — and the table header/right
        ;; column follow suit.
        opp-name          (if defending? attacker-name defender-name)
        opp-own-losses    (if defending? attacker-own-losses defender-own-losses)
        opp-pooled-losses (if defending? attacker-losses defender-losses)
        opp-losses        (or opp-own-losses opp-pooled-losses {})
        ;; MY side's pooled totals (nil, not {}, when absent — see docstring).
        my-counts   (if defending? defender-counts attacker-counts)
        my-losses   (if defending? defender-losses  attacker-losses)
        ;; "Everyone else on a side's" lumped share for one unit — pooled minus this one
        ;; member's own share — nil (no parenthetical) unless BOTH are known, so a record
        ;; missing either half never shows a fabricated number (e.g. treating an absent
        ;; "own" as zero, which would wrongly attribute the whole pooled total to the rest
        ;; of the side).
        team-sum    (fn [pooled own unit-key]
                      (when (and pooled own) (- (get pooled unit-key 0) (get own unit-key 0))))
        opp-allied? (some #(some-> (team-sum opp-pooled-losses opp-own-losses %) pos?)
                          (map :unit-key ally-unit-specs))
        joined-desc (if defending?
                      (str "You joined " defender-name "'s defence against " attacker-name)
                      (str "You joined " attacker-name "'s offensive against " defender-name))]
    (ui/collapsible-card
      {:badge   (ui/mode-badge mode)
       :summary [:<> [:span.text-game-green-soft joined-desc]
                     [:span {:class (if won? "text-green-400" "text-red-400")}
                      (if won? " (SUCCESS)" " (FAILURE)")]]
       :open?   (not won?)}
      [:div.overflow-x-auto
       [:table.w-full.text-xs.table-fixed
        (combat-table-header opp-name "YOU (ALLIES)" opp-allied?)
        [:tbody
         (for [{:keys [label unit-key]} ally-unit-specs
               :let [team-contrib (team-sum my-counts contributed unit-key)
                     team-loss    (team-sum my-losses losses unit-key)
                     opp-ally-loss (team-sum opp-pooled-losses opp-own-losses unit-key)]]
           (combat-row
             [:<> [:span.text-game-green-soft (ui/format-number (get contributed unit-key 0))]
                  (let [lost (get losses unit-key 0)]
                    (when (pos? lost) [:span.text-red-400 {:class "ml-1.5"} "−" (ui/format-number lost)]))
                  ;; Teammates' lumped contribution/loss — same muted-green parenthetical
                  ;; convention as combat-card's own ally breakdown.
                  (when (some-> team-contrib pos?)
                    [:span.text-game-green-muted {:class "ml-1.5"}
                     " (" (ui/format-number team-contrib)
                     (when (some-> team-loss pos?)
                       [:<> "−" (ui/format-number team-loss)])
                     ")"])]
             label
             [:<> (opp-loss-cell (get opp-losses unit-key 0))
                  ;; The opponent's own allies' lumped loss share — mirrors combat-card's
                  ;; make-right.
                  (when (some-> opp-ally-loss pos?)
                    [:span.text-game-green-muted {:class "ml-1.5"}
                     " (" "−" (ui/format-number opp-ally-loss) ")"])]))]]])))

(defn- incoming-espionage-card
  "Render a collapsible card for a successful incoming espionage operation (incite or defect).

  [mode keyword, summary-text string, body-text string] -> hiccup"
  [mode summary-text body-text]
  (ui/collapsible-card
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
    (ui/collapsible-card
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

  Ending a round routes to the diplomacy phase (7) instead of straight to the next income
  phase (1) — see pages/app/diplomacy.clj. The turn/round counters are already advanced to
  their next values here; diplomacy only moves the phase counter the rest of the way to 1
  once the player is done there.

  [ctx ring-ctx] -> ring-response (303 redirect to diplomacy, if ending a round, else income)"
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
              reset-used      (if end-round? 0 turns-used)
              ;; Reset-then-increment for rounds-started-today:
              ;; If last-turn-at is from a previous UTC day, the budget resets to 0.
              ;; Then, if the player just started a round (old turns-used was 0),
              ;; increment by 1 to consume a start from today's budget.
              old-last-turn  (:player/last-turn-at player)
              stale?         (and (some? old-last-turn)
                                  (< (.getTime ^java.util.Date old-last-turn)
                                     (.getTime (utils/utc-midnight now))))
              base-starts    (if stale? 0 (or (:player/rounds-started-today player) 0))
              round-start?   (zero? (:player/turns-used player))
              new-starts     (if round-start? (inc base-starts) base-starts)]
          (biff/submit-tx ctx
            [(merge {:db/doc-type                            :player
                     :db/op                                  :update
                     :xt/id                                  player-id
                     :player/current-turn                    next-turn
                     :player/current-round                   next-round
                     :player/turns-used                      reset-used
                     :player/current-phase                   (if end-round? 7 1)
                     :player/last-turn-at                    now
                     :player/rounds-started-today            new-starts
                     :player/last-battle-result              nil
                     :player/last-espionage-result           nil
                     :player/last-population-growth          nil
                     :player/last-expense-stability-penalty  nil
                     :player/last-stability-breakaway        nil
                     :player/last-stability-recovery         nil
                     :player/last-capture-stability-penalty  nil
                     :player/last-fate-result                nil
                     :player/pending-espionage               nil
                     :player/incoming-attacks                nil
                     :player/incoming-ally-battles            nil
                     :player/incoming-espionage-fails        nil
                     :player/incoming-espionage-agents-gained nil
                     :player/incoming-incite-stability-lost  nil
                     :player/incoming-bomb-result            nil
                     :player/incoming-defect-agents-lost    nil
                     ;; Unlike incoming-treaty-proposed/accepted/withdrawn/declined/ended
                     ;; below, a treaty broken by espionage isn't actionable in the Diplomacy
                     ;; phase — it's already gone — so this clears on the same turn-end
                     ;; schedule as the other incoming-* history fields above, not the
                     ;; treaty-* ones.
                     :player/incoming-espionage-treaty-broken nil
                     ;; incoming-treaty-* is deliberately NOT cleared here — those
                     ;; notifications (someone proposed/accepted/withdrew/declined/ended
                     ;; a treaty with you) stay live through to the Diplomacy phase, where they're
                     ;; actually actionable, and get cleared there instead (apply-diplomacy).
                     ;; Clearing them here would wipe the diplomacy alert before the player
                     ;; even reaches the Diplomacy phase that only opens once per round.
                     :player/incoming-seq                    nil
                     :player/incoming-seen-seq               nil
                     :player/incoming-seen-groups             nil
                     :player/pending-espionage-op            nil
                     :player/score                           (utils/calculate-score game player)}
                    (when end-round?
                      {:player/last-round-completed-at now}))])
          {:status 303
           :headers {"location" (str "/app/game/" player-id
                                      (if end-round? "/diplomacy" "/income"))}})))))

;;;;
;;;; Page
;;;;

(defn outcomes-page
  "Show turn results with a summary strip, collapsible event cards, and the advance button.

  Cards use native <details>/<summary> for collapse. Smart defaults expand events
  that matter (incoming attacks, defeats, non-trivial losses, disasters) and collapse
  routine successes and steady-state events.

  [{:keys [player game battle-result espionage-result pop-growth expense-penalty
           capture-penalty breakaway-result recovery-result fate-result eliminated?]}] -> hiccup"
  [{:keys [player game battle-result espionage-result pop-growth expense-penalty
           capture-penalty breakaway-result recovery-result fate-result eliminated?] :as outcomes}]
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

       ;; Incoming section — attacks and espionage received this turn. Diplomacy notices
       ;; (proposed/accepted/withdrawn/declined/ended) are NOT shown here — see the file
       ;; header comment. A treaty broken by someone else's espionage against you is the one
       ;; diplomacy item that does belong here, since it's a direct consequence of an
       ;; incoming espionage attempt this turn, same as combat's treaty-broken-card above.
       (let [incoming             (seq (utils/take-recent (:player/incoming-attacks player)))
             ally-battles         (seq (utils/take-recent (:player/incoming-ally-battles player)))
             esp-fails            (utils/take-recent (:player/incoming-espionage-fails player))
             esp-agents           (or (:player/incoming-espionage-agents-gained player) 0)
             incite-stab-lost     (or (:player/incoming-incite-stability-lost player) 0)
             bomb-result          (some-> (:player/incoming-bomb-result player) edn/read-string)
             defect-agents-lost   (or (:player/incoming-defect-agents-lost player) 0)
             esp-treaties-broken  (seq (utils/take-recent (:player/incoming-espionage-treaty-broken player)))]
         (when (or incoming ally-battles (seq esp-fails) (pos? incite-stab-lost) bomb-result
                   (pos? defect-agents-lost) esp-treaties-broken)
           [:div.flex.flex-col.gap-2
            (map incoming-card incoming)
            (map ally-battle-card ally-battles)
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
                          :open?             true}))
            (for [empire-name esp-treaties-broken]
              (treaty-broken-card empire-name false))]))

       ;; Outgoing battle result
       (when battle-result
         (let [mode     (get battle-result :mode :invade)
               def-name (:defender-name battle-result)
               card     (if (= mode :strike)
                          (let [committed (:cmd-ships-committed battle-result 0)
                                lost      (:cmd-ships-lost battle-result 0)]
                            (strike-card {:opp-name        def-name
                                          :stations-mine?  false
                                          :dispatched      (:cmd-ships-dispatched battle-result)
                                          :intercepted     lost
                                          :defender-losses (:defender-losses      battle-result)
                                          :open?           (and (pos? committed)
                                                                (> (/ (double lost) committed) 0.05))}))
                          (let [space-r (:space-result battle-result)
                                ;; "YOU" is always this player's own stock alone — never
                                ;; pooled with allies (see combat/resolve-combat's
                                ;; :attacker-own-counts/-losses) — the allies' own lumped
                                ;; contribution/loss is shown separately, in the parenthetical
                                ;; combat-card adds next to each row (see my-allies there).
                                ;; Falls back to the plain :attacker-counts/-losses keys for a
                                ;; result stored before offence treaties existed, when the two
                                ;; were always identical anyway.
                                allied?  (seq (:attacker-allies battle-result))
                                my-counts (or (:attacker-own-counts battle-result)
                                              (:attacker-counts battle-result))
                                my-losses (or (:attacker-own-losses battle-result)
                                              (:attacker-losses battle-result))]
                            (combat-card {:mode                mode
                                          :opp-name            def-name
                                          :attacker-wins?      (:attacker-wins? battle-result)
                                          :my-counts           my-counts
                                          :my-losses           my-losses
                                          :opp-losses          (or (:defender-own-losses battle-result)
                                                                    (:defender-losses battle-result))
                                          :stations-mine?      false
                                          :resources-taken     (:resources-captured battle-result)
                                          :planets-transferred (:planets-transferred battle-result)
                                          :space-att-wins?     (:att-wins? space-r)
                                          :space-buff          (:space-carryover battle-result)
                                          :defender-allies     (:defender-allies battle-result)
                                          :attacker-allies     (:attacker-allies battle-result)
                                          :you-label           (when allied? "YOU (ALLIES)")
                                          :open?               (or (not (:attacker-wins? battle-result))
                                                                       (and space-r (not (:att-wins? space-r)))
                                                                       (non-trivial-losses? my-counts my-losses))})))]
           [:<> card
            (when (:treaty-broken? battle-result)
              (treaty-broken-card def-name true (:treaty-broken-kind battle-result)))]))

       ;; Outgoing espionage result
       (when espionage-result
         (let [won? (:attacker-wins? espionage-result)
               op   (get espionage-result :op "spy")
               card (if (= op "bomb")
                      (bomb-card {:opp-name          (:defender-name espionage-result)
                                  :attacker-wins?    won?
                                  :you-are-attacker? true
                                  :soldiers          (:soldiers   espionage-result)
                                  :transports        (:transports espionage-result)
                                  :fighters          (:fighters   espionage-result)
                                  :carriers          (:carriers   espionage-result)
                                  :agents-lost       (:agents-lost espionage-result 0)
                                  :open?             (not won?)})
                      (spy-card {:opp-name        (:defender-name espionage-result)
                                 :won?            won?
                                 :op              op
                                 :spy             (:intel espionage-result)
                                 :lost            (:agents-lost espionage-result 0)
                                 :stab            (:stability-damage espionage-result)
                                 :agents-defected (:agents-defected espionage-result)
                                 :open?           (not won?)}))]
           (if (:treaty-broken? espionage-result)
             [:<> card (treaty-broken-card (:defender-name espionage-result) true
                         (:treaty-broken-kind espionage-result))]
             card)))

       (stability-card {:expense-penalty  expense-penalty
                        :capture-penalty  capture-penalty
                        :incite-penalty   (or (:player/incoming-incite-stability-lost player) 0)
                        :fate-spill       (:stability-spill fate-result 0)
                        :breakaway-result breakaway-result
                        :recovery-result  recovery-result})

       (when fate-result
         (fate-card fate-result))

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
          (ui/submit-button true (if end-round? "Continue to Diplomacy" "Continue to Next Turn")))))))

