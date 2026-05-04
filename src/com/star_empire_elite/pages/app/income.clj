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
   :erg-food  (* (:player/erg-planets player) (:game/erg-planet-food game))
   :erg-fuel  (* (:player/erg-planets player) (:game/erg-planet-fuel game))
   :mil-soldiers (* (:player/mil-planets player) (:game/mil-planet-soldiers game))
   :mil-fighters (* (:player/mil-planets player) (:game/mil-planet-fighters game))
   :mil-stations (* (:player/mil-planets player) (:game/mil-planet-stations game))
   :tax-credits  (* (:player/population player)  (:game/population-tax-credits game))})

(defn calculate-resources-after-income
  "Calculate all player resources after applying income.

  [player player-map, income income-map] -> {:credits int, :food int, ...}"
  [player income]
  {:credits      (+ (:player/credits player)  (:ore-credits income) (:tax-credits income))
   :food         (+ (:player/food player)     (:erg-food income))
   :fuel         (+ (:player/fuel player)     (:erg-fuel income))
   :population   (:player/population player)
   :stability    (:player/stability player)
   :galaxars     (:player/galaxars player)
   :soldiers     (+ (:player/soldiers player) (:mil-soldiers income))
   :transports   (:player/transports player)
   :generals     (:player/generals player)
   :fighters     (+ (:player/fighters player) (:mil-fighters income))
   :carriers     (:player/carriers player)
   :admirals     (:player/admirals player)
   :stations     (+ (:player/stations player) (:mil-stations income))
   :cmd-ships    (:player/cmd-ships player)
   :agents       (:player/agents player)
   :ore-planets  (:player/ore-planets player)
   :erg-planets  (:player/erg-planets player)
   :mil-planets  (:player/mil-planets player)})

;;;;
;;;; SVG Indicator Bar
;;;;

(def ^:private nice-scales
  [10 25 50 75
   100 250 500 750
   1000 2500 5000 7500
   10000 25000 50000 75000
   100000 250000 500000 750000
   1000000 2500000 5000000 7500000
   10000000 25000000 50000000 75000000
   100000000])

