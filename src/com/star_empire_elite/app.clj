;;;;;
;;;;; App - Route Handlers and Game Management
;;;;;
;;;;; Central handler module wiring all game phases to routes. Contains admin-only game management
;;;;; (create, delete), the player join flow, phase GET handlers that validate the current phase
;;;;; and delegate to their respective page functions, and the outcomes handler which resolves
;;;;; combat, espionage, population growth, stability events, and elimination inline on first load.
;;;;;
;;;;; To add a new page <p>:
;;;;;  - Import the <p> module at the top require with :as alias
;;;;;    - This corresponds to a <p>.clj file in the pages/app directory
;;;;;  - Create a handler function <p>-handler using with-player-and-game
;;;;;  - Add a route in the module map
;;;;;

(ns com.star-empire-elite.app
  (:require [rum.core :as rum]
            [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.pages.app.dashboard :as dashboard]
            [com.star-empire-elite.pages.app.game :as game]
            [com.star-empire-elite.pages.app.income :as income]
            [com.star-empire-elite.pages.app.expenses :as expenses]
            [com.star-empire-elite.pages.app.exchange :as exchange]
            [com.star-empire-elite.pages.app.building :as building]
            [com.star-empire-elite.pages.app.action :as action]
            [com.star-empire-elite.pages.app.espionage :as espionage]
            [com.star-empire-elite.pages.app.outcomes :as outcomes]
            [com.star-empire-elite.pages.app.eliminated :as eliminated]
            [com.star-empire-elite.combat :as combat]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [com.star-empire-elite.log :as gamelog]
            [xtdb.api :as xt]))

;;;;
;;;; Dashboard
;;;;

(defn app
  "Render the main app dashboard showing all games the current user has joined.

  [ctx ring-ctx] -> hiccup"
  [{:keys [session biff/db] :as ctx}]
  (dashboard/dashboard ctx))

;;;;
;;;; Game Management
;;;;

(defn create-game-page
  "Show the create-game form (admin only). Redirects non-admins to /app.

  [ctx ring-ctx] -> hiccup"
  [{:keys [session biff/db] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (ui/page
       {}
       [:div.text-base.w-full.max-w-2xl.mx-auto.overflow-hidden.relative
        {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
                 :border-radius "4px" :color "#4ade80"
                 :font-family "'Courier New', monospace"}}
        (ui/scanline-overlay)
        [:div.flex.items-center
         {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "7px 14px"}}
         [:div {:style {:font-size "18px" :font-weight "bold" :color "#4ade80"
                        :letter-spacing "0.05em"}} "CREATE GAME"]]
        [:div {:style {:padding "14px"}}
         (biff/form
          {:action "/app/create-game"
           :method "post"}
          [:div {:style {:margin-bottom "14px"}}
           (ui/section-label "Galaxy Name")
           [:input.w-full
            {:type "text" :name "game-name" :required true :maxlength 100 :autofocus true
             :style {:background "#0e1810" :border "1px solid #1e6e44" :color "#4ade80"
                     :padding "6px 8px" :font-family "'Courier New', monospace"
                     :font-size "14px" :border-radius "2px" :outline "none" :width "100%"}}]]
          [:div.flex.gap-3
           (ui/action-bar-link "/app" "Cancel")
           (ui/submit-button true "Create")])]]))))

