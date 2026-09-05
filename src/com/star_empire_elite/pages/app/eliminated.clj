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
              :player/credits                (or (:game/starting-credits     game) const/starting-credits)
              :player/food                   (or (:game/starting-food        game) const/starting-food)
              :player/fuel                   (or (:game/starting-fuel        game) const/starting-fuel)
              :player/galaxars               (or (:game/starting-galaxars    game) const/starting-galaxars)
              :player/mil-planets            (or (:game/starting-mil-planets game) const/starting-mil-planets)
              :player/erg-planets            (or (:game/starting-erg-planets game) const/starting-erg-planets)
              :player/ore-planets            (or (:game/starting-ore-planets game) const/starting-ore-planets)
              :player/population             (or (:game/starting-population  game) const/starting-population)
              :player/stability              (or (:game/starting-stability   game) const/starting-stability)
              :player/status                 const/player-status-active
              :player/score                  0
              :player/current-turn           1
              :player/current-round          1
              :player/current-phase          1
              :player/turns-used             0
              :player/rounds-started-today   0
              :player/soldiers               (or (:game/starting-soldiers   game) const/starting-soldiers)
              :player/transports             (or (:game/starting-transports game) const/starting-transports)
              :player/fighters               (or (:game/starting-fighters   game) const/starting-fighters)
              :player/carriers               (or (:game/starting-carriers   game) const/starting-carriers)
              :player/stations               (or (:game/starting-stations   game) const/starting-stations)
              :player/cmd-ships              (or (:game/starting-cmd-ships  game) const/starting-cmd-ships)
              :player/generals               (or (:game/starting-generals   game) const/starting-generals)
              :player/admirals               (or (:game/starting-admirals   game) const/starting-admirals)
              :player/agents                 (or (:game/starting-agents     game) const/starting-agents)
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
       [:div.text-base.w-full.max-w-lg.mx-auto.overflow-hidden.relative
        {:class "border-[1.5px] border-red-900 rounded bg-game-bg text-green-400 font-mono"}
        (ui/scanline-overlay)

        ;; Header
        [:div.border-b {:class "bg-[#1a0808] border-red-900 py-[7px] px-3.5"}
         [:div.font-bold.text-red-400 {:class "text-lg tracking-wider"}
          "EMPIRE ELIMINATED"]]

        ;; Body
        [:div {:class "py-4 px-3.5"}
         [:p.text-sm.mb-6.text-game-green-soft
          (str "The " (:player/empire-name player) " has fallen. "
               "You may rejoin as a new empire, or return to your games.")]
         (when error
           [:p.text-xs.mb-4.text-red-400
            (java.net.URLDecoder/decode error "UTF-8")])
         (biff/form
          {:action (str "/app/game/" player-id "/rejoin")
           :method "post"
           :class  "m-0"}
          [:div.mb-4
           [:label.block.mb-1 {:class "text-[11px] uppercase tracking-widest text-game-green-muted"}
            "New Empire Name"]
           [:input
            {:type "text" :name "empire-name" :required true :maxlength 100 :autofocus true
             :class "w-full bg-game-bg border border-game-green-border text-green-400 py-1.5 px-[10px] font-mono rounded-sm text-sm box-border outline-none"}]]
          [:div.flex.gap-2.mt-2
           [:button
            {:type "submit"
             :class "py-1.5 px-5 border border-green-400 bg-game-green-deep text-green-400 rounded-sm font-mono cursor-pointer text-sm tracking-wider"}
            "Rejoin Game"]
           (ui/action-bar-link "/app" "Back to Games")])]]))))
