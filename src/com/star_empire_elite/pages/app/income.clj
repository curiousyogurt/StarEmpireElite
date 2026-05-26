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
;;; Source grid:
;;; - income-rows: the rows of each income card
;;; - source-grid: four cards for the income sources grid
;;;

(defn- income-rows
  "Convert an income-map to the row-spec format expected by ui/info-card.
  Iterates the six resource keys in display order, skipping any with zero value.

  [income-map {:credits? int, :food? int, ...}] -> seq of {:key, :value, :sign, :unit}"
  [income-map]
  (for [[k unit] [[:credits  "cred"] [:food     "food"] [:fuel     "fuel"]
                  [:soldiers "sold"] [:fighters "fgtr"] [:stations "stn"]]
        :let [v (k income-map)]
        :when (and v (pos? v))] ; include only if v is present (~nil) and pos? (~zero)
    {:key k :value v :sign "+" :unit unit}))

(defn- source-grid
  "Render the income sources grid: four cards (Ore, Erg, Mil, Tax) in a 2×4 responsive grid,
  each showing planet/population count and the resources produced this turn.

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [pop-count (ui/format-population (:player/population player))
        sources   [{:key :ore :title "Ore" :aside (str "x" (:player/ore-planets player))
                    :income-map {:credits (:ore-credits income)}}

                   {:key :erg :title "Erg" :aside (str "x" (:player/erg-planets player))
                    :income-map {:food (:erg-food income)
                                 :fuel (:erg-fuel income)}}

                   {:key :mil :title "Mil" :aside (str "x" (:player/mil-planets player))
                    :income-map {:soldiers (:mil-soldiers income)
                                 :fighters (:mil-fighters income)
                                 :stations (:mil-stations income)}}

                   {:key :taxes :title "Tax" :aside (str "x" pop-count)
                    :income-map {:credits (:tax-credits income)}}]]
    (ui/info-grid
      {:label "Income"
       :grid-class "grid grid-cols-2 md:grid-cols-4 gap-1.5"
       :cards
       (for [{:keys [key title aside income-map]} sources]
         {:key key
          :title title
          :aside aside
          :rows (income-rows income-map)})})))

;;;
;;; Resource table 
;;; - resource-table-row: a single row
;;; - resource-table: put it all together
;;;

(defn- resource-table-row
  "Render one resource row for the resource table.
  Emits two variants: a 4-column mobile row (no bar column) and a 5-column
  desktop row (with the SVG indicator bar), aligned with resource-table-header.

  [name str, before int, delta int, key? bool, filter-id str] -> hiccup"
  [name before delta key? filter-id]
  (let [after        (+ before delta)
        row-class    (str "items-center gap-2 py-1 px-3 border-b border-game-divider"
                          (when key? " bg-game-row"))
        name-cell    [:div.text-base
                      {:class (if key? "font-bold text-green-400" "text-game-green-muted")}
                      name]
        before-cell  [:div.text-base.text-right.text-game-green-muted
                      (ui/format-number before)]
        ;; Give a resource that has changed or is key a green glow; otherwise green muted
        change-class (cond (zero? delta) "text-game-green-muted"
                           key?          "text-green-400 font-bold"
                           :else         "text-green-400")
        change-cell  [:div.text-base.text-right.whitespace-nowrap
                      {:class change-class
                       :style (cond-> {:letter-spacing "0.03em"}
                                (and key? (not (zero? delta)))
                                (assoc :text-shadow "0 0 8px rgba(74,222,128,0.6)"))}
                      (if (zero? delta) "—" [:<> "+" (ui/format-number delta)])]
        after-cell   [:div.text-base.text-right
                      {:class (if key? "text-green-400 font-bold" "text-game-green-soft")}
                      (ui/format-number after)]]
    [:<>

     ;; Mobile
     [:div.income-row-mobile
      {:class (str "md:hidden " row-class)}
      name-cell 
      before-cell 
      change-cell 
      after-cell]

     ;; Desktop
     [:div.income-row-desktop
      {:class (str "hidden md:grid " row-class)}
      name-cell
      (ui/svg-indicator-bar :gain before after filter-id)
      before-cell 
      change-cell 
      after-cell]]))

(defn- resource-table
  "Render the Resource Changes table with before/delta/after columns and SVG bars.

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [credits-delta (+ (:ore-credits income) (:tax-credits income))
        rows [{:name "Credits"               :before (:player/credits  player) 
               :delta credits-delta          :key?   true}
              {:name "Food"                  :before (:player/food     player)
               :delta (:erg-food income)     :key? true}
              {:name "Fuel"                  :before (:player/fuel     player) 
               :delta (:erg-fuel income)     :key? true}
              {:name "Soldiers"              :before (:player/soldiers player) 
               :delta (:mil-soldiers income) :key? false}
              {:name "Fighters"              :before (:player/fighters player) 
               :delta (:mil-fighters income) :key? false}
              {:name "Stations"              :before (:player/stations player) 
               :delta (:mil-stations income) :key? false}]]
    [:div.overflow-hidden.border.border-game-border.rounded-game.bg-game-surface
     (ui/impact-table-header "income")
     (for [{:keys [name before delta key?]} rows]
       (resource-table-row name before delta key? (str "glow-" (str/lower-case name))))]))


;;;;
;;;; Page
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

(defn income-page
  "Show income page for the current turn. No player decisions required. Income is informational
  only, so no htmx dynamic updates are needed.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game flash]}]
  (let [player-id (:xt/id player)
        income    (calculate-income player game)]
    ;; Page header
    (ui/phase-shell 
      player
      game 
      "Income Phase"
      (ui/phase-body player
        ;; Flash if necessary
        (ui/flash-notice flash)
        ;; Empire status
        (ui/snapshot-section player)
        ;; Current resources
        (source-grid player income)
        ;; Incoming resources
        (resource-table player income))
      ;; Buttons
      (ui/phase-action-bar
        (ui/action-bar-link (str "/app/game/" player-id) "Pause")
        (biff/form
          {:action (str "/app/game/" player-id "/apply-income") :method "post"
           :class  "m-0"}
          (ui/submit-button true "Continue to Expenses"))))))