(defn create-game
  "Create a new game entity with all constants baked in. Admin only.

  [ctx ring-ctx] -> ring-response (303 redirect to join-game page)"
  [{:keys [session biff/db params] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (let [game-id   (java.util.UUID/randomUUID)
            game-name (clojure.string/trim (or (:game-name params) ""))
            now       (java.util.Date.)
            end-date  (java.util.Date. (+ (.getTime now) (* 30 24 60 60 1000)))]
        (biff/submit-tx ctx
          [{:db/doc-type                        :game
            :xt/id                              game-id
            :game/name                          game-name
            :game/created-at                    now
            :game/scheduled-end-at              end-date
            :game/status                        0
            :game/turns-per-round               const/turns-per-round
            :game/rounds-per-day                const/rounds-per-day
            :game/hours-between-rounds          const/hours-between-rounds
            :game/raid-defense-multiplier       const/raid-defense-multiplier
            :game/raid-reward-multiplier        const/raid-reward-multiplier
            :game/raid-planet-capture-rate      const/raid-planet-capture-rate
            :game/invade-defense-multiplier     const/invade-defense-multiplier
            :game/invade-reward-multiplier      const/invade-reward-multiplier
            :game/strike-damage-rate            const/strike-damage-rate
            :game/strike-max-dispatch           const/strike-max-dispatch
            :game/strike-interception-rate      const/strike-interception-rate
            :game/strike-interception-cap       const/strike-interception-cap
            :game/defect-defense-multiplier     const/defect-defense-multiplier
            :game/defect-transfer-rate          const/defect-transfer-rate
            :game/defect-transfer-cap           const/defect-transfer-cap
            :game/soldier-power                 const/soldier-power
            :game/fighter-power                 const/fighter-power
            :game/cmd-ship-power                const/cmd-ship-power
            :game/station-power                 const/station-power
            :game/general-power                 const/general-power
            :game/admiral-power                 const/admiral-power
            :game/ore-planet-credits            const/ore-planet-credits
            :game/erg-planet-food               const/erg-planet-food
            :game/erg-planet-fuel               const/erg-planet-fuel
            :game/mil-planet-soldiers           const/mil-planet-soldiers
            :game/mil-planet-fighters           const/mil-planet-fighters
            :game/mil-planet-stations           const/mil-planet-stations
            :game/population-tax-credits        const/population-tax-credits
            :game/planet-upkeep-credits         const/planet-upkeep-credits
            :game/planet-upkeep-food            const/planet-upkeep-food
            :game/soldier-upkeep-credits        const/soldier-upkeep-credits
            :game/soldier-upkeep-food           const/soldier-upkeep-food
            :game/fighter-upkeep-credits        const/fighter-upkeep-credits
            :game/fighter-upkeep-fuel           const/fighter-upkeep-fuel
            :game/station-upkeep-credits        const/station-upkeep-credits
            :game/station-upkeep-fuel           const/station-upkeep-fuel
            :game/agent-upkeep-food             const/agent-upkeep-food
            :game/agent-upkeep-fuel             const/agent-upkeep-fuel
            :game/population-upkeep-food        const/population-upkeep-food
            :game/population-upkeep-fuel        const/population-upkeep-fuel
            :game/soldier-cost                  const/soldier-cost
            :game/transport-cost                const/transport-cost
            :game/general-cost                  const/general-cost
            :game/carrier-cost                  const/carrier-cost
            :game/fighter-cost                  const/fighter-cost
            :game/admiral-cost                  const/admiral-cost
            :game/station-cost                  const/station-cost
            :game/cmd-ship-cost                 const/cmd-ship-cost
            :game/agent-cost                    const/agent-cost
            :game/mil-planet-cost               const/mil-planet-cost
            :game/erg-planet-cost               const/erg-planet-cost
            :game/ore-planet-cost               const/ore-planet-cost
            :game/soldier-sell                  const/soldier-sell
            :game/transport-sell                const/transport-sell
            :game/general-sell                  const/general-sell
            :game/fighter-sell                  const/fighter-sell
            :game/carrier-sell                  const/carrier-sell
            :game/admiral-sell                  const/admiral-sell
            :game/station-sell                  const/station-sell
            :game/cmd-ship-sell                 const/cmd-ship-sell
            :game/agent-sell                    const/agent-sell
            :game/mil-planet-sell               const/mil-planet-sell
            :game/erg-planet-sell               const/erg-planet-sell
            :game/ore-planet-sell               const/ore-planet-sell
            :game/food-buy                      const/food-buy
            :game/food-sell                     const/food-sell
            :game/fuel-buy                      const/fuel-buy
            :game/fuel-sell                     const/fuel-sell
            :game/expense-stability-penalty     const/expense-stability-penalty
            :game/stability-breakaway-threshold const/stability-breakaway-threshold
            :game/stability-breakaway-cap       const/stability-breakaway-cap
            :game/stability-recovery-amount     const/stability-recovery-amount
            :game/stability-recovery-floor      const/stability-recovery-floor}])
        {:status 303
         :headers {"location" (str "/app/join-game/" game-id)}}))))

