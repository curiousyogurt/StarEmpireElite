;;;;;
;;;;; Income Phase - Resource Generation
;;;;;
;;;;; The income phase is the first phase of each turn where players collect resources from their
;;;;; planets and any other sources. Unlike later phases (expenses, exchange, building), the income
;;;;; phase requires no player decisions; it's purely informational, showing what resources will be
;;;;; generated before the player advances to the next phase.
;;;;;

;;;;; Logical Structure:
;;;;;
;;;;; 1)  income-page ← calculate-income
;;;;;     └─ ui/phase-shell
;;;;;        ├─ ui/phase-body
;;;;;        │  ├─ ui/flash-notice
;;;;;        │  ├─ ui/snapshot-section
;;;;;        │  ├─ ui/section-label "Income"
;;;;;        │  ├─ income-grid
;;;;;        │  │  └─ income-card  (×4: Ore, Erg, Mil, Tax)
;;;;;        │  │     └─ income-row  (per resource with positive gain)
;;;;;        │  ├─ ui/section-label "Impact"
;;;;;        │  └─ income-table
;;;;;        │     ├─ ui/impact-table-header
;;;;;        │     └─ income-table-row  (×6: Credits, Food, Fuel, Soldiers, Fighters, Stations)
;;;;;        └─ ui/phase-action-bar
;;;;;           ├─ ui/action-bar-link "Pause"
;;;;;           └─ ui/submit-button "Continue to Expenses"
;;;;;
;;;;; 2)  apply-income ← calculate-income, calculate-resources-after-income

(ns com.star-empire-elite.pages.app.income
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]))


;;;;
;;;; Calculations
;;;;

(defn planet-synergy-bonus-rate
  "Compute the ore-credits synergy bonus rate from how proportionally balanced the player's
  ore/erg/mil planet counts are, independent of their absolute scale (unrelated to the
  building-phase cost ramp, which cares about scale, not proportion).

  spread-fraction = (hi - lo) / hi, where hi/lo are the largest/smallest of the three counts:
  0 when all three are equal, growing toward 1 as one type falls toward zero relative to the
  others. The bonus tapers linearly from the full rate at spread-fraction 0 down to zero at
  the configured threshold, and stays zero beyond it — no cliff edge, and no bonus at all when
  the player has no planets of any type (hi <= 0, which would otherwise divide by zero).

  [player player-map, game game-map] -> double"
  [player game]
  (let [counts    [(:player/ore-planets player 0)
                    (:player/erg-planets player 0)
                    (:player/mil-planets player 0)]
        hi        (apply max counts)
        lo        (apply min counts)
        threshold (:game/planet-synergy-threshold  game)
        max-rate  (:game/planet-synergy-bonus-rate game)]
    (if (<= hi 0)
      0.0
      (let [spread-fraction (/ (- hi lo) (double hi))]
        (if (>= spread-fraction threshold)
          0.0
          (* max-rate (- 1.0 (/ spread-fraction threshold))))))))

(defn calculate-income
  "Calculate income from all sources using game constants.

  :ore-credits already includes the planet synergy bonus (see planet-synergy-bonus-rate);
  :synergy-credits is that bonus broken out on its own, purely so the UI can show it as a
  separate line — ore-credits = base-ore-credits + synergy-credits exactly, so nothing is
  double-counted when both are added to a player's credits.

  [player player-map, game game-map] -> {:ore-credits int,  :synergy-credits int, :erg-food int,
                                         :erg-fuel int,     :mil-soldiers int,   :mil-fighters int,
                                         :mil-stations int, :tax-credits int}"
  [player game]
  ;; Keys such as :player/ore-planets are fully qualified keys in the player map.
  ;;             (* resource-count-for-player     resource-value-in-game)
  (let [base-ore-credits (* (:player/ore-planets player) (:game/ore-planet-credits game))
        ore-credits      (long (Math/round (* base-ore-credits
                                               (+ 1.0 (planet-synergy-bonus-rate player game)))))]
    {:ore-credits      ore-credits
     :synergy-credits  (- ore-credits base-ore-credits)
     :erg-food         (* (:player/erg-planets player) (:game/erg-planet-food        game))
     :erg-fuel         (* (:player/erg-planets player) (:game/erg-planet-fuel        game))
     :mil-soldiers     (* (:player/mil-planets player) (:game/mil-planet-soldiers    game))
     :mil-fighters     (* (:player/mil-planets player) (:game/mil-planet-fighters    game))
     :mil-stations     (* (:player/mil-planets player) (:game/mil-planet-stations    game))
     :tax-credits      (* (:player/population  player) (:game/population-tax-credits game))}))

