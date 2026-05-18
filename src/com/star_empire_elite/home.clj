;;;;;
;;;;; Home - Authentication and Public-Facing Pages
;;;;;
;;;;; Handles all pre-login flows: sign-up, sign-in, email link verification, and 6-digit code entry. 
;;;;; Also wires the public home and about pages. All routes here are either fully public or guarded 
;;;;; by wrap-redirect-signed-in (redirects away if already logged in).
;;;;;

(ns com.star-empire-elite.home
  (:require [com.biffweb :as biff]
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.settings :as settings]
            [com.star-empire-elite.pages.main.home :as main-home]
            [com.star-empire-elite.pages.main.about :as main-about]))

;;;;
;;;; Styles
;;;;

(def ^:private card-style
  {:background "#0e0e0e" :border "1.5px solid #1e6e44" :border-radius "4px"
   :color "#4ade80" :font-family "'Courier New', monospace"})

(def ^:private input-style
  {:width "100%" :background "#0a0a0a" :border "1px solid #1e6e44" :color "#4ade80"
   :padding "6px 10px" :font-family "'Courier New', monospace" :border-radius "2px"
   :font-size "14px" :box-sizing "border-box" :outline "none"})

(def ^:private label-style
  {:display "block" :font-size "11px" :text-transform "uppercase"
   :letter-spacing "0.1em" :color "#7ab88a" :margin-bottom "6px"})

(def ^:private submit-style
  {:width "100%" :padding "8px 0" :border "1px solid #4ade80" :background "#1a3a28"
   :color "#4ade80" :font-family "'Courier New', monospace" :font-size "14px"
   :letter-spacing "0.1em" :cursor "pointer" :border-radius "2px"})

(def ^:private link-style
  {:padding "5px 16px" :border "1px solid #1e6e44" :background "transparent"
   :color "#9adaaa" :border-radius "2px" :font-family "'Courier New', monospace"
   :font-size "13px" :letter-spacing "0.04em" :text-decoration "none"})

(defn- auth-card
  "Wrap auth-page body in the standard terminal card shell with a header title.

  [title str, & body hiccup] -> hiccup"
  [title & body]
  [:div.text-base.w-full.max-w-lg.mx-auto.overflow-hidden.relative
   {:style card-style}
   (ui/scanline-overlay)
   ;; Header
   [:div.text-center
    {:style {:background "#161616" :border-bottom "1px solid #1e6e44" :padding "16px 14px"}}
    [:div {:style {:font-size "22px" :font-weight "bold" :color "#4ade80"
                   :letter-spacing "0.1em"}}
     title]]
   ;; Body
   [:div {:style {:padding "20px 24px"}}
    body]])

;;;;
;;;; Pages
;;;;

(defn home-page
  "Sign-up page with email input and reCAPTCHA. Sends a magic link to the submitted address.

  [ctx ring-ctx] -> hiccup"
  [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (auth-card
    "SIGN IN / SIGN UP"
    (biff/form
     {:action "/auth/send-code"
      :id     "signup"
      :hidden {:on-error "/signup"}
      :style  {:margin 0}}
     (biff/recaptcha-callback "submitSignup" "signup")

     [:div.mb-4
      [:label {:for "email" :style label-style} "Enter your email address"]
      [:input#email
       {:name         "email"
        :type         "email"
        :autocomplete "email"
        :placeholder  "commander@bennington.edu"
        :style        input-style}]]

     (when-some [error (:error params)]
       [:p {:style {:font-size "12px" :color "#f87171" :margin-bottom "12px"}}
        (case error
          "recaptcha"     (str "You failed the recaptcha test. Try again, "
                               "and make sure you aren't blocking scripts from Google.")
          "invalid-email" "You must have a Bennington College email address to play. If you believe you should have access, contact the game administrator."
          "send-failed"   (str "We weren't able to send an email to that address. "
                               "If the problem persists, try another address.")
          "There was an error.")])

     [:button.g-recaptcha
      (merge {:type "submit" :style submit-style}
             (when site-key
               {:data-sitekey site-key :data-callback "submitSignup"}))
      "Send Code"])

    [:div {:style {:border-top "1px solid #1a3020" :margin-top "20px" :padding-top "16px"}}
     [:div.flex.justify-center
      [:a {:href "/" :hx-boost "true" :style link-style} "Home"]]]

    [:div {:style {:margin-top "16px" :font-size "11px" :color "#4a6a58"}}
     biff/recaptcha-disclosure])))

(defn link-sent
  "Confirmation page shown after a sign-up link is emailed.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params] :as ctx}]
  (ui/page
   ctx
   (auth-card
    "CHECK YOUR INBOX"
    [:p {:style {:color "#9adaaa" :font-size "14px" :line-height "1.7"}}
     "We've sent a sign-up link to "
     [:span {:style {:color "#4ade80" :font-weight "bold"}} (:email params)] "."])))

