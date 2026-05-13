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
(defn parse-numeric-input 
  "Parse user input to a non-negative integer, stripping non-numeric chars and treating empty/nil as 
  0"
  [val]
  (let [s (str val)
        cleaned (clojure.string/replace s #"[^0-9]" "")]
    (if (empty? cleaned)
      0
      (parse-long cleaned))))

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

(defn- ms-until-midnight
  "Returns milliseconds until the next UTC midnight."
  []
  (let [cal (doto (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))
              (.setTime (java.util.Date.))
              (.set java.util.Calendar/HOUR_OF_DAY 0)
              (.set java.util.Calendar/MINUTE 0)
              (.set java.util.Calendar/SECOND 0)
              (.set java.util.Calendar/MILLISECOND 0)
              (.add java.util.Calendar/DAY_OF_MONTH 1))]
    (- (.getTimeInMillis cal) (.getTime (java.util.Date.)))))

(defn day-exhausted?
  "Returns true if the player has used all rounds-per-day rounds for today's UTC calendar day."
  [player game]
  (let [completed      (:player/last-round-completed-at player)
        current-round  (:player/current-round player)
        rounds-per-day (:game/rounds-per-day game)]
    (boolean
     (and (> current-round rounds-per-day)
          completed
          (same-calendar-day? completed)))))

(defn round-cooldown-ms
  "Returns ms remaining in cooldown if player is blocked from starting a new round, or nil.
   Two blocking conditions:
   1. Day exhausted: all rounds-per-day used today → blocked until midnight UTC.
   2. Between-round cooldown: minimum hours-between-rounds since last completed round."
  [player game]
  (let [completed (:player/last-round-completed-at player)]
    (cond
      (day-exhausted? player game)
      (ms-until-midnight)

      (and (= (:player/current-turn player) 1)
           (= (:player/turns-used player) 0)
           completed)
      (let [hours       (or (:game/hours-between-rounds game) 0)
            cooldown-ms (* hours 60 60 1000)
            remaining   (- cooldown-ms (- (.getTime (java.util.Date.)) (.getTime completed)))]
        (when (pos? remaining) remaining))

      :else nil)))

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
;;;; Turn/Round Display
;;;;

(defn display-turn-round
  "Return display values {:turn n :round n} for turn and round indicators.
  Shows the completed state when a player is waiting between rounds or for the next day,
  rather than the misleading reset values.

  [player game] -> {:turn int :round int}"
  [player game]
  (let [rounds-per-day  (:game/rounds-per-day game)
        turns-per-round (:game/turns-per-round game)
        current-round   (:player/current-round player)
        current-turn    (:player/current-turn player)
        turns-used      (:player/turns-used player)
        last-completed  (:player/last-round-completed-at player)
        day-complete?   (> current-round rounds-per-day)
        between-rounds? (and (not day-complete?)
                             (= current-turn 1)
                             (= turns-used 0)
                             (some? last-completed))]
    {:turn  (if (or day-complete? between-rounds?) turns-per-round current-turn)
     :round (cond
              day-complete?   rounds-per-day
              between-rounds? (dec current-round)
              :else           current-round)}))

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
   :advisors    (get player :player/advisors 0)
   :ore-planets (get player :player/ore-planets 0)
   :erg-planets (get player :player/erg-planets 0)
   :mil-planets (get player :player/mil-planets 0)})

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
