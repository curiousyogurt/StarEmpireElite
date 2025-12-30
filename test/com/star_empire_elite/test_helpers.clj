(ns com.star-empire-elite.test-helpers
  (:require [com.biffweb :as biff])
  (:import (java.util UUID)
           (java.time Instant)))

;; ---- trivial helpers (no external utils) ------------------------------------

(defn uuid [] (UUID/randomUUID))
(defn now ^Instant [] (Instant/now))
(defn plus-days ^Instant [^Instant inst ^long days]
  (.plusSeconds inst (* 86400 days)))            ;; Instant supports Duration/seconds

;; ---- Test fixture utilities -------------------------------------------------

;;; Returns a function that simulates XTDB's entity lookup for given entities.
;;; This is used across multiple test files to mock database entity retrieval.
(defn fake-entity 
  "Creates a mock entity lookup function for testing.
   Takes a collection of entities and returns a function that looks them up by :xt/id."
  [entities]
  (fn [_ id]
    (first (filter #(= (:xt/id %) id) entities))))

;; ---- schema-complete defaults (match your Malli keys) -----------------------

(def game-defaults
  {:db/doc-type                    :game
   :xt/id                          (uuid)
   :game/name                      "Test Game"
   :game/created-at                (now)
   :game/scheduled-end-at          (plus-days (now) 30)
   :game/status                    0
   :game/turns-per-day             6
   :game/rounds-per-day            4

   ;; required fields seen in your error report:
   :game/soldier-upkeep-credits    0
   :game/soldier-upkeep-food       0
   :game/fighter-upkeep-credits    0
   :game/fighter-upkeep-fuel       0
   :game/agent-upkeep-credits      0
   :game/agent-upkeep-food         0
   :game/population-upkeep-credits 0
   :game/population-upkeep-food    0
   :game/station-upkeep-credits    0
   :game/station-upkeep-fuel       0
   :game/planet-upkeep-credits     0
   :game/planet-upkeep-food        0
   :game/ore-planet-credits        0
   :game/ore-planet-fuel           0
   :game/ore-planet-galaxars       0
   :game/food-planet-food          0
   :game/mil-planet-stations  0
   :game/mil-planet-fighters  0
   :game/mil-planet-soldiers  0
   :game/mil-planet-agents    0})

(defn make-game [sys overrides]
  (let [doc (merge game-defaults overrides)]
    (biff/submit-tx sys [{:db/op :db/put :db/doc doc}])
    doc))

(def player-defaults
  {:db/doc-type               :player
   :xt/id                     (uuid)
   :player/user               (uuid)
   :player/game               (uuid)   ;; set to game :xt/id when creating
   :player/empire-name        "Test Empire"
   :player/status             0
   :player/current-phase      0
   :player/current-round      1
   :player/current-turn       1
   :player/turns-used         0
   :player/stability          75
   :player/score              0

   ;; resources
   :player/credits            10000
   :player/galaxars           1000
   :player/fuel               3000
   :player/food               5000
   :player/population         1000000

   ;; assets (namespaced keys!)
   :player/fighters           50
   :player/soldiers           1000
   :player/agents             10
   :player/admirals           3
   :player/generals           5
   :player/transports         10
   :player/carriers           2
   :player/cmd-ships          1
   :player/stations           5

   ;; planet summaries
   :player/ore-planets        1
   :player/food-planets       3
   :player/mil-planets   2})

(defn make-player [sys game-id overrides]
  (let [doc (-> player-defaults
                (assoc :player/game game-id)
                (merge overrides))]
    (biff/submit-tx sys [{:db/op :db/put :db/doc doc}])
    doc))