(defn delete-game
  "Delete a game and all associated players (admin only).

  [ctx ring-ctx] -> ring-response (303 redirect to /app)"
  [{:keys [session biff/db path-params] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (let [game-id  (java.util.UUID/fromString (:game-id path-params))
            players  (q db '{:find [player]
                             :in [game-id]
                             :where [[player :player/game game-id]]}
                         game-id)
            messages (q db '{:find [msg]
                             :in [game-id]
                             :where [[msg :message/game game-id]]}
                         game-id)]
        (biff/submit-tx ctx
          (concat
            (for [[player-id] players] {:db/op :delete :xt/id player-id})
            (for [[msg-id]    messages] {:db/op :delete :xt/id msg-id})
            [{:db/op :delete :xt/id game-id}]))
        {:status 303 :headers {"location" "/app"}}))))

;;;;
;;;; Join Game
;;;;

(defn join-game-page
  "Show the empire name prompt for joining a game.

  [ctx ring-ctx] -> hiccup"
  [{:keys [session biff/db path-params params] :as ctx}]
  (let [game-id (java.util.UUID/fromString (:game-id path-params))
        game (xt/entity db game-id)
        error (:error params)]
    (if (nil? game)
      {:status 404 :body "Game not found"}
      (ui/page
       {}
       [:div.text-base.w-full.max-w-2xl.mx-auto.overflow-hidden.relative
        {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
                 :border-radius "4px" :color "#4ade80"
                 :font-family "'Courier New', monospace"}}
        (ui/scanline-overlay)
        [:div.flex.items-center
         {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "7px 14px"}}
         [:div
          [:div {:style {:font-size "18px" :font-weight "bold" :color "#4ade80"
                         :letter-spacing "0.05em"}} "JOIN GAME"]
          [:div.text-sm.mt-px {:style {:color "#9adaaa"}} (:game/name game)]]]
        [:div {:style {:padding "14px"}}
         (when error
           [:div {:style {:color "#f87171" :font-size "13px" :margin-bottom "10px"}} error])
         (biff/form
          {:action (str "/app/join-game/" game-id)
           :method "post"}
          [:div {:style {:margin-bottom "14px"}}
           (ui/section-label "Empire Name")
           [:input.w-full
            {:type "text" :name "empire-name" :required true :maxlength 50 :autofocus true
             :style {:background "#0e1810" :border "1px solid #1e6e44" :color "#4ade80"
                     :padding "6px 8px" :font-family "'Courier New', monospace"
                     :font-size "14px" :border-radius "2px" :outline "none" :width "100%"}}]]
          [:div.flex.gap-3
           (ui/action-bar-link "/app" "Cancel")
           (ui/submit-button true "Join")])]]))))

(defn join-game
  "Create a player entity in the chosen game after validating empire name uniqueness.

  [ctx ring-ctx] -> ring-response (303 redirect to game overview)"
  [{:keys [session biff/db path-params params] :as ctx}]
  (let [game-id (java.util.UUID/fromString (:game-id path-params))
        game (xt/entity db game-id)
        empire-name (clojure.string/trim (or (:empire-name params) ""))
        uid (:uid session)]
    (cond
      (nil? game)
      {:status 404 :body "Game not found"}

      (clojure.string/blank? empire-name)
      {:status 303
       :headers {"location" (str "/app/join-game/" game-id "?error=Empire+name+cannot+be+blank")}}

      ;; check if another user already has this empire name in any game
      (seq (q db '{:find [player]
                   :in [empire-name uid]
                   :where [[player :player/empire-name empire-name]
                           [player :player/user other-user]
                           [(not= other-user uid)]]}
              empire-name uid))
      {:status 303
       :headers {"location" (str "/app/join-game/" game-id "?error=That+empire+name+is+already+taken")}}

      :else
      (let [player-id (java.util.UUID/randomUUID)]
        (biff/submit-tx ctx
          [{:db/doc-type                    :player
            :xt/id                          player-id
            :player/user                    uid
            :player/game                    game-id
            :player/empire-name             empire-name
            :player/credits                 const/starting-credits
            :player/food                    const/starting-food
            :player/fuel                    const/starting-fuel
            :player/galaxars                const/starting-galaxars
            :player/mil-planets             const/starting-mil-planets
            :player/erg-planets             const/starting-erg-planets
            :player/ore-planets             const/starting-ore-planets
            :player/population              const/starting-population
            :player/stability               const/starting-stability
            :player/status                  0
            :player/score                   0
            :player/current-turn            1
            :player/current-round           1
            :player/current-phase           1
            :player/turns-used              0
            :player/generals                const/starting-generals
            :player/admirals                const/starting-admirals
            :player/soldiers                const/starting-soldiers
            :player/transports              const/starting-transports
            :player/stations                const/starting-stations
            :player/carriers                const/starting-carriers
            :player/fighters                const/starting-fighters
            :player/cmd-ships               const/starting-cmd-ships
            :player/agents                  const/starting-agents
            :player/last-population-growth  nil}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}))))

