(ns com.star-empire-elite.pages.main.about-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.main.about :as about]))

;;
;; about-page / tab handlers
;;
;; about-page takes a tab keyword (:about, :essay, or :design) and returns a full-page
;; hiccup tree. The three handler functions (about, essay, design) delegate to it.
;; Tests verify that each tab renders without error and displays its own content.
;;

(deftest test-about-page-overview-renders
  (testing "about-page with :about tab returns a hiccup vector"
    (let [result (about/about-page :about)]
      (is (vector? result))
      (is (some? result)))))

(deftest test-about-page-essay-renders
  (testing "about-page with :essay tab returns a hiccup vector"
    (let [result (about/about-page :essay)]
      (is (vector? result))
      (is (some? result)))))

(deftest test-about-page-design-renders
  (testing "about-page with :design tab returns a hiccup vector"
    (let [result (about/about-page :design)]
      (is (vector? result))
      (is (some? result)))))

(deftest test-about-handler
  (testing "about handler ignores ctx and delegates to about-page :about"
    (let [result (about/about {})]
      (is (vector? result)))))

(deftest test-essay-handler
  (testing "essay handler ignores ctx and delegates to about-page :essay"
    (let [result (about/essay {})]
      (is (vector? result)))))

(deftest test-design-handler
  (testing "design handler ignores ctx and delegates to about-page :design"
    (let [result (about/design {})]
      (is (vector? result)))))

;;
;; Tab content
;;
;; Each tab renders distinct content. We verify the page tree contains the expected
;; header text and at least some tab-specific content.
;;

(deftest test-about-page-contains-about-heading
  (testing "Page header shows ABOUT"
    (let [result (pr-str (about/about-page :about))]
      (is (clojure.string/includes? result "ABOUT")))))

(deftest test-about-page-overview-content
  (testing "Overview tab includes the legacy game attribution"
    (let [result (pr-str (about/about-page :about))]
      (is (clojure.string/includes? result "Space Dynasty")))))

(deftest test-about-page-essay-content
  (testing "History/essay tab includes Sumerian Game reference"
    (let [result (pr-str (about/about-page :essay))]
      (is (clojure.string/includes? result "Sumerian")))))

(deftest test-about-page-design-content
  (testing "Design tab includes game design section heading"
    (let [result (pr-str (about/about-page :design))]
      (is (clojure.string/includes? result "Software Implementation")))))

;;
;; Tab navigation links
;;
;; All three tab pages should include links to the other two tabs.
;;

(deftest test-about-page-tab-links-present
  (testing "All three tab links appear on every tab page"
    (doseq [tab [:about :essay :design]]
      (let [result (pr-str (about/about-page tab))]
        (is (clojure.string/includes? result "/about")        (str "Missing /about on tab " tab))
        (is (clojure.string/includes? result "/about/essay")  (str "Missing /about/essay on tab " tab))
        (is (clojure.string/includes? result "/about/design") (str "Missing /about/design on tab " tab))))))

(deftest test-about-page-nav-links-present
  (testing "All tab pages include signup, signin, and home navigation links"
    (doseq [tab [:about :essay :design]]
      (let [result (pr-str (about/about-page tab))]
        (is (clojure.string/includes? result "/signup") (str "Missing /signup on tab " tab))
        (is (clojure.string/includes? result "/signin") (str "Missing /signin on tab " tab))
        (is (clojure.string/includes? result "\"Home\"") (str "Missing Home link on tab " tab))))))
