(ns com.star-empire-elite.home-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.home :as home]))

;;
;; Auth page rendering tests
;;
;; All auth handlers (home-page, link-sent, verify-email-page, signin-page, enter-code-page)
;; ignore most of the ring context and return a full hiccup page via ui/page.
;; We test that each page renders without error and contains the expected structure/copy.
;;

;;;; Helpers

(defn- bare-ctx
  "Minimal ring context with no optional fields."
  []
  {:params {} :query-params {}})

(defn- ctx-with-params
  "Context that sets the :params map."
  [params]
  {:params params :query-params {}})

;;
;; home-page (sign-up page)
;;

(deftest test-home-page-renders
  (testing "home-page returns a hiccup vector for an empty context"
    (let [result (home/home-page (bare-ctx))]
      (is (vector? result))
      (is (some? result)))))

(deftest test-home-page-contains-sign-up-heading
  (testing "home-page includes the SIGN UP heading"
    (let [result (pr-str (home/home-page (bare-ctx)))]
      (is (clojure.string/includes? result "SIGN UP")))))

(deftest test-home-page-contains-email-field
  (testing "home-page includes an email input"
    (let [result (pr-str (home/home-page (bare-ctx)))]
      (is (clojure.string/includes? result "email")))))

(deftest test-home-page-contains-send-code-action
  (testing "home-page form posts to /auth/send-code"
    (let [result (pr-str (home/home-page (bare-ctx)))]
      (is (clojure.string/includes? result "/auth/send-code")))))

(deftest test-home-page-recaptcha-error-rendered
  (testing "home-page shows a recaptcha error message when ?error=recaptcha"
    (let [result (pr-str (home/home-page (ctx-with-params {:error "recaptcha"})))]
      (is (clojure.string/includes? result "recaptcha")))))

(deftest test-home-page-invalid-email-error-rendered
  (testing "home-page shows an invalid-email error message when ?error=invalid-email"
    (let [result (pr-str (home/home-page (ctx-with-params {:error "invalid-email"})))]
      (is (clojure.string/includes? result "Bennington")))))

(deftest test-home-page-has-unified-title
  (testing "home-page shows unified Sign In / Sign Up title"
    (let [result (pr-str (home/home-page (bare-ctx)))]
      (is (clojure.string/includes? result "SIGN IN / SIGN UP")))))

;;
;; link-sent
;;

(deftest test-link-sent-renders
  (testing "link-sent returns a hiccup vector"
    (let [result (home/link-sent (ctx-with-params {:email "commander@test.edu"}))]
      (is (vector? result))
      (is (some? result)))))

(deftest test-link-sent-shows-email
  (testing "link-sent echoes the submitted email address"
    (let [result (pr-str (home/link-sent (ctx-with-params {:email "jane@star.edu"})))]
      (is (clojure.string/includes? result "jane@star.edu")))))

(deftest test-link-sent-contains-inbox-message
  (testing "link-sent shows a 'check your inbox' heading"
    (let [result (pr-str (home/link-sent (ctx-with-params {:email "x@y.edu"})))]
      (is (clojure.string/includes? result "INBOX")))))

;;
;; verify-email-page
;;

(deftest test-verify-email-page-renders
  (testing "verify-email-page returns a hiccup vector"
    (let [result (home/verify-email-page (ctx-with-params {:token "abc123"}))]
      (is (vector? result))
      (is (some? result)))))

(deftest test-verify-email-page-contains-verify-action
  (testing "verify-email-page form posts to /auth/verify-link"
    (let [result (pr-str (home/verify-email-page (ctx-with-params {:token "tok"})))]
      (is (clojure.string/includes? result "/auth/verify-link")))))

(deftest test-verify-email-page-embeds-token
  (testing "verify-email-page includes the token as a hidden field"
    (let [result (pr-str (home/verify-email-page (ctx-with-params {:token "secret-token"})))]
      (is (clojure.string/includes? result "secret-token")))))

(deftest test-verify-email-page-incorrect-email-error
  (testing "verify-email-page shows an error when ?error=incorrect-email"
    (let [ctx    {:params {:token "tok" :error "incorrect-email"} :query-params {}}
          result (pr-str (home/verify-email-page ctx))]
      (is (clojure.string/includes? result "Incorrect email")))))

;;
;; signin-page
;;

(deftest test-signin-page-renders
  (testing "signin-page returns a hiccup vector for an empty context"
    (let [result (home/signin-page (bare-ctx))]
      (is (vector? result))
      (is (some? result)))))

(deftest test-signin-page-contains-sign-in-heading
  (testing "signin-page includes the SIGN IN heading"
    (let [result (pr-str (home/signin-page (bare-ctx)))]
      (is (clojure.string/includes? result "SIGN IN")))))

(deftest test-signin-page-contains-send-code-action
  (testing "signin-page form posts to /auth/send-code"
    (let [result (pr-str (home/signin-page (bare-ctx)))]
      (is (clojure.string/includes? result "/auth/send-code")))))

(deftest test-signin-page-not-signed-in-error
  (testing "signin-page shows a redirect message when ?error=not-signed-in"
    (let [result (pr-str (home/signin-page (ctx-with-params {:error "not-signed-in"})))]
      (is (clojure.string/includes? result "signed in")))))

(deftest test-signin-page-has-unified-title
  (testing "signin-page shows unified Sign In / Sign Up title"
    (let [result (pr-str (home/signin-page (bare-ctx)))]
      (is (clojure.string/includes? result "SIGN IN / SIGN UP")))))

;;
;; enter-code-page
;;

(deftest test-enter-code-page-renders
  (testing "enter-code-page returns a hiccup vector"
    (let [result (home/enter-code-page (ctx-with-params {:email "pilot@galaxy.edu"}))]
      (is (vector? result))
      (is (some? result)))))

(deftest test-enter-code-page-heading
  (testing "enter-code-page includes the ENTER CODE heading"
    (let [result (pr-str (home/enter-code-page (ctx-with-params {:email "x@y.edu"})))]
      (is (clojure.string/includes? result "ENTER CODE")))))

(deftest test-enter-code-page-shows-email
  (testing "enter-code-page shows the destination email address"
    (let [result (pr-str (home/enter-code-page (ctx-with-params {:email "fleet@star.edu"})))]
      (is (clojure.string/includes? result "fleet@star.edu")))))

(deftest test-enter-code-page-contains-verify-action
  (testing "enter-code-page form posts to /auth/verify-code"
    (let [result (pr-str (home/enter-code-page (ctx-with-params {:email "x@y.edu"})))]
      (is (clojure.string/includes? result "/auth/verify-code")))))

(deftest test-enter-code-page-invalid-code-error
  (testing "enter-code-page shows an error message when ?error=invalid-code"
    (let [result (pr-str (home/enter-code-page
                          (ctx-with-params {:email "x@y.edu" :error "invalid-code"})))]
      (is (clojure.string/includes? result "Invalid code")))))

(deftest test-enter-code-page-resend-link
  (testing "enter-code-page includes an option to request another code"
    (let [result (pr-str (home/enter-code-page (ctx-with-params {:email "x@y.edu"})))]
      (is (clojure.string/includes? result "another code")))))
