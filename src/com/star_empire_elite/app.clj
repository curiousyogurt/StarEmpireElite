;;;;;
;;;;; App - Route Handlers and Game Management
;;;;;
;;;;; Central handler module wiring all game phases to routes. Contains admin-only game management
;;;;; (create, delete), the player join flow, phase GET handlers that validate the current phase
;;;;; and delegate to their respective page functions, and the outcomes handler which delegates
;;;;; turn resolution to resolution/resolve-turn.
;;;;;
;;;;; To add a new page <p>:
;;;;;  - Import the <p> module into the ns with the :as alias
;;;;;    - This corresponds to a <p>.clj file in the pages/app directory
;;;;;  - Create a handler function <p>-handler using with-player-and-game
;;;;;  - Add a route in the module map
;;;;;

(ns com.star-empire-elite.app
  (:require [rum.core                                   :as rum]
            [com.biffweb                                :as biff :refer [q]]
            [com.star-empire-elite.middleware           :as mid]
            ;; Game pages
            [com.star-empire-elite.pages.app.dashboard  :as dashboard]
            [com.star-empire-elite.pages.app.game       :as game]
            [com.star-empire-elite.pages.app.income     :as income]
            [com.star-empire-elite.pages.app.expenses   :as expenses]
            [com.star-empire-elite.pages.app.exchange   :as exchange]
            [com.star-empire-elite.pages.app.building   :as building]
            [com.star-empire-elite.pages.app.action     :as action]
            [com.star-empire-elite.pages.app.espionage  :as espionage]
            [com.star-empire-elite.pages.app.outcomes   :as outcomes]
            [com.star-empire-elite.pages.app.eliminated :as eliminated]
            [com.star-empire-elite.pages.app.news       :as news]
            ;; Game functions
            [com.star-empire-elite.constants            :as const]
            [com.star-empire-elite.resolution           :as resolution]
            [com.star-empire-elite.ui                   :as ui]
            [com.star-empire-elite.utils                :as utils]
            [com.star-empire-elite.log                  :as gamelog]
            [xtdb.api                                   :as xt]))

;;;;
;;;; Dashboard
;;;;

(defn app
  "Render the main app dashboard showing all games the current user has joined.

  [ctx ring-ctx] -> hiccup"
  [ctx]
  (dashboard/dashboard ctx))

;;;;
;;;; Game Management
;;;;

(def player-defaults
  "Starting state for a new player. Merged with identity fields (user, game, empire-name)
  at join time."
  {:db/doc-type                   :player
   :player/credits                const/starting-credits
   :player/food                   const/starting-food
   :player/fuel                   const/starting-fuel
   :player/galaxars               const/starting-galaxars
   :player/mil-planets            const/starting-mil-planets
   :player/erg-planets            const/starting-erg-planets
   :player/ore-planets            const/starting-ore-planets
   :player/population             const/starting-population
   :player/stability              const/starting-stability
   :player/status                 0
   :player/score                  0
   :player/current-turn           1
   :player/current-round          1
   :player/current-phase          1
   :player/turns-used             0
   :player/generals               const/starting-generals
   :player/admirals               const/starting-admirals
   :player/soldiers               const/starting-soldiers
   :player/transports             const/starting-transports
   :player/stations               const/starting-stations
   :player/carriers               const/starting-carriers
   :player/fighters               const/starting-fighters
   :player/cmd-ships              const/starting-cmd-ships
   :player/agents                 const/starting-agents
   :player/last-population-growth nil})