;;;;
;;;; Phase Handlers
;;;;
;;;; Each handler uses the with-player-and-game macro which:
;;;;  1. Loads player and game entities from the database
;;;;  2. Handles the 404 error case automatically
;;;;  3. Provides clean bindings for player, game, and player-id
;;;;  4. Validates the player is in the correct phase
;;;;  5. Calls the appropriate page function with the entities
;;;;

(defn- cooldown-page
  "Render the between-rounds waiting screen, showing time until the next round or midnight UTC.

  [{:keys [player game remaining-ms]}] -> hiccup"
  [{:keys [player game remaining-ms]}]
  (let [day-exhausted? (utils/day-exhausted? player game)]
    (ui/page
     {}
     [:div.text-green-400.font-mono
      [:h1.text-3xl.font-bold.mb-6 (:player/empire-name player)]
      [:div.border.border-yellow-400.p-6.mb-6.bg-yellow-400.bg-opacity-5
       [:h2.text-xl.font-bold.mb-4.text-yellow-400 (if day-exhausted? "ALL ROUNDS COMPLETE" "ROUND COOLDOWN")]
       [:p.mb-4 (if day-exhausted?
                  "You have played all rounds for today. Rounds reset at midnight UTC."
                  "Your empire needs time to consolidate before the next round.")]
       [:p.text-2xl.font-bold.text-yellow-400.mb-2
        (utils/format-cooldown-duration remaining-ms)]
       [:p.text-sm (if day-exhausted? "Time remaining until midnight UTC" "Time remaining before next round")]
       (when-not day-exhausted?
         [:p.text-xs.mt-2 (str "Minimum " (:game/hours-between-rounds game) " hours between rounds.")])]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
       {:href (str "/app/game/" (:xt/id player))} "Back to Overview"]])))

(defn income-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 1 player-id)
        (when-let [remaining-ms (utils/round-cooldown-ms player game)]
          (cooldown-page {:player player :game game :remaining-ms remaining-ms}))
        (income/income-page {:player player :game game}))))

(defn expenses-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 2 player-id)
        (expenses/expenses-page {:player player :game game}))))

(defn exchange-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Exchange is a sub-phase of expenses (phase 2)
    (or (utils/validate-phase player 2 player-id)
        (exchange/exchange-page {:player player :game game}))))

(defn building-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 3 player-id)
        (building/building-page {:player player :game game}))))

(defn action-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 4 player-id)
        (action/action-page {:player player :game game :db (:biff/db ctx)}))))

(defn espionage-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 5 player-id)
        (espionage/espionage-page {:player player :game game :db (:biff/db ctx)}))))

