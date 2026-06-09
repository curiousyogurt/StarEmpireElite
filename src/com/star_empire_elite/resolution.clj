;;;;;
;;;;; Resolution - Turn Event Resolution and Persistence
;;;;;
;;;;; Resolves all turn events (combat, espionage, population growth, stability) on the first
;;;;; outcomes page load, persisting results and caching them so page refreshes show identical
;;;;; results without re-rolling. Each step is idempotent: a cached result is returned without
;;;;; writing to the database again.
;;;;;
;;;;; Public API: resolve-turn!, calculate-stability-breakaway, calculate-stability-recovery
;;;;;

(ns com.star-empire-elite.resolution
  (:require [com.biffweb               :as biff]
            [com.star-empire-elite.combat     :as combat]
            [com.star-empire-elite.constants  :as const]
            [com.star-empire-elite.events     :as events]
            [com.star-empire-elite.utils      :as utils]
            [xtdb.api                         :as xt]))

;;;;
;;;; Stability Calculations
;;;;

(defn- distribute-breakaway-planets
  "Distribute n planet losses proportionally across ore/erg/mil counts.
  Uses largest-remainder method so the total always equals n exactly.

  [ore int, erg int, mil int, n int] -> {:ore int, :erg int, :mil int}"
  [ore erg mil n]
  (let [total (+ ore erg mil)]
    (if (zero? total)
      {:ore 0 :erg 0 :mil 0}
      (let [ore-frac (* n (/ (double ore) total))
            erg-frac (* n (/ (double erg) total))
            mil-frac (* n (/ (double mil) total))
            ore-base (long ore-frac)
            erg-base (long erg-frac)
            mil-base (long mil-frac)
            remainder (- n ore-base erg-base mil-base)
            by-frac  (sort-by second >
                       [[:ore (- ore-frac ore-base)]
                        [:erg (- erg-frac erg-base)]
                        [:mil (- mil-frac mil-base)]])
            extras   (set (map first (take remainder by-frac)))]
        {:ore (min ore (+ ore-base (if (extras :ore) 1 0)))
         :erg (min erg (+ erg-base (if (extras :erg) 1 0)))
         :mil (min mil (+ mil-base (if (extras :mil) 1 0)))}))))

(defn calculate-stability-breakaway
  "Roll for a stability breakaway event. Returns a result map whether or not
  a breakaway occurs; :triggered? indicates whether planets were lost.

  [player player-map, game game-map] -> result-map"
  [player game]
  (let [stability  (:player/stability player)
        threshold  (:game/stability-breakaway-threshold game)
        cap        (/ (:game/stability-breakaway-cap game) 100.0)
        roll       (inc (rand-int 100))
        triggered? (> roll (+ stability threshold))]
    (if-not triggered?
      {:roll roll :stability stability :triggered? false}
      (let [ore   (:player/ore-planets player)
            erg   (:player/erg-planets player)
            mil   (:player/mil-planets player)
            total (+ ore erg mil)
            fraction     (min cap (/ (double (- roll stability threshold)) 100.0))
            planets-lost (max 1 (long (Math/ceil (* fraction total))))
            lost         (distribute-breakaway-planets ore erg mil planets-lost)]
        {:roll        roll
         :stability   stability
         :triggered?  true
         :ore-lost    (:ore lost)
         :erg-lost    (:erg lost)
         :mil-lost    (:mil lost)
         :total-lost  planets-lost}))))

(defn calculate-stability-recovery
  "Roll for a stability recovery event. Only call when expenses were fully paid
  and stability is below 100. Triggers if roll < max(stability, 50), so higher
  stability means a greater chance of recovery, with a 50% floor.

  [player player-map, game game-map] -> result-map"
  [player game]
  (let [stability  (:player/stability player)
        amount     (:game/stability-recovery-amount game)
        floor      (:game/stability-recovery-floor game)
        roll       (inc (rand-int 100))
        target     (max stability floor)
        triggered? (< roll target)]
    {:roll       roll
     :stability  stability
     :triggered? triggered?
     :amount     (when triggered? (min amount (- 100 stability)))}))

;;;;
;;;; Resolution Steps
;;;;

