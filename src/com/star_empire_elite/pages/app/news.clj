;;;;;
;;;;; News - Per-Game Public Event Feed
;;;;;
;;;;; Displays a chronological feed (newest-first) of game events visible to the current player.
;;;;; Visibility filtering: :public events are shown to all, :attacker-only to the attacker,
;;;;; :defender-only to the defender. Each event renders as a single-line entry with a badge,
;;;;; verb phrase, and empire names.
;;;;;

(ns com.star-empire-elite.pages.app.news
  (:require [clojure.edn :as edn]
            [com.biffweb :refer [q]]
            [com.star-empire-elite.ui :as ui])
  (:import (java.time LocalDate ZoneId)
           (java.time.temporal ChronoUnit)))

;;;;
;;;; Data Fetching
;;;;

(defn- visible-events
  "Fetch all game events visible to a player, newest-first.
  Visible means: :public, or :attacker-only where player is attacker,
  or :defender-only where player is defender.

  [db xtdb-db, game-id uuid, player-id uuid] -> [event-map]"
  [db game-id player-id]
  (let [all-events (q db '{:find  (pull evt [*])
                            :in    [game-id]
                            :where [[evt :event/game game-id]]}
                       game-id)
        visible?   (fn [evt]
                     (case (:event/visibility evt)
                       :public       true
                       :attacker-only (= (:event/attacker evt) player-id)
                       :defender-only (= (:event/defender evt) player-id)
                       false))]
    (->> (seq all-events)
         (filter visible?)
         (map #(assoc % :event/payload-data (edn/read-string (:event/payload %))))
         (sort-by :event/at #(compare %2 %1)))))

;;;;
;;;; Event Rendering — Badge + Verb Phrase
;;;;

(defn- event-badge [kind]
  (ui/mode-badge kind))

(defn- empire-span
  "Render an empire name in a highlighted span.

  [name str] -> hiccup"
  [name]
  [:span.text-green-300.font-bold name])

(defn- combat-summary
  "Verb phrase for invade/raid/strike events.

  [payload map, kind keyword, player-id uuid] -> hiccup-fragment"
  [payload kind player-id]
  (let [attacker?   (= (str player-id) (:attacker-id payload))
        defender?   (= (str player-id) (:defender-id payload))
        att-name    (:attacker-name payload)
        def-name    (:defender-name payload)
        won?        (:attacker-wins? payload)
        verb        (case kind
                      :invade (if won? "invaded" "attempted to invade")
                      :raid   (if won? "raided" "attempted to raid")
                      :strike "launched a missile strike against")
        outcome     (if won? " (SUCCESS)" " (FAILURE)")]
    (cond
      attacker? [:span "You " verb " " (empire-span def-name) outcome]
      defender? [:span (empire-span att-name) " " verb " you" outcome]
      :else     [:span (empire-span att-name) " " verb " " (empire-span def-name) outcome])))

(defn- espionage-summary
  "Verb phrase for spy/incite/bomb/defect events.

  [payload map, kind keyword, player-id uuid, visibility keyword] -> hiccup-fragment"
  [payload kind player-id visibility]
  (let [attacker?   (= :attacker-only visibility)
        def-name    (:defender-name payload)
        won?        (:attacker-wins? payload)
        verb        (case kind
                      :spy    (if won? "spied on"           "attempted to spy on")
                      :incite (if won? "incited unrest in"  "attempted to incite unrest in")
                      :bomb   (if won? "bombed"             "attempted to bomb")
                      :defect (if won? "turned agents from" "attempted to turn agents from"))
        result      (if won? " (SUCCESS)" " (FAILURE)")]
    (if attacker?
      [:span "You " verb " " (empire-span def-name) result]
      [:span "An unknown agent " verb " you " result])))

(defn- breakaway-summary
  "Verb phrase for breakaway events.

  Shows 'You' when the viewing player is the subject; empire name otherwise.

  [payload map, empire-name string, mine? boolean] -> hiccup-fragment"
  [payload empire-name mine?]
  (let [lost (+ (:ore-lost payload 0) (:erg-lost payload 0) (:mil-lost payload 0))
        subj (if mine? "You" (empire-span empire-name))]
    [:span subj " lost " (ui/format-number lost) " planets to breakaway"]))

(defn- elimination-summary
  "Verb phrase for elimination events.

  Shows 'You' when the viewing player is the subject; empire name otherwise.

  [empire-name string, mine? boolean] -> hiccup-fragment"
  [empire-name mine?]
  (let [subj (if mine? "You" (empire-span empire-name))]
    [:span subj " were eliminated"]))

(defn- fate-summary
  "Verb phrase for disaster/boon events.

  For single-target fates (drought, pirate raid, plague, bumper harvest, gold rush,
  foreign aid, migration wave) we show the one delta. For multi-target solar flare
  we sum the absolute losses across all targets.

  [payload map, empire-name string] -> hiccup-fragment"
  [payload empire-name]
  (let [name     empire-name
        label    (:label payload)
        polarity (:polarity payload)
        effect   (:effect payload)
        ;; Sum absolute deltas across all targets for a single display number.
        total    (reduce (fn [acc [_k v]] (+ acc (Math/abs (long v)))) 0 effect)
        ;; Pick the resource label from the first (and usually only) target key.
        first-key (ffirst effect)
        res-name  (case first-key
                    :player/food       "food"
                    :player/credits    "credits"
                    :player/population "population"
                    ;; Solar flare hits multiple unit types — label them collectively.
                    "units")
        ;; Population is stored in millions; use format-population so "4" → "4M".
        formatted (if (= first-key :player/population)
                    (ui/format-population total)
                    (ui/format-number total))]
    (if (= polarity :disaster)
      [:span {:class "text-red-400"}
       (empire-span name) " suffered a " label
       " — lost " formatted " " res-name]
      [:span {:class "text-green-400"}
       (empire-span name) " enjoyed a " label
       " — gained " formatted " " res-name])))

(defn- event-summary
  "Dispatch to the appropriate summary renderer for an event.

  [event map, player-id uuid] -> hiccup-fragment"
  [event player-id]
  (let [payload      (:event/payload-data event)
        kind         (:event/kind event)
        vis          (:event/visibility event)
        def-name     (:event/defender-name event)
        ;; Is the viewing player the subject (defender) of this event?
        mine?        (= player-id (:event/defender event))]
    (case kind
      (:invade :raid :strike) (combat-summary payload kind player-id)
      (:spy :incite :bomb :defect) (espionage-summary payload kind player-id vis)
      :breakaway   (breakaway-summary payload def-name mine?)
      :elimination (elimination-summary def-name mine?)
      (:disaster :boon) (fate-summary payload def-name)
      [:span "Unknown event"])))

;;;;
;;;; Event Visibility
;;;;

(defn- show-event?
  "Return false for events that carry no meaningful information (e.g. a fate
  event whose effect totals to zero because the player had nothing to lose/gain).

  [event event-map] -> boolean"
  [event]
  (let [kind    (:event/kind event)
        payload (:event/payload-data event)]
    (if (#{:disaster :boon} kind)
      (let [effect (:effect payload)]
        (pos? (reduce (fn [acc [_k v]] (+ acc (Math/abs (long v)))) 0 effect)))
      true)))

;;;;
;;;; Day Grouping
;;;;

(defn- event-local-date
  "Return the LocalDate (server timezone) for an event's :event/at inst.

  [evt event-map] -> LocalDate"
  [evt]
  (-> (:event/at evt)
      .toInstant
      (.atZone (ZoneId/systemDefault))
      .toLocalDate))

(defn- day-label
  "Return a relative day label: Today, Yesterday, N Days Ago (up to 5), or nil for older.

  [event-date LocalDate, today LocalDate] -> str | nil"
  [event-date today]
  (let [days-ago (.between ChronoUnit/DAYS event-date today)]
    (cond
      (= days-ago 0) "Today"
      (= days-ago 1) "Yesterday"
      (<= days-ago 5) (str days-ago " Days Ago")
      :else nil)))

;;;;
;;;; Page
;;;;

(defn news-page
  "Render the news feed page for a player's game.

  [player player-map, game game-map, db xtdb-db] -> hiccup"
  [player game db]
  (let [player-id (:xt/id player)
        events    (visible-events db (:xt/id game) player-id)
        today     (LocalDate/now (ZoneId/systemDefault))
        ;; Group by local date, keeping only events within 5 days that have content to show.
        dated     (keep (fn [evt]
                          (when (show-event? evt)
                            (let [d (event-local-date evt)
                                  l (day-label d today)]
                              (when l [l evt]))))
                        events)
        grouped   (partition-by first dated)]
    (ui/phase-shell player game "NEWS"
      [:div.flex.flex-col.gap-2
       {:class "py-2.5 px-3.5"}
       (if (empty? dated)
         [:div.text-game-green-dim.text-sm.py-4.text-center "No recent events."]
         (for [group grouped
               :let [label (ffirst group)]]
           [:div
            (ui/section-label label)
            [:div.flex.flex-col.gap-1
             (for [[_ evt] group]
               [:div.flex.items-center.gap-2.text-sm
                {:class "py-[3px] px-2 bg-game-row rounded-sm"}
                ;; Fixed-width column so all badge labels (ACTION through ESPIONAGE)
                ;; align the description text to the same left edge.
                [:div {:class "w-[100px] flex-shrink-0"}
                 (event-badge (:event/kind evt))]
                [:span.text-game-green-soft.flex-1 (event-summary evt player-id)]])]]))]
      (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" (:xt/id player)) "Back to Game")))))
