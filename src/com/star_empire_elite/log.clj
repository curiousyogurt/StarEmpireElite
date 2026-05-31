;;;;;
;;;;; Game Action Logger
;;;;;
;;;;; Lightweight structured logging of game actions to a flat EDN file.
;;;;; One EDN map per line. Uses a Clojure agent to serialize all writes.
;;;;;
;;;;; Every log entry has the same base shape:
;;;;;   {:ts :phase :game-id :player-id :empire :turn :round :before :after :details}
;;;;;
;;;;; :before and :after are full empire snapshots (empire-snapshot).
;;;;; :details is phase-specific and produced by the phase-details multimethod.
;;;;;
;;;;; To add logging for a new phase/route:
;;;;;   1. Add an entry to `logged-routes`.
;;;;;   2. Add a (defmethod phase-details :your-phase ...) implementation. No changes 
;;;;;      to the middleware or existing methods required.
;;;;;

(ns com.star-empire-elite.log
  (:require [xtdb.api :as xt]
            [clojure.string :as str]
            [com.star-empire-elite.constants :as const]))

;;;;
;;;; Logger State
;;;;

;;; defonce so hot-reloads don't recreate the atom and lose the open writer.
(defonce logger (atom nil))

(defn- write-entry! [^java.io.BufferedWriter writer entry]
  (.write writer (pr-str entry))
  (.newLine writer)
  (.flush writer))

(defn log-event!
  "Append one EDN map to the log file. Thread-safe via agent dispatch.
  No-op if logging is disabled or logger is not started."
  [entry]
  (when const/enable-game-logging
    (when-let [{:keys [agent writer]} @logger]
      (send agent (fn [_] (write-entry! writer entry))))))

;;;;
;;;; Biff Component
;;;;

(defn use-game-logger
  "Biff component. Opens the log file (append mode) and starts the write agent.
  Log file path taken from :star-empire-elite/gamelog-path in system,
  defaulting to data/gamelog.edn."
  [system]
  (let [path   (or (:star-empire-elite/gamelog-path system) "data/gamelog.edn")
        file   (java.io.File. path)
        _      (.mkdirs (.getParentFile file))
        writer (java.io.BufferedWriter. (java.io.FileWriter. file true))
        agt    (agent nil :error-mode :continue)]
    (reset! logger {:writer writer :agent agt})
    (update system :biff/stop conj
            (fn []
              (await agt)
              (.close writer)
              (reset! logger nil)))))

;;;;
;;;; Helpers
;;;;

(defn- diff-snapshot
  "Return only the fields in `after` that differ from `before`.
  Falls back to the full `after` snapshot when `before` is nil (e.g. join-game)."
  [before after]
  (if (nil? before)
    after
    (when after
      (into {} (filter (fn [[k v]] (not= v (get before k))) after)))))

(defn- read-edn [s]
  (when (string? s)
    (try (clojure.core/read-string s) (catch Exception _ nil))))

(defn- fetch-player [db player-id-str]
  (when (and db player-id-str)
    (try (xt/entity db (java.util.UUID/fromString player-id-str))
         (catch Exception _ nil))))


;;;;
;;;; Empire Snapshot
;;;;

(defn- empire-snapshot
  "Complete empire state snapshot. Used for both :before and :after fields.
  Returns nil when player is nil (e.g. join-game pre-player)."
  [player]
  (when player
    {:credits     (:player/credits     player)
     :food        (:player/food        player)
     :fuel        (:player/fuel        player)
     :ore-planets (:player/ore-planets player)
     :erg-planets (:player/erg-planets player)
     :mil-planets (:player/mil-planets player)
     :population  (:player/population  player)
     :stability   (:player/stability   player)
     :soldiers    (:player/soldiers    player)
     :transports  (:player/transports  player)
     :generals    (:player/generals    player)
     :fighters    (:player/fighters    player)
     :carriers    (:player/carriers    player)
     :admirals    (:player/admirals    player)
     :cmd-ships   (:player/cmd-ships   player)
     :stations    (:player/stations    player)
     :agents      (:player/agents      player)
     :score       (:player/score       player)
     :status      (:player/status      player)}))

