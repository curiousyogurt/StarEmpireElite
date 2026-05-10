;;;;;
;;;;; Action Phase - Combat Target Selection
;;;;;
;;;;; The action phase is the fourth phase of each turn where players choose an empire to attack.
;;;;; Selecting a target queues the attack; no combat is resolved here. The actual battle is
;;;;; computed when the player loads the outcomes page (phase 6), where both sides take casualties
;;;;; and any captured planets are transferred. Players can also skip combat entirely by submitting
;;;;; without choosing a target.
;;;;;

(ns com.star-empire-elite.pages.app.action
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; UI Components
;;;;

(defn target-row
  "Render a single row in the targets table with a radio-button attack selector.
  The radio input is visually hidden; its label renders as the attack button.
  A small onclick handler enables deselect, since radio buttons don't natively uncheck on re-click.

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
     [:td {:style {:padding "4px 12px"}}
      [:label.block.cursor-pointer
       [:input.peer.sr-only
        {:type "radio"
         :name "target-player-id"
         :value player-id-str
         :onclick (str "var p=this.dataset.was==='true';"
                       "document.querySelectorAll('[name=target-player-id]').forEach(function(r){r.dataset.was='false';});"
                       "if(p){this.checked=false;}else{this.dataset.was='true';}")}]
       [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border.transition-colors
        {:class "text-green-400 border-green-400 hover:text-yellow-400 hover:border-yellow-400 peer-checked:text-yellow-400 peer-checked:border-yellow-400 peer-checked:bg-yellow-400 peer-checked:bg-opacity-10"}
        "Attack"]]]]))

;;;;
;;;; Actions
;;;;

(defn apply-action
  "Store the pending attack target (nil if none chosen) and advance to espionage phase.

  [ctx ring-ctx] -> ring-response (303 redirect to espionage)"
  [{:keys [path-params params] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 4 player-id)]
      redirect
      (let [target-str (:target-player-id params)
            target-id  (when (and target-str (not (empty? target-str)))
                         (java.util.UUID/fromString target-str))]
        (biff/submit-tx ctx [{:db/doc-type          :player
                              :db/op                :update
                              :xt/id                player-id
                              :player/current-phase 5
                              :player/pending-attack target-id}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/espionage")}}))))

;;;;
;;;; Page
;;;;

(defn action-page
  "Show the action phase: choose a target empire to attack, or skip combat.

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
      (ui/phase-topbar player "ACTION PHASE")
      (biff/form
       {:action (str "/app/game/" player-id "/apply-action")
        :method "post"
        :style  {:margin 0}}
       ;; Body
       [:div.flex.flex-col.gap-2
        {:style {:padding "10px 14px"}}
        (ui/snapshot-section player)
        (if (empty? other-players)
          [:p.text-sm {:style {:color "#9adaaa"}}
           "There are no other empires in the galaxy to attack."]
          [:div
           (ui/section-label "Choose a Target")
           [:p.text-xs.mb-2 {:style {:color "#7ab88a"}}
            "Select an empire to attack. Your attack will be resolved in the Outcomes phase."]
           [:div.overflow-x-auto
            [:table.w-full.text-sm
             {:style {:border "1px solid #253530" :border-collapse "collapse"}}
             [:thead
              [:tr {:style {:background "#151f1a" :border-bottom "1px solid #253530"}}
               [:th.text-left.px-3.py-1  {:style th-style} "Empire"]
               [:th.text-right.px-3.py-1 {:style th-style} "Planets"]
               [:th.text-right.px-3.py-1 {:style th-style} "Score"]
               [:th.px-3.py-1            {:style th-style} "Operation"]]]
             [:tbody
              (for [target other-players]
                (target-row target))]]]])
        ;; Warning banner — shown by CSS when a target radio is selected
        [:div.queued-warning.items-center
         [:p.text-sm {:style {:color "#facc15"}} "\u26a0 Attack queued for Outcomes phase."]]
        (ui/incoming-alert player)]
       ;; Action bar
       [:div.flex.gap-2
        {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        [:button.cancel-target.text-sm
         {:type    "button"
          :onclick "document.querySelectorAll('[name=target-player-id]').forEach(function(r){r.checked=false;r.dataset.was='false';});"
          :style   {:padding "5px 14px" :border "1px solid #1e6e44" :background "transparent"
                    :color "#9adaaa" :border-radius "2px" :letter-spacing "0.05em"
                    :font-family "'Courier New', monospace" :cursor "pointer"}}
         "Cancel Attack"]
        (ui/submit-button true "Continue to Espionage")])])))
