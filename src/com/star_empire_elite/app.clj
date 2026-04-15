;;; Development Notes
;;; In order to add a new page <p>, the following changes must be made to app.clj:
;;;  - Import the <p> module at the top require with :as alias
;;;    - This corresponds to a <p>.clj file in the pages/app directory
;;;  - Create a handler function <p>-handler that uses with-player-and-game macro
;;;  - Add a route that maps a URL to the handler

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
            [com.star-empire-elite.combat :as combat]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

;; :: main app dashboard showing all player games
(defn app [{:keys [session biff/db] :as ctx}]
  (dashboard/dashboard ctx))

;; :: show game name prompt
(defn create-game-page [{:keys [session biff/db] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (ui/page
       {}
       [:div.text-green-400.font-mono
        [:h1.text-3xl.font-bold.mb-6 "Create Game"]
        (biff/form
         {:action "/app/create-game"
          :method "post"}
         [:div.mb-4
          [:label.block.text-xs.mb-1 "Galaxy Name"]
          [:input.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono
           {:type "text" :name "game-name" :required true :maxlength 100 :autofocus true}]]
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href "/app"} "Cancel"]
          [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
           {:type "submit"} "Create"]])]))))

;; :: create a new game with the submitted name
(defn create-game [{:keys [session biff/db params] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (let [game-id   (java.util.UUID/randomUUID)
            game-name (clojure.string/trim (or (:game-name params) ""))
            now       (java.util.Date.)
            end-date  (java.util.Date. (+ (.getTime now) (* 30 24 60 60 1000)))]
        (biff/submit-tx ctx
          [{:db/doc-type :game
            :xt/id game-id
            :game/name game-name
            :game/created-at now
            :game/scheduled-end-at end-date
            :game/status 0
            :game/turns-per-round const/turns-per-round
            :game/rounds-per-day const/rounds-per-day
            :game/hours-between-rounds const/hours-between-rounds
            :game/soldier-power  const/soldier-power
            :game/fighter-power  const/fighter-power
            :game/cmd-ship-power const/cmd-ship-power
            :game/station-power  const/station-power
            :game/general-power  const/general-power
            :game/admiral-power  const/admiral-power
            :game/ore-planet-credits const/ore-planet-credits
            :game/ore-planet-fuel const/ore-planet-fuel
            :game/ore-planet-galaxars const/ore-planet-galaxars
            :game/food-planet-food const/food-planet-food
            :game/mil-planet-soldiers const/mil-planet-soldiers
            :game/mil-planet-fighters const/mil-planet-fighters
            :game/mil-planet-stations const/mil-planet-stations
            :game/mil-planet-agents const/mil-planet-agents
            :game/planet-upkeep-credits const/planet-upkeep-credits
            :game/planet-upkeep-food const/planet-upkeep-food
            :game/soldier-upkeep-credits const/soldier-upkeep-credits
            :game/soldier-upkeep-food const/soldier-upkeep-food
            :game/fighter-upkeep-credits const/fighter-upkeep-credits
            :game/fighter-upkeep-fuel const/fighter-upkeep-fuel
            :game/station-upkeep-credits const/station-upkeep-credits
            :game/station-upkeep-fuel const/station-upkeep-fuel
            :game/agent-upkeep-food const/agent-upkeep-food
            :game/agent-upkeep-fuel const/agent-upkeep-fuel
            :game/population-upkeep-food const/population-upkeep-food
            :game/population-upkeep-fuel const/population-upkeep-fuel
            :game/soldier-cost const/soldier-cost
            :game/transport-cost const/transport-cost
            :game/general-cost const/general-cost
            :game/carrier-cost const/carrier-cost
            :game/fighter-cost const/fighter-cost
            :game/admiral-cost const/admiral-cost
            :game/station-cost const/station-cost
            :game/cmd-ship-cost const/cmd-ship-cost
            :game/agent-cost    const/agent-cost
            :game/mil-planet-cost const/mil-planet-cost
            :game/food-planet-cost const/food-planet-cost
            :game/ore-planet-cost const/ore-planet-cost
            :game/soldier-sell    const/soldier-sell
            :game/transport-sell  const/transport-sell
            :game/general-sell    const/general-sell
            :game/fighter-sell    const/fighter-sell
            :game/carrier-sell    const/carrier-sell
            :game/admiral-sell    const/admiral-sell
            :game/station-sell    const/station-sell
            :game/cmd-ship-sell   const/cmd-ship-sell
            :game/agent-sell      const/agent-sell
            :game/mil-planet-sell const/mil-planet-sell
            :game/food-planet-sell const/food-planet-sell
            :game/ore-planet-sell const/ore-planet-sell
            :game/food-buy        const/food-buy
            :game/food-sell       const/food-sell
            :game/fuel-buy        const/fuel-buy
            :game/fuel-sell       const/fuel-sell}])
        {:status 303
         :headers {"location" (str "/app/join-game/" game-id)}}))))

