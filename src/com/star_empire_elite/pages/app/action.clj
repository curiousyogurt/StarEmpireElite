;;;;;
;;;;; Action Phase - Combat Target Selection
;;;;;
;;;;; The action phase is the fourth phase of each turn where players choose an empire to attack.
;;;;; Selecting a target queues the attack; no combat is resolved here. The actual battle is
;;;;; computed when the player loads the outcomes page (phase 6), where both sides take casualties
;;;;; and any captured planets are transferred. Players can also skip combat entirely by submitting
;;;;; without choosing a target.
;;;;;

;;;;; Logical Structure:
;;;;;
;;;;; 1)  action-page ← combat/effective-forces, utils/get-other-players
;;;;;     └─ ui/phase-shell
;;;;;        ├─ biff/form
;;;;;        │  ├─ ui/phase-body
;;;;;        │  │  ├─ ui/snapshot-section
;;;;;        │  │  ├─ forces-grid
;;;;;        │  │  │  └─ forces-card  (×2: Ground, Fleet)
;;;;;        │  │  │     └─ forces-row  (per force stat)
;;;;;        │  │  ├─ ui/section-label "Choose a Target"
;;;;;        │  │  └─ target-row  (per other player)
;;;;;        │  └─ ui/phase-warning
;;;;;        └─ ui/phase-action-bar
;;;;;           ├─ ui/action-bar-link "Pause"
;;;;;           ├─ ui/action-bar-button "Cancel Attack"
;;;;;           └─ ui/submit-button "Continue to Espionage"
;;;;;
;;;;; 2)  update-action-warning  (HTMX OOB handler — toggles attack-queued warning)
;;;;;
;;;;; 3)  apply-action ← parse target-action param

(ns com.star-empire-elite.pages.app.action
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.combat :as combat]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; UI Components
;;;; - Forces Grid
;;;; - Target Row
;;;;

;;;
;;; The Forces Grid shows deployable Ground and Fleet strength in two cards.
;;; Each card lists total units and deployable count (with tooltip when capped).
;;;
;;; Forces Grid:
;;; - forces-row:  one pill for a force stat (total, deployable, or limit)
;;; - forces-card: one card with title and force rows
;;; - forces-grid: two cards (Ground, Fleet) in a 2-column grid
;;;

(defn- forces-row
  "Render one forces pill row.

  [label str, value int-or-str, & [{:keys [warn? display title]}]] -> hiccup"
  [label value & [{:keys [warn? display title]}]]
  (let [pill (ui/info-pill (cond-> {:key label :label label}
                             display (assoc :display display)
                             (not display) (assoc :value value)
                             warn? (assoc :warn? true)))]
    (if title
      (-> pill
          (assoc-in [1 :title] title)
          (update-in [1 :class] #(str % " cursor-help")))
      pill)))

(defn- forces-card
  "Render one forces card with title and pill rows.

  [title str, & body hiccup] -> hiccup"
  [title & body]
  [:div
   {:class "flex flex-col gap-1 py-1.5 px-2 border border-game-border rounded-game bg-game-card"}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 title]]
   [:div {:class "flex flex-col gap-0.5"}
    body]])

(defn- forces-grid
  "Render Ground and Fleet deployable-forces cards in a 2-column grid.

  [player player-map] -> hiccup"
  [player]
  (let [ef             (combat/effective-forces player)
        agents         (get player :player/agents     0)
        generals       (get player :player/generals   0)
        transports     (get player :player/transports 0)
        soldiers-total (get player :player/soldiers   0)
        soldiers-avail (:soldiers ef)
        cmd-ships      (get player :player/cmd-ships  0)
        admirals       (get player :player/admirals   0)
        carriers       (get player :player/carriers   0)
        fighters-total (get player :player/fighters   0)
        fighters-avail (:fighters ef)]
    [:div {:class "grid grid-cols-2 gap-1.5"}
     (forces-card "Ground"
                  (forces-row "Agents"     agents)
                  (forces-row "Generals"   generals)
                  (forces-row "Transports" transports)
                  (forces-row "Soldiers"   soldiers-total)
                  (if (= soldiers-avail soldiers-total)
                    (forces-row "Deployable" nil {:display "All"})
                    (forces-row "ⓘ Deployable" soldiers-avail
                                {:warn? true
                                 :title "Full deployment requires 100:1 soldiers-to-transports and 1000:1 soldiers-to-generals"}))
)
     (forces-card "Fleet"
                  (forces-row "Cmd Ships"  cmd-ships)
                  (forces-row "Admirals"   admirals)
                  (forces-row "Carriers"   carriers)
                  (forces-row "Fighters"   fighters-total)
                  (if (= fighters-avail fighters-total)
                    (forces-row "Deployable" nil {:display "All"})
                    (forces-row "ⓘ Deployable" fighters-avail
                                {:warn? true
                                 :title "Full deployment requires 100:1 fighters-to-carriers and 1000:1 fighters-to-admirals"})))]))