;;;;
;;;; Combat and Espionage Summaries
;;;;

(defn- nz
  "Remove zero-valued entries from a map."
  [m]
  (into {} (remove (fn [[_ v]] (zero? v)) m)))

(defn- combat-summary
  "Structured summary of a resolve-combat result map.
  Attacker identity omitted — always the logging player (top-level :empire/:player-id)."
  [result]
  (when result
    {:winner          (if (:attacker-wins? result) :attacker :defender)
     :margin          (:margin result)
     :attacker        {:forces (:attacker-forces result)
                       :losses (:attacker-losses result)}
     :defender        {:name   (:defender-name result)
                       :id     (:defender-id   result)
                       :forces (:defender-forces result)
                       :losses (:defender-losses result)}
     :planets-captured (:planets-transferred result)}))

(defn- espionage-summary
  "Structured summary of a resolve-espionage/incite/bomb result map.
  :attacker-agents comes from the pre-handler player state.
  :defender-agents is available only for spy ops (via intel snapshot)."
  [result pre-player]
  (when result
    (let [op        (:op result)
          att-wins? (:attacker-wins? result)]
      {:op              op
       :winner          (if att-wins? :attacker :defender)
       :defender        {:name (:defender-name result)
                         :id   (:defender-id   result)}
       :attacker-agents (:player/agents pre-player)
       :outcome
       (case op
         "spy"    {:intel-gained?   att-wins?
                   :intel           (when att-wins? (:intel result))
                   :agents-captured (when-not att-wins? (:agents-captured result))}
         "incite" {:stability-damage (when att-wins? (:stability-damage result))
                   :agents-captured  (when-not att-wins? (:agents-captured result))}
         "bomb"   (nz {:soldiers        (or (:soldiers   result) 0)
                        :transports      (or (:transports result) 0)
                        :fighters        (or (:fighters   result) 0)
                        :carriers        (or (:carriers   result) 0)
                        :agents-captured (or (when-not att-wins? (:agents-captured result)) 0)})
         {:agents-captured (when-not att-wins? (:agents-captured result))})})))

;;;;
;;;; Phase Details (multimethod)
;;;;
;;;; Each method receives [phase pre-player post-player params db] and returns
;;;; a details map (or nil). Only add a new defmethod to handle a new phase;
;;;; no other changes are needed.
;;;;

(defmulti phase-details
  "Return phase-specific detail map for a log entry."
  (fn [phase _pre _post _params _db] phase))

;;; Action: record intended attack target, or skip.
;;; Note: combat is NOT resolved here — it resolves at :outcomes. The result is in the
;;; :outcomes log entry for the same turn/round under :details :combat.
(defmethod phase-details :action [_ pre _post params db]
  (let [target-str (when (seq (:target-player-id params)) (:target-player-id params))
        target     (fetch-player db target-str)]
    (if target-str
      {:skip?         false
       :target-id     target-str
       :target-empire (:player/empire-name target)}
      {:skip? true})))

