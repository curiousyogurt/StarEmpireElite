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

(defn- op-radio
  "Render a radio input + styled label for one operation on one target.
  A hyperscript handler enables deselect, since radio buttons don't natively uncheck on re-click.

  [op string, player-id-str string, label string] -> hiccup"
  [op player-id-str label]
  [:label.block.cursor-pointer
   [:input.peer.sr-only
    {:type  "radio"
     :name  "espionage-action"
     :value (str op ":" player-id-str)
     :_     (str "on click"
                 " if my @data-was is 'true'"
                 " set my's checked to false"
                 " set my @data-was to 'false'"
                 " else"
                 " for r in <[name=espionage-action]> set r's @data-was to 'false' end"
                 " set my @data-was to 'true'"
                 " end")}]
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
        td-border     {:border-right "1px solid #253530" :padding "4px 12px" :color "#9adaaa"}
        td-right      (assoc td-border :text-align "right")]
    [:tr {:style {:border-bottom "1px solid #1a2820" :background "#121a18"}}
     [:td {:style td-border} (:player/empire-name player)]
     [:td {:style td-right}  total-planets]
     [:td {:style td-right}  (:player/score player)]
     [:td {:style td-border} (op-radio "spy"    player-id-str "Spy")]
     [:td {:style td-border} (op-radio "incite" player-id-str "Incite")]
     [:td {:style {:padding "4px 12px"}} (op-radio "bomb" player-id-str "Bomb")]]))

(defn apply-espionage
  "Parse the chosen operation and target, store them as pending espionage, and advance to outcomes.

  [ctx ring-ctx] -> ring-response (303 redirect to outcomes)"
  [{:keys [path-params params] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 5 player-id)]
      redirect
      (let [action    (get params :espionage-action)
            match     (when (seq action) (re-matches #"(spy|incite|bomb):(.*)" action))
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
        th-style      {:color "#4ade80" :font-size "11px" :letter-spacing "0.08em"
                       :text-transform "uppercase"}]
    (ui/page
     {}
     [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
      {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
               :border-radius "4px" :color "#4ade80"
               :font-family "'Courier New', monospace"}}
      (ui/scanline-overlay)
      (ui/phase-topbar player "ESPIONAGE PHASE")
      (biff/form
       {:action (str "/app/game/" player-id "/apply-espionage")
        :method "post"
        :style  {:margin 0}}
       ;; Body
       [:div.flex.flex-col.gap-2
        {:style {:padding "10px 14px"}}
        (ui/snapshot-section player)
        (cond
          (zero? (:player/agents player))
          [:p.text-sm {:style {:color "#facc15"}}
           "\u26a0 You have no agents. You cannot undertake covert operations this turn."]

          (empty? other-players)
          [:p.text-sm {:style {:color "#9adaaa"}} "There are no other empires to target."]

          :else
          [:div
           (ui/section-label "Choose a Target")
           [:p.text-xs.mb-2 {:style {:color "#7ab88a"}}
            "Spy reveals the target's military. Incite reduces their stability. Bomb covertly destroys units."]
           [:div.overflow-x-auto
            [:table.w-full.text-sm
             {:style {:border "1px solid #253530" :border-collapse "collapse"}}
             [:thead
              [:tr {:style {:background "#151f1a" :border-bottom "1px solid #253530"}}
               [:th.text-left.px-3.py-1  {:style th-style} "Empire"]
               [:th.text-right.px-3.py-1 {:style th-style} "Planets"]
               [:th.text-right.px-3.py-1 {:style th-style} "Score"]
               [:th.px-3.py-1 {:colspan 3 :style th-style} "Operations"]]]
             [:tbody
              (for [target other-players]
                (target-row target))]]]])
        ;; Warning banner — shown by CSS when an operation is selected
        [:div.queued-warning.items-center
         [:p.text-sm {:style {:color "#facc15"}} "\u26a0 Operation queued for Outcomes phase."]]
        (ui/incoming-alert player)]
       ;; Action bar
       [:div.flex.gap-2
        {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (ui/action-bar-button
         "on click for r in <[name=espionage-action]> set r's checked to false set r's @data-was to 'false' end"
         "Cancel Operation"
         {:class "cancel-target"})
        (ui/submit-button true "Continue to Outcomes")])])))
