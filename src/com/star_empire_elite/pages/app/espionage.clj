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

(ns com.star-empire-elite.pages.app.espionage
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; UI Components
;;;;

(def ^:private op-js
  (str "var p=this.dataset.was==='true';"
       "document.querySelectorAll('[name=espionage-action]').forEach(function(r){r.dataset.was='false';});"
       "if(p){this.checked=false;}else{this.dataset.was='true';}"))

(defn- op-radio
  "Render a radio input + styled label for one operation on one target.
  attacker-id-str is the current player's id, used for the HTMX warning endpoint.

  [op string, target-id-str string, attacker-id-str string, label string] -> hiccup"
  [op target-id-str attacker-id-str label]
  [:label.block.cursor-pointer
   [:input.peer.sr-only
    {:type       "radio"
     :name       "espionage-action"
     :value      (str op ":" target-id-str)
     :hx-post    (str "/app/game/" attacker-id-str "/espionage-warning")
     :hx-trigger "click"
     :hx-target  "#espionage-warning"
     :hx-swap    "outerHTML"
     :onclick    op-js}]
   [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border.transition-colors
    {:class "text-green-400 border-green-400 hover:text-yellow-400 hover:border-yellow-400 peer-checked:text-yellow-400 peer-checked:border-yellow-400 peer-checked:bg-yellow-400 peer-checked:bg-opacity-10"}
    label]])

(defn target-row
  "Render a single row in the targets table with Spy, Incite, Bomb, and Defect radio buttons.

  [player player-map, attacker-id-str string] -> hiccup"
  [player attacker-id-str]
  (let [total-planets (+ (:player/mil-planets player)
                         (:player/erg-planets player)
                         (:player/ore-planets player))
        target-id-str (str (:xt/id player))
        td-cls        "border-r border-game-border py-1 px-3 text-game-green-soft"
        td-right-cls  "border-r border-game-border py-1 px-3 text-game-green-soft text-right"]
    [:tr.bg-game-row.border-b.border-game-divider
     [:td {:class td-cls}       (:player/empire-name player)]
     [:td {:class td-right-cls} total-planets]
     [:td {:class td-right-cls} (:player/score player)]
     [:td.py-1.px-3 (op-radio "spy"    target-id-str attacker-id-str "Spy")]
     [:td.py-1.px-3 (op-radio "incite" target-id-str attacker-id-str "Incite")]
     [:td.py-1.px-3 (op-radio "bomb"   target-id-str attacker-id-str "Bomb")]
     [:td.py-1.px-3 (op-radio "defect" target-id-str attacker-id-str "Defect")]]))

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
      (let [action    (get params :espionage-action)
            match     (when (seq action) (re-matches #"(spy|incite|bomb|defect):(.*)" action))
            op        (get match 1)
            id-str    (get match 2)
            target-id (when id-str (java.util.UUID/fromString id-str))]
        (biff/submit-tx ctx [{:db/doc-type                  :player
                              :db/op                        :update
                              :xt/id                        player-id
                              :player/current-phase         6
                              :player/pending-espionage     target-id
                              :player/pending-espionage-op  op}])
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
        other-players (utils/get-other-players db (:player/game player) player-id)
        th-cls        "text-green-400 text-[11px] tracking-[0.08em] uppercase"]
    (ui/phase-shell player game "ESPIONAGE PHASE"
      (biff/form
       {:action (str "/app/game/" player-id "/apply-espionage")
        :method "post"
        :class  "m-0"}
       (ui/phase-body player
        (ui/snapshot-section player)
        (cond
          (zero? (:player/agents player))
          [:p.text-sm.text-yellow-400
           "\u26a0 You have no agents. You cannot undertake covert operations this turn."]

          (empty? other-players)
          [:p.text-sm.text-game-green-soft "There are no other empires to target."]

          :else
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
                (target-row target attacker-id))]]]]))
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