(def game-defaults
  "Snapshot of all constants baked into a game entity at creation time.
  Values are stored on the game so that mid-game constant changes don't
  affect games already in progress."
  {:game/turns-per-round               const/turns-per-round
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
   :game/stability-recovery-floor      const/stability-recovery-floor})

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
         {:class "bg-game-bg text-green-400 font-mono border-[1.5px] border-game-green-border rounded"}
         (ui/scanline-overlay)
         [:div.flex.items-center
          {:class "bg-game-surface border-b border-game-green-border py-[7px] px-3.5"}
          [:div.text-lg.font-bold.text-green-400 {:class "tracking-wider"} "CREATE GAME"]]
         [:div.p-3.5
          (biff/form
            {:action "/app/create-game"
             :method "post"}
            [:div.mb-3.5
             (ui/section-label "Galaxy Name")
             [:input.w-full
              {:type "text" :name "game-name" :required true :maxlength 100 :autofocus true
               :class "bg-game-green-done border border-game-green-border text-green-400 py-1.5 px-2 font-mono text-sm rounded-sm outline-none w-full"}]]
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
          [(merge {:db/doc-type           :game
                   :xt/id                 game-id
                   :game/name             game-name
                   :game/created-at       now
                   :game/scheduled-end-at end-date
                   :game/status           0}
                  game-defaults)])
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
                        game-id)]
        (biff/submit-tx ctx
                        (concat
                          (for [[player-id] players] {:db/op :delete :xt/id player-id})
                          [{:db/op :delete :xt/id game-id}]))
        {:status 303 :headers {"location" "/app"}}))))

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
         {:class "bg-game-bg text-green-400 font-mono border-[1.5px] border-game-green-border rounded"}
         (ui/scanline-overlay)
         [:div.flex.items-center
          {:class "bg-game-surface border-b border-game-green-border py-[7px] px-3.5"}
          [:div
           [:div.text-lg.font-bold.text-green-400 {:class "tracking-wider"} "JOIN GAME"]
           [:div.text-sm.mt-px.text-game-green-soft (:game/name game)]]]
         [:div.p-3.5
          (when error
            [:div.text-red-400 {:class "text-[13px] mb-[10px]"} error])
          (biff/form
            {:action (str "/app/join-game/" game-id)
             :method "post"}
            [:div.mb-3.5
             (ui/section-label "Empire Name")
             [:input.w-full
              {:type "text" :name "empire-name" :required true :maxlength 50 :autofocus true
               :class "bg-game-green-done border border-game-green-border text-green-400 py-1.5 px-2 font-mono text-sm rounded-sm outline-none w-full"}]]
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

      ;; Check if another user already has this empire name in any game
      (seq (q db '{:find [player]
                   :in [empire-name uid]
                   :where [[player :player/empire-name empire-name]
                           [player :player/user other-user]
                           [(not= other-user uid)]]}
              empire-name uid))
      {:status 303
       :headers {"location" (str "/app/join-game/" game-id "?error=That+empire+name+is+already+taken")}}

      :else
      ;; Set up new player in the game, pulling from game constants
      (let [player-id (java.util.UUID/randomUUID)]
        (biff/submit-tx ctx
                        [(merge player-defaults
                                {:xt/id              player-id
                                 :player/user        uid
                                 :player/game        game-id
                                 :player/empire-name empire-name})])
        {:status 303
         :headers {"location" (str "/app/game/" player-id)}}))))

;;;;
;;;; Phase Handlers
;;;;
;;;; Each handler uses the with-player-and-game macro which:
;;;;  1. Loads player and game entities from the database
;;;;  2. Returns a 404 response if the player ID is not found
;;;;  3. Provides clean bindings for player, game, and player-id
;;;;
;;;; The handler body uses `or` to short-circuit through checks in order:
;;;;  - validate-phase: redirects to the correct phase if the player isn't on this one
;;;;  - cooldown check (income only): shows the waiting screen between rounds
;;;;  - the page function: renders the phase UI
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

(defn income-handler
  "Phase 1 — income. Checks for a round cooldown before rendering; if the player
  must wait before starting the next round, shows the waiting screen instead."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 1 player-id)
        (when-let [remaining-ms (utils/round-cooldown-ms player game)]
          (cooldown-page {:player player :game game :remaining-ms remaining-ms}))
        (income/income-page {:player player :game game}))))

(defn expenses-handler
  "Phase 2 — expenses. Pay upkeep for units, planets, and population."
  [{:keys [session] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 2 player-id)
        (let [[flash new-session] (utils/take-flash session)]
          {:status  200
           :headers {"content-type" "text/html"}
           :session new-session
           :body    (rum/render-static-markup
                      (expenses/expenses-page {:player player :game game :flash flash}))}))))

(defn exchange-handler
  "Phase 2 (sub-phase) — exchange. Buy and sell food, fuel, and units on the open market.
  Shares phase 2 with expenses so the player can move between them freely."
  [{:keys [session] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 2 player-id)
        (let [[flash new-session] (utils/take-flash session)]
          {:status  200
           :headers {"content-type" "text/html"}
           :session new-session
           :body    (rum/render-static-markup
                      (exchange/exchange-page {:player player :game game :flash flash}))}))))

