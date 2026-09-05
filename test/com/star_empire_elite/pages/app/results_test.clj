(ns com.star-empire-elite.pages.app.results-test
  (:require [clojure.test :refer :all]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.star-empire-elite :as main]
            [com.star-empire-elite.pages.app.results :as results]
            [xtdb.api :as xt]))

(defn- get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

;;
;; standings-table (pure rendering — mirrors game/players-table tests)
;;

(deftest test-standings-table-empty
  (testing "Renders a table for an empty standings list"
    (let [result (results/standings-table [])]
      (is (vector? result)))))

(deftest test-standings-table-contains-empire-and-score
  (testing "Empire name, planets, and score appear in the rendered hiccup"
    (let [standings [{:standing/rank 1 :standing/empire-name "Solar Syndicate"
                       :standing/planets 6 :standing/score 5000}]
          rendered  (pr-str (results/standings-table standings))]
      (is (clojure.string/includes? rendered "Solar Syndicate"))
      (is (clojure.string/includes? rendered "5000"))
      (is (clojure.string/includes? rendered "6")))))

(deftest test-standings-table-order-preserved
  (testing "Rows are rendered in the order given (already rank-sorted upstream)"
    (let [standings [{:standing/rank 1 :standing/empire-name "Alpha" :standing/planets 0 :standing/score 900}
                      {:standing/rank 2 :standing/empire-name "Beta"  :standing/planets 0 :standing/score 100}]
          rendered  (pr-str (results/standings-table standings))]
      (is (< (.indexOf rendered "Alpha") (.indexOf rendered "Beta"))))))

;;
;; format-date (private helper, accessed via var deref per project convention)
;;

(def ^:private format-date @#'results/format-date)

(deftest test-format-date
  (testing "Formats an inst as yyyy-MM-dd"
    (let [d (java.util.Date. (- (.getTime (java.util.Date.)) (* 1000 60 60 24 400)))]
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (format-date d))))))

;;
;; get-game-results (integration — real XTDB node, newest-first ordering)
;;

(deftest test-get-game-results-newest-first
  (testing "Archived games are returned newest ended-at first"
    (with-open [node (test-xtdb-node [])]
      (let [ctx      (get-context node)
            older    (java.util.Date. (- (.getTime (java.util.Date.)) 100000))
            newer    (java.util.Date.)]
        (biff/submit-tx ctx
          [{:db/doc-type            :game-result
            :xt/id                  (random-uuid)
            :game-result/game-name  "Older Game"
            :game-result/created-at older
            :game-result/ended-at   older
            :game-result/standings  [{:standing/rank 1 :standing/empire-name "A"
                                       :standing/planets 1 :standing/score 1}]}
           {:db/doc-type            :game-result
            :xt/id                  (random-uuid)
            :game-result/game-name  "Newer Game"
            :game-result/created-at newer
            :game-result/ended-at   newer
            :game-result/standings  [{:standing/rank 1 :standing/empire-name "B"
                                       :standing/planets 1 :standing/score 1}]}])
        (let [db      (xt/db node)
              results (results/get-game-results db)]
          (is (= ["Newer Game" "Older Game"] (map :game-result/game-name results))))))))

(deftest test-get-game-results-empty
  (testing "Returns an empty seq when nothing has been archived"
    (with-open [node (test-xtdb-node [])]
      (is (empty? (results/get-game-results (xt/db node)))))))

;;
;; results-page (renders end-to-end from the DB)
;;

(deftest test-results-page-empty-state
  (testing "Shows the empty-state message when no games are archived"
    (with-open [node (test-xtdb-node [])]
      (let [rendered (pr-str (results/results-page {:biff/db (xt/db node)}))]
        (is (clojure.string/includes? rendered "No completed games yet."))))))

(deftest test-results-page-lists-archived-game
  (testing "Shows the game name and winning empire once a game is archived"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)]
        (biff/submit-tx ctx
          [{:db/doc-type            :game-result
            :xt/id                  (random-uuid)
            :game-result/game-name  "Galactic Conquest"
            :game-result/created-at (java.util.Date.)
            :game-result/ended-at   (java.util.Date.)
            :game-result/standings  [{:standing/rank 1 :standing/empire-name "Star Federation"
                                       :standing/planets 6 :standing/score 5000}
                                      {:standing/rank 2 :standing/empire-name "Rebel Alliance"
                                       :standing/planets 3 :standing/score 2000}]}])
        (let [rendered (pr-str (results/results-page {:biff/db (xt/db node)}))]
          (is (clojure.string/includes? rendered "Galactic Conquest"))
          (is (clojure.string/includes? rendered "Star Federation"))
          (is (clojure.string/includes? rendered "won")))))))