;; :: delete a game and all associated players and messages (admin only)
(defn delete-game [{:keys [session biff/db path-params] :as ctx}]
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

;; :: show empire name prompt for joining a game
(defn join-game-page [{:keys [session biff/db path-params params] :as ctx}]
  (let [game-id (java.util.UUID/fromString (:game-id path-params))
        game (xt/entity db game-id)
        error (:error params)]
    (if (nil? game)
      {:status 404 :body "Game not found"}
      (ui/page
       {}
       [:div.text-green-400.font-mono
        [:h1.text-3xl.font-bold.mb-6 "Join Game"]
        [:p.mb-4 (str "Game: " (:game/name game))]
        (when error
          [:p.text-red-400.mb-4 error])
        (biff/form
         {:action (str "/app/join-game/" game-id)
          :method "post"}
         [:div.mb-4
          [:label.block.text-xs.mb-1 "Empire Name"]
          [:input.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono
           {:type "text" :name "empire-name" :required true :maxlength 50
            :autofocus true}]]
         [:div.flex.gap-4
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href "/app"} "Cancel"]
          [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
           {:type "submit"} "Join"]])]))))

;; :: create player in a game after validating empire name uniqueness
(defn join-game [{:keys [session biff/db path-params params] :as ctx}]
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
          [{:db/doc-type          :player
            :xt/id                player-id
            :player/user          uid
            :player/game          game-id
            :player/empire-name   empire-name
            :player/credits       const/starting-credits
            :player/food          const/starting-food
            :player/fuel          const/starting-fuel
            :player/galaxars      const/starting-galaxars
            :player/mil-planets   const/starting-mil-planets
            :player/food-planets  const/starting-food-planets
            :player/ore-planets   const/starting-ore-planets
            :player/population    const/starting-population
            :player/stability     const/starting-stability
            :player/status        0
            :player/score         0
            :player/current-turn  1
            :player/current-round 1
            :player/current-phase 1
            :player/turns-used    0
            :player/generals      const/starting-generals
            :player/admirals      const/starting-admirals
            :player/soldiers      const/starting-soldiers
            :player/transports    const/starting-transports
            :player/stations      const/starting-stations
            :player/carriers      const/starting-carriers
            :player/fighters      const/starting-fighters
            :player/cmd-ships     const/starting-cmd-ships
            :player/agents        const/starting-agents}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}))))

;;;;
;;;; Phase Handlers - Streamlined using utils/with-player-and-game
;;;;
;;;; Each handler now uses the with-player-and-game macro which:
;;;;  1. Loads player and game entities from the database
;;;;  2. Handles the 404 error case automatically
;;;;  3. Provides clean bindings for player, game, and player-id
;;;;  4. Validates the player is in the correct phase
;;;;  5. Calls the appropriate page function with the entities
;;;;

