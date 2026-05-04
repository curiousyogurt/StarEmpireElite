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

(def ^:private nice-scale-multipliers
  [1 2.5 5 7.5])

(def ^:private nice-scales
  "Nice scale ceilings for resource bars.

  Generates:
  10, 25, 50, 75,
  100, 250, 500, 750,
  ...
  up through decillion-scale values."
  (vec
    (for [exp (range 1 35)
          m   nice-scale-multipliers]
      (* m (Math/pow 10 exp)))))

(defn- choose-scale-max
  "Choose a nice scale ceiling for a resource bar.

  [before number, after number] -> number"
  [before after]
  (let [needed (max 1 (double before) (double after))]
    (or (first (drop-while #(< % needed) nice-scales))
        needed)))

(defn- fmt-tick [v]
  (let [abs-v (Math/abs (double v))
        sign  (if (neg? (double v)) "-" "")]
    (cond
      (< abs-v 1000) (format "%.0f" (double v))
      (< abs-v 1000000) (let [s (format "%.1f" (/ abs-v 1000.0))]
                          (str sign
                               (str/replace s #"\.0$" "")
                               "K"))
      :else
      (ui/format-number-str v))))

(defn- resource-bar
  "Render an SVG arrow indicator bar with HTML tick labels below.

  [before int, after int, filter-id str] -> hiccup"
  [before after filter-id]
  (let [scale-max  (choose-scale-max before after)
        x-before   (min (* (/ (double before) scale-max) 100.0) 100.0)
        x-after    (min (* (/ (double after)  scale-max) 100.0) 100.0)
        has-arrow? (> after before)
        ly         5
        arrow-w    3.5
        arrow-h    2.5]
    [:div {:style {:display "flex" :flex-direction "column" :justify-content "center"
                   :height "100%" :padding "0px 32px"}}
     ;; SVG: track line, tick marks, and arrow only -- no text in SVG
     [:svg {:viewBox "0 0 100 10"
            :preserveAspectRatio "none"
            :style {:display "block" :width "100%" :height "8px" :overflow "visible"
                    :margin-top "4px" :margin-bottom "4px"}}
      [:defs
       [:filter {:id filter-id :x "-50%" :y "-50%" :width "200%" :height "200%"}
        [:feGaussianBlur {:stdDeviation "0.8" :result "blur"}]
        [:feMerge
         [:feMergeNode {:in "blur"}]
         [:feMergeNode {:in "SourceGraphic"}]]]]
      [:line {:x1 0 :y1 ly :x2 100 :y2 ly :stroke "#152a1e" :stroke-width "0.8"}]
      (when has-arrow?
        (let [tip-x  (- x-after arrow-w)
              base-x (- x-after (* 2 arrow-w))]
          (list
            [:line {:x1 x-before :y1 ly
                    :x2 (- base-x (* arrow-w 0.55)) :y2 ly
                    :stroke "#4ade80" :stroke-width "1.2"
                    :filter (str "url(#" filter-id ")")}]
            [:polygon {:points (str tip-x "," ly " "
                                    base-x "," (- ly arrow-h) " "
                                    base-x "," (+ ly arrow-h))
                       :fill "#4ade80"
                       :filter (str "url(#" filter-id ")")}])))
      [:line {:x1 0   :y1 (+ ly 1) :x2 0   :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 25  :y1 (+ ly 1) :x2 25  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 50  :y1 (+ ly 1) :x2 50  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 75  :y1 (+ ly 1) :x2 75  :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]
      [:line {:x1 100 :y1 (+ ly 1) :x2 100 :y2 (+ ly 3) :stroke "#2a4a38" :stroke-width "0.6"}]]
     ;; Tick labels: absolutely positioned so each label centres on its tick mark
     [:div.text-xs.text-gray-400 {:style {:position "relative" :height "1em" :margin-bottom "8px"}}
      [:span {:style {:position "absolute" :left "0"   :transform "translateX(0%)"}}   "0"]
      [:span {:style {:position "absolute" :left "50%" :transform "translateX(-50%)"}} (fmt-tick (* 0.5 scale-max))]
      [:span {:style {:position "absolute" :right "0"  :transform "translateX(0%)"}}  (fmt-tick scale-max)]]]))

;;;;
;;;; UI Components
;;;;

(defn- phase-stepper
  "Render the topbar phase stepper. Current phase is highlighted; done phases show a checkmark.

  [current-phase int] -> hiccup"
  [current-phase]
  (let [labels ["1" "2" "3" "4" "5" "6"]]
    [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
     (for [[i label] (map-indexed vector labels)
           :let [phase   (inc i)
                 active? (= phase current-phase)
                 done?   (< phase current-phase)]]
       [:div {:key phase :style {:display "flex" :align-items "center" :gap "4px"}}
        ;; text-xs for phase circle labels
        [:div.text-xs {:style (merge {:width "22px" :height "22px" :border-radius "50%"
                                      :border "1.5px solid #1e6e44"
                                      :display "flex" :align-items "center" :justify-content "center"}
                                     (cond
                                       active? {:border-color "#4ade80" :color "#4ade80" :background "#1a3a28"}
                                       done?   {:border-color "#1e6e44" :background "#162a1e" :color "#4ade80"}
                                       :else   {:color "#7ab88a"}))}
         (if done? "✓" label)]
        (when (< phase 6)
          [:span.text-xs {:style {:color "#7ab88a"}} "›"])])]))

(defn- source-pills
  "Render income pills for a source card: one pill per non-zero resource.

  [income-map {:credits? int, :food? int, ...}] -> seq of hiccup"
  [income-map]
  (for [[k label suffix] [[:credits "+" "cr"] [:food "+" "food"] [:fuel "+" "fuel"]
                          [:soldiers "+" "sold"] [:fighters "+" "fgtr"] [:stations "+" "dstn"]]
        :let [v (or (k income-map) 0)]
        :when (pos? v)]
    ;; text-xs for pill labels
    [:span.text-xs {:key k
                    :style {:display "inline-block" :padding "1px 5px" :border-radius "2px"
                            :background "#1a3a28" :color "#4ade80"}}
     label (ui/format-number v) " " suffix]))

(defn- source-card
  "Render one income source card with name, count, and resource pills.

  [{:keys [name count income-map]}] -> hiccup"
  [{:keys [name count income-map]}]
  [:div {:style {:border "1px solid #253530" :border-radius "3px"
                 :padding "6px 8px" :background "#1e1e1e"
                 :display "flex" :flex-direction "column" :gap "4px"}}
   [:div {:style {:display "flex" :justify-content "space-between" :align-items "baseline"}}
    ;; text-base for source name
    [:span.text-base.font-bold {:style {:color "#4ade80"}} name]
    ;; text-xs for count
    [:span.text-xs {:style {:color "#7ab88a"}} (str "x" count)]]
   [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}
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
                 {:name "Tax" :count pop-count
                  :income-map {:credits (:tax-credits income)}}]]
    [:div
     ;; text-xs for section label
     [:div.text-xs {:style {:text-transform "uppercase" :letter-spacing "0.12em"
                            :color "#7ab88a" :margin-bottom "4px"}}
      "Sources"]
     [:div {:class "grid grid-cols-2 md:grid-cols-4" :style {:gap "6px"}}
      (for [src sources]
        (source-card src))]]))

(defn- resource-row
  "Render one resource row in two variants: mobile (no bar) and desktop (with bar).

  [name str, before int, delta int, key? bool, filter-id str] -> hiccup"
  [name before delta key? filter-id]
  (let [after      (+ before delta)
        row-style  (merge {:align-items "center" :gap "8px" :padding "4px 12px"
                           :border-bottom "1px solid #1a2820"}
                          (when key? {:background "#121a18"}))
        name-cell  [:div.text-base {:style (if key? {:color "#4ade80" :font-weight "bold"} {:color "#7ab88a"})} name]
        before-cell [:div.text-base {:style {:text-align "right" :color "#7ab88a"}} (ui/format-number before)]
        delta-cell [:div.text-base {:style (merge {:text-align "right" :white-space "nowrap" :letter-spacing "0.03em"}
                                                  (cond
                                                    (zero? delta) {:color "#7ab88a"}
                                                    key?          {:color "#4ade80" :font-weight "bold"
                                                                   :text-shadow "0 0 8px rgba(74,222,128,0.6)"}
                                                    :else         {:color "#4ade80"}))}
                    (if (zero? delta) "-" [:<> "+" (ui/format-number delta)])]
        after-cell [:div.text-base {:style (if key?
                                             {:text-align "right" :color "#4ade80" :font-weight "bold"}
                                             {:text-align "right" :color "#9adaaa"})} (ui/format-number after)]]
    [:<>
     ;; Mobile: 4-col grid, no bar
     [:div.income-row-mobile {:class "md:hidden" :style row-style}
      name-cell before-cell delta-cell after-cell]
     ;; Desktop: 5-col grid, with bar
     [:div.income-row-desktop {:class "hidden md:grid" :style row-style}
      name-cell (resource-bar before after filter-id) before-cell delta-cell after-cell]]))

(defn- resource-table-header []
  (let [header-style {:gap "8px"
                      :background "#151f1a"
                      :border-bottom "1px solid #253530"
                      :padding "4px 12px"
                      :align-items "center"}
        label-style  {:letter-spacing "0.08em"
                      :text-transform "uppercase"
                      :color "#4ade80"}
        item-style   (assoc label-style :letter-spacing "0.1em")
        right-style  (assoc label-style
                            :justify-self "end"
                            :text-align "right")
        label        (fn [text style]
                       [:span.text-xs {:style style} text])]
    [:<>
     ;; Mobile header: Item / before / change / after
     [:div.income-row-mobile
      {:class "md:hidden"
       :style header-style}
      (label "Item" item-style)
      (label "before" right-style)
      (label "change" right-style)
      (label "after" right-style)]

     ;; Desktop header: Item / blank bar column / before / change / after
     [:div.income-row-desktop
      {:class "hidden md:grid"
       :style header-style}
      (label "Item" item-style)
      [:span]
      (label "before" right-style)
      (label "change" right-style)
      (label "after" right-style)]]))

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
    [:div {:style {:border "1px solid #253530"
                   :border-radius "3px"
                   :background "#161616"
                   :overflow "hidden"}}
     (resource-table-header)

     ;; Rows
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
      [:div.text-base.w-full.max-w-4xl.mx-auto
       {:style {:background "#0e0e0e" :border "1.5px solid #1e6e44"
                :border-radius "4px" :overflow "hidden"
                :color "#4ade80"
                :font-family "'Courier New', monospace"
                :position "relative"}}
       ;; Scanline overlay
       [:div {:style {:position "absolute" :top 0 :right 0 :bottom 0 :left 0
                      :pointer-events "none" :z-index 10
                      :background "repeating-linear-gradient(to bottom, transparent 0px, transparent 2px, rgba(0,0,0,0.07) 2px, rgba(0,0,0,0.07) 3px)"}}]

       ;; Topbar
       [:div {:style {:background "#161616" :border-bottom "1px solid #1e6e44"
                      :padding "7px 14px" :display "flex"
                      :align-items "center" :justify-content "space-between"}}
        [:div
         ;; text-3xl for empire name, matching expenses.clj h1
         [:div.text-3xl.font-bold {:style {:color "#4ade80" :letter-spacing "0.05em"}}
          (:player/empire-name player)]
         ;; text-sm for subtitle
         [:div.text-sm {:style {:color "#9adaaa" :margin-top "1px"}}
          (str "INCOME PHASE · Turn " (:player/current-turn player)
               " · Round " (:player/current-round player))]]
        (phase-stepper (:player/current-phase player))]

       ;; Body
       [:div {:style {:padding "10px 14px" :display "flex" :flex-direction "column" :gap "8px"}}
        (source-grid player income)
        (resource-table player income)
        (ui/incoming-alert player)]

       ;; Action bar: text-sm for buttons, matching expenses.clj button style
       [:div {:style {:padding "8px 14px" :border-top "1px solid #253530"
                      :display "flex" :gap "8px"}}
        [:a.text-sm {:href  (str "/app/game/" player-id)
                     :style {:padding "5px 14px"
                             :font-family "'Courier New', monospace"
                             :border "1px solid #1e6e44" :background "transparent"
                             :color "#9adaaa" :letter-spacing "0.05em"
                             :border-radius "2px" :text-decoration "none"}}
         "Pause"]
        (biff/form
          {:action (str "/app/game/" player-id "/apply-income") :method "post"
           :style  {:margin 0}}
          [:button.text-sm {:type  "submit"
                            :style {:padding "5px 14px"
                                    :font-family "'Courier New', monospace"
                                    :border "1px solid #4ade80" :background "#1a3a28"
                                    :color "#4ade80" :letter-spacing "0.05em"
                                    :border-radius "2px"}}
           "Continue to Expenses"])]])))
