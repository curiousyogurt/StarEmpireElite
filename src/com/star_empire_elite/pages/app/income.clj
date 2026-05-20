;;;;;
;;;;; Income Phase - Resource Generation
;;;;;
;;;;; The income phase is the first phase of each turn where players collect resources from their
;;;;; planets and any other sources. Unlike later phases (expenses, exchange, building), the income
;;;;; phase requires no player decisions; it's purely informational, showing what resources will be
;;;;; generated before the player advances to the next phase.
;;;;;

(ns com.star-empire-elite.pages.app.income
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))

;;;;
;;;; Calculations
;;;;

(defn calculate-income
  "Calculate income from all sources using game constants.

  [player player-map, game game-map] -> {:ore-credits int,  :erg-food int,     :erg-fuel int,
                                         :mil-soldiers int, :mil-fighters int, :mil-stations int,
                                         :tax-credits int}"
  [player game]
  (let [ore-count (:player/ore-planets player)
        erg-count (:player/erg-planets player)]
    {:ore-credits  (* ore-count                    (:game/ore-planet-credits     game))
     :erg-food     (* erg-count                    (:game/erg-planet-food        game))
     :erg-fuel     (* erg-count                    (:game/erg-planet-fuel        game))
     :mil-soldiers (* (:player/mil-planets player) (:game/mil-planet-soldiers    game))
     :mil-fighters (* (:player/mil-planets player) (:game/mil-planet-fighters    game))
     :mil-stations (* (:player/mil-planets player) (:game/mil-planet-stations    game))
     :tax-credits  (* (:player/population  player) (:game/population-tax-credits game))}))

(defn calculate-resources-after-income
  "Calculate all player resources after applying income.
  Uses utils/player-snapshot which returns a flat map with un-namespaced keys
  ({:credits n :food n :fuel n ...}), so we update :credits rather than :player/credits.

  [player player-map, income income-map] -> {:credits int, :food int, ...}"
  [player income]
  (-> (utils/player-snapshot player)
      ;; Update reads the value of :credits, applies the + transformation,
      ;; and writes the result back to a new map using a new key.
      (update :credits  + (:ore-credits  income) (:tax-credits income))
      (update :food     + (:erg-food     income))
      (update :fuel     + (:erg-fuel     income))
      (update :soldiers + (:mil-soldiers income))
      (update :fighters + (:mil-fighters income))
      (update :stations + (:mil-stations income))))

;;;;
;;;; UI Components
;;;;


(defn- resource-table-header
  "Render the column-label row for the resource table.
  Emits two variants: a 4-column mobile row (no bar column) and a 5-column
  desktop row (with an empty bar column between Item and the value columns).

  [] -> hiccup"
  []
  (let [header-class "gap-2 py-1 px-3 items-center bg-game-header border-b border-game-border"
        col-cls      "tracking-[0.08em] text-green-400"
        item-cls     "tracking-widest text-green-400"
        col-label    (fn [text cls extra-class]
                       [:span.text-xs.uppercase {:class (str cls (when extra-class (str " " extra-class)))} text])
        value-label  (fn [text]
                       (col-label text col-cls "text-right justify-self-end"))]
    [:<>
     [:div.income-row-mobile
      {:class (str "md:hidden " header-class)}
      (col-label "Item" item-cls nil)
      (value-label "before")
      (value-label "change")
      (value-label "after")]
     [:div.income-row-desktop
      {:class (str "hidden md:grid " header-class)}
      (col-label "Item" item-cls nil)
      [:span]
      (value-label "before")
      (value-label "change")
      (value-label "after")]]))

