(ns com.star-empire-elite.pages.app.eliminated-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.eliminated :as eliminated]
            [xtdb.api :as xt]))

;;
;; Test fixtures
;;

(def test-game-id   #uuid "00000000-0000-0000-0000-000000000001")
(def test-player-id #uuid "00000000-0000-0000-0000-000000000002")
(def test-user-id   #uuid "00000000-0000-0000-0000-000000000003")

(def minimal-game
  {:xt/id                test-game-id
   :game/name            "Doomed Galaxy"
   :game/turns-per-round 4
   :game/rounds-per-day  3})

(def minimal-player
  {:xt/id              test-player-id
   :player/user        test-user-id
   :player/game        test-game-id
   :player/empire-name "Fallen Empire"
   :player/status      1
   :player/current-phase 1
   :player/current-turn  1
   :player/current-round 1
   :player/turns-used    0
   :player/last-round-completed-at nil})

;;
;; eliminated-page
;;
;; eliminated-page calls utils/with-player-and-game, which calls xt/entity for both the
;; player and the game. Returns a 404 ring response when the player is not found;
;; otherwise returns a hiccup page.
;;

(deftest test-eliminated-page-player-not-found
  (testing "Returns 404 when the player entity does not exist"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [ctx    {:path-params   {:player-id (str test-player-id)}
                    :biff/db       nil
                    :query-params  {}}
            result (eliminated/eliminated-page ctx)]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-eliminated-page-bad-uuid
  (testing "Throws when the player-id path param is not a valid UUID"
    (let [ctx {:path-params {:player-id "not-a-uuid"} :biff/db nil :query-params {}}]
      (is (thrown? IllegalArgumentException (eliminated/eliminated-page ctx))))))

(deftest test-eliminated-page-renders-for-valid-player
  (testing "Returns a hiccup vector when player and game are found"
    (with-redefs [xt/entity (fn [_ id]
                              (cond (= id test-player-id) minimal-player
                                    (= id test-game-id)   minimal-game
                                    :else nil))]
      (let [ctx    {:path-params   {:player-id (str test-player-id)}
                    :biff/db       nil
                    :query-params  {}}
            result (eliminated/eliminated-page ctx)]
        ;; Page-level functions return a hiccup vector (rendered by Rum at the HTTP layer)
        (is (vector? result))))))

(deftest test-eliminated-page-includes-empire-name
  (testing "The eliminated page includes the fallen empire's name"
    (with-redefs [xt/entity (fn [_ id]
                              (cond (= id test-player-id) minimal-player
                                    (= id test-game-id)   minimal-game
                                    :else nil))]
      (let [ctx    {:path-params   {:player-id (str test-player-id)}
                    :biff/db       nil
                    :query-params  {}}
            result (pr-str (eliminated/eliminated-page ctx))]
        (is (clojure.string/includes? result "Fallen Empire"))))))

(deftest test-eliminated-page-includes-rejoin-link
  (testing "The eliminated page includes a form action pointing to the rejoin route"
    (with-redefs [xt/entity (fn [_ id]
                              (cond (= id test-player-id) minimal-player
                                    (= id test-game-id)   minimal-game
                                    :else nil))]
      (let [ctx    {:path-params   {:player-id (str test-player-id)}
                    :biff/db       nil
                    :query-params  {}}
            result (pr-str (eliminated/eliminated-page ctx))]
        (is (clojure.string/includes? result "rejoin"))))))

(deftest test-eliminated-page-shows-error-param
  (testing "An ?error query param is displayed on the page"
    (with-redefs [xt/entity (fn [_ id]
                              (cond (= id test-player-id) minimal-player
                                    (= id test-game-id)   minimal-game
                                    :else nil))]
      (let [ctx    {:path-params   {:player-id (str test-player-id)}
                    :biff/db       nil
                    :query-params  {:error "Empire+name+cannot+be+blank"}}
            result (pr-str (eliminated/eliminated-page ctx))]
        (is (clojure.string/includes? result "Empire name cannot be blank"))))))

;;
;; rejoin
;;
;; rejoin validates the new empire name before writing to the DB. Validation happens
;; after with-player-and-game resolves the entities, so we mock xt/entity to return
;; minimal player+game data for the validation tests.
;;

(defn- ctx-for-rejoin
  "Minimal ring ctx for the rejoin action handler."
  [empire-name]
  {:path-params {:player-id (str test-player-id)}
   :biff/db     nil
   :params      {:empire-name empire-name}})

(deftest test-rejoin-blank-name-redirects
  (testing "Redirects back with an error when the empire name is blank"
    (with-redefs [xt/entity (fn [_ id]
                              (cond (= id test-player-id) minimal-player
                                    (= id test-game-id)   minimal-game
                                    :else nil))]
      (let [result (eliminated/rejoin (ctx-for-rejoin ""))]
        (is (= 303 (:status result)))
        (is (clojure.string/includes?
             (get-in result [:headers "location"])
             "error="))))))

(deftest test-rejoin-whitespace-only-name-redirects
  (testing "A name of only spaces is treated as blank and triggers an error redirect"
    (with-redefs [xt/entity (fn [_ id]
                              (cond (= id test-player-id) minimal-player
                                    (= id test-game-id)   minimal-game
                                    :else nil))]
      (let [result (eliminated/rejoin (ctx-for-rejoin "   "))]
        (is (= 303 (:status result)))
        (is (clojure.string/includes?
             (get-in result [:headers "location"])
             "error="))))))