(defn- resolve-combat
  "Resolve combat on first load; return cached result on refresh.
  Returns [result updated-player] where result is nil if no attack was pending."
  [ctx player-id player game]
  (let [db             (:biff/db ctx)
        pending-attack (:player/pending-attack player)
        mode           (:player/pending-attack-mode player)
        stored-battle  (some-> (:player/last-battle-result player) clojure.core/read-string)]
    (if-not pending-attack
      [nil player]
      (let [defender (xt/entity db pending-attack)]
        (if (and stored-battle (= (:defender-id stored-battle) (str pending-attack)))
          ;; Cache hit — changes already applied; player entity is fresh on this request
          [stored-battle player]
          (if (= mode :strike)
            ;; --- Strike branch ---
            (let [result        (combat/resolve-strike game player defender)
                  dl            (:defender-losses result)
                  ships-lost    (:cmd-ships-lost result)
                  att-cmd-ships (max 0 (- (:player/cmd-ships player) ships-lost))
                  def-soldiers   (max 0 (- (:player/soldiers   defender) (:soldiers   dl)))
                  def-transports (max 0 (- (:player/transports defender) (:transports dl)))
                  def-generals   (max 0 (- (:player/generals   defender) (:generals   dl)))
                  def-fighters   (max 0 (- (:player/fighters   defender) (:fighters   dl)))
                  def-carriers   (max 0 (- (:player/carriers   defender) (:carriers   dl)))
                  def-admirals   (max 0 (- (:player/admirals   defender) (:admirals   dl)))
                  def-stations   (max 0 (- (:player/stations   defender) (:stations   dl)))]
              (biff/submit-tx
                ctx
                [{:db/doc-type :player :db/op :update :xt/id player-id
                  :player/last-battle-result (pr-str result)
                  :player/cmd-ships att-cmd-ships
                  :player/score     (utils/calculate-score
                                      (assoc player :player/cmd-ships att-cmd-ships))}
                 {:db/doc-type :player :db/op :update :xt/id (:xt/id defender)
                  :player/soldiers   def-soldiers
                  :player/transports def-transports
                  :player/generals   def-generals
                  :player/fighters   def-fighters
                  :player/carriers   def-carriers
                  :player/admirals   def-admirals
                  :player/stations   def-stations
                  :player/score      (utils/calculate-score
                                       (assoc defender
                                         :player/soldiers   def-soldiers
                                         :player/generals   def-generals
                                         :player/fighters   def-fighters
                                         :player/admirals   def-admirals
                                         :player/stations   def-stations))
                  :player/incoming-attacks
                  (conj (or (:player/incoming-attacks defender) []) (pr-str result))}])
              [result (assoc player :player/cmd-ships att-cmd-ships)])
            ;; --- Invade / Raid branch ---
            (let [result  (combat/resolve-combat game player defender mode)
                  al      (:attacker-losses result)
                  dl      (:defender-losses result)
                  raw-pt  (or (:planets-transferred result) {:mil 0 :erg 0 :ore 0})
                  pt-mil  (min (:mil raw-pt) (:player/mil-planets defender))
                  pt-erg  (min (:erg raw-pt) (:player/erg-planets defender))
                  pt-ore  (min (:ore raw-pt) (:player/ore-planets defender))
                  capped       (assoc result :planets-transferred {:mil pt-mil :erg pt-erg :ore pt-ore})
                  rc           (or (:resources-captured capped) {:credits 0 :food 0 :fuel 0})
                  rc-credits   (:credits rc)
                  rc-food      (:food    rc)
                  rc-fuel      (:fuel    rc)
                  att-soldiers   (max 0 (- (:player/soldiers   player) (:soldiers   al)))
                  att-transports (max 0 (- (:player/transports player) (:transports al)))
                  att-generals   (max 0 (- (:player/generals   player) (:generals   al)))
                  att-fighters   (max 0 (- (:player/fighters   player) (:fighters   al)))
                  att-carriers   (max 0 (- (:player/carriers   player) (:carriers   al)))
                  att-admirals   (max 0 (- (:player/admirals   player) (:admirals   al)))
                  att-cmd-ships  (max 0 (- (:player/cmd-ships  player) (:cmd-ships  al)))
                  att-mil-plts   (+ (:player/mil-planets  player) pt-mil)
                  att-erg-plts   (+ (:player/erg-planets  player) pt-erg)
                  att-ore-plts   (+ (:player/ore-planets  player) pt-ore)
                  att-credits    (+ (:player/credits      player) rc-credits)
                  att-food       (+ (:player/food         player) rc-food)
                  att-fuel       (+ (:player/fuel         player) rc-fuel)
                  def-soldiers   (max 0 (- (:player/soldiers    defender) (:soldiers   dl)))
                  def-transports (max 0 (- (:player/transports  defender) (:transports dl)))
                  def-generals   (max 0 (- (:player/generals    defender) (:generals   dl)))
                  def-fighters   (max 0 (- (:player/fighters    defender) (:fighters   dl)))
                  def-carriers   (max 0 (- (:player/carriers    defender) (:carriers   dl)))
                  def-admirals   (max 0 (- (:player/admirals    defender) (:admirals   dl)))
                  def-cmd-ships  (max 0 (- (:player/cmd-ships   defender) (:cmd-ships  dl)))
                  def-stations   (max 0 (- (:player/stations    defender) (:stations   dl)))
                  def-mil-plts   (max 0 (- (:player/mil-planets defender) pt-mil))
                  def-erg-plts   (max 0 (- (:player/erg-planets defender) pt-erg))
                  def-ore-plts   (max 0 (- (:player/ore-planets defender) pt-ore))
                  def-credits    (max 0 (- (:player/credits     defender) rc-credits))
                  def-food       (max 0 (- (:player/food        defender) rc-food))
                  def-fuel       (max 0 (- (:player/fuel        defender) rc-fuel))]
              (biff/submit-tx
                ctx
                [{:db/doc-type :player :db/op :update :xt/id player-id
                  :player/last-battle-result (pr-str capped)
                  :player/soldiers     att-soldiers
                  :player/transports   att-transports
                  :player/generals     att-generals
                  :player/fighters     att-fighters
                  :player/carriers     att-carriers
                  :player/admirals     att-admirals
                  :player/cmd-ships    att-cmd-ships
                  :player/mil-planets  att-mil-plts
                  :player/erg-planets  att-erg-plts
                  :player/ore-planets  att-ore-plts
                  :player/credits      att-credits
                  :player/food         att-food
                  :player/fuel         att-fuel
                  :player/score        (utils/calculate-score
                                         (assoc player
                                           :player/soldiers     att-soldiers
                                           :player/generals     att-generals
                                           :player/fighters     att-fighters
                                           :player/admirals     att-admirals
                                           :player/cmd-ships    att-cmd-ships
                                           :player/mil-planets  att-mil-plts
                                           :player/erg-planets  att-erg-plts
                                           :player/ore-planets  att-ore-plts
                                           :player/credits      att-credits
                                           :player/food         att-food
                                           :player/fuel         att-fuel))}
                 {:db/doc-type :player :db/op :update :xt/id (:xt/id defender)
                  :player/soldiers     def-soldiers
                  :player/transports   def-transports
                  :player/generals     def-generals
                  :player/fighters     def-fighters
                  :player/carriers     def-carriers
                  :player/admirals     def-admirals
                  :player/cmd-ships    def-cmd-ships
                  :player/stations     def-stations
                  :player/mil-planets  def-mil-plts
                  :player/erg-planets  def-erg-plts
                  :player/ore-planets  def-ore-plts
                  :player/credits      def-credits
                  :player/food         def-food
                  :player/fuel         def-fuel
                  :player/score        (utils/calculate-score
                                         (assoc defender
                                           :player/soldiers     def-soldiers
                                           :player/generals     def-generals
                                           :player/fighters     def-fighters
                                           :player/admirals     def-admirals
                                           :player/cmd-ships    def-cmd-ships
                                           :player/stations     def-stations
                                           :player/mil-planets  def-mil-plts
                                           :player/erg-planets  def-erg-plts
                                           :player/ore-planets  def-ore-plts
                                           :player/credits      def-credits
                                           :player/food         def-food
                                           :player/fuel         def-fuel))
                  :player/incoming-attacks
                  (conj (or (:player/incoming-attacks defender) []) (pr-str capped))}])
              [capped 
               (merge player 
                      {:player/soldiers     att-soldiers
                       :player/transports   att-transports
                       :player/generals     att-generals
                       :player/fighters     att-fighters
                       :player/carriers     att-carriers
                       :player/admirals     att-admirals
                       :player/cmd-ships    att-cmd-ships
                       :player/mil-planets  att-mil-plts
                       :player/erg-planets  att-erg-plts
                       :player/ore-planets  att-ore-plts
                       :player/credits      att-credits
                       :player/food         att-food
                       :player/fuel         att-fuel})])))))))