(defn calculate-resources-after-income
  "Calculate all player resources after applying income.

  [player player-map, income income-map] -> {:credits int, :food int, ...}"
  [player income]
  (-> (utils/player-snapshot player)
      (update :credits  + (:ore-credits  income) (:tax-credits income))
      (update :food     + (:erg-food     income))
      (update :fuel     + (:erg-fuel     income))
      (update :soldiers + (:mil-soldiers income))
      (update :fighters + (:mil-fighters income))
      (update :stations + (:mil-stations income))))


;;;;
;;;; UI Components
;;;; - Income Grid
;;;; - Income Table
;;;;

;;;
;;; The Income Grid shows income from four sources: ore planets, erg planets, mil planets, and tax
;;; due to population, each in a card.  Each card contains a header/aside, plus relevant income rows.
;;; The idea is that the player can easily survey the sources of income, as well as the particular
;;; types of income that come from each source.
;;;
;;; Income Grid:
;;; - income-row:  one pill row for a particular card, showing a single resource gain
;;; - income-card: one card with header (on the left), aside (on right), and income rows
;;; - income-grid: four cards in a 2×4 responsive grid
;;;

(defn- income-row
  "Render one income row pill for a resource with a positive gain.

  [resource keyword, unit str, income-map map] -> hiccup or nil"
  [resource unit income-map]
  (let [amount (resource income-map)]
    ;; Only make available rows for values that are non-nil and pos
    (when (and amount (pos? amount))
      (ui/info-pill {:key resource :value amount :sign "+" :unit unit}))))

(defn- income-card
  "Render one income source card with a header, aside count, and income rows.

  [title str, aside str, income-map map] -> hiccup"
  [title aside income-map]
  [:div
   {:class "flex flex-col gap-1 py-1.5 px-2 border border-game-border rounded-game bg-game-card"}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 title]
    [:span.text-xs.text-game-green-muted aside]]
   [:div {:class "flex flex-col gap-0.5"}
    ;; Call income-row for all possible; but nils in the income map not display
    (income-row :credits         "cred"     income-map)
    (income-row :food            "food"     income-map)
    (income-row :fuel            "fuel"     income-map)
    (income-row :soldiers        "sold"     income-map)
    (income-row :fighters        "fgtr"     income-map)
    (income-row :stations        "stn"      income-map)
    (income-row :tax-credits     "cred tax" income-map)
    (income-row :synergy-credits "cred syn" income-map)]])

(defn- income-grid
  "Render the income grid: four cards (Ore, Erg, Mil, Econ) in a 2×4 responsive grid,
  each with a header, aside, and income rows showing resources produced this turn. The fourth
  card ('Econ', for economy) covers both population tax and the planet synergy bonus
  (see planet-synergy-bonus-rate) since neither has enough rows on its own for a full card.

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [pop-count (ui/format-population (:player/population player))]
    [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-1.5"}
     (income-card "Ore" (str "x" (ui/format-number-str (:player/ore-planets player)))
                  {:credits (:ore-credits income)})
     (income-card "Erg" (str "x" (ui/format-number-str (:player/erg-planets player)))
                  {:food (:erg-food income)
                   :fuel (:erg-fuel income)})
     (income-card "Mil" (str "x" (ui/format-number-str (:player/mil-planets player)))
                  {:soldiers (:mil-soldiers income)
                   :fighters (:mil-fighters income)
                   :stations (:mil-stations income)})
     (income-card "Econ" (str "x" pop-count)
                  {:tax-credits     (:tax-credits income)
                   :synergy-credits (:synergy-credits income)})]))

;;;
;;; The Income Table shows income by type instead of by source: credits, food, fuel; soldiers, 
;;; fighters, stations.  The layout columns are item name, indicator-bar, before, change, and after.  
;;; Everything is static: the player gets what the player gets, and cannot make choices.  This means 
;;; that the resource table (along with everything else in the income phase) is for information only.
;;;
;;; Income Table
;;; - income-table-row: a single row
;;; - income-table:     assembles header (from ui/impact-table-header) and rows
;;;

