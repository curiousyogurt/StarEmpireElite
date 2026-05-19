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
        td-cls        "border-r border-game-border py-1 px-3 text-game-green-soft"
        td-right-cls  "border-r border-game-border py-1 px-3 text-game-green-soft text-right"
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
                         [:span.block.w-full.px-3.py-1.text-sm.font-bold.text-center.bg-black.border.text-game-green-dim.border-game-border.cursor-not-allowed
                          "Strike"]])]
    [:tr.bg-game-row.border-b.border-game-divider
     [:td {:class td-cls} (:player/empire-name player)]
     [:td {:class td-right-cls}  total-planets]
     [:td {:class td-right-cls}  (:player/score player)]
     [:td.py-1.px-2 (action-btn :invade "Invade")]
     [:td.py-1.px-2 (action-btn :raid   "Raid")]
     [:td.py-1.px-2 strike-btn]]))

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
        th-cls        "text-green-400 text-[11px] tracking-[0.08em] uppercase"]
    (ui/page
     {}
     [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
      {:class "border-[1.5px] border-game-green-border rounded bg-game-bg text-green-400 font-mono"}
      (ui/scanline-overlay)
      (ui/phase-topbar player game "ACTION PHASE")
      (biff/form
       {:action (str "/app/game/" player-id "/apply-action")
        :method "post"
        :class  "m-0"}
       ;; Body
       [:div.flex.flex-col.gap-2
        {:class "py-2.5 px-3.5"}
        (ui/snapshot-section player {:show-ground? false :show-fleet? false :show-ops? false})
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
                                       (= soldiers-avail gen-cap)) "Transports/Generals"
                                  (= soldiers-avail trans-cap)     "Transports"
                                  :else                            "Generals"))
              fleet-limit     (when (< fighters-avail fighters-total)
                                (cond
                                  (and (= fighters-avail carrier-cap)
                                       (= fighters-avail admiral-cap)) "Carriers/Admirals"
                                  (= fighters-avail carrier-cap)       "Carriers"
                                  :else                                "Admirals"))]
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
          [:p.text-sm.text-game-green-soft
           "There are no other empires in the galaxy to attack."]
          [:div
           (ui/section-label "Choose a Target")
           [:p.text-xs.mb-2.text-game-green-muted
            "Invade or Raid to fight for planets. Strike dispatches cmd-ships to damage their forces — no planet capture."]
           [:div.overflow-x-auto
            [:table.w-full.text-sm
             {:class "border border-game-border border-collapse"}
             [:thead
              [:tr.bg-game-header.border-b.border-game-border
               [:th.text-left.px-3.py-1  {:class th-cls} "Empire"]
               [:th.text-right.px-3.py-1 {:class th-cls} "Planets"]
               [:th.text-right.px-3.py-1 {:class th-cls} "Score"]
               [:th.px-3.py-1.text-center {:class th-cls :col-span 3} "Operations"]]]
             [:tbody
              (for [target other-players]
                (target-row target (:player/cmd-ships player)))]]]])
        ;; Warning banner — shown by CSS when a target radio is selected
        [:div.queued-warning.items-center
         [:p.text-sm.text-yellow-400 "\u26a0 Attack queued for Outcomes phase."]]
        (ui/incoming-alert player)]
       ;; Action bar
       [:div.flex.gap-2
        {:class "py-2 px-3.5 border-t border-game-border"}
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (ui/action-bar-button "Cancel Attack"
          {:class   "cancel-target"
           :onclick "document.querySelectorAll('[name=target-action]').forEach(function(r){r.checked=false;r.dataset.was='false';});"})
        (ui/submit-button true "Continue to Espionage")])])))