;;; Espionage: record intended operation and target, or skip.
;;; Note: espionage is NOT resolved here — it resolves at :outcomes. The result is in the
;;; :outcomes log entry for the same turn/round under :details :espionage.
(defmethod phase-details :espionage [_ _pre _post params db]
  (let [raw        (or (:espionage-action params) "")
        match      (re-matches #"(spy|incite|bomb):(.*)" raw)
        op         (get match 1)
        target-str (get match 2)
        target     (fetch-player db target-str)]
    (if target-str
      {:skip?         false
       :op            op
       :target-id     target-str
       :target-empire (:player/empire-name target)}
      {:skip? true})))

;;; Outcomes: full combat + espionage results, stability events, population growth.
;;; All results are read from pre-player (they're cached there by the GET handler
;;; and cleared by apply-outcomes, so post-player no longer has them).
(defmethod phase-details :outcomes [_ pre _post _params _db]
  (let [battle    (read-edn (:player/last-battle-result       pre))
        espionage (read-edn (:player/last-espionage-result     pre))
        breakaway (read-edn (:player/last-stability-breakaway  pre))
        recovery  (read-edn (:player/last-stability-recovery   pre))
        penalty   (:player/last-expense-stability-penalty pre)
        pop-growth (:player/last-population-growth pre)
        stability (let [b (when (:triggered? breakaway)
                            {:ore-lost   (:ore-lost   breakaway)
                             :erg-lost   (:erg-lost   breakaway)
                             :mil-lost   (:mil-lost   breakaway)
                             :total-lost (:total-lost breakaway)})
                        r (when (:triggered? recovery) {:amount (:amount recovery)})
                        p (when (and penalty (pos? penalty)) penalty)]
                    (when (or b r p)
                      (merge (when p {:expense-penalty p})
                             (when b {:breakaway b})
                             (when r {:recovery  r}))))]
    (merge
     (when battle       {:combat    (combat-summary    battle)})
     (when espionage    {:espionage (espionage-summary espionage pre)})
     (when stability    {:stability stability})
     (when (some? pop-growth) {:population {:growth pop-growth}}))))

;;; Join/rejoin: record chosen empire name. pre-player is nil for join-game.
(defmethod phase-details :join-game [_ _pre _post params _db]
  {:empire-name (:empire-name params)})

(defmethod phase-details :rejoin [_ _pre _post params _db]
  {:empire-name (:empire-name params)})

(defmethod phase-details :default [_ _ _ _ _] nil)

;;;;
;;;; Ring Middleware
;;;;

;;; Map from URL terminal segment → phase keyword.
;;; Add an entry here (and a defmethod above) to log a new route.
(def ^:private logged-routes
  {"apply-income"    :income
   "apply-expenses"  :expenses
   "apply-exchange"  :exchange
   "apply-building"  :building
   "apply-action"    :action
   "apply-espionage" :espionage
   "apply-outcomes"  :outcomes
   "join-game"       :join-game
   "rejoin"          :rejoin})

(defn- phase-for-uri [uri]
  (when uri
    (get logged-routes (last (str/split uri #"/")))))

(defn wrap-game-action-log
  "Ring middleware. For each logged POST route, captures pre- and post-handler
  player state and dispatches to the appropriate phase-details method.
  Writes asynchronously via log-event! — zero impact on response latency."
  [handler]
  (fn [{:keys [request-method uri path-params biff/db params] :as ctx}]
    (let [phase (when (= request-method :post) (phase-for-uri uri))]
      (if (nil? phase)
        (handler ctx)
        (let [player-id-str (:player-id path-params)
              game-id-str   (:game-id   path-params)
              pre-player    (fetch-player db player-id-str)
              response      (handler ctx)
              post-db       (when-let [node (:biff.xtdb/node ctx)] (xt/db node))
              post-player   (fetch-player post-db player-id-str)
              ;; Use pre-player for base fields; fall back to post for join-game
              player        (or pre-player post-player)
              game-id       (or (some-> (:player/game player) str) game-id-str)]
          (log-event!
           {:ts        (java.util.Date.)
            :phase     phase
            :game-id   game-id
            :player-id (or player-id-str (some-> (:xt/id post-player) str))
            :empire    (:player/empire-name player)
            :turn      (:player/current-turn  pre-player)
            :round     (:player/current-round pre-player)
            :before    (empire-snapshot pre-player)
            :after     (diff-snapshot (empire-snapshot pre-player) (empire-snapshot post-player))
            :details   (phase-details phase pre-player post-player params db)})
          response)))))