(defn- resolve-espionage
  "Resolve espionage on first load; return cached result on refresh.
  Returns [result updated-player] where result is nil if no espionage was pending."
  [ctx player-id player game]
  (let [db                   (:biff/db ctx)
        pending-espionage    (:player/pending-espionage player)
        pending-espionage-op (:player/pending-espionage-op player)
        stored-espionage     (some-> (:player/last-espionage-result player) clojure.core/read-string)]
    (if-not pending-espionage
      [nil player]
      (let [target (xt/entity db pending-espionage)]
        (if (and stored-espionage (= (:defender-id stored-espionage) (str pending-espionage)))
          ;; Cache hit — changes already applied on first load
          [stored-espionage player]
          ;; Fresh resolve
          (let [incite?     (= pending-espionage-op "incite")
                bomb?       (= pending-espionage-op "bomb")
                defect?     (= pending-espionage-op "defect")
                result      (cond
                              incite?  (combat/resolve-incite    player target)
                              bomb?    (combat/resolve-bomb      player target)
                              defect?  (combat/resolve-defect    game player target)
                              :else    (combat/resolve-espionage player target))
                att-wins?   (:attacker-wins? result)
                agents-lost (or (:agents-captured result) 0)
                stab-dmg    (or (:stability-damage result) 0)]
            (biff/submit-tx 
              ctx
              (remove nil?
                      [{:db/doc-type :player :db/op :update :xt/id player-id
                        :player/last-espionage-result (pr-str result)
                        :player/agents (max 0 (- (:player/agents player) agents-lost))}
                       (when (pos? agents-lost)
                         {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                          :player/agents (+ (or (:player/agents target) 0) agents-lost)
                          :player/incoming-espionage-fails
                          (conj (or (:player/incoming-espionage-fails target) [])
                                (cond incite? "incite" bomb? "bomb" defect? "defect" :else "spy"))
                          :player/incoming-espionage-agents-gained
                          (+ (or (:player/incoming-espionage-agents-gained target) 0) agents-lost)})
                       (when (and incite? att-wins? (pos? stab-dmg))
                         {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                          :player/stability (max 0 (- (:player/stability target) stab-dmg))
                          :player/incoming-incite-stability-lost
                          (+ (or (:player/incoming-incite-stability-lost target) 0) stab-dmg)})
                       (when (and bomb? att-wins?)
                         (let [sd          (or (:soldiers   result) 0)
                               td          (or (:transports result) 0)
                               fd          (or (:fighters   result) 0)
                               cd          (or (:carriers   result) 0)
                               new-soldiers (max 0 (- (:player/soldiers  target) sd))
                               new-fighters (max 0 (- (:player/fighters  target) fd))]
                           {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                            :player/soldiers   new-soldiers
                            :player/transports (max 0 (- (:player/transports target) td))
                            :player/fighters   new-fighters
                            :player/carriers   (max 0 (- (:player/carriers   target) cd))
                            :player/score      (utils/calculate-score
                                                 (assoc target
                                                   :player/soldiers new-soldiers
                                                   :player/fighters new-fighters))
                            :player/incoming-bomb-result
                            (pr-str {:attacker-name (:player/empire-name player)
                                     :soldiers      sd
                                     :transports    td
                                     :fighters      fd
                                     :carriers      cd})}))
                       (when (and defect? att-wins?)
                         (let [n (or (:agents-defected result) 0)]
                           {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                            :player/agents (max 0 (- (or (:player/agents target) 0) n))
                            :player/incoming-defect-agents-lost
                            (+ (or (:player/incoming-defect-agents-lost target) 0) n)}))]))
            (let [agents-gained (if (and defect? att-wins?) (or (:agents-defected result) 0) 0)]
              [result (assoc player :player/agents
                             (max 0 (+ (- (:player/agents player) agents-lost) agents-gained)))])))))))