(defn verify-email-page
  "Cross-device email verification fallback — shown when a magic link is opened on a
  different device/browser than the one used to sign up.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params] :as ctx}]
  (ui/page
   ctx
   (auth-card
    (str "SIGN UP FOR " settings/app-name)
    (biff/form
     {:action "/auth/verify-link"
      :hidden {:token (:token params)}
      :style  {:margin 0}}
     [:p {:style {:color "#9adaaa" :font-size "13px" :margin-bottom "12px" :line-height "1.6"}}
      "It looks like you opened this link on a different device or browser than the one "
      "you signed up on. For verification, please enter the email you signed up with:"]
     [:div.mb-4
      [:label {:for "email" :style label-style} "Email address"]
      [:input#email {:name "email" :type "email" :placeholder "Enter your email address"
                     :style input-style}]]
     [:button {:type "submit" :style submit-style} "Sign In"])
    (when-some [error (:error params)]
      [:p {:style {:font-size "12px" :color "#f87171" :margin-top "10px"}}
       (case error
         "incorrect-email" "Incorrect email address. Try again."
         "There was an error.")]))))

(defn signin-page
  "Sign-in page with email input and reCAPTCHA. Sends a 6-digit code to the submitted address.

  [ctx ring-ctx] -> hiccup"
  [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (auth-card
    "SIGN IN / SIGN UP"
    (biff/form
     {:action "/auth/send-code"
      :id     "signin"
      :hidden {:on-error "/signin"}
      :style  {:margin 0}}
     (biff/recaptcha-callback "submitSignin" "signin")

     [:div.mb-4
      [:label {:for "email" :style label-style} "Enter your email address"]
      [:input#email
       {:name         "email"
        :type         "email"
        :autocomplete "email"
        :placeholder  "commander@galaxy.com"
        :style        input-style}]]

     (when-some [error (:error params)]
       [:p {:style {:font-size "12px" :color "#f87171" :margin-bottom "12px"}}
        (case error
          "recaptcha"     (str "You failed the recaptcha test. Try again, "
                               "and make sure you aren't blocking scripts from Google.")
          "invalid-email" "You must have a Bennington College email address to play. If you believe you should have access, contact the game administrator."
          "send-failed"   (str "We weren't able to send an email to that address. "
                               "If the problem persists, try another address.")
          "invalid-link"  "Invalid or expired link. Sign in to get a new link."
          "not-signed-in" "You must be signed in to view that page."
          "There was an error.")])

     [:button.g-recaptcha
      (merge {:type "submit" :style submit-style}
             (when site-key
               {:data-sitekey site-key :data-callback "submitSignin"}))
      "Send Code"])

    [:div {:style {:border-top "1px solid #1a3020" :margin-top "20px" :padding-top "16px"}}
     [:div.flex.justify-center
      [:a {:href "/" :hx-boost "true" :style link-style} "Home"]]]

    [:div {:style {:margin-top "16px" :font-size "11px" :color "#4a6a58"}}
     biff/recaptcha-disclosure])))

(defn enter-code-page
  "Code entry page shown after a sign-in email is sent. Accepts the 6-digit verification code.

  [ctx ring-ctx] -> hiccup"
  [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (auth-card
    "ENTER CODE"
    (biff/form
     {:action "/auth/verify-code"
      :id     "code-form"
      :hidden {:email (:email params)}
      :style  {:margin 0}}
     (biff/recaptcha-callback "submitCode" "code-form")

     [:div.mb-4
      [:label {:for "code" :style label-style}
       "6-digit code sent to "
       [:span {:style {:color "#4ade80"}} (:email params)]]
      [:input#code
       {:name        "code"
        :type        "text"
        :placeholder "000000"
        :style       (merge input-style {:text-align "center" :font-size "22px"
                                         :letter-spacing "0.4em"})}]]

     (when-some [error (:error params)]
       [:p {:style {:font-size "12px" :color "#f87171" :margin-bottom "12px"}}
        (case error
          "invalid-code" "Invalid code. Try again."
          "There was an error.")])

     [:button.g-recaptcha
      (merge {:type "submit" :style submit-style}
             (when site-key
               {:data-sitekey site-key :data-callback "submitCode"}))
      "Verify Code"])

    [:div {:style {:border-top "1px solid #1a3020" :margin-top "20px" :padding-top "16px"
                   :text-align "center"}}
     (biff/form
      {:action "/auth/send-code"
       :id     "signin"
       :hidden {:email (:email params) :on-error "/signin"}
       :style  {:margin 0 :display "inline"}}
      (biff/recaptcha-callback "submitSignin" "signin")
      [:button.g-recaptcha
       (merge {:type "submit"
               :style {:background "transparent" :border "none" :color "#9adaaa"
                       :font-family "'Courier New', monospace" :font-size "13px"
                       :cursor "pointer" :text-decoration "underline" :padding 0}}
              (when site-key
                {:data-sitekey site-key :data-callback "submitSignin"}))
       "Send another code"])])))

;;;;
;;;; Routes
;;;;

(def module
  {:routes [[""
             ["/" {:get main-home/home}]
             ["/about"              {:get main-about/about}]
             ["/about/essay"        {:get main-about/essay}]
             ["/about/design" {:get main-about/design}]]
            ["" {:middleware [mid/wrap-redirect-signed-in]}
             ["/link-sent"    {:get link-sent}]
             ["/verify-link"  {:get verify-email-page}]
             ["/signin"       {:get signin-page}]
             ["/verify-code"  {:get enter-code-page}]
             ["/signup"       {:get home-page}]]]})
