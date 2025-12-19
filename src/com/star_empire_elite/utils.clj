;;;;;
;;;;; Shared Utilities
;;;;;
;;;;; Common utility functions used across multiple game phases. This namespace contains reusable 
;;;;; helpers for input parsing, validation, and entity loading to reduce code duplication across 
;;;;; various pages.
;;;;;

(ns com.star-empire-elite.utils
  (:require [xtdb.api :as xt]))

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