(defn- resolve-population
  "Resolve population growth on first load; return cached result on refresh.
  Returns [growth updated-player] where growth is the population increase (may be 0)."
  [ctx player-id player _game]
  (let [cached (:player/last-population-growth player)]
    (if (some? cached)
      [cached player]
      (let [pop      (:player/population player)
            planets  (+ (:player/ore-planets  player)
                        (:player/erg-planets  player)
                        (:player/mil-planets  player))
            capacity (* planets const/pop-capacity-per-planet)
            raw      (+ (* pop const/pop-growth-rate)
                        (* planets const/pop-growth-per-planet))
            crowding (if (zero? capacity) 0.0 (max 0.0 (- 1.0 (/ pop capacity))))
            rnd         (+ const/pop-random-min
                           (* (rand) (- const/pop-random-max const/pop-random-min)))
            raw-growth  (* raw crowding rnd)
            growth-int  (long raw-growth)
            growth-frac (- raw-growth growth-int)
            growth      (max 0 (+ growth-int (if (< (rand) growth-frac) 1 0)))]
        (biff/submit-tx ctx
                        [{:db/doc-type :player :db/op :update :xt/id player-id
                          :player/population             (+ pop growth)
                          :player/last-population-growth growth}])
        [growth (-> player
                    (assoc :player/population             (+ pop growth))
                    (assoc :player/last-population-growth growth))]))))