(defn- cooldown-page [{:keys [player game remaining-ms]}]
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
    ;; Validate phase 1 (income), then check round cooldown, then show page
    (or (utils/validate-phase player 1 player-id)
        (when-let [remaining-ms (utils/round-cooldown-ms player game)]
          (cooldown-page {:player player :game game :remaining-ms remaining-ms}))
        (income/income-page {:player player :game game}))))

(defn expenses-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 2 (expenses), then show page
    (or (utils/validate-phase player 2 player-id)
        (expenses/expenses-page {:player player :game game}))))

(defn exchange-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Exchange is a sub-phase of expenses (phase 2)
    (or (utils/validate-phase player 2 player-id)
        (exchange/exchange-page {:player player :game game}))))

(defn building-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 3 (building), then show page
    (or (utils/validate-phase player 3 player-id)
        (building/building-page {:player player :game game}))))

(defn action-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 4 (action), then show page
    (or (utils/validate-phase player 4 player-id)
        (action/action-page {:player player :game game :db (:biff/db ctx)}))))

(defn espionage-handler [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    ;; Validate phase 5 (espionage), then show page
    (or (utils/validate-phase player 5 player-id)
        (espionage/espionage-page {:player player :game game :db (:biff/db ctx)}))))

(defn outcomes-handler [{:keys [biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 6 player-id)

        ;; --- combat ---
        ;; On first load: resolve, apply ALL stat changes to both sides, store result.
        ;; On refresh (cache hit): stored result already applied — use player as-is.
        (let [pending-attack (:player/pending-attack player)
              stored-battle  (some-> (:player/last-battle-result player) clojure.core/read-string)

              [battle-result display-player]
              (if-not pending-attack
                [nil player]
                (let [defender (xt/entity db pending-attack)]
                  (if (and stored-battle (= (:defender-id stored-battle) (str pending-attack)))
                    ;; Cache hit — changes already applied; player entity is fresh on this request
                    [stored-battle player]
                    ;; Fresh resolve — apply everything now
                    (let [result  (combat/resolve-combat game player defender)
                          al      (:attacker-losses result)
                          dl      (:defender-losses result)
                          raw-pt  (or (:planets-transferred result) {:mil 0 :food 0 :ore 0})
                          pt-mil  (min (:mil  raw-pt) (:player/mil-planets  defender))
                          pt-food (min (:food raw-pt) (:player/food-planets defender))
                          pt-ore  (min (:ore  raw-pt) (:player/ore-planets  defender))
                          capped  (assoc result :planets-transferred {:mil pt-mil :food pt-food :ore pt-ore})
                          att-soldiers   (max 0 (- (:player/soldiers   player) (:soldiers-lost   al)))
                          att-transports (max 0 (- (:player/transports player) (:transports-lost  al)))
                          att-generals   (max 0 (- (:player/generals   player) (:generals-lost   al)))
                          att-fighters   (max 0 (- (:player/fighters   player) (:fighters-lost   al)))
                          att-carriers   (max 0 (- (:player/carriers   player) (:carriers-lost   al)))
                          att-admirals   (max 0 (- (:player/admirals   player) (:admirals-lost   al)))
                          att-cmd-ships  (max 0 (- (:player/cmd-ships  player) (:cmd-ships-lost  al)))
                          att-mil-plts   (+ (:player/mil-planets  player) pt-mil)
                          att-food-plts  (+ (:player/food-planets player) pt-food)
                          att-ore-plts   (+ (:player/ore-planets  player) pt-ore)]
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
                          :player/food-planets att-food-plts
                          :player/ore-planets  att-ore-plts}
                         {:db/doc-type :player :db/op :update :xt/id (:xt/id defender)
                          :player/soldiers     (max 0 (- (:player/soldiers   defender) (:soldiers-lost   dl)))
                          :player/transports   (max 0 (- (:player/transports defender) (:transports-lost dl)))
                          :player/generals     (max 0 (- (:player/generals   defender) (:generals-lost   dl)))
                          :player/fighters     (max 0 (- (:player/fighters   defender) (:fighters-lost   dl)))
                          :player/carriers     (max 0 (- (:player/carriers   defender) (:carriers-lost   dl)))
                          :player/admirals     (max 0 (- (:player/admirals   defender) (:admirals-lost   dl)))
                          :player/cmd-ships    (max 0 (- (:player/cmd-ships  defender) (:cmd-ships-lost  dl)))
                          :player/stations     (max 0 (- (:player/stations   defender) (:stations-lost   dl)))
                          :player/mil-planets  (max 0 (- (:player/mil-planets  defender) pt-mil))
                          :player/food-planets (max 0 (- (:player/food-planets defender) pt-food))
                          :player/ore-planets  (max 0 (- (:player/ore-planets  defender) pt-ore))
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
                                             :player/food-planets att-food-plts
                                             :player/ore-planets  att-ore-plts})]))))

              ;; --- espionage ---
              pending-espionage (:player/pending-espionage player)
              stored-espionage  (some-> (:player/last-espionage-result player) clojure.core/read-string)

              espionage-result
              (when pending-espionage
                (let [target (xt/entity db pending-espionage)]
                  (if (and stored-espionage (= (:defender-id stored-espionage) (str pending-espionage)))
                    stored-espionage
                    (let [result (combat/resolve-espionage player target)]
                      (biff/submit-tx ctx
                        (remove nil?
                          [{:db/doc-type :player :db/op :update :xt/id player-id
                            :player/last-espionage-result (pr-str result)}
                           (when-not (:attacker-wins? result)
                             {:db/doc-type :player :db/op :update :xt/id (:xt/id target)
                              :player/incoming-espionage-fails
                              (inc (or (:player/incoming-espionage-fails target) 0))})]))
                      result))))]

          (outcomes/outcomes-page {:player           display-player
                                   :game             game
                                   :battle-result    battle-result
                                   :espionage-result espionage-result})))))

