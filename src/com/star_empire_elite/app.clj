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
            [com.star-empire-elite.pages.app.diplomacy  :as diplomacy]
            [com.star-empire-elite.pages.app.eliminated :as eliminated]
            [com.star-empire-elite.pages.app.news       :as news]
            [com.star-empire-elite.pages.app.guide      :as guide]
            [com.star-empire-elite.pages.app.results    :as results]
            ;; Game functions
            [com.star-empire-elite.constants            :as const]
            [com.star-empire-elite.game-config          :as cfg]
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

(defn player-defaults
  "Starting state for a new player, seeded from the game's :game/starting-* fields (every game
  has these snapshotted at creation — see create-game). Merged with identity fields (user, game,
  empire-name) at join time.

  [game game-map] -> player-map"
  [game]
  {:db/doc-type                   :player
   :player/credits                (:game/starting-credits     game)
   :player/food                   (:game/starting-food        game)
   :player/fuel                   (:game/starting-fuel        game)
   :player/galaxars               (:game/starting-galaxars    game)
   :player/mil-planets            (:game/starting-mil-planets game)
   :player/erg-planets            (:game/starting-erg-planets game)
   :player/ore-planets            (:game/starting-ore-planets game)
   :player/population             (:game/starting-population  game)
   :player/stability              (:game/starting-stability   game)
   :player/status                 0
   :player/score                  0
   :player/current-turn           1
   :player/current-round          1
   :player/current-phase          1
   :player/turns-used             0
   :player/rounds-started-today   0
   :player/generals               (:game/starting-generals   game)
   :player/admirals               (:game/starting-admirals   game)
   :player/soldiers               (:game/starting-soldiers   game)
   :player/transports             (:game/starting-transports game)
   :player/stations               (:game/starting-stations   game)
   :player/carriers               (:game/starting-carriers   game)
   :player/fighters               (:game/starting-fighters   game)
   :player/cmd-ships              (:game/starting-cmd-ships  game)
   :player/agents                 (:game/starting-agents     game)
   :player/last-population-growth nil})

;;; A field-spec becomes one labeled number input. Plain HTML5 type="number" with min/max/step
;;; gives client-side validation for free (spinner buttons, out-of-range highlighting) with no
;;; JS — the authoritative check is still cfg/coerce-config + cfg/enforce-invariants server-side.
(defn- config-field-input
  "[spec map] -> hiccup"
  [{:keys [key label type min max step default]}]
  (let [field-name (name (cfg/param-key key))
        ;; :step is a browser UX hint only — game-config/coerce-config is the real authority
        ;; on precision, server-side. For :double fields, a numeric step risks a native "step
        ;; mismatch" that silently blocks form submission with no visible error whenever the
        ;; field sits inside a collapsed <details> (the browser has nothing visible to show a
        ;; validation tooltip on). "any" disables that check entirely for doubles; every :int
        ;; field uses step 1, which no integer default can ever mismatch.
        html-step (if (= type :double) "any" step)]
    [:div
     [:label.block.mb-1
      {:for field-name :class "text-[10px] uppercase tracking-[0.1em] text-game-green-muted"}
      label]
     [:input.w-full
      {:type "number" :id field-name :name field-name
       :min min :max max :step html-step :value default
       ;; Suppress the native up/down spinner (Firefox via appearance, WebKit via the
       ;; pseudo-element selectors) — min/max/step still enforce range, just without the widget.
       :class "bg-game-green-done border border-game-green-border text-green-400 py-1 px-2 font-mono text-xs rounded-sm outline-none w-full [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"}]]))

(defn- config-category-section
  "One collapsed <details> card per category, listing its fields in a responsive grid.

  [[category keyword, label str]] -> hiccup"
  [[category label]]
  (ui/collapsible-card
    {:summary (str label " (" (count (cfg/fields-in-category category)) ")")}
    [:div.grid.grid-cols-1.gap-3.p-3
     {:class "sm:grid-cols-2"}
     (map config-field-input (cfg/fields-in-category category))]))