(defn building-handler
  "Phase 3 — building. Purchase new units and planets."
  [{:keys [session] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 3 player-id)
        (let [[flash new-session] (utils/take-flash session)]
          {:status  200
           :headers {"content-type" "text/html"}
           :session new-session
           :body    (rum/render-static-markup
                      (building/building-page {:player player :game game :flash flash}))}))))

(defn action-handler
  "Phase 4 — action. Declare an attack, raid, or strike against another empire."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 4 player-id)
        (action/action-page {:player player :game game :db (:biff/db ctx)}))))

(defn espionage-handler
  "Phase 5 — espionage. Send agents on a covert mission against another empire."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 5 player-id)
        (espionage/espionage-page {:player player :game game :db (:biff/db ctx)}))))

(defn outcomes-handler
  "Phase 6 — outcomes. Delegates all turn resolution (combat, espionage, population
  growth, stability events, elimination) to resolution/resolve-turn, then renders results."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 6 player-id)
        (outcomes/outcomes-page (resolution/resolve-turn ctx player-id player game)))))

;;;;
;;;; News
;;;;

(defn news-handler
  "Per-game event feed — shows all events visible to the current player."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (news/news-page player game (:biff/db ctx))))

;;;;
;;;; Alerts
;;;;

(defn alerts-handler
  "HTMX polling fragment — returns the rendered alert banner content.
  Updates in place every 10s; no page refresh.

  [ctx ring-ctx] -> ring-response (200 with HTML fragment)"
  [{:keys [biff/db path-params] :as ctx}]
  (let [player-id (java.util.UUID/fromString (:player-id path-params))
        player    (xt/entity db player-id)]
    {:status  200
     :headers {"content-type" "text/html"}
     :body    (rum/render-static-markup (ui/incoming-alert-content player))}))

;;;;
;;;; Routes
;;;;

;;; A map of all the routes
(def module
  {:routes
   ;; Base path (/app) and middleware injector
   ["/app" {:middleware [mid/wrap-signed-in gamelog/wrap-game-action-log]}
    ;; Path parameters ... http method ... function to call
    [""                                    {:get  app}]
    ["/create-game"                        {:get  create-game-page :post create-game}]
    ["/delete-game/:game-id"               {:post delete-game}]
    ["/join-game/:game-id"                 {:get  join-game-page :post join-game}]
    ["/game/:player-id"                    {:get  game/game-view}]
    ["/game/:player-id/income"             {:get  income-handler}]
    ["/game/:player-id/apply-income"       {:post income/apply-income}]
    ["/game/:player-id/expenses"           {:get  expenses-handler}]
    ["/game/:player-id/apply-expenses"     {:post expenses/apply-expenses}]
    ["/game/:player-id/calculate-expenses" {:post expenses/calculate-expenses-oob}]
    ["/game/:player-id/exchange"           {:get  exchange-handler}]
    ["/game/:player-id/apply-exchange"     {:post exchange/apply-exchange}]
    ["/game/:player-id/calculate-exchange" {:post exchange/calculate-exchange-oob}]
    ["/game/:player-id/building"           {:get  building-handler}]
    ["/game/:player-id/apply-building"     {:post building/apply-building}]
    ["/game/:player-id/calculate-building" {:post building/calculate-building-oob}]
    ["/game/:player-id/action"             {:get  action-handler}]
    ["/game/:player-id/action-warning"     {:post action/update-action-warning}]
    ["/game/:player-id/apply-action"       {:post action/apply-action}]
    ["/game/:player-id/espionage"          {:get  espionage-handler}]
    ["/game/:player-id/espionage-warning"  {:post espionage/update-espionage-warning}]
    ["/game/:player-id/apply-espionage"    {:post espionage/apply-espionage}]
    ["/game/:player-id/outcomes"           {:get  outcomes-handler}]
    ["/game/:player-id/apply-outcomes"     {:post outcomes/apply-outcomes}]
    ["/game/:player-id/eliminated"         {:get  eliminated/eliminated-page}]
    ["/game/:player-id/rejoin"             {:post eliminated/rejoin}]
    ["/game/:player-id/news"               {:get  news-handler}]
    ["/game/:player-id/alerts"             {:get  alerts-handler}]]})