(defn- resource-row
  "Render one resource row in two variants: mobile (no bar) and desktop (with bar).

  [name str, before int, delta int, key? bool, filter-id str] -> hiccup"
  [name before delta key? filter-id]
  (let [after       (+ before delta)
        row-class   (str "items-center gap-2 py-1 px-3 border-b border-game-divider"
                         (when key? " bg-game-row"))
        name-cell   [:div.text-base
                     {:class (if key? "font-bold text-green-400" "text-game-green-muted")}
                     name]
        before-cell [:div.text-base.text-right.text-game-green-muted
                     (ui/format-number before)]
        delta-cell  [:div.text-base.text-right.whitespace-nowrap
                     {:class (str "tracking-[0.03em] "
                                  (cond
                                    (zero? delta) "text-game-green-muted"
                                    key?          "text-green-400 font-bold"
                                    :else         "text-green-400"))
                      :style (when (and key? (not (zero? delta)))
                               {:text-shadow "0 0 8px rgba(74,222,128,0.6)"})}
                     (if (zero? delta) "-" [:<> "+" (ui/format-number delta)])]
        after-cell  [:div.text-base.text-right
                     {:class (if key? "text-green-400 font-bold" "text-game-green-soft")}
                     (ui/format-number after)]]
    [:<>
     ;; Mobile: 4-col grid, no bar
     [:div.income-row-mobile
      {:class (str "md:hidden " row-class)}
      name-cell before-cell delta-cell after-cell]
     ;; Desktop: 5-col grid, with bar
     [:div.income-row-desktop
      {:class (str "hidden md:grid " row-class)}
      name-cell (ui/svg-indicator-bar :gain before after filter-id) before-cell delta-cell after-cell]]))

(defn- resource-table
  "Render the Resource Changes table with before/delta/after columns and SVG bars.

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [rows [{:name "Credits"  :before (:player/credits  player)
              :delta (+ (:ore-credits income) (:tax-credits income))
              :key? true}
              {:name "Food"     :before (:player/food     player) :delta (:erg-food  income) :key? true}
              {:name "Fuel"     :before (:player/fuel     player) :delta (:erg-fuel income) :key? true}
              {:name "Soldiers" :before (:player/soldiers player) :delta (:mil-soldiers income) :key? false}
              {:name "Fighters" :before (:player/fighters player) :delta (:mil-fighters income) :key? false}
              {:name "Stations" :before (:player/stations player) :delta (:mil-stations income) :key? false}]]
    [:div.overflow-hidden.rounded-game.bg-game-surface
     {:class "border border-game-border"}
     (resource-table-header)
     (for [{:keys [name before delta key?]} rows]
       (resource-row name before delta key? (str "glow-" (str/lower-case name))))]))


;;;;
;;;; Actions
;;;;

(defn apply-income
  "Apply planet income to the player and advance to the expenses phase.
  Resets current-round to 1 when last-round-completed-at is from a previous UTC calendar day,
  covering both the all-rounds-used and skipped-round cases.

  [ctx ring-ctx] -> ring-response (303 redirect to expenses)"
  ;; ctx is the Biff context map. :keys destructures that map to pull out commonly-used keys, while
  ;; keeping the full map bound to ctx so we can pass it to utils/with-player-and-game and
  ;; biff/submit-tx.
  [{:keys [path-params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 1 player-id)]
      redirect
      (let [income         (calculate-income player game)
            after          (calculate-resources-after-income player income)
            last-completed (:player/last-round-completed-at player)
            day-reset?     (and (> (:player/current-round player) 1)
                                last-completed
                                (not (utils/same-calendar-day? last-completed)))]
        (biff/submit-tx 
          ctx
          [(cond-> {:db/doc-type          :player
                    :db/op                :update
                    :xt/id                player-id
                    :player/credits       (:credits after)
                    :player/food          (:food after)
                    :player/fuel          (:fuel after)
                    :player/soldiers      (:soldiers after)
                    :player/fighters      (:fighters after)
                    :player/stations      (:stations after)
                    :player/current-phase 2}
             day-reset? (assoc :player/current-round 1))])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/expenses")}}))))

;;;;
;;;; Page
;;;;

(defn income-page
  "Show income preview for the current turn. No player decisions required — income is informational
  only, so no htmx dynamic updates are needed.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [player-id (:xt/id player)
        income    (calculate-income player game)]
    (ui/page
      {}
      ;; Terminal shell: text-base sets the base font size for all descendants
      [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative.bg-game-bg.rounded.text-green-400.font-mono
       {:class "border-[1.5px] border-game-green-border"}

       ;; Scanline overlay
       (ui/scanline-overlay)

       ;; Topbar
       (ui/phase-topbar player game "INCOME PHASE")

       ;; Body
       [:div.flex.flex-col.gap-2
        {:class "py-2.5 px-3.5"}
        (ui/snapshot-section player)
        (resource-table player income)
        (ui/incoming-alert player)]

       ;; Action bar
       [:div.flex.gap-2
        {:class "py-2 px-3.5 border-t border-game-border"}
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (biff/form
          {:action (str "/app/game/" player-id "/apply-income") :method "post"
           :class  "m-0"}
          (ui/submit-button true "Continue to Expenses"))]])))