(defn outcomes-handler
  "Resolve all turn events on first load (combat, espionage, population, stability, elimination),
  cache results in the player entity, then render the outcomes page. On refresh, cached results
  are used without re-rolling.

  [ctx ring-ctx] -> hiccup"
  [{:keys [biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 6 player-id)

        ;; --- combat ---
        ;; On first load: resolve, apply ALL stat changes to both sides, store result.
        ;; On refresh (cache hit): stored result already applied — use player as-is.
        (let [pending-attack (:player/pending-attack player)
              mode           (:player/pending-attack-mode player)
              stored-battle  (some-> (:player/last-battle-result player) clojure.core/read-string)

              [battle-result display-player]
              (if-not pending-attack
                [nil player]
                (let [defender (xt/entity db pending-attack)]
                  (if (and stored-battle (= (:defender-id stored-battle) (str pending-attack)))
                    ;; Cache hit — changes already applied; player entity is fresh on this request
                    [stored-battle player]
                    ;; Fresh resolve — branch on mode
                    (if (= mode :strike)
                      ;; --- Strike branch ---
                      (let [result     (combat/resolve-strike game player defender)
                            ud         (:units-destroyed result)
                            ships-lost (:cmd-ships-lost result)]
                        (biff/submit-tx ctx
                          [{:db/doc-type :player :db/op :update :xt/id player-id
                            :player/last-battle-result (pr-str result)
                            :player/cmd-ships (max 0 (- (:player/cmd-ships player) ships-lost))}
                           {:db/doc-type :player :db/op :update :xt/id (:xt/id defender)
                            :player/soldiers   (max 0 (- (:player/soldiers   defender) (:soldiers   ud)))
                            :player/transports (max 0 (- (:player/transports defender) (:transports ud)))
                            :player/generals   (max 0 (- (:player/generals   defender) (:generals   ud)))
                            :player/fighters   (max 0 (- (:player/fighters   defender) (:fighters   ud)))
                            :player/carriers   (max 0 (- (:player/carriers   defender) (:carriers   ud)))
                            :player/admirals   (max 0 (- (:player/admirals   defender) (:admirals   ud)))
                            :player/incoming-attacks
                            (conj (or (:player/incoming-attacks defender) []) (pr-str result))}])
                        [result (assoc player :player/cmd-ships
                                       (max 0 (- (:player/cmd-ships player) ships-lost)))])
                      ;; --- Invade / Raid branch ---
                      (let [result  (combat/resolve-combat game player defender mode)
                            al      (:attacker-losses result)
                            dl      (:defender-losses result)
                            raw-pt  (or (:planets-transferred result) {:mil 0 :erg 0 :ore 0})
                            pt-mil  (min (:mil raw-pt) (:player/mil-planets defender))
                            pt-erg  (min (:erg raw-pt) (:player/erg-planets defender))
                            pt-ore  (min (:ore raw-pt) (:player/ore-planets defender))
                            capped  (assoc result :planets-transferred {:mil pt-mil :erg pt-erg :ore pt-ore})
                            rc           (or (:resources-captured capped) {:credits 0 :food 0 :fuel 0})
                            rc-credits   (:credits rc)
                            rc-food      (:food    rc)
                            rc-fuel      (:fuel    rc)
                            att-soldiers   (max 0 (- (:player/soldiers   player) (:soldiers-lost   al)))
                            att-transports (max 0 (- (:player/transports player) (:transports-lost  al)))
                            att-generals   (max 0 (- (:player/generals   player) (:generals-lost   al)))
                            att-fighters   (max 0 (- (:player/fighters   player) (:fighters-lost   al)))
                            att-carriers   (max 0 (- (:player/carriers   player) (:carriers-lost   al)))
                            att-admirals   (max 0 (- (:player/admirals   player) (:admirals-lost   al)))
                            att-cmd-ships  (max 0 (- (:player/cmd-ships  player) (:cmd-ships-lost  al)))
                            att-mil-plts   (+ (:player/mil-planets  player) pt-mil)
                            att-erg-plts   (+ (:player/erg-planets player) pt-erg)
                            att-ore-plts   (+ (:player/ore-planets  player) pt-ore)
                            att-credits    (+ (:player/credits player) rc-credits)
                            att-food-res   (+ (:player/food    player) rc-food)
                            att-fuel-res   (+ (:player/fuel    player) rc-fuel)]
                        (biff/submit-tx ctx
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
                            :player/erg-planets att-erg-plts
                            :player/ore-planets  att-ore-plts
                            :player/credits      att-credits
                            :player/food         att-food-res
                            :player/fuel         att-fuel-res}
                           {:db/doc-type :player :db/op :update :xt/id (:xt/id  defender)
                            :player/soldiers     (max 0 (- (:player/soldiers    defender) (:soldiers-lost   dl)))
                            :player/transports   (max 0 (- (:player/transports  defender) (:transports-lost dl)))
                            :player/generals     (max 0 (- (:player/generals    defender) (:generals-lost   dl)))
                            :player/fighters     (max 0 (- (:player/fighters    defender) (:fighters-lost   dl)))
                            :player/carriers     (max 0 (- (:player/carriers    defender) (:carriers-lost   dl)))
                            :player/admirals     (max 0 (- (:player/admirals    defender) (:admirals-lost   dl)))
                            :player/cmd-ships    (max 0 (- (:player/cmd-ships   defender) (:cmd-ships-lost  dl)))
                            :player/stations     (max 0 (- (:player/stations    defender) (:stations-lost   dl)))
                            :player/mil-planets  (max 0 (- (:player/mil-planets defender) pt-mil))
                            :player/erg-planets  (max 0 (- (:player/erg-planets defender) pt-erg))
                            :player/ore-planets  (max 0 (- (:player/ore-planets defender) pt-ore))
                            :player/credits      (max 0 (- (:player/credits     defender) rc-credits))
                            :player/food         (max 0 (- (:player/food        defender) rc-food))
                            :player/fuel         (max 0 (- (:player/fuel        defender) rc-fuel))
                            :player/incoming-attacks
                            (conj (or (:player/incoming-attacks defender) []) (pr-str capped))}])
                        ;; Build locally updated player for accurate resource display this request
                        [capped (merge player {:player/soldiers     att-soldiers
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
                                               :player/food         att-food-res
                                               :player/fuel         att-fuel-res})])))))

              ;; --- espionage ---
              pending-espionage    (:player/pending-espionage player)
              pending-espionage-op (:player/pending-espionage-op player)
              stored-espionage     (some-> (:player/last-espionage-result player) clojure.core/read-string)

              [espionage-result display-player]
              (if-not pending-espionage
                [nil display-player]
                (let [target (xt/entity db pending-espionage)]
                  (if (and stored-espionage (= (:defender-id stored-espionage) (str pending-espionage)))
                    ;; Cache hit — changes already applied on first load
                    [stored-espionage display-player]
                    ;; Fresh resolve
                    (let [incite?     (= pending-espionage-op "incite")
                          bomb?       (= pending-espionage-op "bomb")
                          defect?     (= pending-espionage-op "defect")
                          result      (cond
                                        incite?  (combat/resolve-incite  player target)
                                        bomb?    (combat/resolve-bomb    player target)
                                        defect?  (combat/resolve-defect  game player target)
                                        :else    (combat/resolve-espionage player target))
                          att-wins?   (:attacker-wins? result)
                          agents-lost (or (:agents-captured result) 0)
                          stab-dmg    (or (:stability-damage result) 0)]
                      (biff/submit-tx ctx
                        (remove nil?
                          [{:db/doc-type :player :db/op :update :xt/id player-id
                            :player/last-espionage-result (pr-str result)
                            :player/agents (max 0 (- (:player/agents display-player) agents-lost))}
                           (when (pos? agents-lost)
                             {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                              :player/agents (+ (:player/agents target) agents-lost)
                              :player/incoming-espionage-fails
                              (inc (or (:player/incoming-espionage-fails target) 0))
                              :player/incoming-espionage-agents-gained
                              (+ (or (:player/incoming-espionage-agents-gained target) 0) agents-lost)})
                           (when (and incite? att-wins? (pos? stab-dmg))
                             {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                              :player/stability (max 0 (- (:player/stability target) stab-dmg))
                              :player/incoming-incite-stability-lost
                              (+ (or (:player/incoming-incite-stability-lost target) 0) stab-dmg)})
                           (when (and bomb? att-wins?)
                             (let [sd (or (:soldiers-destroyed   result) 0)
                                   td (or (:transports-destroyed result) 0)
                                   fd (or (:fighters-destroyed   result) 0)
                                   cd (or (:carriers-destroyed   result) 0)]
                               {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                                :player/soldiers   (max 0 (- (:player/soldiers   target) sd))
                                :player/transports (max 0 (- (:player/transports target) td))
                                :player/fighters   (max 0 (- (:player/fighters   target) fd))
                                :player/carriers   (max 0 (- (:player/carriers   target) cd))
                                :player/incoming-bomb-result
                                (pr-str {:soldiers-destroyed   sd
                                         :transports-destroyed td
                                         :fighters-destroyed   fd
                                         :carriers-destroyed   cd})}))
                           (when (and defect? att-wins?)
                             (let [n (or (:agents-defected result) 0)]
                               {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                                :player/agents (max 0 (- (or (:player/agents target) 0) n))}))]))
                      (let [agents-gained (if (and defect? att-wins?)
                                            (or (:agents-defected result) 0)
                                            0)]
                        [result (assoc display-player :player/agents
                                       (max 0 (+ (- (:player/agents display-player) agents-lost)
                                                 agents-gained)))])))))

              ;; --- population growth (end of round only) ---
              end-round?  (>= (:player/current-turn display-player) (:game/turns-per-round game))
              [pop-growth final-player]
              (if-not end-round?
                [nil display-player]
                (let [cached (:player/last-population-growth display-player)]
                  (if (some? cached)
                    [cached display-player]
                    (let [pop      (:player/population display-player)
                          planets  (+ (:player/ore-planets  display-player)
                                      (:player/erg-planets display-player)
                                      (:player/mil-planets  display-player))
                          capacity (* planets const/pop-capacity-per-planet)
                          raw      (+ (* pop const/pop-growth-rate)
                                      (* planets const/pop-growth-per-planet))
                          crowding (if (zero? capacity) 0.0
                                     (max 0.0 (- 1.0 (/ pop capacity))))
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
                      [growth (-> display-player
                                  (assoc :player/population             (+ pop growth))
                                  (assoc :player/last-population-growth growth))]))))

              ;; --- expense stability penalty ---
              pending-penalty (:player/expense-stability-penalty final-player)
              last-penalty    (:player/last-expense-stability-penalty final-player)

              [expense-penalty final-player-2]
              (cond
                ;; Cache hit — already applied on a previous load this turn
                (some? last-penalty)
                [last-penalty final-player]

                ;; No penalty this turn
                (or (nil? pending-penalty) (zero? pending-penalty))
                [nil final-player]

                ;; Fresh apply
                :else
                (let [new-stability (max 0 (- (:player/stability final-player) pending-penalty))]
                  (biff/submit-tx ctx
                    [{:db/doc-type                            :player
                      :db/op                                  :update
                      :xt/id                                  player-id
                      :player/stability                       new-stability
                      :player/last-expense-stability-penalty  pending-penalty
                      :player/expense-stability-penalty       nil}])
                  [pending-penalty (-> final-player
                                       (assoc :player/stability new-stability)
                                       (assoc :player/last-expense-stability-penalty pending-penalty))]))

              ;; --- stability breakaway ---
              stored-breakaway (some-> (:player/last-stability-breakaway final-player-2)
                                       clojure.core/read-string)

              [breakaway-result final-player-3]
              (if (some? stored-breakaway)
                ;; Cache hit — already resolved this turn
                [stored-breakaway final-player-2]
                ;; Fresh roll
                (let [result (outcomes/calculate-stability-breakaway final-player-2 game)]
                  (if-not (:triggered? result)
                    (do
                      (biff/submit-tx ctx
                        [{:db/doc-type                    :player
                          :db/op                          :update
                          :xt/id                          player-id
                          :player/last-stability-breakaway (pr-str result)}])
                      [result final-player-2])
                    (let [ore-after (- (:player/ore-planets final-player-2) (:ore-lost result))
                          erg-after (- (:player/erg-planets final-player-2) (:erg-lost result))
                          mil-after (- (:player/mil-planets final-player-2) (:mil-lost result))]
                      (biff/submit-tx ctx
                        [{:db/doc-type                     :player
                          :db/op                           :update
                          :xt/id                           player-id
                          :player/ore-planets              ore-after
                          :player/erg-planets              erg-after
                          :player/mil-planets              mil-after
                          :player/last-stability-breakaway (pr-str result)}])
                      [result (-> final-player-2
                                  (assoc :player/ore-planets ore-after)
                                  (assoc :player/erg-planets erg-after)
                                  (assoc :player/mil-planets mil-after))]))))

              ;; --- stability recovery ---
              ;; Eligible only when expenses were fully paid (pending-penalty = 0, not nil)
              ;; and no breakaway triggered this turn.
              stored-recovery  (some-> (:player/last-stability-recovery final-player-3)
                                       clojure.core/read-string)
              recovery-eligible? (and (some? pending-penalty)
                                      (zero? pending-penalty)
                                      (not (:triggered? breakaway-result))
                                      (< (:player/stability final-player-3) 100))

              [recovery-result final-player-4]
              (cond
                ;; Cache hit — already resolved this turn
                (some? stored-recovery)
                [stored-recovery final-player-3]

                ;; Not eligible — skip without rolling
                (not recovery-eligible?)
                [nil final-player-3]

                ;; Fresh roll
                :else
                (let [result (outcomes/calculate-stability-recovery final-player-3 game)]
                  (if-not (:triggered? result)
                    (do
                      (biff/submit-tx ctx
                        [{:db/doc-type                    :player
                          :db/op                          :update
                          :xt/id                          player-id
                          :player/last-stability-recovery (pr-str result)}])
                      [result final-player-3])
                    (let [new-stability (min 100 (+ (:player/stability final-player-3) (:amount result)))]
                      (biff/submit-tx ctx
                        [{:db/doc-type                    :player
                          :db/op                          :update
                          :xt/id                          player-id
                          :player/stability               new-stability
                          :player/last-stability-recovery (pr-str result)}])
                      [result (assoc final-player-3 :player/stability new-stability)]))))]

          ;; --- elimination check ---
          (let [total-planets (+ (:player/ore-planets final-player-4)
                                 (:player/erg-planets final-player-4)
                                 (:player/mil-planets final-player-4))
                eliminated?   (zero? total-planets)]
            (when (and eliminated?
                       (not= (:player/status final-player-4) const/player-status-eliminated))
              (biff/submit-tx ctx
                [{:db/doc-type   :player
                  :db/op         :update
                  :xt/id         player-id
                  :player/status const/player-status-eliminated}]))
            (outcomes/outcomes-page {:player           final-player-4
                                     :game             game
                                     :battle-result    battle-result
                                     :espionage-result espionage-result
                                     :pop-growth       pop-growth
                                     :expense-penalty  expense-penalty
                                     :breakaway-result breakaway-result
                                     :recovery-result  recovery-result
                                     :eliminated?      eliminated?}))))))

