;;;;;
;;;;; Alerts - Status-Line Chip and Fold-Out Panel
;;;;;
;;;;; Pure functions behind the status-line alert chip and its fold-out panel (see
;;;;; design_handoff_immediate_messages/). Turns a player's :player/incoming-* history fields
;;;;; into grouped, deduplicated, capped rows for display — the same events already shown, in
;;;;; detail, on the Outcomes phase page (the old in-body activity block this replaced
;;;;; rendered the same fields one row per raw message; this groups identical rows together).
;;;;;
;;;;; Public API: incoming-messages, group-messages, group-counts, summary, chip-state,
;;;;; unread-count, seen-groups
;;;;;

(ns com.star-empire-elite.alerts
  (:require [clojure.edn     :as edn]
            [clojure.string  :as str]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Category Order
;;;;

;;; Drives both the chip summary and the fold-out panel's sort — strikes/invasions/raids
;;; first (most urgent, since they cost planets or ships), then espionage, then diplomacy.
(def category-order {:action 0 :espionage 1 :diplomacy 2})

;;;;
;;;; Message Extraction
;;;;

(defn- attack-messages
  "Turn a player's pr-str'd incoming-attacks history into alert rows, including a second
  DIPLOMACY row for any attack that also broke an active peace treaty.

  [player player-map] -> [alert-map]"
  [player]
  (mapcat
    (fn [record-str]
      (let [r        (edn/read-string record-str)
            mode     (:mode r)
            attacker (:attacker-name r)
            verb     (case mode
                       :invade "invaded"
                       :raid   "raided"
                       :strike "launched a strike against"
                       "attacked")
            outcome  (when (not= mode :strike)
                       (if (:attacker-wins? r) " (SUCCESS)" " (FAILURE)"))]
        (cond-> [{:alert/category :action :alert/badge mode
                  :alert/sender attacker :alert/text (str verb " you" outcome)}]
          (:treaty-broken? r)
          (conj {:alert/category :diplomacy :alert/badge :treaty-broken
                 :alert/sender attacker :alert/text "broke a peace treaty with you."}))))
    (utils/take-recent (:player/incoming-attacks player))))

(defn- espionage-fail-messages
  "[player player-map] -> [alert-map]"
  [player]
  (for [op (utils/take-recent (:player/incoming-espionage-fails player))]
    (let [verb (case op
                 "spy"    "spied on"
                 "incite" "incited unrest in"
                 "bomb"   "bombed"
                 "defect" "turned agents from"
                 "attacked")]
      {:alert/category :espionage :alert/badge (keyword op)
       :alert/sender "An unknown agent" :alert/text (str verb " you (FAILED)")})))

(defn- bomb-message
  "nil when no bomb landed this turn. [player player-map] -> alert-map | nil"
  [player]
  (when-let [r (some-> (:player/incoming-bomb-result player) edn/read-string)]
    (let [total (+ (or (:soldiers r) 0) (or (:transports r) 0)
                   (or (:fighters r) 0) (or (:carriers r) 0))]
      {:alert/category :espionage :alert/badge :bomb :alert/sender "An unknown agent"
       :alert/text (str "bombed you " (if (pos? total) "(SUCCESS)" "(FAILURE)"))})))

(defn- incite-message
  "nil when no incite damage landed this turn. [player player-map] -> alert-map | nil"
  [player]
  (when (pos? (or (:player/incoming-incite-stability-lost player) 0))
    {:alert/category :espionage :alert/badge :incite :alert/sender "An unknown agent"
     :alert/text "incited unrest in your empire (SUCCESS)"}))

(defn- defect-message
  "nil when no agents defected this turn. [player player-map] -> alert-map | nil"
  [player]
  (when (pos? (or (:player/incoming-defect-agents-lost player) 0))
    {:alert/category :espionage :alert/badge :defect :alert/sender "An unknown agent"
     :alert/text "turned agents from you (SUCCESS)"}))

(defn- treaty-messages
  "Build one alert row per empire name in the given incoming-treaty-* field.

  [player player-map, field keyword, badge keyword, text str] -> [alert-map]"
  [player field badge text]
  (for [empire-name (utils/take-recent (get player field))]
    {:alert/category :diplomacy :alert/badge badge :alert/sender empire-name :alert/text text}))

(defn incoming-messages
  "Flatten a player's incoming-* history fields into alert rows, one per raw message —
  the same events shown in detail on the Outcomes phase page. Order: attacks (plus any
  treaty-broken rows), espionage (plus any treaty-broken-by-espionage rows), then the four
  treaty-lifecycle vectors.

  [player player-map] -> [alert-map]"
  [player]
  (vec
    (concat
      (attack-messages player)
      (keep identity [(bomb-message player) (incite-message player) (defect-message player)])
      (espionage-fail-messages player)
      (treaty-messages player :player/incoming-espionage-treaty-broken :treaty-broken
                        "broke a peace treaty with you (espionage).")
      (treaty-messages player :player/incoming-treaty-proposed  :treaty-formed
                        "proposed a peace treaty with you.")
      (treaty-messages player :player/incoming-treaty-accepted  :treaty-formed
                        "accepted your peace treaty proposal.")
      (treaty-messages player :player/incoming-treaty-withdrawn :treaty-dissolved
                        "withdrew their peace treaty proposal.")
      (treaty-messages player :player/incoming-treaty-dissolved :treaty-dissolved
                        "dissolved your peace treaty."))))

;;;;
;;;; Grouping, Summary, Chip State
;;;;

(defn- group-key
  "The (category, sender, text) identity two messages must share to collapse into one row.

  [msg alert-map] -> vector"
  [msg]
  ((juxt :alert/category :alert/sender :alert/text) msg))

(defn group-messages
  "Dedupe msgs by (category, sender, text), sort by category order then by the arrival index
  of the group's first message, and mark each group :alert/new? when more copies have arrived
  than were present in seen-groups (a snapshot taken when the panel was last closed).

  [msgs [alert-map], seen-groups {group-key int} | nil] -> [alert-map with :alert/count, :alert/new?]"
  [msgs seen-groups]
  (let [seen-groups (or seen-groups {})
        indexed     (map-indexed (fn [i m] (assoc m :alert/index i)) msgs)]
    (->> indexed
         (group-by group-key)
         (map (fn [[k group]]
                (let [first-msg (apply min-key :alert/index group)
                      n         (count group)]
                  (assoc first-msg :alert/count n :alert/new? (> n (get seen-groups k 0))))))
         (sort-by (juxt (comp category-order :alert/category) :alert/index)))))

(defn group-counts
  "Reduce msgs to a snapshot map of {group-key count}, for persisting as
  :player/incoming-seen-groups when the panel closes.

  [msgs [alert-map]] -> {group-key int}"
  [msgs]
  (into {} (map (fn [[k group]] [k (count group)])) (group-by group-key msgs)))

(defn seen-groups
  "Parse the player's snapshotted seen-groups map (nil-safe).

  [player player-map] -> {group-key int}"
  [player]
  (some-> (:player/incoming-seen-groups player) edn/read-string))

(defn summary
  "Render the chip's summary string: message counts by category, in category order, with
  zero categories omitted, joined by ' · '. E.g. \"10 attacks · 4 espionage · 2 diplomacy\"
  (singular \"1 attack\" for a single ACTION message).

  [msgs [alert-map]] -> str"
  [msgs]
  (let [counts (frequencies (map :alert/category msgs))]
    (->> [:action :espionage :diplomacy]
         (keep (fn [cat]
                 (when-let [n (get counts cat)]
                   (case cat
                     :action    (str n (if (= n 1) " attack" " attacks"))
                     :espionage (str n " espionage")
                     :diplomacy (str n " diplomacy")))))
         (str/join " · "))))

(defn chip-state
  "Return the chip's visual state, per the handoff's state table: empty (no messages ever),
  open (panel open), unread (unseen messages exist), or read (all seen, panel closed).

  [total int, unread int, open? bool] -> :empty | :unread | :read | :open"
  [total unread open?]
  (cond
    (zero? total) :empty
    open?         :open
    (pos? unread) :unread
    :else         :read))

(defn unread-count
  "Number of incoming messages that have arrived since the panel was last closed. Reads the
  monotonic :player/incoming-seq counter rather than counting the capped incoming-* vectors,
  which lose count once a vector hits its cap — see utils/next-incoming-seq.

  [player player-map] -> int"
  [player]
  (max 0 (- (or (:player/incoming-seq player) 0) (or (:player/incoming-seen-seq player) 0))))