(defn- resolve-expense-penalty
  "Apply the expense stability penalty on first load; return cached result on refresh.
  Returns [penalty updated-player] where penalty is nil if no penalty applies."
  [ctx player-id player _game]
  (let [pending-penalty (:player/expense-stability-penalty player)
        last-penalty    (:player/last-expense-stability-penalty player)]
    (cond
      (some? last-penalty)
      [last-penalty player]

      (or (nil? pending-penalty) (zero? pending-penalty))
      [nil player]

      :else
      (let [new-stability (max 0 (- (:player/stability player) pending-penalty))]
        (biff/submit-tx ctx
                        [{:db/doc-type                           :player
                          :db/op                                 :update
                          :xt/id                                 player-id
                          :player/stability                      new-stability
                          :player/last-expense-stability-penalty pending-penalty
                          :player/expense-stability-penalty      nil}])
        [pending-penalty (-> player
                              (assoc :player/stability                      new-stability)
                              (assoc :player/last-expense-stability-penalty pending-penalty))]))))

(defn- resolve-stability-breakaway
  "Roll for a stability breakaway on first load; return cached result on refresh.
  Returns [result updated-player]."
  [ctx player-id player game]
  (let [stored (some-> (:player/last-stability-breakaway player) clojure.core/read-string)]
    (if (some? stored)
      [stored player]
      (let [result (calculate-stability-breakaway player game)]
        (if-not (:triggered? result)
          (do (biff/submit-tx ctx
                              [{:db/doc-type                     :player
                                :db/op                           :update
                                :xt/id                           player-id
                                :player/last-stability-breakaway (pr-str result)}])
              [result player])
          (let [ore-after (- (:player/ore-planets player) (:ore-lost result))
                erg-after (- (:player/erg-planets player) (:erg-lost result))
                mil-after (- (:player/mil-planets player) (:mil-lost result))]
            (biff/submit-tx ctx
                            [{:db/doc-type                     :player
                              :db/op                           :update
                              :xt/id                           player-id
                              :player/ore-planets              ore-after
                              :player/erg-planets              erg-after
                              :player/mil-planets              mil-after
                              :player/last-stability-breakaway (pr-str result)}])
            [result (-> player
                        (assoc :player/ore-planets ore-after)
                        (assoc :player/erg-planets erg-after)
                        (assoc :player/mil-planets mil-after))]))))))

