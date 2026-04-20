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
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Data Fetching
;;;;

(defn get-other-players
  "Fetch all other players in the same game, sorted by score descending.

  [db xtdb-db, game-id uuid, current-player-id uuid] -> seq of player maps"
  [db game-id current-player-id]
  (let [players (filter #(not= (:player/status %) const/player-status-eliminated)
                        (q db '{:find (pull player [*])
                                :in [game-id current-player-id]
                                :where [[player :player/game game-id]
                                        [(not= player current-player-id)]]}
                             game-id current-player-id))]
    (sort-by :player/score > (seq players))))

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
        player-id-str (str (:xt/id player))]
    [:tr.border-b.border-green-400
     [:td.border-r.border-green-400.px-3.py-2 (:player/empire-name player)]
     [:td.border-r.border-green-400.px-3.py-2.text-right total-planets]
     [:td.border-r.border-green-400.px-3.py-2.text-right (:player/score player)]
     [:td.px-3.py-2
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
  (let [player-id (:xt/id player)
        other-players (get-other-players db (:player/game player) player-id)]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

      (ui/phase-header (:player/current-phase player) "ACTION"
                       (str "Turn " (:player/current-turn player) " | Round " (:player/current-round player)))

      (biff/form
       {:action (str "/app/game/" player-id "/apply-action")
        :method "post"}

       (if (empty? other-players)
         [:p.mb-6 "There are no other empires in the galaxy to attack."]
         [:div.mb-6
          [:h2.text-xl.font-bold.mb-4 "Choose a Target"]
          [:p.text-sm.mb-4.text-green-400.text-opacity-75
           "Select an empire to attack. Your attack will be resolved in the Outcomes phase."]
          [:div.overflow-x-auto
           [:table.w-full.text-sm.border.border-green-400
            [:thead
             [:tr.border-b.border-green-400
              [:th.border-r.border-green-400.px-3.py-2.text-left.w-56 "Empire"]
              [:th.border-r.border-green-400.px-3.py-2.text-right.w-36 "Planets"]
              [:th.border-r.border-green-400.px-3.py-2.text-right.w-36 "Score"]
              [:th.px-3.py-2 ""]]]
            [:tbody
             (for [target other-players]
               (target-row target))]]]])

       ;; Warning banner — shown by CSS when a target radio is selected
       [:div.queued-warning.items-center
        [:p.text-yellow-400 "\u26a0 Attack queued for Outcomes phase."]]

       (ui/incoming-alert player)

       [:div.flex.gap-4
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" player-id)} "Pause"]
        [:button.cancel-target.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:type "button"
          :onclick "document.querySelectorAll('[name=target-player-id]').forEach(function(r){r.checked=false;r.dataset.was='false';});"}
         "Cancel Attack"]
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         "Continue to Espionage"]])])))