(defn- choose-scale-max [before after]
  (let [target (* 1.2 (max before after))]
    (or (first (filter #(>= % target) nice-scales))
        (last nice-scales))))

(defn- fmt-tick [v]
  (let [r (long (Math/round (double v)))]
    (cond
      (zero? r)    "0"
      (>= r 1000000) (-> (format "%.1f" (/ (double r) 1000000))
                         (str/replace #"\.0$" "")
                         (str "M"))
      (>= r 1000)  (str (long (Math/round (double (/ r 1000)))) "K")
      :else        (str r))))

(defn- resource-bar
  "Render an SVG arrow indicator bar showing the before→after transition on a nice per-row scale.

  [before int, after int, filter-id str] -> hiccup"
  [before after filter-id]
  (let [scale-max  (choose-scale-max before after)
        x-before   (min (* (/ (double before) scale-max) 100.0) 100.0)
        x-after    (min (* (/ (double after)  scale-max) 100.0) 100.0)
        has-arrow? (> after before)
        ly         9
        arrow-w    2.5
        arrow-h    1.8]
    [:div {:style {:height "30px" :position "relative"}}
     [:svg {:viewBox "0 0 100 30"
            :preserveAspectRatio "none"
            :style {:display "block" :width "100%" :height "100%" :overflow "visible"}}
      [:defs
       [:filter {:id filter-id :x "-50%" :y "-50%" :width "200%" :height "200%"}
        [:feGaussianBlur {:stdDeviation "0.8" :result "blur"}]
        [:feMerge
         [:feMergeNode {:in "blur"}]
         [:feMergeNode {:in "SourceGraphic"}]]]]
      [:line {:x1 0 :y1 ly :x2 100 :y2 ly :stroke "#152a1e" :stroke-width "0.8"}]
      (when has-arrow?
        (list
          [:line {:x1 x-before :y1 ly
                  :x2 (- x-after (* arrow-w 0.55)) :y2 ly
                  :stroke "#3ddc84" :stroke-width "1.2"
                  :filter (str "url(#" filter-id ")")}]
          [:polygon {:points (str x-after "," ly " "
                                  (- x-after arrow-w) "," (- ly arrow-h) " "
                                  (- x-after arrow-w) "," (+ ly arrow-h))
                     :fill "#3ddc84"
                     :filter (str "url(#" filter-id ")")}]))
      (for [pct [0 0.25 0.5 0.75 1.0]
            :let [tx (* pct 100)
                  v  (* pct scale-max)]]
        [:g {:key pct}
         [:line {:x1 tx :y1 (+ ly 3) :x2 tx :y2 (+ ly 5)
                 :stroke "#2a4a38" :stroke-width "0.6"}]
         [:text {:x tx :y (+ ly 13) :text-anchor "middle"
                 :font-size "4.5" :fill "#3a6050"
                 :font-family "'Courier New', monospace"}
          (fmt-tick v)]])]]))

;;;;
;;;; UI Components
;;;;

(defn- phase-stepper
  "Render the topbar phase stepper. Current phase is highlighted; done phases show ✓; future phases are dim.

  [current-phase int] -> hiccup"
  [current-phase]
  (let [labels ["INC" "EXP" "MIL" "DIP" "TRD" "END"]]
    [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
     (for [[i label] (map-indexed vector labels)
           :let [phase   (inc i)
                 active? (= phase current-phase)
                 done?   (< phase current-phase)]]
       [:div {:key phase :style {:display "flex" :align-items "center" :gap "4px"}}
        [:div {:style (merge {:width "22px" :height "22px" :border-radius "50%"
                              :border "1.5px solid #1e6e44"
                              :display "flex" :align-items "center" :justify-content "center"
                              :font-size "9px"}
                             (cond
                               active? {:border-color "#3ddc84" :color "#3ddc84" :background "#1a3a28"}
                               done?   {:border-color "#1e6e44" :background "#162a1e" :color "#2a9058"}
                               :else   {:color "#4a7a5a"}))}
         (if done? "✓" label)]
        (when (< phase 6)
          [:span {:style {:color "#4a7a5a" :font-size "9px"}} "›"])])]))

(defn- source-pills
  "Render income pills for a source card: one pill per non-zero resource.

  [income-map {:credits? int, :food? int, ...}] -> seq of hiccup"
  [income-map]
  (for [[k label suffix] [[:credits "+" "cr"] [:food "+" "food"] [:fuel "+" "fuel"]
                          [:soldiers "+" "sol"] [:fighters "+" "ftr"] [:stations "+" "stn"]]
        :let [v (or (k income-map) 0)]
        :when (pos? v)]
    [:span {:key k
            :style {:display "inline-block" :padding "1px 5px" :border-radius "2px"
                    :font-size "9px" :background "#1a3a28" :color "#3ddc84"}}
     (str label (ui/format-number-str v) " " suffix)]))

(defn- source-card
  "Render one income source card with name, count, and resource pills.

  [{:keys [name count income-map]}] -> hiccup"
  [{:keys [name count income-map]}]
  [:div {:style {:border "1px solid #253530" :border-radius "3px"
                 :padding "6px 8px" :background "#1e1e1e"
                 :display "flex" :flex-direction "column" :gap "4px"}}
   [:div {:style {:display "flex" :justify-content "space-between" :align-items "baseline"}}
    [:span {:style {:color "#2a9058" :font-size "11px" :font-weight "bold"}} name]
    [:span {:style {:color "#4a7a5a" :font-size "9px"}} (str "×" count)]]
   [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}
    (source-pills income-map)]])

(defn- source-grid
  "Render the 4-column income sources grid (Ore, Erg, Mil, Pop).

  [player player-map, income income-map] -> hiccup"
  [player income]
  (let [pop-count (-> (format "%.1f" (double (:player/population player)))
                      (str/replace #"\.0$" "")
                      (str "M"))
        sources [{:name "Ore" :count (:player/ore-planets player)
                  :income-map {:credits (:ore-credits income)}}
                 {:name "Erg" :count (:player/erg-planets player)
                  :income-map {:food (:erg-food income) :fuel (:erg-fuel income)}}
                 {:name "Mil" :count (:player/mil-planets player)
                  :income-map {:soldiers (:mil-soldiers income)
                               :fighters (:mil-fighters income)
                               :stations (:mil-stations income)}}
                 {:name "Pop" :count pop-count
                  :income-map {:credits (:tax-credits income)}}]]
    [:div
     [:div {:style {:font-size "9px" :text-transform "uppercase" :letter-spacing "0.12em"
                    :color "#4a7a5a" :margin-bottom "4px"}}
      "Income Sources"]
     [:div {:style {:display "grid" :grid-template-columns "repeat(4, 1fr)" :gap "6px"}}
      (for [src sources]
        (source-card src))]]))

(defn- resource-row
  "Render one resource row: name | SVG bar | before | delta | after.

  [name str, before int, delta int, key? bool, filter-id str] -> hiccup"
  [name before delta key? filter-id]
  (let [after (+ before delta)]
    [:div {:style (merge {:display "grid"
                          :grid-template-columns "80px 1fr 64px 68px 72px"
                          :align-items "center"
                          :gap "8px"
                          :padding "4px 12px"
                          :border-bottom "1px solid #1a2820"}
                         (when key? {:background "#121a18"}))}
     ;; Name
     [:div {:style (if key?
                     {:font-size "11px" :color "#2a9058" :font-weight "bold"}
                     {:font-size "10px" :color "#4a7a5a"})}
      (when key? [:span {:style {:margin-right "3px" :font-size "8px" :color "#3ddc84"}} "★"])
      name]
     ;; SVG indicator bar
     (resource-bar before after filter-id)
     ;; Before
     [:div {:style {:text-align "right" :color "#4a7a5a" :font-size "9px"}}
      (ui/format-number before)]
     ;; Delta
     [:div {:style (merge {:text-align "right" :white-space "nowrap"
                           :letter-spacing "0.03em"}
                          (cond
                            (zero? delta) {:color "#4a7a5a" :font-size "10px"}
                            key?          {:color "#3ddc84" :font-size "12px" :font-weight "bold"
                                           :text-shadow "0 0 8px rgba(61,220,132,0.6)"}
                            :else         {:color "#3ddc84" :font-size "10px"}))}
      (if (zero? delta)
        "—"
        [:<> "+" (ui/format-number delta)])]
     ;; After
     [:div {:style (if key?
                     {:text-align "right" :font-size "13px" :color "#3ddc84" :font-weight "bold"}
                     {:text-align "right" :font-size "10px" :color "#6aaa80"})}
      (ui/format-number after)]]))

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
    [:div {:style {:border "1px solid #253530" :border-radius "3px" :background "#161616" :overflow "hidden"}}
     ;; Table header
     [:div {:style {:background "#151f1a" :border-bottom "1px solid #253530"
                    :padding "4px 12px" :display "flex"
                    :justify-content "space-between" :align-items "center"}}
      [:span {:style {:font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                      :color "#2a9058"}}
       "Resource Changes"]
      [:span {:style {:font-size "8px" :color "#4a7a5a" :letter-spacing "0.08em"}}
       "INCOME ONLY"]]
     ;; Rows
     (for [{:keys [name before delta key?]} rows]
       (resource-row name before delta key? (str "glow-" (clojure.string/lower-case name))))
     ;; Footer labels
     [:div {:style {:display "grid" :grid-template-columns "80px 1fr 64px 68px 72px"
                    :gap "8px" :padding "3px 12px"
                    :border-top "1px solid #253530"}}
      [:div] [:div]
      [:div {:style {:font-size "8px" :color "#4a7a5a" :text-align "right"}} "before"]
      [:div {:style {:font-size "8px" :color "#4a7a5a" :text-align "right"}} "change"]
      [:div {:style {:font-size "8px" :color "#4a7a5a" :text-align "right"}} "after"]]]))

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
      ;; Terminal shell
      [:div {:style {:max-width "896px" :width "100%" :margin "0 auto"
                     :background "#0e0e0e" :border "1.5px solid #1e6e44"
                     :border-radius "4px" :overflow "hidden"
                     :color "#c8e6c9" :font-size "11px"
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
         [:div {:style {:color "#3ddc84" :font-size "13px" :font-weight "bold"
                        :letter-spacing "0.05em"}}
          (:player/empire-name player)]
         [:div {:style {:color "#6aaa80" :font-size "10px" :margin-top "1px"}}
          (str "INCOME PHASE · Turn " (:player/current-turn player)
               " · Round " (:player/current-round player))]]
        (phase-stepper (:player/current-phase player))]

       ;; Body
       [:div {:style {:padding "10px 14px" :display "flex" :flex-direction "column" :gap "8px"}}
        (source-grid player income)
        (resource-table player income)
        (ui/incoming-alert player)]

       ;; Action bar
       [:div {:style {:padding "8px 14px" :border-top "1px solid #253530"
                      :display "flex" :gap "8px"}}
        [:a {:href  (str "/app/game/" player-id)
             :style {:padding "5px 14px" :font-size "10px"
                     :font-family "'Courier New', monospace"
                     :border "1px solid #1e6e44" :background "transparent"
                     :color "#6aaa80" :cursor "pointer" :letter-spacing "0.05em"
                     :border-radius "2px" :text-decoration "none"}}
         "Pause"]
        (biff/form
          {:action (str "/app/game/" player-id "/apply-income") :method "post"
           :style  {:margin 0}}
          [:button {:type  "submit"
                    :style {:padding "5px 14px" :font-size "10px"
                            :font-family "'Courier New', monospace"
                            :border "1px solid #3ddc84" :background "#1e6e44"
                            :color "#3ddc84" :cursor "pointer" :letter-spacing "0.05em"
                            :border-radius "2px"}}
           "Continue to Expenses »"])]])))
