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
  attacker-id-str is the current player's id, used for the HTMX warning endpoint.

  [player player-map, attacker-cmd-ships int, attacker-id-str string] -> hiccup"
  [player attacker-cmd-ships attacker-id-str]
  (let [total-planets (+ (:player/mil-planets player)
                         (:player/erg-planets player)
                         (:player/ore-planets player))
        target-id-str (str (:xt/id player))
        td-cls        "border-r border-game-border py-1 px-3 text-game-green-soft"
        td-right-cls  "border-r border-game-border py-1 px-3 text-game-green-soft text-right"
        action-btn    (fn [mode label]
                        [:label.block.cursor-pointer
                         [:input.peer.sr-only
                          {:type       "radio"
                           :name       "target-action"
                           :value      (str target-id-str ":" (name mode))
                           :hx-post    (str "/app/game/" attacker-id-str "/action-warning")
                           :hx-trigger "click"
                           :hx-target  "#action-warning"
                           :hx-swap    "outerHTML"
                           :onclick    (str "var p=this.dataset.was==='true';"
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

(defn update-action-warning
  "Return a phase-warning-div for direct outerHTML swap.
  Shows when target-action param is non-empty, clears otherwise.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params]}]
  (let [queued? (seq (:target-action params))]
    (biff/render
      (ui/phase-warning-div "action-warning"
        (when queued? "\u24d8 Attack queued for Outcomes phase.")
        {:color "text-yellow-400"}))))

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
  (let [player-id      (:xt/id player)
        attacker-id    (str player-id)
        other-players  (utils/get-other-players db (:player/game player) player-id)
        th-cls         "text-green-400 text-[11px] tracking-[0.08em] uppercase"
        ef             (combat/effective-forces player)
        soldiers-avail (:soldiers ef)
        fighters-avail (:fighters ef)
        soldiers-total (get player :player/soldiers 0)
        fighters-total (get player :player/fighters 0)
        trans-cap      (* (get player :player/transports 0) const/soldiers-per-transport)
        gen-cap        (* (get player :player/generals   0) const/soldiers-per-general)
        carrier-cap    (* (get player :player/carriers   0) const/fighters-per-carrier)
        admiral-cap    (* (get player :player/admirals   0) const/fighters-per-admiral)
        army-limit     (when (< soldiers-avail soldiers-total)
                         (cond
                           (and (= soldiers-avail trans-cap)
                                (= soldiers-avail gen-cap)) "Transports/Generals"
                           (= soldiers-avail trans-cap)     "Transports"
                           :else                            "Generals"))
        fleet-limit    (when (< fighters-avail fighters-total)
                         (cond
                           (and (= fighters-avail carrier-cap)
                                (= fighters-avail admiral-cap)) "Carriers/Admirals"
                           (= fighters-avail carrier-cap)       "Carriers"
                           :else                                "Admirals"))]
    (ui/phase-shell player game "ACTION PHASE"
      (biff/form
       {:action (str "/app/game/" player-id "/apply-action")
        :method "post"
        :class  "m-0"}
       (ui/phase-body player
        (ui/snapshot-section player)
        (ui/info-grid
          {:label      "Forces"
           :grid-class "grid grid-cols-2 gap-1.5"
           :cards
           [{:title "Ground"
             :rows  (cond-> [{:label "Soldiers"   :value soldiers-total}
                             {:label "Deployable" :value soldiers-avail
                              :warn? (< soldiers-avail soldiers-total)}]
                      army-limit (conj {:label "Limited by " :display army-limit}))}
            {:title "Fleet"
             :rows  (cond-> [{:label "Fighters"   :value fighters-total}
                             {:label "Deployable" :value fighters-avail
                              :warn? (< fighters-avail fighters-total)}]
                      fleet-limit (conj {:label "Limited by " :display fleet-limit}))}]})
        (if (empty? other-players)
          [:p.text-sm.text-game-green-soft
           "There are no other empires in the galaxy to attack."]
          [:div
           (ui/section-label "Choose a Target")
           [:p.text-xs.mb-2.text-game-green-muted
            "Invade: Target entire empire. Raid: Target outer planets. Strike: Missile strike from command ships."]
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
                (target-row target (:player/cmd-ships player) attacker-id))]]]]))
       (ui/phase-warning "action-warning")
       (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (ui/action-bar-button "Cancel Attack"
          {:class      "cancel-target"
           :hx-post    (str "/app/game/" player-id "/action-warning")
           :hx-target  "#action-warning"
           :hx-swap    "outerHTML"
           :hx-params  "none"
           :onclick    "document.querySelectorAll('[name=target-action]').forEach(function(r){r.checked=false;r.dataset.was='false';});"})
        (ui/submit-button true "Continue to Espionage"))))))
