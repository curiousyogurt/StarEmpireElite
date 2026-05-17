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
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.combat :as combat]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; UI Components
;;;;

(defn target-row
  "Render a single row in the targets table with Invade, Raid, and Strike selectors.
  Each row produces three radio inputs sharing the same group name so only one
  selection across the whole table can be made.
  Composite radio values 'player-id:mode' are parsed in apply-action.
  attacker-cmd-ships is used to disable the Strike button when the attacker has none.

  [player player-map, attacker-cmd-ships int] -> hiccup"
  [player attacker-cmd-ships]
  (let [total-planets (+ (:player/mil-planets player)
                         (:player/erg-planets player)
                         (:player/ore-planets player))
        player-id-str (str (:xt/id player))
        td-border     {:border-right "1px solid #253530" :padding "4px 12px" :color "#9adaaa"}
        td-right      (assoc td-border :text-align "right")
        action-btn    (fn [mode label]
                        [:label.block.cursor-pointer
                         [:input.peer.sr-only
                          {:type "radio"
                           :name "target-action"
                           :value (str player-id-str ":" (name mode))
                           :onclick (str "var p=this.dataset.was==='true';"
                                         "document.querySelectorAll('[name=target-action]').forEach(function(r){r.dataset.was='false';});"
                                         "if(p){this.checked=false;}else{this.dataset.was='true';}")}]
                         [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border.transition-colors
                          {:class "text-green-400 border-green-400 hover:text-yellow-400 hover:border-yellow-400 peer-checked:text-yellow-400 peer-checked:border-yellow-400 peer-checked:bg-yellow-400 peer-checked:bg-opacity-10"}
                          label]])
        can-strike?   (pos? attacker-cmd-ships)
        strike-btn    (if can-strike?
                        (action-btn :strike "Strike")
                        [:label
                         [:input.sr-only {:type "radio" :name "target-action" :disabled true}]
                         [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border
                          {:style {:color "#3a5a48" :border-color "#253530" :cursor "not-allowed"}}
                          "Strike"]])]
    [:tr {:style {:border-bottom "1px solid #1a2820" :background "#121a18"}}
     [:td {:style td-border} (:player/empire-name player)]
     [:td {:style td-right}  total-planets]
     [:td {:style td-right}  (:player/score player)]
     [:td {:style {:padding "4px 8px"}} (action-btn :invade "Invade")]
     [:td {:style {:padding "4px 8px"}} (action-btn :raid   "Raid")]
     [:td {:style {:padding "4px 8px"}} strike-btn]]))

;;;;
;;;; Actions
;;;;

