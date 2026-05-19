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

(def ^:private card-cls
  "bg-game-bg text-green-400 font-mono border-[1.5px] border-game-green-border rounded")

(def ^:private input-cls
  "w-full bg-game-bg border border-game-green-border text-green-400 py-1.5 px-[10px] font-mono rounded-sm text-sm box-border outline-none")

(def ^:private label-cls
  "block text-[11px] uppercase tracking-widest text-game-green-muted mb-1.5")

(def ^:private submit-cls
  "w-full py-2 border border-green-400 bg-game-green-deep text-green-400 font-mono text-sm tracking-widest cursor-pointer rounded-sm")

(def ^:private link-cls
  "py-[5px] px-4 border border-game-green-border bg-transparent text-game-green-soft rounded-sm font-mono text-[13px] tracking-[0.04em] no-underline")

(defn- auth-card
  "Wrap auth-page body in the standard terminal card shell with a header title.

  [title str, & body hiccup] -> hiccup"
  [title & body]
  [:div.text-base.w-full.max-w-lg.mx-auto.overflow-hidden.relative
   {:class card-cls}
   (ui/scanline-overlay)
   ;; Header
   [:div.text-center.bg-game-surface.border-b.border-game-green-border
    {:class "py-4 px-3.5"}
    [:div.font-bold.text-green-400 {:class "text-[22px] tracking-widest"}
     title]]
   ;; Body
   [:div {:class "py-5 px-6"}
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
      :class  "m-0"}
     (biff/recaptcha-callback "submitSignup" "signup")

     [:div.mb-4
      [:label {:for "email" :class label-cls} "Enter your email address"]
      [:input#email
       {:name         "email"
        :type         "email"
        :autocomplete "email"
        :placeholder  "commander@bennington.edu"
        :class        input-cls}]]

     (when-some [error (:error params)]
       [:p.text-xs.text-red-400.mb-3
        (case error
          "recaptcha"     (str "You failed the recaptcha test. Try again, "
                               "and make sure you aren't blocking scripts from Google.")
          "invalid-email" "You must have a Bennington College email address to play. If you believe you should have access, contact the game administrator."
          "send-failed"   (str "We weren't able to send an email to that address. "
                               "If the problem persists, try another address.")
          "There was an error.")])

     [:button.g-recaptcha
      (merge {:type "submit" :class submit-cls}
             (when site-key
               {:data-sitekey site-key :data-callback "submitSignup"}))
      "Send Code"])

    [:div.border-t.border-game-divider.mt-5.pt-4
     [:div.flex.justify-center
      [:a {:href "/" :hx-boost "true" :class link-cls} "Home"]]]

    [:div.mt-4.text-game-green-dim {:class "text-[11px]"}
     biff/recaptcha-disclosure])))

(defn link-sent
  "Confirmation page shown after a sign-up link is emailed.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params] :as ctx}]
  (ui/page
   ctx
   (auth-card
    "CHECK YOUR INBOX"
    [:p.text-game-green-soft.text-sm {:class "leading-[1.7]"}
     "We've sent a sign-up link to "
     [:span.text-green-400.font-bold (:email params)] "."])))

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
      :class  "m-0"}
     [:p.text-game-green-soft.mb-3 {:class "text-[13px] leading-[1.6]"}
      "It looks like you opened this link on a different device or browser than the one "
      "you signed up on. For verification, please enter the email you signed up with:"]
     [:div.mb-4
      [:label {:for "email" :class label-cls} "Email address"]
      [:input#email {:name "email" :type "email" :placeholder "Enter your email address"
                     :class input-cls}]]
     [:button {:type "submit" :class submit-cls} "Sign In"])
    (when-some [error (:error params)]
      [:p.text-xs.text-red-400 {:class "mt-[10px]"}
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
      :class  "m-0"}
     (biff/recaptcha-callback "submitSignin" "signin")

     [:div.mb-4
      [:label {:for "email" :class label-cls} "Enter your email address"]
      [:input#email
       {:name         "email"
        :type         "email"
        :autocomplete "email"
        :placeholder  "commander@galaxy.com"
        :class        input-cls}]]

     (when-some [error (:error params)]
       [:p.text-xs.text-red-400.mb-3
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
      (merge {:type "submit" :class submit-cls}
             (when site-key
               {:data-sitekey site-key :data-callback "submitSignin"}))
      "Send Code"])

    [:div.border-t.border-game-divider.mt-5.pt-4
     [:div.flex.justify-center
      [:a {:href "/" :hx-boost "true" :class link-cls} "Home"]]]

    [:div.mt-4.text-game-green-dim {:class "text-[11px]"}
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
      :class  "m-0"}
     (biff/recaptcha-callback "submitCode" "code-form")

     [:div.mb-4
      [:label {:for "code" :class label-cls}
       "6-digit code sent to "
       [:span.text-green-400 (:email params)]]
      [:input#code
       {:name        "code"
        :type        "text"
        :placeholder "000000"
        :class       (str input-cls " text-center text-[22px] tracking-[0.4em]")}]]

     (when-some [error (:error params)]
       [:p.text-xs.text-red-400.mb-3
        (case error
          "invalid-code" "Invalid code. Try again."
          "There was an error.")])

     [:button.g-recaptcha
      (merge {:type "submit" :class submit-cls}
             (when site-key
               {:data-sitekey site-key :data-callback "submitCode"}))
      "Verify Code"])

    [:div.border-t.border-game-divider.mt-5.pt-4.text-center
     (biff/form
      {:action "/auth/send-code"
       :id     "signin"
       :hidden {:email (:email params) :on-error "/signin"}
       :class  "m-0 inline"}
      (biff/recaptcha-callback "submitSignin" "signin")
      [:button.g-recaptcha
       (merge {:type "submit"
               :class "bg-transparent border-none text-game-green-soft font-mono text-[13px] cursor-pointer underline p-0"}
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