;;;;
;;;; Alerts
;;;;

(defn alerts-handler
  "HTMX polling fragment — checks for incoming attacks or espionage failures.
  Returns HX-Refresh to reload the page when new alerts arrive.

  [ctx ring-ctx] -> ring-response (200 with HTML fragment)"
  [{:keys [biff/db path-params params] :as ctx}]
  (let [player-id  (java.util.UUID/fromString (:player-id path-params))
        player     (xt/entity db player-id)
        had-alerts (= "true" (:had-alerts params))
        has-alerts (or (seq (:player/incoming-attacks player))
                       (pos? (or (:player/incoming-espionage-fails player) 0)))]
    (if (and has-alerts (not had-alerts))
      {:status 200 :headers {"content-type" "text/html" "HX-Refresh" "true"} :body ""}
      {:status  200
       :headers {"content-type" "text/html"}
       :body    (rum/render-static-markup (ui/incoming-alert-content player))})))

;;;;
;;;; Routes
;;;;

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in gamelog/wrap-game-action-log]}
            ["" {:get app}]
            ["/create-game"                        {:get create-game-page :post create-game}]
            ["/delete-game/:game-id"               {:post delete-game}]
            ["/join-game/:game-id"                 {:get join-game-page :post join-game}]
            ["/game/:player-id"                    {:get game/game-view}]
            ["/game/:player-id/income"             {:get income-handler}]
            ["/game/:player-id/apply-income"       {:post income/apply-income}]
            ["/game/:player-id/expenses"           {:get expenses-handler}]
            ["/game/:player-id/apply-expenses"     {:post expenses/apply-expenses}]
            ["/game/:player-id/calculate-expenses" {:post expenses/calculate-expenses}]
            ["/game/:player-id/exchange"           {:get exchange-handler}]
            ["/game/:player-id/apply-exchange"     {:post exchange/apply-exchange}]
            ["/game/:player-id/calculate-exchange" {:post exchange/calculate-exchange}]
            ["/game/:player-id/building"           {:get building-handler}]
            ["/game/:player-id/apply-building"     {:post building/apply-building}]
            ["/game/:player-id/calculate-building" {:post building/calculate-building}]
            ["/game/:player-id/action"             {:get action-handler}]
            ["/game/:player-id/apply-action"       {:post action/apply-action}]
            ["/game/:player-id/espionage"          {:get espionage-handler}]
            ["/game/:player-id/apply-espionage"    {:post espionage/apply-espionage}]
            ["/game/:player-id/outcomes"           {:get outcomes-handler}]
            ["/game/:player-id/apply-outcomes"     {:post outcomes/apply-outcomes}]
            ["/game/:player-id/eliminated"         {:get eliminated/eliminated-page}]
            ["/game/:player-id/rejoin"             {:post eliminated/rejoin}]
            ["/game/:player-id/alerts"             {:get alerts-handler}]
            ]})
