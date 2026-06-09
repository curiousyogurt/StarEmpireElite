;;;;;
;;;;; Espionage Phase - Covert Operation Selection
;;;;;
;;;;; The espionage phase is the fifth phase of each turn where players choose a target empire and
;;;;; a covert operation to run against it. Players with no agents are blocked from the phase.
;;;;; Like combat, the actual roll is resolved when the player loads the outcomes page.
;;;;;
;;;;; Operations:
;;;;;   Spy    — on success, reveals the target's full military unit counts as intel
;;;;;   Incite — on success, reduces the target's stability
;;;;;   Both ops share the same failure mechanic: some agents are captured by the defender.
;;;;;

;;;;; Logical Structure:
;;;;;
;;;;; 1)  espionage-page ← utils/get-other-players
;;;;;     └─ ui/phase-shell
;;;;;        ├─ biff/form
;;;;;        │  ├─ ui/phase-body
;;;;;        │  │  ├─ ui/snapshot-section
;;;;;        │  │  ├─ ui/section-label "Choose a Target"
;;;;;        │  │  └─ target-row  (per other player)
;;;;;        │  └─ ui/phase-warning
;;;;;        └─ ui/phase-action-bar
;;;;;           ├─ ui/action-bar-link "Pause"
;;;;;           ├─ ui/action-bar-button "Cancel Operation"
;;;;;           └─ ui/submit-button "Continue to Outcomes"
;;;;;
;;;;; 2)  update-espionage-warning  (HTMX OOB handler — toggles operation-queued warning)
;;;;;
;;;;; 3)  apply-espionage ← parse espionage-action param

(ns com.star-empire-elite.pages.app.espionage
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; UI Components
;;;; - Target Row
;;;;

;;;
;;; Target rows populate the targets table, one row per other player in the game.
;;; Each row shows empire name, planet count, score, and four radio-button operation
;;; modes (Spy, Incite, Bomb, Defect). A local `op-btn` fn renders each radio input
;;; with toggle-on-reclick behaviour and HTMX warning updates.
;;;
;;; - target-row: one table row with four radio-button operation modes
;;;

(defn- target-row
  "Render a single row in the targets table with Spy, Incite, Bomb, and Defect radio buttons.
  Each row produces four radio inputs sharing the same group name so only one
  selection across the whole table can be made.
  Composite radio values 'op:player-id' are parsed in apply-espionage.
  attacker-id-str is the current player's id, used for the HTMX warning endpoint.

  [player player-map, attacker-id-str string] -> hiccup"
  [player attacker-id-str has-agents?]
  (let [target-id-str (str (:xt/id player))
        radio-name    "espionage-action"
        warning-ep    (str "/app/game/" attacker-id-str "/espionage-warning")
        warning-id    "espionage-warning"
        op-btn        (fn [op label]
                        (if has-agents?
                          (ui/op-radio-btn radio-name
                            (str op ":" target-id-str)
                            label warning-ep warning-id)
                          (ui/disabled-radio-btn radio-name label)))]
    (into [:tr.bg-game-row.border-b.border-game-divider]
      (concat
        (ui/target-info-tds player)
        [[:td.py-1.px-3 (op-btn "spy"    "Spy")]
         [:td.py-1.px-3 (op-btn "incite" "Incite")]
         [:td.py-1.px-3 (op-btn "bomb"   "Bomb")]
         [:td.py-1.px-3 (op-btn "defect" "Defect")]]))))

;;;;
;;;; Actions
;;;;

(defn update-espionage-warning
  "Return a phase-warning-div for direct outerHTML swap.
  Shows when espionage-action param is non-empty, clears otherwise.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params]}]
  (let [queued? (seq (:espionage-action params))]
    (biff/render
      (ui/phase-warning-div "espionage-warning"
        (when queued? "\u24d8 Operation queued for Outcomes phase.")
        {:color "text-yellow-400"}))))

(defn apply-espionage
  "Parse the chosen operation and target, store them as pending espionage, and advance to outcomes.

  [ctx ring-ctx] -> ring-response (303 redirect to outcomes)"
  [{:keys [path-params params] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 5 player-id)]
      redirect
      (let [espionage-action (:espionage-action params)
            [op target-id-str] (when (and espionage-action (str/includes? espionage-action ":"))
                                 (str/split espionage-action #":" 2))
            target-uuid (when target-id-str (parse-uuid target-id-str))]
        (biff/submit-tx ctx [{:db/doc-type                  :player
                              :db/op                        :update
                              :xt/id                        player-id
                              :player/current-phase         6
                              :player/pending-espionage     target-uuid
                              :player/pending-espionage-op  op
                              :player/score                 (utils/calculate-score player)}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/outcomes")}}))))

;;;;
;;;; Page
;;;;

(defn espionage-page
  "Show the espionage phase: choose a target and operation, or skip.

  [{:keys [player game db]}] -> hiccup"
  [{:keys [player game db]}]
  (let [player-id     (:xt/id player)
        attacker-id   (str player-id)
        has-agents?   (pos? (:player/agents player))
        other-players (utils/get-other-players db (:player/game player) player-id)
        th-cls        "text-green-400 text-[11px] tracking-[0.08em] uppercase"]
    (ui/phase-shell player game "ESPIONAGE PHASE"
      (biff/form
       {:action (str "/app/game/" player-id "/apply-espionage")
        :method "post"
        :class  "m-0"}
       (ui/phase-body player
        (ui/snapshot-section player)
        (if (empty? other-players)
          [:p.text-sm.text-game-green-soft "There are no other empires to target."]
          [:div
           (ui/section-label "Choose a Target")
           [:p.text-xs.mb-2.text-game-green-muted
            "Spy: Reveal military forces. Incite: Reduce stability. Bomb: Covertly destroy uints. Defect: Turn enemy agents."]
           [:div.overflow-x-auto
            [:table.w-full.text-sm
             {:class "border border-game-border border-collapse"}
             [:thead
              [:tr.bg-game-header.border-b.border-game-border
               [:th.text-left.px-3.py-1  {:class th-cls} "Empire"]
               [:th.text-right.px-3.py-1 {:class th-cls} "Planets"]
               [:th.text-right.px-3.py-1 {:class th-cls} "Score"]
               [:th.px-3.py-1 {:colspan 4 :class th-cls} "Operations"]]]
             [:tbody
              (for [target other-players]
                (target-row target attacker-id has-agents?))]]]]))
       (when-not has-agents?
         [:p.text-sm.text-yellow-400.px-3.py-2
          "\u26a0 You have no agents. You cannot undertake covert operations this turn."])
       (ui/phase-warning "espionage-warning")
       (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (ui/action-bar-button "Cancel Operation"
          {:class      "cancel-target"
           :hx-post    (str "/app/game/" player-id "/espionage-warning")
           :hx-target  "#espionage-warning"
           :hx-swap    "outerHTML"
           :hx-params  "none"
           :onclick    "document.querySelectorAll('[name=espionage-action]').forEach(function(r){r.checked=false;r.dataset.was='false';});"})
        (ui/submit-button true "Continue to Outcomes"))))))
