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
  ;; Keys such as :player/ore-planets are fully qualified keys in the player map.
  ;; That is, there is a key in the player map named :player/ore-planets, etc.
  ;;             (* resource-count-for-player     resource-value-in-game)
  {:ore-credits  (* (:player/ore-planets player) (:game/ore-planet-credits game))
   :erg-food     (* (:player/erg-planets player) (:game/erg-planet-food game))
   :erg-fuel     (* (:player/erg-planets player) (:game/erg-planet-fuel game))
   :mil-soldiers (* (:player/mil-planets player) (:game/mil-planet-soldiers game))
   :mil-fighters (* (:player/mil-planets player) (:game/mil-planet-fighters game))
   :mil-stations (* (:player/mil-planets player) (:game/mil-planet-stations game))
   :tax-credits  (* (:player/population player)  (:game/population-tax-credits game))})

(defn calculate-resources-after-income
  "Calculate all player resources after applying income.

  [player player-map, income income-map] -> {:credits int, :food int, ...}"
  [player income]
  (-> (utils/player-snapshot player)
      (update :credits + (:ore-credits income) (:tax-credits income))
      (update :food    + (:erg-food     income))
      (update :fuel    + (:erg-fuel     income))
      (update :soldiers + (:mil-soldiers income))
      (update :fighters + (:mil-fighters income))
      (update :stations + (:mil-stations income))))


;;;;
;;;; UI Components
;;;;

;;;
;;; Source grid
;;;

(defn- resource-pill
  "Render one resource pill: a small rounded badge displaying a labeled value.
  Currently income-shaped: prefix is always passed in by the caller (typically '+'),
  and the value is rendered as-is (no sign handling). Generalize when expenses or
  exchange need their own pill style.

  attrs is merged into the span's attribute map; pass {:key k} for iteration contexts.

  [prefix str, value number, suffix str, attrs? map] -> hiccup"
  [prefix value suffix & [attrs]]
  [:span.text-xs.inline-block.rounded-sm.text-green-400.bg-game-green-deep
   (merge {:style {:padding "1px 5px"}} attrs)
   prefix (ui/format-number value) " " suffix])

(defn- resource-pills
  "Render a sequence of resource pills for a resource-card body: one pill per non-zero
  resource amount in the income map. Iterates the six resource keys in display order.

  [income-map {:credits? int, :food? int, ...}] -> seq of hiccup"
  [income-map]
  (for [[k prefix suffix] [[:credits  "+" "cr"]   [:food     "+" "food"] [:fuel     "+" "fuel"]
                           [:soldiers "+" "sold"] [:fighters "+" "fgtr"] [:stations "+" "stn"]]
        :let [v (or (k income-map) 0)]
        :when (pos? v)]
    (resource-pill prefix v suffix {:key k})))

(defn- resource-card
  "Render one resource card: bordered container with a title + count header and a stack
  of resource pills showing the amounts produced. Currently used for income sources;
  candidate for promotion to ui.clj once it has cross-phase callers.

  [{:keys [name count income-map]}] -> hiccup"
  [{:keys [name count income-map]}]
  [:div.flex.flex-col.gap-1.border.border-game-border.rounded-game.bg-game-card
   {:style {:padding "6px 8px"}}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 name]
    [:span.text-xs.text-game-green-muted (str "x" count)]]
   [:div {:class "flex flex-col gap-0.5"}
    (resource-pills income-map)]])

;; Note: The resources are listed within the source-grid because we distinguish between "source" (ore 
;; planets, energy planets, military planets, taxes) and a "resource" (credits, food, fuel, military)
;; that is generated.
(defn- source-grid
  "Render the 4-column income sources grid (Ore, Erg, Mil, Pop).

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [pop-count (-> (format "%.1f" (double (:player/population player)))
                      (str/replace #"\.0$" "")
                      (str "M"))
        sources [{:name "Ore"
                  :count (:player/ore-planets player)
                  :income-map {:credits (:ore-credits income)}}
                 {:name "Erg"
                  :count (:player/erg-planets player)
                  :income-map {:food (:erg-food income) :fuel (:erg-fuel income)}}
                 {:name "Mil"
                  :count (:player/mil-planets player)
                  :income-map {:soldiers (:mil-soldiers income)
                               :fighters (:mil-fighters income)
                               :stations (:mil-stations income)}}
                 {:name "Taxes" :count pop-count
                  :income-map {:credits (:tax-credits income)}}]]
    [:div
     (ui/section-label "Sources")
     [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-1.5"}
      (for [src sources]
        (resource-card src))]]))

;;;
;;; Resource table definitions:
;;; - resource-table-row: a single row
;;; - resource-table-header: the table header
;;; - resource-table: put it all together
;;;