(defn- resolve-stability-recovery
  "Roll for a stability recovery on first load; return cached result on refresh.
  Only eligible when expenses were fully paid and no breakaway triggered this turn.
  Returns [result updated-player] where result is nil if not eligible."
  [ctx player-id player game pending-penalty breakaway-result]
  (let [stored             (some-> (:player/last-stability-recovery player) clojure.core/read-string)
        recovery-eligible? (and (some? pending-penalty)
                                (zero? pending-penalty)
                                (not (:triggered? breakaway-result))
                                (< (:player/stability player) 100))]
    (cond
      (some? stored)
      [stored player]

      (not recovery-eligible?)
      [nil player]

      :else
      (let [result (calculate-stability-recovery player game)]
        (if-not (:triggered? result)
          (do (biff/submit-tx ctx
                              [{:db/doc-type                    :player
                                :db/op                          :update
                                :xt/id                          player-id
                                :player/last-stability-recovery (pr-str result)}])
              [result player])
          (let [new-stability (min 100 (+ (:player/stability player) (:amount result)))]
            (biff/submit-tx ctx
                            [{:db/doc-type                    :player
                              :db/op                          :update
                              :xt/id                          player-id
                              :player/stability               new-stability
                              :player/last-stability-recovery (pr-str result)}])
            [result (assoc player :player/stability new-stability)]))))))

;;;;
;;;; Turn Resolution
;;;;

(defn resolve-turn
  "Resolve all turn events for the player on first outcomes page load; serve cached
  results on refresh. Steps: combat → espionage → population → expense penalty →
  stability breakaway → stability recovery → elimination check.

  After resolution, writes game-event documents for any freshly resolved results.
  Cache-hit results (already persisted on a prior page load) do not produce duplicate
  events — the cache fields on the original player entity are checked before resolution
  to distinguish fresh vs cached.

  Returns a map suitable for passing directly to outcomes/outcomes-page.

  [ctx ring-ctx, player-id uuid, player player-map, game game-map] -> outcomes-map"
  [ctx player-id player game]
  (let [;; Snapshot cache state before resolution to detect fresh results
        combat-cached?    (some? (:player/last-battle-result player))
        espionage-cached? (some? (:player/last-espionage-result player))
        breakaway-cached? (some? (:player/last-stability-breakaway player))

        [battle-result    p1] (resolve-combat              ctx player-id player game)
        [espionage-result p2] (resolve-espionage           ctx player-id p1     game)
        [pop-growth       p3] (resolve-population          ctx player-id p2     game)
        [expense-penalty  p4] (resolve-expense-penalty     ctx player-id p3     game)
        [breakaway-result p5] (resolve-stability-breakaway ctx player-id p4     game)
        ;; Read the original pending penalty (set in expenses phase, not modified by resolution)
        ;; to determine recovery eligibility — zero means expenses were fully paid this turn.
        pending-penalty       (:player/expense-stability-penalty player)
        [recovery-result  p6] (resolve-stability-recovery  ctx player-id p5     game
                                                             pending-penalty breakaway-result)
        total-planets         (+ (:player/ore-planets p6)
                                 (:player/erg-planets  p6)
                                 (:player/mil-planets  p6))
        eliminated?           (zero? total-planets)

        ;; Build event metadata shared by all events this resolution
        event-meta {:game-id (:player/game player)
                    :turn    (:player/current-turn player)
                    :round   (:player/current-round player)
                    :at      (java.util.Date.)}

        ;; Collect events only for freshly resolved (non-cached) results
        events (cond-> []
                 (and battle-result (not combat-cached?))
                 (conj (events/event-of-battle-result battle-result event-meta))

                 (and espionage-result (not espionage-cached?))
                 (into (events/event-of-espionage-result
                         espionage-result
                         (:player/empire-name player)
                         event-meta))

                 (and breakaway-result (:triggered? breakaway-result) (not breakaway-cached?))
                 (conj (events/event-of-breakaway
                         breakaway-result player-id
                         (:player/empire-name player) event-meta))

                 (and eliminated? (not= (:player/status p6) const/player-status-eliminated))
                 (conj (events/event-of-elimination
                         player-id (:player/empire-name player) event-meta)))]

    (when (and eliminated? (not= (:player/status p6) const/player-status-eliminated))
      (biff/submit-tx ctx [{:db/doc-type   :player
                            :db/op         :update
                            :xt/id         player-id
                            :player/status const/player-status-eliminated}]))
    (events/record-events! ctx events)
    {:player           p6
     :game             game
     :battle-result    battle-result
     :espionage-result espionage-result
     :pop-growth       pop-growth
     :expense-penalty  expense-penalty
     :breakaway-result breakaway-result
     :recovery-result  recovery-result
     :eliminated?      eliminated?}))
