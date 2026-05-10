(ns com.star-empire-elite.pages.main.home-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.main.home :as home]))

;;
;; home
;;
;; The home/home function ignores ctx and returns a full-page hiccup tree via ui/page.
;; Tests verify structural properties: the result is a non-nil hiccup vector containing
;; the expected marketing copy and navigation links.
;;

(deftest test-home-renders
  (testing "Returns a hiccup vector for any context"
    (let [result (home/home {})]
      (is (vector? result))
      (is (some? result)))))

(deftest test-home-contains-game-title
  (testing "The game title appears somewhere in the rendered tree"
    (let [result (pr-str (home/home {}))]
      (is (clojure.string/includes? result "STAR EMPIRE ELITE")))))

(deftest test-home-contains-sign-up-link
  (testing "A link to /signup is present on the home page"
    (let [result (pr-str (home/home {}))]
      (is (clojure.string/includes? result "/signup")))))

(deftest test-home-contains-sign-in-link
  (testing "A link to /signin is present on the home page"
    (let [result (pr-str (home/home {}))]
      (is (clojure.string/includes? result "/signin")))))

(deftest test-home-contains-about-link
  (testing "A link to /about is present on the home page"
    (let [result (pr-str (home/home {}))]
      (is (clojure.string/includes? result "/about")))))

(deftest test-home-contains-pillars
  (testing "All four game-feature pillars appear in the page"
    (let [result (pr-str (home/home {}))]
      (is (clojure.string/includes? result "Empire Building"))
      (is (clojure.string/includes? result "Military Power"))
      (is (clojure.string/includes? result "Diplomacy"))
      (is (clojure.string/includes? result "Covert Ops")))))

(deftest test-home-contains-attribution
  (testing "The Space Dynasty attribution appears in the footer"
    (let [result (pr-str (home/home {}))]
      (is (clojure.string/includes? result "Space Dynasty")))))
