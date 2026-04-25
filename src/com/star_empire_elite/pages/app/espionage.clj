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
        player-id-str (str (:xt/id player))]
    [:tr.border-b.border-green-400
     [:td.border-r.border-green-400.px-3.py-2.w-56 (:player/empire-name player)]
     [:td.border-r.border-green-400.px-3.py-2.text-right.w-36 total-planets]
     [:td.border-r.border-green-400.px-3.py-2.text-right.w-36 (:player/score player)]
     [:td.border-r.border-green-400.px-3.py-2.w-24 (op-radio "spy"    player-id-str "Spy")]
     [:td.border-r.border-green-400.px-3.py-2.w-24 (op-radio "incite" player-id-str "Incite")]
     [:td.px-3.py-2.w-24                           (op-radio "bomb"   player-id-str "Bomb")]]))

;;;;
;;;; Actions
;;;;

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
          "\u26a0 You have no agents. You cannot undertake covert operations this turn."]

         (empty? other-players)
         [:p.mb-6 "There are no other empires to target."]

         :else
         [:div.mb-6
          [:h2.text-xl.font-bold.mb-4 "Choose a Target and Operation"]
          [:p.text-sm.mb-4.text-green-400.text-opacity-75
           "Spy reveals the target's military. Incite reduces their stability. Bomb destroys units."]
          [:div.overflow-x-auto
           [:table.w-full.text-sm.border.border-green-400
            [:thead
             [:tr.border-b.border-green-400
              [:th.border-r.border-green-400.px-3.py-2.text-left.w-56 "Empire"]
              [:th.border-r.border-green-400.px-3.py-2.text-right.w-36 "Planets"]
              [:th.border-r.border-green-400.px-3.py-2.text-right.w-36 "Score"]
              [:th.border-green-400.px-3.py-2 {:colspan 3} "Operation"]]]
            [:tbody
             (for [target other-players]
               (target-row target))]]]])

       ;; Warning banner — shown by CSS when an operation is selected
       [:div.queued-warning.items-center
        [:p.text-yellow-400 "\u26a0 Operation queued for Outcomes phase."]]

       (ui/incoming-alert player)

       [:div.flex.gap-4
        [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:href (str "/app/game/" player-id)} "Pause"]
        [:button.cancel-target.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
         {:type    "button"
          :onclick "document.querySelectorAll('[name=espionage-action]').forEach(function(r){r.checked=false;r.dataset.was='false';});"}
         "Cancel Operation"]
        [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
         {:type "submit"}
         "Continue to Outcomes"]])])))
