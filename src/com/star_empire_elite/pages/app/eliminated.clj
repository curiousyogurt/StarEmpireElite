;;;;;
;;;;; Eliminated - Empire Defeat and Rejoin Flow
;;;;;
;;;;; Shown when a player reaches 0 planets at the outcomes phase. The player can either rejoin the 
;;;;; current game as a new empire (entering a fresh empire name and starting with default resources), 
;;;;; or return to the dashboard. Rejoining creates a new player entity; the old eliminated entity 
;;;;; remains in the DB with status=eliminated but is hidden from all leaderboards, target lists, and 
;;;;; dashboard views.
;;;;;

(ns com.star-empire-elite.pages.app.eliminated
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Actions
;;;;

(defn rejoin
  "Create a fresh player entity in the same game under a new empire name.
  Validates that the name is non-blank and not taken by another user.

  [ctx ring-ctx] -> ring-response (303 redirect to new player's income, or back to eliminated page with error)"
  [{:keys [params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (let [empire-name (clojure.string/trim (or (:empire-name params) ""))
          game-id     (:player/game player)
          uid         (:player/user player)]
      (cond
        (clojure.string/blank? empire-name)
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/eliminated?error=Empire+name+cannot+be+blank")}}

        (seq (q db '{:find [p]
                     :in [empire-name uid]
                     :where [[p :player/empire-name empire-name]
                             [p :player/user other-user]
                             [(not= other-user uid)]]}
               empire-name uid))
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/eliminated?error=That+empire+name+is+already+taken")}}

        :else
        (let [new-player-id (java.util.UUID/randomUUID)]
          (biff/submit-tx ctx
            [{:db/doc-type                   :player
              :xt/id                         new-player-id
              :player/user                   uid
              :player/game                   game-id
              :player/empire-name            empire-name
              :player/credits                const/starting-credits
              :player/food                   const/starting-food
              :player/fuel                   const/starting-fuel
              :player/galaxars               const/starting-galaxars
              :player/mil-planets            const/starting-mil-planets
              :player/erg-planets            const/starting-erg-planets
              :player/ore-planets            const/starting-ore-planets
              :player/population             const/starting-population
              :player/stability              const/starting-stability
              :player/status                 const/player-status-active
              :player/score                  0
              :player/current-turn           1
              :player/current-round          1
              :player/current-phase          1
              :player/turns-used             0
              :player/soldiers               const/starting-soldiers
              :player/transports             const/starting-transports
              :player/fighters               const/starting-fighters
              :player/carriers               const/starting-carriers
              :player/stations               const/starting-stations
              :player/cmd-ships              const/starting-cmd-ships
              :player/generals               const/starting-generals
              :player/admirals               const/starting-admirals
              :player/agents                 const/starting-agents
              :player/last-population-growth nil}])
          {:status 303
           :headers {"location" (str "/app/game/" new-player-id "/income")}})))))

;;;;
;;;; Page
;;;;

(defn eliminated-page
  "Show the elimination screen with a rejoin form and a back-to-games link.
  Reads an optional ?error query param to display validation errors.

  [ctx ring-ctx] -> hiccup"
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (let [error (get-in ctx [:query-params :error])]
      (ui/page
       {}
       [:div.mx-auto.max-w-lg.mt-16.p-8.border.border-red-400.text-green-400.font-mono
        [:h1.text-2xl.font-bold.mb-4.text-red-400 "Empire Eliminated"]
        [:p.text-sm.mb-8
         (str "The " (:player/empire-name player) " has fallen. "
              "You may rejoin as a new empire, or return to your games.")]
        (when error
          [:p.text-xs.text-red-400.mb-4 (java.net.URLDecoder/decode error "UTF-8")])
        (biff/form
         {:action (str "/app/game/" player-id "/rejoin")
          :method "post"}
         [:div.mb-4
          [:label.block.text-xs.mb-1 "New Empire Name"]
          [:input.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono
           {:type "text" :name "empire-name" :required true :maxlength 100 :autofocus true}]]
         [:div.flex.gap-4.mt-2
          [:button.bg-green-400.text-black.px-6.py-2.font-bold.hover:bg-green-300.transition-colors
           {:type "submit"} "Rejoin Game"]
          [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors
           {:href "/app"} "Back to Games"]])]))))