;; :: HTMX fragment — polls for incoming alerts; triggers HX-Refresh when new alerts arrive
(defn alerts-handler [{:keys [biff/db path-params params] :as ctx}]
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

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["" {:get app}]
            ["/create-game" {:get create-game-page :post create-game}]
            ["/delete-game/:game-id" {:post delete-game}]
            ["/join-game/:game-id" {:get join-game-page :post join-game}]
            ["/game/:player-id" {:get game/game-view}]
            ["/game/:player-id/income" {:get income-handler}]
            ["/game/:player-id/apply-income" {:post income/apply-income}]
            ["/game/:player-id/expenses" {:get expenses-handler}]
            ["/game/:player-id/apply-expenses" {:post expenses/apply-expenses}]
            ["/game/:player-id/calculate-expenses" {:post expenses/calculate-expenses}]
            ["/game/:player-id/exchange" {:get exchange-handler}]
            ["/game/:player-id/apply-exchange" {:post exchange/apply-exchange}]
            ["/game/:player-id/calculate-exchange" {:post exchange/calculate-exchange}]
            ["/game/:player-id/building" {:get building-handler}]
            ["/game/:player-id/apply-building" {:post building/apply-building}]
            ["/game/:player-id/calculate-building" {:post building/calculate-building}]
            ["/game/:player-id/action" {:get action-handler}]
            ["/game/:player-id/apply-action" {:post action/apply-action}]
            ["/game/:player-id/espionage" {:get espionage-handler}]
            ["/game/:player-id/apply-espionage" {:post espionage/apply-espionage}]
            ["/game/:player-id/outcomes" {:get outcomes-handler}]
            ["/game/:player-id/apply-outcomes" {:post outcomes/apply-outcomes}]
            ["/game/:player-id/alerts" {:get alerts-handler}]
            ]})
