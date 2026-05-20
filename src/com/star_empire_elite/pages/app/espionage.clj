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

  [op string, player-id-str string, label string] -> hiccup"
  [op player-id-str label]
  [:label.block.cursor-pointer
   [:input.peer.sr-only
    {:type    "radio"
     :name    "espionage-action"
     :value   (str op ":" player-id-str)
     :onclick op-js}]
   [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border.transition-colors
    {:class "text-green-400 border-green-400 hover:text-yellow-400 hover:border-yellow-400 peer-checked:text-yellow-400 peer-checked:border-yellow-400 peer-checked:bg-yellow-400 peer-checked:bg-opacity-10"}
    label]])

(defn target-row
  "Render a single row in the targets table with Spy and Incite radio buttons.

  [player player-map] -> hiccup"
  [player]
  (let [total-planets (+ (:player/mil-planets player)
                         (:player/erg-planets player)
                         (:player/ore-planets player))
        player-id-str (str (:xt/id player))
        td-cls        "border-r border-game-border py-1 px-3 text-game-green-soft"
        td-right-cls  "border-r border-game-border py-1 px-3 text-game-green-soft text-right"]
    [:tr.bg-game-row.border-b.border-game-divider
     [:td {:class td-cls}       (:player/empire-name player)]
     [:td {:class td-right-cls} total-planets]
     [:td {:class td-right-cls} (:player/score player)]
     [:td.py-1.px-3 (op-radio "spy"    player-id-str "Spy")]
     [:td.py-1.px-3 (op-radio "incite" player-id-str "Incite")]
     [:td.py-1.px-3 (op-radio "bomb"   player-id-str "Bomb")]
     [:td.py-1.px-3 (op-radio "defect" player-id-str "Defect")]]))

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
        other-players (utils/get-other-players db (:player/game player) player-id)
        th-cls        "text-green-400 text-[11px] tracking-[0.08em] uppercase"]
    (ui/page
     {}
     [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
      {:class "border-[1.5px] border-game-green-border rounded bg-game-bg text-green-400 font-mono"}
      (ui/scanline-overlay)
      (ui/phase-topbar player game "ESPIONAGE PHASE")
      (biff/form
       {:action (str "/app/game/" player-id "/apply-espionage")
        :method "post"
        :class  "m-0"}
       ;; Body
       [:div.flex.flex-col.gap-2
        {:class "py-2.5 px-3.5"}
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
            "Spy reveals the target's military. Incite reduces their stability. Bomb covertly destroys units. Defect flips a fraction of their agents to your side."]
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
                (target-row target))]]]])
        ;; Warning banner — shown by CSS when an operation is selected
        [:div.queued-warning.items-center
         [:p.text-sm.text-yellow-400 "\u26a0 Operation queued for Outcomes phase."]]
        (ui/incoming-alert player)]
       ;; Action bar
       [:div.flex.gap-2
        {:class "py-2 px-3.5 border-t border-game-border"}
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (ui/action-bar-button "Cancel Operation"
          {:class   "cancel-target"
           :onclick "document.querySelectorAll('[name=espionage-action]').forEach(function(r){r.checked=false;r.dataset.was='false';});"})
        (ui/submit-button true "Continue to Outcomes")])])))
