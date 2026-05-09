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
  {:credits     (+ (:player/credits player) (:ore-credits income) (:tax-credits income))
   :food        (+ (:player/food player) (:erg-food income))
   :fuel        (+ (:player/fuel player) (:erg-fuel income))
   :population  (:player/population player)
   :stability   (:player/stability player)
   :galaxars    (:player/galaxars player)
   :soldiers    (+ (:player/soldiers player) (:mil-soldiers income))
   :transports  (:player/transports player)
   :generals    (:player/generals player)
   :fighters    (+ (:player/fighters player) (:mil-fighters income))
   :carriers    (:player/carriers player)
   :admirals    (:player/admirals player)
   :stations    (+ (:player/stations player) (:mil-stations income))
   :cmd-ships   (:player/cmd-ships player)
   :agents      (:player/agents player)
   :ore-planets (:player/ore-planets player)
   :erg-planets (:player/erg-planets player)
   :mil-planets (:player/mil-planets player)})

;;;;
;;;; SVG Indicator Bar
;;;;

(defn- resource-bar
  "Render an SVG arrow indicator bar showing resource gain.

  [before int, after int, filter-id str] -> hiccup"
  [before after filter-id]
  (ui/svg-indicator-bar :gain before after filter-id))

;;;;
;;;; UI Components
;;;;


(defn- source-pills
  "Render income pills for a source card: one pill per non-zero resource.

  [income-map {:credits? int, :food? int, ...}] -> seq of hiccup"
  [income-map]
  (for [[k label suffix] [[:credits "+" "cr"] [:food "+" "food"] [:fuel "+" "fuel"]
                          [:soldiers "+" "sold"] [:fighters "+" "fgtr"] [:stations "+" "stn"]]
        :let [v (or (k income-map) 0)]
        :when (pos? v)]
    [:span.text-xs.inline-block.rounded-sm.text-green-400
     {:key k :style {:padding "1px 5px" :background "#1a3a28"}}
     label (ui/format-number v) " " suffix]))

(defn- source-card
  "Render one income source card with name, count, and resource pills.

  [{:keys [name count income-map]}] -> hiccup"
  [{:keys [name count income-map]}]
  [:div.flex.flex-col.gap-1
   {:style {:border "1px solid #253530" :border-radius "3px"
            :padding "6px 8px" :background "#1e1e1e"}}
   [:div.flex.justify-between.items-baseline
    [:span.text-base.font-bold.text-green-400 name]
    [:span.text-xs {:style {:color "#7ab88a"}} (str "x" count)]]
   [:div {:class "flex flex-col gap-0.5"}
    (source-pills income-map)]])

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
        (source-card src))]]))

(defn- resource-row
  "Render one resource row in two variants: mobile (no bar) and desktop (with bar).

  [name str, before int, delta int, key? bool, filter-id str] -> hiccup"
  [name before delta key? filter-id]
  (let [after       (+ before delta)
        row-class   "items-center gap-2 py-1 px-3"
        row-style   (merge {:border-bottom "1px solid #1a2820"}
                           (when key? {:background "#121a18"}))
        name-cell   [:div.text-base
                     {:class (when key? "font-bold")
                      :style (if key? {:color "#4ade80"} {:color "#7ab88a"})}
                     name]
        before-cell [:div.text-base.text-right
                     {:style {:color "#7ab88a"}}
                     (ui/format-number before)]
        delta-cell  [:div.text-base.text-right.whitespace-nowrap
                     {:style (merge {:letter-spacing "0.03em"}
                                    (cond
                                      (zero? delta) {:color "#7ab88a"}
                                      key?          {:color "#4ade80" :font-weight "bold"
                                                     :text-shadow "0 0 8px rgba(74,222,128,0.6)"}
                                      :else         {:color "#4ade80"}))}
                     (if (zero? delta) "-" [:<> "+" (ui/format-number delta)])]
        after-cell  [:div.text-base.text-right
                     {:style (if key?
                               {:color "#4ade80" :font-weight "bold"}
                               {:color "#9adaaa"})}
                     (ui/format-number after)]]
    [:<>
     ;; Mobile: 4-col grid, no bar
     [:div.income-row-mobile
      {:class (str "md:hidden " row-class)
       :style row-style}
      name-cell before-cell delta-cell after-cell]
     ;; Desktop: 5-col grid, with bar
     [:div.income-row-desktop
      {:class (str "hidden md:grid " row-class)
       :style row-style}
      name-cell (resource-bar before after filter-id) before-cell delta-cell after-cell]]))

