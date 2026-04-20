;;;;;
;;;;; Espionage Phase - Infiltration Target Selection
;;;;;
;;;;; The espionage phase is the fifth phase of each turn where players choose an empire to
;;;;; spy on. Players with no agents are blocked from choosing a target. Like combat, the
;;;;; actual espionage roll is resolved when the player loads the outcomes page: a successful
;;;;; infiltration reveals the target's full military unit counts as intel; a failed attempt
;;;;; notifies the defender that their security detected an intrusion.
;;;;;

(ns com.star-empire-elite.pages.app.espionage
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
  "Render a single row in the targets table with a radio-button spy selector.

  [player player-map] -> hiccup"
  [player]
  (let [total-planets (+ (:player/mil-planets player)
                         (:player/erg-planets player)
                         (:player/ore-planets player))
        player-id-str (str (:xt/id player))]
    [:tr.border-b.border-green-400
     [:td.border-r.border-green-400.px-3.py-2.w-56 (:player/empire-name player)]
     [:td.border-r.border-green-400.px-3.py-2.text-right.w-36 total-planets]
     [:td.border-r.border-green-400.px-3.py-2.text-right.w-36 (:player/score player)]
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
        "Spy"]]]]))

;;;;
;;;; Actions
;;;;

(defn apply-espionage
  "Store the pending espionage target (nil if none chosen) and advance to outcomes phase.

  [ctx ring-ctx] -> ring-response (303 redirect to outcomes)"
  [{:keys [path-params params] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 5 player-id)]
      redirect
      (let [target-str (:target-player-id params)
            target-id  (when (and target-str (not (empty? target-str)))
                         (java.util.UUID/fromString target-str))]
        (biff/submit-tx ctx [{:db/doc-type              :player
                              :db/op                    :update
                              :xt/id                    player-id
                              :player/current-phase     6
                              :player/pending-espionage target-id}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/outcomes")}}))))

;;;;
;;;; Page
;;;;

(defn espionage-page
  "Show the espionage phase: choose a target empire to spy on, or skip espionage.

  [{:keys [player game db]}] -> hiccup"
  [{:keys [player game db]}]
  (let [player-id (:xt/id player)
        other-players (get-other-players db (:player/game player) player-id)]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]

      (ui/phase-header (:player/current-phase player) "ESPIONAGE"
                       (str "Turn " (:player/current-turn player) " | Round " (:player/current-round player)))

      (biff/form
       {:action (str "/app/game/" player-id "/apply-espionage")
        :method "post"}

       (cond
         (zero? (:player/agents player))
         [:p.text-yellow-400.mb-6
          "\u26a0 You have no agents. You cannot undertake espionage operations this turn."]

         (empty? other-players)
         [:p.mb-6 "There are no other empires to spy on."]

         :else
         [:div.mb-6
          [:h2.text-xl.font-bold.mb-4 "Choose a Target"]
          [:p.text-sm.mb-4.text-green-400.text-opacity-75
           "Select an empire to spy on. Espionage results will be revealed in the Outcomes phase."]
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
        [:p.text-yellow-400 "\u26a0 Infiltration queued for Outcomes phase."]]

       (ui/incoming-alert player)

       [:div.flex.gap-4
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" player-id)} "Pause"]
        [:button.cancel-target.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:type "button"
          :onclick "document.querySelectorAll('[name=target-player-id]').forEach(function(r){r.checked=false;r.dataset.was='false';});"}
         "Cancel Infiltration"]
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         "Continue to Outcomes"]])])))