(defn create-game-page
  "Show the create-game form (admin only). Redirects non-admins to /app.

  Below the galaxy name, every admin-tunable constant (game-config/field-specs) is editable
  inside a collapsed-by-default <details> section per category (Game, Income, Expenses, ...),
  seeded with its constants.clj default.

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
            [:div.mb-3.5.flex.flex-col.gap-2
             (map config-category-section cfg/category-order)]
            [:div.flex.gap-3
             (ui/action-bar-link "/app" "Cancel")
             (ui/submit-button true "Create")])]]))))

(defn create-game
  "Create a new game entity. Admin only. Every constant in game-config/field-specs is read from
  the submitted form, clamped to its legal range, and checked against cross-field invariants
  (game-config/coerce-config, game-config/enforce-invariants) before being baked into the game
  entity — so mid-game constant changes never affect games already in progress, and an
  out-of-range or invariant-violating submission is silently corrected rather than rejected.

  [ctx ring-ctx] -> ring-response (303 redirect to join-game page)"
  [{:keys [session biff/db params] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (let [game-id   (java.util.UUID/randomUUID)
            game-name (clojure.string/trim (or (:game-name params) ""))
            config    (cfg/enforce-invariants (cfg/coerce-config params))
            now       (java.util.Date.)
            end-date  (java.util.Date. (+ (.getTime now) (* (:game/game-duration-days config) 24 60 60 1000)))]
        (biff/submit-tx ctx
          [(merge {:db/doc-type           :game
                   :xt/id                 game-id
                   :game/name             game-name
                   :game/created-at       now
                   :game/scheduled-end-at end-date
                   :game/status           const/game-status-active}
                  config)])
        {:status 303
         :headers {"location" (str "/app/join-game/" game-id)}}))))

(defn delete-game
  "Delete a game and all associated players and alliances (admin only).

  If the game's day counter has already reached its scheduled length (utils/game-days-complete?,
  the same 'Day N/N' point shown on screen) — i.e. the admin is clearing away a completed game
  rather than killing one early — first snapshot the final standings into a :game-result entity,
  in the same transaction as the deletes. This is the only durable record of who won, since the
  :game, :player, and :alliance entities are gone afterward.

  [ctx ring-ctx] -> ring-response (303 redirect to /app)"
  [{:keys [session biff/db path-params] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (let [game-id  (java.util.UUID/fromString (:game-id path-params))
            game     (xt/entity db game-id)
            now      (java.util.Date.)
            players  (q db '{:find [player]
                             :in [game-id]
                             :where [[player :player/game game-id]]}
                        game-id)
            alliances (q db '{:find [alliance]
                              :in [game-id]
                              :where [[alliance :alliance/game game-id]]}
                         game-id)
            result-op (when (and game (utils/game-days-complete? game now))
                        (let [standings (mapv (fn [p]
                                                 {:standing/rank        (:rank p)
                                                  :standing/empire-name (:player/empire-name p)
                                                  :standing/planets     (utils/total-planets p)
                                                  :standing/score       (:player/score p)})
                                               (game/get-game-players db game-id))]
                          [{:db/doc-type            :game-result
                            :xt/id                  (random-uuid)
                            :game-result/game-name  (:game/name game)
                            :game-result/created-at (:game/created-at game)
                            :game-result/ended-at   now
                            :game-result/standings  standings}]))]
        (biff/submit-tx ctx
                        (concat
                          result-op
                          (for [[player-id] players] {:db/op :delete :xt/id player-id})
                          (for [[alliance-id] alliances] {:db/op :delete :xt/id alliance-id})
                          [{:db/op :delete :xt/id game-id}]))
        {:status 303 :headers {"location" "/app"}}))))