(defn- resource-table-header
  "Render the column-label row for the resource table.
  Emits two variants: a 4-column mobile row (no bar column) and a 5-column
  desktop row (with an empty bar column between Item and the value columns).

  [] -> hiccup"
  []
  (let [header-style {:background "#151f1a" :border-bottom "1px solid #253530"}
        header-class "gap-2 py-1 px-3 items-center"
        col-style    {:letter-spacing "0.08em" :color "#4ade80"}
        item-style   (assoc col-style :letter-spacing "0.1em")
        col-label    (fn [text style extra-class]
                       [:span.text-xs.uppercase {:class extra-class :style style} text])
        value-label  (fn [text]
                       (col-label text col-style "text-right justify-self-end"))]
    [:<>
     [:div.income-row-mobile
      {:class (str "md:hidden " header-class) :style header-style}
      (col-label "Item" item-style nil)
      (value-label "before")
      (value-label "change")
      (value-label "after")]
     [:div.income-row-desktop
      {:class (str "hidden md:grid " header-class) :style header-style}
      (col-label "Item" item-style nil)
      [:span]
      (value-label "before")
      (value-label "change")
      (value-label "after")]]))

(defn- resource-table
  "Render the Resource Changes table with before/delta/after columns and SVG bars.

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [rows [{:name "Credits"  :before (:player/credits  player) :delta (+ (:ore-credits income) (:tax-credits income)) :key? true}
              {:name "Food"     :before (:player/food     player) :delta (:erg-food  income) :key? true}
              {:name "Fuel"     :before (:player/fuel     player) :delta (:erg-fuel  income) :key? true}
              {:name "Soldiers" :before (:player/soldiers player) :delta (:mil-soldiers income) :key? false}
              {:name "Fighters" :before (:player/fighters player) :delta (:mil-fighters income) :key? false}
              {:name "Stations" :before (:player/stations player) :delta (:mil-stations income) :key? false}]]
    [:div.overflow-hidden
     {:style {:border "1px solid #253530" :border-radius "3px" :background "#161616"}}
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
  "Show income preview for the current turn. No player decisions required — income is informational
  only, so no htmx dynamic updates are needed.

  [{:keys [player game]}] -> hiccup"
  [{:keys [player game]}]
  (let [player-id (:xt/id player)
        income    (calculate-income player game)]
    (ui/page
      {}
      ;; Terminal shell: text-base sets the base font size for all descendants
      [:div.text-base.w-full.max-w-4xl.mx-auto.overflow-hidden.relative
       {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
                :border-radius "4px" :color "#4ade80"
                :font-family "'Courier New', monospace"}}
       ;; Scanline overlay
       (ui/scanline-overlay)

       ;; Topbar
       (ui/phase-topbar player "INCOME PHASE")

       ;; Body
       [:div.flex.flex-col.gap-2
        {:style {:padding "10px 14px"}}
        (source-grid player income)
        (resource-table player income)
        (ui/incoming-alert player)]

       ;; Action bar
       [:div.flex.gap-2
        {:style {:padding "8px 14px" :border-top "1px solid #253530"}}
        (let [btn-base {:padding "5px 14px" :font-family "'Courier New', monospace"
                        :letter-spacing "0.05em" :border-radius "2px"}]
          (list
            (ui/action-bar-link (str "/app/game/" player-id) "Pause")
            (biff/form
              {:action (str "/app/game/" player-id "/apply-income") :method "post"
               :style  {:margin 0}}
              [:button.text-sm
               {:type  "submit"
                :style (merge btn-base {:border "1px solid #4ade80" :background "#1a3a28"
                                        :color "#4ade80"})}
               "Continue to Expenses"])))]])))

