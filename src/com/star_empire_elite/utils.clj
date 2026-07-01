;;;;;
;;;;; Shared Utilities
;;;;;
;;;;; Common utility functions used across multiple game phases. This namespace contains reusable 
;;;;; helpers for input parsing, validation, and entity loading to reduce code duplication across 
;;;;; various pages.
;;;;;

(ns com.star-empire-elite.utils
  (:require [com.biffweb :refer [q]]
            [com.star-empire-elite.constants :as const]
            [xtdb.api :as xt]))

;;;;
;;;; Input Parsing
;;;;

;;; Parse user numeric input safely, treating empty/nil/negative as 0. This is used across all phases 
;;; that accept user input (expenses, exchange, building).
(def ^:private max-input-value
  "Maximum value accepted from user input. Caps at 1 billion to prevent long overflow when
  quantities are multiplied by per-unit costs in building and exchange calculations."
  1000000000)

(defn parse-numeric-input
  "Parse user input to a non-negative integer, stripping non-numeric chars and treating empty/nil as
  0. Caps at max-input-value to prevent long overflow on subsequent multiplication."
  [val]
  (let [s (str val)
        cleaned (clojure.string/replace s #"[^0-9]" "")
        trimmed (subs cleaned 0 (min (count cleaned) 10))]
    (if (empty? trimmed)
      0
      (min (parse-long trimmed) max-input-value))))

(defn parse-choice-value
  "Split a UI choice value of the form 'left:right'. Returns nil for blank or malformed values.

  [value string] -> [string string] | nil"
  [value]
  (when (and (string? value) (clojure.string/includes? value ":"))
    (clojure.string/split value #":" 2)))

;;;;
;;;; Flash Messages
;;;;

(defn flash
  "Attach a flash message to the response. The message persists in the session for exactly
  one subsequent request, then is cleared by take-flash on the next page render.

  Level is one of :info, :warn, :error (controls styling).

  Usage:
    {:status 303
     :headers {\"location\" \"/some-path\"}
     :session (flash session :warn \"Your submit was rejected.\")}

  [session ring-session-map, level keyword, message string] -> ring-session-map"
  [session level message]
  (assoc session :flash {:level level :message message}))

(defn take-flash
  "Return [flash-map updated-session] where flash-map is the current flash (or nil) and
  updated-session is the session with :flash removed. Pages call this once during render
  and merge the updated session back into their response.

  [session ring-session-map] -> [{:level keyword, :message string} | nil, ring-session-map]"
  [session]
  [(:flash session) (dissoc session :flash)])

;;;;
;;;; Phase Validation
;;;;

;;; Validates that a player is in the expected phase. Returns nil if valid, or a redirect response if 
;;; the player is in the wrong phase. This prevents players from accessing phase endpoints out of 
;;; order.
(defn validate-phase
  "Check if player is in expected phase. Returns nil if valid, redirect response if invalid."
  [player expected-phase player-id]
  (when (not= (:player/current-phase player) expected-phase)
    {:status 303
     :headers {"location" (str "/app/game/" player-id)}}))

;;;;
;;;; Round Cooldown
;;;;

(defn same-calendar-day?
  "Returns true if the given Date falls on today's UTC calendar day."
  [^java.util.Date d]
  (let [cal (doto (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))
              (.setTime (java.util.Date.))
              (.set java.util.Calendar/HOUR_OF_DAY 0)
              (.set java.util.Calendar/MINUTE 0)
              (.set java.util.Calendar/SECOND 0)
              (.set java.util.Calendar/MILLISECOND 0))]
    (>= (.getTime d) (.getTimeInMillis cal))))

(defn format-cooldown-duration
  "Format a millisecond duration as a human-readable string.
   Examples: 26280000 -> '7h 18m', 900000 -> '15m 0s', 42000 -> '42s'"
  [ms]
  (let [total-s (quot ms 1000)
        h       (quot total-s 3600)
        m       (quot (rem total-s 3600) 60)
        s       (rem total-s 60)]
    (cond
      (pos? h) (str h "h " m "m")
      (pos? m) (str m "m " s "s")
      :else    (str s "s"))))

;;;;
;;;; Round Status (Pure Core)
;;;;
;;;; All round/turn gating and display logic lives in one pure function, `round-status`,
;;;; which takes `now` explicitly so it can be tested without wall-clock dependencies.
;;;; The three public functions (display-turn-round, round-cooldown-ms, day-exhausted?)
;;;; are thin wrappers that pass (java.util.Date.) to this core.
;;;;

(defn utc-midnight
  "Return a Date for the start (00:00:00.000) of the UTC day containing `d`.

  [d Date] -> Date"
  ^java.util.Date [^java.util.Date d]
  (let [cal (doto (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))
              (.setTime d)
              (.set java.util.Calendar/HOUR_OF_DAY 0)
              (.set java.util.Calendar/MINUTE 0)
              (.set java.util.Calendar/SECOND 0)
              (.set java.util.Calendar/MILLISECOND 0))]
    (java.util.Date. (.getTimeInMillis cal))))

(defn- next-utc-midnight
  "Return a Date for the start of the next UTC day after `now`.

  [now Date] -> Date"
  ^java.util.Date [^java.util.Date now]
  (let [cal (doto (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))
              (.setTime now)
              (.set java.util.Calendar/HOUR_OF_DAY 0)
              (.set java.util.Calendar/MINUTE 0)
              (.set java.util.Calendar/SECOND 0)
              (.set java.util.Calendar/MILLISECOND 0)
              (.add java.util.Calendar/DAY_OF_MONTH 1))]
    (java.util.Date. (.getTimeInMillis cal))))

(defn- stale-day?
  "True if `last-turn-at` is non-nil and falls on an earlier UTC calendar day than `now`.
  Used to decide whether the day's round budget has rolled over.

  [last-turn-at Date|nil, now Date] -> bool"
  [^java.util.Date last-turn-at ^java.util.Date now]
  (and (some? last-turn-at)
       (< (.getTime last-turn-at) (.getTime (utc-midnight now)))))

(defn round-status
  "Compute the player's round/turn display and gating state from stored fields and wall clock.
  Pure function — all time-dependent logic is parameterised by `now`.

  Invariants enforced:
    I1  At most rounds-per-day round-starts per UTC day (budget).
    I2  A new round may start only when now >= last-turn-at + hours-between-rounds (spacing).
    I3  Once a round is started (turns-used > 0), every turn in it may be finished,
        even across UTC midnight (gentle spillover).

  The display derivation never reads the stored current-round; it is derived from
  effective-starts-today and the player's turns-used/current-turn, which prevents the
  R0 underflow and stranded-turns-used bugs.

  [rounds-started-today int, turns-used int, current-turn int, current-phase int,
   last-turn-at Date|nil, rounds-per-day int, turns-per-round int,
   hours-between-rounds number, now Date] -> status-map

  Returns:
    {:display-round  int       ; round number to show in the UI
     :display-turn   int       ; turn number to show in the UI
     :can-act?       bool      ; true if the player can take a turn right now
     :state          keyword   ; :active | :spacing | :budget-exhausted
     :unlock-at      Date|nil} ; when the block lifts (nil when :active)"
  [rounds-started-today turns-used current-turn current-phase
   last-turn-at rounds-per-day turns-per-round hours-between-rounds now]
  (let [;; Effective starts today: resets to 0 when last-turn-at is on a previous UTC day.
        ;; This is the budget counter — it tracks how many rounds the player has *started*
        ;; today. Stale means the day rolled over and the budget is fresh.
        e           (if (stale-day? last-turn-at now)
                      0
                      (or rounds-started-today 0))
        mid-round?  (pos? turns-used)
        poised?     (zero? turns-used)
        spacing-ms  (* hours-between-rounds 60 60 1000)
        ;; can-start? gates round-starts only (poised? = turns-used is 0).
        ;; Budget must have room AND the spacing cooldown must have elapsed.
        can-start?  (and poised?
                        (< e rounds-per-day)
                        (or (nil? last-turn-at)
                            (>= (.getTime now)
                                (+ (.getTime last-turn-at) spacing-ms))))
        ;; Mid-turn exemption: if the player has already committed to a turn
        ;; (current-phase > 1, e.g. they're on expenses/exchange/etc.) they
        ;; must be allowed to finish it regardless of gating.
        can-act?    (or mid-round? can-start? (> current-phase 1))

        ;; Display derivation — never reads stored current-round.
        display-turn  (cond
                        (or mid-round? can-start?) current-turn
                        (>= e 1)                   turns-per-round
                        :else                      1)
        display-round (cond
                        mid-round?  (max e 1)   ;; floor prevents R0 in spillover
                        can-start?  (inc e)     ;; about to start the next round
                        (>= e 1)    e           ;; blocked: stay on completed round
                        :else       1)          ;; cross-midnight locked

        ;; Unlock time: the later of the spacing gate and the budget gate.
        ;; midnight reset refreshes the budget but never clears the spacing cooldown.
        budget-gate (if (>= e rounds-per-day)
                      (.getTime (next-utc-midnight now))
                      0)
        spacing-gate (if last-turn-at
                       (+ (.getTime last-turn-at) spacing-ms)
                       0)
        unlock-ms   (max budget-gate spacing-gate)
        state       (cond
                      can-act?              :active
                      (>= e rounds-per-day) :budget-exhausted
                      :else                 :spacing)]
    {:display-round  display-round
     :display-turn   display-turn
     :can-act?       can-act?
     :state          state
     :unlock-at      (when-not can-act?
                       (java.util.Date. (long unlock-ms)))}))

(defn- player-round-status
  "Extract player/game fields and call round-status with the current wall clock.
  Shared helper for the three public gating/display functions.

  [player player-map, game game-map] -> status-map"
  [player game]
  (round-status (or (:player/rounds-started-today player) 0)
                (or (:player/turns-used player) 0)
                (or (:player/current-turn player) 1)
                (or (:player/current-phase player) 1)
                (:player/last-turn-at player)
                (or (:game/rounds-per-day game) 2)
                (or (:game/turns-per-round game) 6)
                (or (:game/hours-between-rounds game) 0)
                (java.util.Date.)))

;;;;
;;;; Round Gating & Display
;;;; Thin wrappers around round-status for the three public APIs.
;;;;

(defn day-exhausted?
  "Returns true if the player has used all rounds-per-day round-starts for today's UTC day.

  [player player-map, game game-map] -> bool"
  [player game]
  (= :budget-exhausted (:state (player-round-status player game))))

(defn round-cooldown-ms
  "Returns ms remaining in cooldown if the player is blocked from starting a new round,
  or nil if the player can act now. Two blocking conditions:
    1. Budget exhausted: all rounds-per-day started today → blocked until midnight UTC.
    2. Spacing: minimum hours-between-rounds since last turn.

  [player player-map, game game-map] -> long|nil"
  [player game]
  (let [s (player-round-status player game)]
    (when-not (:can-act? s)
      (let [remaining (- (.getTime (:unlock-at s)) (.getTime (java.util.Date.)))]
        (when (pos? remaining) remaining)))))

(defn display-turn-round
  "Return display values {:turn n :round n} for turn and round indicators.
  Derived from round-status — never reads the stored current-round, which prevents
  the R0 underflow and stranded-turns-used bugs.

  [player player-map, game game-map] -> {:turn int :round int}"
  [player game]
  (let [s (player-round-status player game)]
    {:turn  (:display-turn s)
     :round (:display-round s)}))

;;;;
;;;; Player Resource Snapshot
;;;;

;;; Strips the :player/ namespace prefix from a player entity and returns a plain resource map with
;;; all 18 standard resource/unit/planet keys. Used by the calculate-resources-after-* functions in
;;; the income, expenses, building, and exchange phases to eliminate repeated boilerplate.
(defn player-snapshot
  "Extract the standard resource map from a player entity, using unqualified keys.
  Returns a map with all 18 resource/unit/planet keys; values default to 0 when absent.

  [player player-map] -> resource-map"
  [player]
  {:credits     (get player :player/credits 0)
   :food        (get player :player/food 0)
   :fuel        (get player :player/fuel 0)
   :galaxars    (get player :player/galaxars 0)
   :population  (get player :player/population 0)
   :stability   (get player :player/stability 0)
   :soldiers    (get player :player/soldiers 0)
   :transports  (get player :player/transports 0)
   :generals    (get player :player/generals 0)
   :fighters    (get player :player/fighters 0)
   :carriers    (get player :player/carriers 0)
   :admirals    (get player :player/admirals 0)
   :stations    (get player :player/stations 0)
   :cmd-ships   (get player :player/cmd-ships 0)
   :agents      (get player :player/agents 0)
   :ore-planets (get player :player/ore-planets 0)
   :erg-planets (get player :player/erg-planets 0)
   :mil-planets (get player :player/mil-planets 0)})

(defn total-planets
  "Return a player's total planets across ore, energy, and military classes.

  [player player-map] -> int"
  [player]
  (+ (get player :player/ore-planets 0)
     (get player :player/erg-planets 0)
     (get player :player/mil-planets 0)))

(defn qualify-snapshot
  "Re-qualify an unqualified resource map (as returned by player-snapshot) back to
  :player/* namespaced keys, suitable for merging onto a player entity.

  [snapshot resource-map] -> player-like map"
  [snapshot]
  (reduce-kv #(assoc %1 (keyword "player" (name %2)) %3) {} snapshot))

(defn calculate-score
  "Compute player score: planets (dominant) + military units (power-weighted).

  [player player-map] -> int"
  [player]
  (+ (* (get player :player/population   0) const/score-population)
     (* (get player :player/mil-planets  0) const/score-mil-planet)
     (* (get player :player/erg-planets  0) const/score-erg-planet)
     (* (get player :player/ore-planets  0) const/score-ore-planet)
     (* (get player :player/soldiers     0) const/score-soldier)
     (* (get player :player/transports   0) const/score-transport)
     (* (get player :player/fighters     0) const/score-fighter)
     (* (get player :player/carriers     0) const/score-carrier)
     (* (get player :player/cmd-ships    0) const/score-cmd-ship)
     (* (get player :player/stations     0) const/score-station)
     (* (get player :player/generals     0) const/score-general)
     (* (get player :player/admirals     0) const/score-admiral)
     (* (get player :player/agents       0) const/score-agent)))

;;;;
;;;; Other-Player Fetching
;;;;

;;; Both the action phase (combat targeting) and the espionage phase (covert-op targeting) need the
;;; same query: all non-eliminated players in the same game, sorted by score. Centralised here to
;;; avoid duplication across those two page namespaces.
(defn get-other-players
  "Fetch all non-eliminated players in the same game as current-player-id, sorted by score
  descending.

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
;;;; Entity Loading
;;;;

;;; Loads player and game entities from database with proper error handling. This pattern is repeated 
;;; in every phase handler, so extracting it reduces boilerplate.
(defn load-player-and-game
  "Load player and game entities from database. Returns map with :player and :game,
  or error response if player not found."
  ;; Database connection and uuid as string for the player
  [db player-id-str]
  ;; Convert uuid as string to a java uuid object
  ;; Lookup the player in the database using the uuid
  (let [player-id (java.util.UUID/fromString player-id-str)
        player (xt/entity db player-id)]
    (if (nil? player)
      {:error {:status 404 :body "Player not found"}}
      ;; The player exists, so lookup the game game for the player, and package it into a hash
      (let [game (xt/entity db (:player/game player))]
        {:player-id player-id
         :player player
         :game game}))))

;;; Macro for handlers that need player and game entities. Automatically loads entities and handles 
;;; error cases, allowing the handler body to focus on business logic.
(defmacro with-player-and-game
  "Load player and game, execute body with bindings, or return error response.
   Usage: (with-player-and-game [player game player-id] ctx
            ... body that uses player, game, player-id ...)"
  [[player-sym game-sym player-id-sym] ctx & body]
  `(let [result# (load-player-and-game (:biff/db ~ctx) 
                                       (get-in ~ctx [:path-params :player-id]))]
     (if (:error result#)
       (:error result#)
       (let [~player-sym (:player result#)
             ~game-sym (:game result#)
             ~player-id-sym (:player-id result#)]
         ~@body))))
