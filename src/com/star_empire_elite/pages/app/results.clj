;;;;;
;;;;; Hall of Fame - Completed Game Standings
;;;;;
;;;;; Lists every completed game an admin has archived (see app/delete-game), newest first.
;;;;; Each game is a collapsible card — collapsed it shows the game name and winning empire;
;;;;; expanded it shows the full survivor standings that were captured at archive time.
;;;;;

(ns com.star-empire-elite.pages.app.results
  (:require [com.biffweb :as biff :refer [q]]
            [com.star-empire-elite.ui :as ui]))

;;;;
;;;; Data Fetching
;;;;

(defn get-game-results
  "Fetch all archived game results, newest-first by archive time.

  [db xtdb-db] -> [game-result-map]"
  [db]
  (->> (q db '{:find (pull result [*])
               :where [[result :game-result/game-name _]]})
       (sort-by :game-result/ended-at #(compare %2 %1))))

;;;;
;;;; UI Components
;;;;

(defn- format-date
  "Format an inst as a plain calendar date, e.g. '2026-09-05'.

  [d inst] -> string"
  [d]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") d))

(defn standings-table
  "Render a completed game's final standings, ranked by score.

  [standings [standing-map]] -> hiccup"
  [standings]
  (let [th-cls       "text-green-400 text-[11px] tracking-[0.08em] uppercase"
        td-cls       "border-r border-game-border py-1 px-3 text-game-green-soft"
        td-right-cls "border-r border-game-border py-1 px-3 text-game-green-soft text-right"]
    [:div.overflow-x-auto
     [:table.w-full.text-sm
      {:class "border border-game-border border-collapse"}
      [:thead
       [:tr.bg-game-header.border-b.border-game-border
        [:th.text-left.px-3.py-1  {:class th-cls} "Rank"]
        [:th.text-left.px-3.py-1  {:class th-cls} "Empire"]
        [:th.text-right.px-3.py-1 {:class th-cls} "Planets"]
        [:th.text-right.px-3.py-1 {:class th-cls} "Score"]]]
      [:tbody
       (for [standing standings]
         [:tr.bg-game-row.border-b.border-game-divider
          [:td {:class td-cls} (:standing/rank standing)]
          [:td {:class td-cls} (:standing/empire-name standing)]
          [:td {:class td-right-cls} (ui/format-number (:standing/planets standing))]
          [:td {:class td-right-cls} (ui/format-number (:standing/score standing))]])]]]))

(defn- result-card
  "Render one archived game as a collapsible card. Collapsed: game name and winner.
  Expanded: full standings table.

  [result game-result-map] -> hiccup"
  [result]
  (let [standings (:game-result/standings result)
        winner    (:standing/empire-name (first standings))]
    (ui/collapsible-card
      {:summary (str (:game-result/game-name result)
                      " — " winner " won"
                      " (" (format-date (:game-result/ended-at result)) ")")}
      (standings-table standings))))

;;;;
;;;; Page
;;;;

(defn results-page
  "Render the Hall of Fame page listing every completed game's final standings.

  [ctx ring-ctx] -> hiccup"
  [{:keys [biff/db] :as ctx}]
  (let [results (get-game-results db)]
    (ui/page
      {}
      [:div.text-base.w-full.max-w-2xl.mx-auto.overflow-hidden.relative
       {:class "bg-game-bg text-green-400 font-mono border-[1.5px] border-game-green-border rounded"}
       (ui/scanline-overlay)
       [:div.flex.items-center
        {:class "bg-game-surface border-b border-game-green-border py-[7px] px-3.5"}
        [:div.text-lg.font-bold.text-green-400 {:class "tracking-wider"} "HALL OF FAME"]]
       [:div.p-3.5
        (if (empty? results)
          [:p.text-sm.mb-4.text-game-green-soft "No completed games yet."]
          [:div.flex.flex-col.gap-2.mb-3.5
           (map result-card results)])
        (ui/action-bar-link "/app" "Back")]])))