(defn apply-action
  "Store the pending attack target and mode (nil if none chosen) and advance to espionage phase.

  [ctx ring-ctx] -> ring-response (303 redirect to espionage)"
  [{:keys [path-params params] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 4 player-id)]
      redirect
      (let [target-action (:target-action params)
            [target-id mode-str] (when (and target-action (str/includes? target-action ":"))
                                   (str/split target-action #":" 2))
            target-uuid   (when target-id (parse-uuid target-id))
            mode-kw       (when mode-str (keyword mode-str))]
        (biff/submit-tx ctx [{:db/doc-type               :player
                              :db/op                     :update
                              :xt/id                     player-id
                              :player/current-phase      5
                              :player/pending-attack      target-uuid
                              :player/pending-attack-mode mode-kw}])
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
      (ui/phase-topbar player game "ACTION PHASE")
      (biff/form
       {:action (str "/app/game/" player-id "/apply-action")
        :method "post"
        :style  {:margin 0}}
       ;; Body
       [:div.flex.flex-col.gap-2
        {:style {:padding "10px 14px"}}
        (ui/snapshot-section player)
        ;; Army / Fleet readiness pills
        (let [ef              (combat/effective-forces player)
              soldiers-avail  (:soldiers ef)
              fighters-avail  (:fighters ef)
              soldiers-total  (get player :player/soldiers 0)
              fighters-total  (get player :player/fighters 0)
              trans-cap       (* (get player :player/transports 0) const/soldiers-per-transport)
              gen-cap         (* (get player :player/generals   0) const/soldiers-per-general)
              carrier-cap     (* (get player :player/carriers   0) const/fighters-per-carrier)
              admiral-cap     (* (get player :player/admirals   0) const/fighters-per-admiral)
              army-limit      (when (< soldiers-avail soldiers-total)
                                (cond
                                  (and (= soldiers-avail trans-cap)
                                       (= soldiers-avail gen-cap)) "Trans/Gen"
                                  (= soldiers-avail trans-cap)     "Trans"
                                  :else                            "Gen"))
              fleet-limit     (when (< fighters-avail fighters-total)
                                (cond
                                  (and (= fighters-avail carrier-cap)
                                       (= fighters-avail admiral-cap)) "Carr/Adm"
                                  (= fighters-avail carrier-cap)       "Carr"
                                  :else                                "Adm"))]
          [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-1.5"}
           (ui/stat-pill "Ground"
             (cond-> [{:label "Soldiers"   :value soldiers-total}
                      {:label "Transports" :value (:player/transports player)}
                      {:label "Generals"   :value (:player/generals   player)}
                      {:label "Deployable" :value soldiers-avail
                       :highlight? (= soldiers-avail soldiers-total)
                       :warn?      (< soldiers-avail soldiers-total)}]
               army-limit (conj {:label "Limit" :display army-limit})))
           (ui/stat-pill "Fleet"
             (cond-> [{:label "Fighters"   :value fighters-total}
                      {:label "Carriers"   :value (:player/carriers player)}
                      {:label "Admirals"   :value (:player/admirals player)}
                      {:label "Deployable" :value fighters-avail
                       :highlight? (= fighters-avail fighters-total)
                       :warn?      (< fighters-avail fighters-total)}]
               fleet-limit (conj {:label "Limit" :display fleet-limit})))
           (ui/stat-pill "Operations"
             [{:label "Cmd Ships" :value (:player/cmd-ships player)}
              {:label "Agents"    :value (:player/agents    player)}])])
        (if (empty? other-players)
          [:p.text-sm {:style {:color "#9adaaa"}}
           "There are no other empires in the galaxy to attack."]
          [:div
           (ui/section-label "Choose a Target")
           [:p.text-xs.mb-2 {:style {:color "#7ab88a"}}
            "Invade or Raid to fight for planets. Strike dispatches cmd-ships to damage their forces — no planet capture."]
           [:div.overflow-x-auto
            [:table.w-full.text-sm
             {:style {:border "1px solid #253530" :border-collapse "collapse"}}
             [:thead
              [:tr {:style {:background "#151f1a" :border-bottom "1px solid #253530"}}
               [:th.text-left.px-3.py-1  {:style th-style} "Empire"]
               [:th.text-right.px-3.py-1 {:style th-style} "Planets"]
               [:th.text-right.px-3.py-1 {:style th-style} "Score"]
               [:th.px-3.py-1 {:style (assoc th-style :text-align "center") :col-span 3} "Operations"]]]
             [:tbody
              (for [target other-players]
                (target-row target (:player/cmd-ships player)))]]]])
        ;; Warning banner — shown by CSS when a target radio is selected
        [:div.queued-warning.items-center
         [:p.text-sm {:style {:color "#facc15"}} "\u26a0 Attack queued for Outcomes phase."]]
        (ui/incoming-alert player)]
       ;; Action bar
       [:div.flex.gap-2
        {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (ui/action-bar-button "Cancel Attack"
          {:class   "cancel-target"
           :onclick "document.querySelectorAll('[name=target-action]').forEach(function(r){r.checked=false;r.dataset.was='false';});"})
        (ui/submit-button true "Continue to Espionage")])])))