(defn remove-player
  "Remove a single player from a game (admin only), leaving the game and its other players
  untouched.

  Sets the player's status to eliminated rather than deleting the entity outright. Other
  players may hold a live :player/pending-attack or :player/pending-espionage pointing at
  this player's id (see resolution.clj); a hard delete would leave those dangling and crash
  outcomes resolution for them. Eliminated status already hides the player from leaderboards,
  target lists, and the dashboard (game/get-game-players, dashboard/get-user-games) while
  keeping the entity in place for those pending references to resolve against.

  [ctx ring-ctx] -> ring-response (303 redirect to /app)"
  [{:keys [session biff/db path-params] :as ctx}]
  (let [uid  (:uid session)
        user (xt/entity db uid)]
    (if-not (const/admin-emails (:user/email user))
      {:status 303 :headers {"location" "/app"}}
      (let [player-id (java.util.UUID/fromString (:player-id path-params))]
        (biff/submit-tx ctx
          [{:db/doc-type   :player
            :db/op         :update
            :xt/id         player-id
            :player/status const/player-status-eliminated}])
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
                        [(merge (player-defaults game)
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
;;;;  - game-ended check (income only): redirects to the overview when the game is over
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
          [:p.text-xs.mt-2 (str "Minimum " (:game/minutes-between-rounds game) " minutes between rounds.")])]
       [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
        {:href (str "/app/game/" (:xt/id player))} "Back to Overview"]])))

(defn income-handler
  "Phase 1 — income. Checks game-over and round cooldown before rendering; redirects to
  the game overview when the scheduled end has passed, or shows the waiting screen when
  the player must wait before starting the next round."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 1 player-id)
        (when (utils/game-ended? game (java.util.Date.))
          {:status 303 :headers {"location" (str "/app/game/" player-id)}})
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

(defn diplomacy-handler
  "Phase 7 — diplomacy. Opens only once per round, after the last turn's outcomes — see
  outcomes/apply-outcomes. Every move (propose, accept, decline, withdraw, dissolve) is a
  deferred radio choice resolved all at once by apply-diplomacy on End Current Round."
  [{:keys [session] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (or (utils/validate-phase player 7 player-id)
        (let [[flash new-session] (utils/take-flash session)]
          {:status  200
           :headers {"content-type" "text/html"}
           :session new-session
           :body    (rum/render-static-markup
                      (diplomacy/diplomacy-page
                        {:player player :game game :db (:biff/db ctx) :flash flash}))}))))

;;;;
;;;; News
;;;;

(defn news-handler
  "Per-game event feed — shows all events visible to the current player."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (news/news-page player game (:biff/db ctx))))

;;;;
;;;; Guide
;;;;

(defn guide-handler
  "Game reference guide — documents all mechanics with live constants from the game entity."
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (guide/guide-page player game)))

;;;;
;;;; Cooldown polling fragment
;;;;

(defn cooldown-handler
  "HTMX polling fragment — returns the cooldown bar widget for the game overview action bar.
  Polled every 30s; when cooldown clears, returns the Play link so polling stops naturally.

  [ctx ring-ctx] -> ring-response (200 with HTML fragment)"
  [{:keys [biff/db path-params] :as ctx}]
  (let [player-id     (java.util.UUID/fromString (:player-id path-params))
        player        (xt/entity db player-id)
        game          (xt/entity db (:player/game player))
        mid-turn?     (> (:player/current-phase player) 1)
        cooldown-ms   (when-not mid-turn? (utils/round-cooldown-ms player game))
        day-exhausted? (utils/day-exhausted? player game)
        phase-url     (game/get-phase-url player-id (:player/current-phase player))]
    {:status  200
     :headers {"content-type" "text/html"}
     :body    (rum/render-static-markup
                (game/cooldown-bar player-id cooldown-ms day-exhausted? phase-url))}))

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
    ["/remove-player/:player-id"           {:post remove-player}]
    ["/results"                            {:get  results/results-page}]
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
    ["/game/:player-id/diplomacy"          {:get  diplomacy-handler}]
    ["/game/:player-id/diplomacy-warning"  {:post diplomacy/update-diplomacy-warning}]
    ["/game/:player-id/apply-diplomacy"    {:post diplomacy/apply-diplomacy}]
    ["/game/:player-id/eliminated"         {:get  eliminated/eliminated-page}]
    ["/game/:player-id/rejoin"             {:post eliminated/rejoin}]
    ["/game/:player-id/news"               {:get  news-handler}]
    ["/game/:player-id/guide"              {:get  guide-handler}]
    ["/game/:player-id/alerts"             {:get  alerts-handler}]
    ["/game/:player-id/cooldown"           {:get  cooldown-handler}]]})