;;;
;;; Target rows populate the targets table, one row per other player in the game.
;;; Each row shows empire name, planet count, score, and three radio-button attack
;;; modes (Invade, Raid, Strike).  Invade requires deployable soldiers, deployable
;;; fighters, and command ships.  Raid requires deployable soldiers and deployable
;;; fighters.  Strike requires command ships.
;;;
;;; - target-row: one table row with three radio-button attack modes
;;;

(defn- target-row
  "Render a single row in the targets table with Invade, Raid, and Strike selectors.
  Each row produces three radio inputs sharing the same group name so only one
  selection across the whole table can be made.
  Composite radio values 'player-id:mode' are parsed in apply-action.
  Invade requires deployable soldiers, deployable fighters, and command ships.
  Raid requires deployable soldiers and deployable fighters.
  Strike requires command ships.
  attacker-id-str is the current player's id, used for the HTMX warning endpoint.

  [player player-map, ef {:soldiers int :fighters int}, cmd-ships int, attacker-id-str string] -> hiccup"
  [player ef cmd-ships attacker-id-str]
  (let [target-id-str (str (:xt/id player))
        radio-name    "target-action"
        warning-ep    (str "/app/game/" attacker-id-str "/action-warning")
        warning-id    "action-warning"
        op-btn        (fn [mode label]
                        (ui/op-radio-btn radio-name
                          (str target-id-str ":" (name mode))
                          label warning-ep warning-id))
        disabled-btn  (fn [label]
                        (ui/disabled-radio-btn radio-name label))
        has-soldiers? (pos? (:soldiers ef))
        has-fighters? (pos? (:fighters ef))
        has-cmd?      (pos? cmd-ships)
        can-attack?   (and has-soldiers? has-fighters?)]
    (into [:tr.bg-game-row.border-b.border-game-divider]
      (concat
        (ui/target-info-tds player)
        [[:td.py-1.px-2 (if can-attack? (op-btn :invade "Invade") (disabled-btn "Invade"))]
         [:td.py-1.px-2 (if can-attack? (op-btn :raid   "Raid")   (disabled-btn "Raid"))]
         [:td.py-1.px-2 (if has-cmd?    (op-btn :strike "Strike") (disabled-btn "Strike"))]]))))

;;;;
;;;; Actions
;;;;

(defn update-action-warning
  "Return a phase-warning-div for direct outerHTML swap.
  When a target is selected, shows which action is queued.
  When deselected, restores the disabled-reason hint if present.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params]}]
  (let [target-action  (:target-action params)
        disabled-hint  (:action-disabled-hint params)
        mode           (when (and target-action (str/includes? target-action ":"))
                         (second (str/split target-action #":" 2)))
        mode-label     (case mode
                         "invade" "Invade"
                         "raid"   "Raid"
                         "strike" "Strike"
                         nil)]
    (biff/render
      (ui/phase-warning-div "action-warning"
        (if mode-label
          (str "\u24d8 " mode-label " action queued for Outcomes phase.")
          disabled-hint)
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
                              :player/pending-attack-mode mode-kw
                              :player/score               (utils/calculate-score player)}])
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
        ef             (combat/effective-forces player)
        other-players  (utils/get-other-players db (:player/game player) player-id)
        has-soldiers?  (pos? (:soldiers ef))
        has-fighters?  (pos? (:fighters ef))
        has-cmd?       (pos? (get player :player/cmd-ships 0))
        can-raid?      (and has-soldiers? has-fighters?)
        can-strike?    has-cmd?
        disabled-hints (when (seq other-players)
                         (cond-> []
                           (not can-raid?)   (conj "Invade and Raid actions require deployable soldiers and fighters.")
                           (not can-strike?) (conj "Strike action requires a command ship.")))
        disabled-hint  (when (seq disabled-hints) (str/join " " disabled-hints))
        th-cls         "text-green-400 text-[11px] tracking-[0.08em] uppercase"]
    (ui/phase-shell 
      player 
      game 
      "Action Phase"
      (biff/form
       {:action (str "/app/game/" player-id "/apply-action")
        :method "post"
        :class  "m-0"}
       (ui/phase-body player
        (ui/snapshot-section player)
        (forces-grid player)
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
                (target-row target ef (:player/cmd-ships player) attacker-id))]]]]))
       (when disabled-hint
         [:input {:type "hidden" :name "action-disabled-hint" :value disabled-hint}])
       (ui/phase-warning-div "action-warning" disabled-hint {:color "text-yellow-400"})
       (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (ui/action-bar-button "Cancel Attack"
          {:class      "cancel-target"
           :hx-post    (str "/app/game/" player-id "/action-warning")
           :hx-target  "#action-warning"
           :hx-swap    "outerHTML"
           :hx-params  "none"
           :hx-include "[name=action-disabled-hint]"
           :onclick    "document.querySelectorAll('[name=target-action]').forEach(function(r){r.checked=false;r.dataset.was='false';});"})
        (ui/submit-button true "Continue to Espionage"))))))