(defn- resource-table-row
  "Render one resource row for the resource table.
  Emits two variants: a 4-column mobile row (no bar column) and a 5-column
  desktop row (with the SVG indicator bar), aligned with resource-table-header.

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
        delta-class (cond
                      (zero? delta) "text-game-green-muted"
                      key?          "text-green-400 font-bold"
                      :else         "text-green-400")
        delta-cell  [:div.text-base.text-right.whitespace-nowrap
                     {:class delta-class
                      :style (cond-> {:letter-spacing "0.03em"}
                               (and key? (not (zero? delta)))
                               (assoc :text-shadow "0 0 8px rgba(74,222,128,0.6)"))}
                     (if (zero? delta) "-" [:<> "+" (ui/format-number delta)])]
        after-cell  [:div.text-base.text-right
                     {:class (if key? "text-green-400 font-bold" "text-game-green-soft")}
                     (ui/format-number after)]]
    [:<>

     ;; Mobile
     [:div.income-row-mobile
      {:class (str "md:hidden " row-class)}
      name-cell before-cell delta-cell after-cell]

     ;; Desktop
     [:div.income-row-desktop
      {:class (str "hidden md:grid " row-class)}
      name-cell
      (ui/svg-indicator-bar :gain before after filter-id)
      before-cell delta-cell after-cell]]))

(defn- resource-table-header
  "Render the column-label row for the resource table.
  Emits two variants: a 4-column mobile row (no bar column) and a 5-column
  desktop row with an empty cell aligning the header grid to the data-row grid,
  which uses that column for the SVG indicator bar.

  [] -> hiccup"
  []
  (let [header-class    "gap-2 py-1 px-3 items-center bg-game-header border-b border-game-border"
        col-class       "tracking-[0.08em] text-green-400"
        item-class      "tracking-[0.1em] text-green-400"
        col-label       (fn [text base-cls extra-cls]
                          [:span.text-xs.uppercase
                           {:class (str base-cls (when extra-cls (str " " extra-cls)))}
                           text])
        value-label     (fn [text]
                          (col-label text col-class "text-right justify-self-end"))
        ;; Empty cell aligning the header grid with the data-row grid,
        ;; which uses this column for the SVG indicator bar (see resource-table-row).
        bar-placeholder [:span]]
    [:<>

     ;; Mobile
     [:div.income-row-mobile
      {:class (str "md:hidden " header-class)}
      (col-label "Item" item-class nil)
      (value-label "before")
      (value-label "change")
      (value-label "after")]

     ;; Desktop
     [:div.income-row-desktop
      {:class (str "hidden md:grid " header-class)}
      (col-label "Item" item-class nil)
      bar-placeholder
      (value-label "before")
      (value-label "change")
      (value-label "after")]]))

(defn- resource-table
  "Render the Resource Changes table with before/delta/after columns and SVG bars.

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [credits-delta (+ (:ore-credits income) (:tax-credits income))
        rows [{:name "Credits"  :before (:player/credits  player) :delta credits-delta          :key? true}
              {:name "Food"     :before (:player/food     player) :delta (:erg-food     income) :key? true}
              {:name "Fuel"     :before (:player/fuel     player) :delta (:erg-fuel     income) :key? true}
              {:name "Soldiers" :before (:player/soldiers player) :delta (:mil-soldiers income) :key? false}
              {:name "Fighters" :before (:player/fighters player) :delta (:mil-fighters income) :key? false}
              {:name "Stations" :before (:player/stations player) :delta (:mil-stations income) :key? false}]]
    [:div.overflow-hidden.border.border-game-border.rounded-game.bg-game-surface
     (resource-table-header)
     (for [{:keys [name before delta key?]} rows]
       (resource-table-row name before delta key? (str "glow-" (str/lower-case name))))]))


;;;;
;;;; Actions
;;;;

(defn apply-income
  "Apply planet income to the player and advance to the expenses phase.
  Resets current-round to 1 when last-round-completed-at is from a previous UTC calendar day,
  covering both the all-rounds-used and skipped-round cases.

  [ctx ring-ctx] -> ring-response (303 redirect to expenses)"
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 1 player-id)]
      redirect
      (let [income         (calculate-income player game)
            after          (calculate-resources-after-income player income)
            last-completed (:player/last-round-completed-at player)
            day-reset?     (and (> (:player/current-round player) 1)
                                last-completed
                                (not (utils/same-calendar-day? last-completed)))]
        (biff/submit-tx ctx
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
  "Show income page for the current turn. No player decisions required. Income is informational
  only, so no htmx dynamic updates are needed.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [player-id (:xt/id player)
        income    (calculate-income player game)]
    (ui/phase-shell player game "INCOME PHASE"
      (ui/phase-body player
        (ui/snapshot-section player)
        (source-grid player income)
        (resource-table player income))
      (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (biff/form
          {:action (str "/app/game/" player-id "/apply-income") :method "post"
           :class  "m-0"}
          (ui/submit-button true "Continue to Expenses"))))))