(defn- income-table-row
  "Render one row for the income table.
  Single-row layout: the SVG bar column is hidden on mobile via CSS.

  [name str, before int, change int, key? bool, filter-id str] -> hiccup"
  [name before change key? filter-id]
  (let [after        (+ before change)
        change-class (cond (zero? change) "text-game-green-muted"
                           key?           "text-green-400 font-bold" ; Bold if a key value
                           :else          "text-green-400")]
    [:div.income-row
     {:class (str "items-center gap-2 py-1 px-3 border-b border-game-divider"
                  (when key? " bg-game-row"))}
     ;; Name
     [:div.text-base
      {:class (if key? "font-bold text-green-400" "text-game-green-muted")}
      name]
     ;; Indicator Bar
     [:div.hidden.md:block
      (ui/svg-indicator-bar :gain before after filter-id)]
     ;; Before
     [:div.text-base.text-right.text-game-green-muted
      (ui/format-number before)]
     ;; Change
     [:div.text-base.text-right.whitespace-nowrap
      {:class change-class
       :style (cond-> {:letter-spacing "0.03em"}
                (and key? (not (zero? change)))
                (assoc :text-shadow "0 0 8px rgba(74,222,128,0.6)"))}
      (if (zero? change) "+0" [:<> "+" (ui/format-number change)])]
     ;; After
     [:div.text-base.text-right
      {:class (if key? "text-green-400 font-bold" "text-game-green-soft")}
      (ui/format-number after)]]))

(defn- income-table
  "Render the income table: header and before/change/after rows with SVG bars.

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [credits-change (+ (:ore-credits income) (:tax-credits income))
        rows [{:name "Credits"                :before (:player/credits  player)
               :change credits-change         :key?   true}
              {:name "Food"                   :before (:player/food     player)
               :change (:erg-food income)     :key? true}
              {:name "Fuel"                   :before (:player/fuel     player)
               :change (:erg-fuel income)     :key? true}
              {:name "Soldiers"               :before (:player/soldiers player)
               :change (:mil-soldiers income) :key? false}
              {:name "Fighters"               :before (:player/fighters player)
               :change (:mil-fighters income) :key? false}
              {:name "Stations"               :before (:player/stations player)
               :change (:mil-stations income) :key? false}]]
    [:div.overflow-hidden.border.border-game-border.rounded-game.bg-game-surface
     ;; Call table header with income prefix
     (ui/impact-table-header "income" {:single-row? true})
     ;; Table rows
     (for [{:keys [name before change key?]} rows]
       (income-table-row name before change key? (str "glow-" (str/lower-case name))))]))


;;;;
;;;; Actions
;;;;

(defn apply-income
  "Apply planet income to the player and advance to the expenses phase.

  [ctx ring-ctx] -> ring-response (303 redirect to expenses)"
  [ctx]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 1 player-id)]
      redirect
      (let [income (calculate-income player game)
            after  (calculate-resources-after-income player income)]
        (biff/submit-tx ctx
          [{:db/doc-type          :player
            :db/op                :update
            :xt/id                player-id
            :player/credits       (:credits after)
            :player/food          (:food after)
            :player/fuel          (:fuel after)
            :player/soldiers      (:soldiers after)
            :player/fighters      (:fighters after)
            :player/stations      (:stations after)
            :player/current-phase 2
            :player/score         (utils/calculate-score
                                    game (assoc player
                                      :player/credits  (:credits after)
                                      :player/food     (:food after)
                                      :player/fuel     (:fuel after)
                                      :player/soldiers (:soldiers after)
                                      :player/fighters (:fighters after)
                                      :player/stations (:stations after)))}])
        {:status 303
         :headers {"location" (str "/app/game/" player-id "/expenses")}}))))


;;;;
;;;; Page
;;;;

(defn income-page
  "Show income page for the current turn. No player decisions required. Income is informational
  only, so no htmx dynamic updates are needed.

  [{:keys [player game flash]}] -> hiccup"
  [{:keys [player game flash]}]
  (let [player-id (:xt/id player)
        income    (calculate-income player game)]
    (ui/phase-shell
      player
      game 
      "Income Phase"
      (ui/phase-body 
        player
        ;; Flash if necessary
        (ui/flash-notice flash)
        ;; Empire status
        (ui/snapshot-section player)
        ;; Current resources
        (ui/section-label "Income" "‣ Current Turn")
        (income-grid player income)
        ;; Incoming resources
        (ui/section-label "Impact")
        (income-table player income))
      ;; Buttons
      (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (biff/form
          {:action (str "/app/game/" player-id "/apply-income") :method "post"
           :class  "m-0"}
          (ui/submit-button true "Continue to Expenses"))))))

