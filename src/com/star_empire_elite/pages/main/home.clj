;;;;;
;;;;; Star Empire Elite - Authentication Pages
;;;;; 
;;;;; This module handles all authentication-related pages including sign-up, sign-in, email
;;;;; verification, and code entry. It uses Biff's built-in authentication system with email-based
;;;;; magic links and verification codes, enhanced with reCAPTCHA protection.
;;;;;
;;;;; The UI follows a retro terminal/sci-fi aesthetic with green text on black background,
;;;;; establishing the the Star Empire Elite theme. All forms use HTMX.
;;;;;

(ns com.star-empire-elite.home
  (:require [com.biffweb :as biff]
            ;; Application-specific modules
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.settings :as settings]
            ;; Page components for main site content
            [com.star-empire-elite.pages.main.home :as main-home]
            [com.star-empire-elite.pages.main.about :as main-about]))

;;;;
;;;; UI Components and Styling
;;;;
;;;; These components provide consistent styling and messaging across authentication pages.
;;;;

;;; Development notice displayed when email services aren't configured. This helps developers
;;; understand why sign-up links are printed to console instead of being emailed.
(def email-disabled-notice
  [:.text-sm.mt-3.bg-blue-100.rounded.p-2
   "Until you add API keys for MailerSend and reCAPTCHA, we'll print your sign-up "
   "link to the console. See config.edn."])

;;;;
;;;; Authentication Pages
;;;;
;;;; Each page follows the same visual structure: title with stars, divider, form, divider, alternate
;;;; action links. The retro terminal styling creates a cohesive appearance.
;;;;

;;; Sign-up page where new users enter their email to receive a magic link. Uses reCAPTCHA to prevent
;;; spam and abuse.
(defn home-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)                 ; Enable reCAPTCHA scripts
   [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4

    ;; Title section
    [:div.text-center.mb-8
     [:span.star.text-2xl "☆ "]
     [:h1.text-4xl.font-bold.glow "SIGN UP"]
     [:span.star.text-2xl " ☆"]]

    ;; Divider
    [:div.w-96.border-t.border-green-400.mb-8]

    ;; Signup form
    (biff/form
     {:action "/auth/send-link"                    ; Biff's built-in auth endpoint
      :id "signup"
      :hidden {:on-error "/"}                      ; Redirect here on error
      :class "w-full max-w-md"}
     (biff/recaptcha-callback "submitSignup" "signup") ; reCAPTCHA integration
 
     [:div.mb-6
      [:label.block.mb-3 {:for "email"} "Enter your email address:"]
      [:input#email.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono
       {:name "email"
        :type "email"
        :autocomplete "email"
        :placeholder "commander@galaxy.com"}]]     ; Thematic placeholder
 
     ;; Display user-friendly error messages based on the error parameter
     (when-some [error (:error params)]
       [:<>
        [:.mb-4
         [:.text-sm.text-red-600
          (case error
            "recaptcha" (str "You failed the recaptcha test. Try again, "
                             "and make sure you aren't blocking scripts from Google.")
            "invalid-email" "Invalid email. Try again with a different address."
            "send-failed" (str "We weren't able to send an email to that address. "
                               "If the problem persists, try another address.")
            "There was an error.")]]])
 
     ;; Submit button integrates with reCAPTCHA when site-key is available
     [:button.bg-green-400.text-black.px-6.py-2.font-bold.w-full.hover:bg-green-300.transition-colors.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignup"})
             {:type "submit"})
      "Send Sign-Up Link"])

    ;; Divider
    [:div.w-96.border-t.border-green-400.my-8]

    ;; Alternative actions for users who already have accounts or want to go home
    [:div.text-center
     [:p.text-sm.mb-4 "Already have an account?"]
     [:div.flex.gap-4.justify-center
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/signin"} "Sign In"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/" :hx-boost "true"} "Home"]]]      ; HTMX boost for SPA-like navigation

    ;; Disclosure for reCAPTCHA usage
    [:.text-xs.text-green-400.text-opacity-75.mt-8
     biff/recaptcha-disclosure]]))

;;; Confirmation page shown after user submits email for sign-up. Simple feedback to let them know
;;; the process has started.
(defn link-sent [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-xl.font-bold "Check your inbox"]
   [:p "We've sent a sign-up link to " [:span.font-bold (:email params)] "."]))

;;; Email verification page for when users open magic links on different devices. This is a security
;;; measure to prevent unauthorized access if someone intercepts a magic link.
(defn verify-email-page [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-2xl.font-bold (str "Sign up for " settings/app-name)]
   [:.h-3]                                         ; Spacing div
   (biff/form
    {:action "/auth/verify-link"
     :hidden {:token (:token params)}}             ; Include the token from the magic link
    [:div [:label {:for "email"}
           "It looks like you opened this link on a different device or browser than the one "
           "you signed up on. For verification, please enter the email you signed up with:"]]
    [:.h-3]
    [:.flex
     [:input#email {:name "email" :type "email"
                    :placeholder "Enter your email address"}]
     [:.w-3]                                       ; Spacing between input and button
     [:button.btn {:type "submit"}
      "Sign in"]])
 
   ;; Error handling for incorrect email verification
   (when-some [error (:error params)]
     [:<>
       [:.h-1]
       [:.text-sm.text-red-600
        (case error
          "incorrect-email" "Incorrect email address. Try again."
          "There was an error.")]])))

;;; Sign-in page for returning users. Very similar to sign-up page but sends a verification code
;;; instead of a magic link for faster authentication.
(defn signin-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4

    ;; Title section
    [:div.text-center.mb-8
     [:span.star.text-2xl "☆ "]
     [:h1.text-4xl.font-bold.glow "SIGN IN"]
     [:span.star.text-2xl " ☆"]]

    ;; Divider
    [:div.w-96.border-t.border-green-400.mb-8]

    ;; Signin form
    (biff/form
     {:action "/auth/send-code"                    ; Sends verification code instead of magic link
      :id "signin"
      :hidden {:on-error "/signin"}
      :class "w-full max-w-md"}
     (biff/recaptcha-callback "submitSignin" "signin")
 
     [:div.mb-6
      [:label.block.mb-3 {:for "email"} "Enter your email address:"]
      [:input#email.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono
       {:name "email"
        :type "email"
        :autocomplete "email"
        :placeholder "commander@galaxy.com"}]]
 
     ;; More error cases than sign-up, including expired links and authentication failures
     (when-some [error (:error params)]
       [:<>
        [:.mb-4
         [:.text-sm.text-red-600
          (case error
            "recaptcha" (str "You failed the recaptcha test. Try again, "
                             "and make sure you aren't blocking scripts from Google.")
            "invalid-email" "Invalid email. Try again with a different address."
            "send-failed" (str "We weren't able to send an email to that address. "
                               "If the problem persists, try another address.")
            "invalid-link" "Invalid or expired link. Sign in to get a new link."
            "not-signed-in" "You must be signed in to view that page."
            "There was an error.")]]])
 
     ;; Submit button
     [:button.bg-green-400.text-black.px-6.py-2.font-bold.w-full.hover:bg-green-300.transition-colors.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignin"})
             {:type "submit"})
      "Send Sign-In Code"])

    ;; Divider
    [:div.w-96.border-t.border-green-400.my-8]

    ;; Alternative action for users who don't have accounts yet
    [:div.text-center
     [:p.text-sm.mb-4 "Don't have an account yet?"]
     [:div.flex.gap-4.justify-center
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/signup"} "Sign Up"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/" :hx-boost "true"} "Home"]]]

    ;; Recaptcha disclosure
    [:.text-xs.text-green-400.text-opacity-75.mt-8
     biff/recaptcha-disclosure]]))

;;; Code verification page where users enter the 6-digit code sent to their email. The large,
;;; centered input field makes it easy to enter the code.
(defn enter-code-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4
 
    ;; Title section
    [:div.text-center.mb-8
     [:span.star.text-2xl "☆ "]
     [:h1.text-4xl.font-bold.glow "ENTER CODE"]
     [:span.star.text-2xl " ☆"]]
 
    ;; Divider
    [:div.w-96.border-t.border-green-400.mb-8]

    ;; Code verification form
    (biff/form
     {:action "/auth/verify-code"
      :id "code-form"
      :hidden {:email (:email params)}             ; Pass email from previous step
      :class "w-full max-w-md"}
     (biff/recaptcha-callback "submitCode" "code-form")
 
     [:div.mb-6
      [:label.block.mb-3 {:for "code"} 
       "We sent a 6-digit code to " [:span.font-bold (:email params)]]
      ;; Large, centered input optimized for 6-digit codes
      [:input#code.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono.text-center.text-2xl.tracking-widest
       {:name "code" :type "text" :placeholder "000000"}]]
 
     ;; Error messages
     (when-some [error (:error params)]
       [:<>
        [:.mb-4
         [:.text-sm.text-red-600
          (case error
            "invalid-code" "Invalid code. Try again."
            "There was an error.")]]])
 
     ;; Submit button
     [:button.bg-green-400.text-black.px-6.py-2.font-bold.w-full.hover:bg-green-300.transition-colors.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitCode"})
             {:type "submit"})
      "Verify Code"])
 
    ;; Divider
    [:div.w-96.border-t.border-green-400.my-8]

    ;; Resend code link: allow users to request a new code if theirs expired or was lost
    [:div.text-center
     (biff/form
      {:action "/auth/send-code"
       :id "signin"
       :hidden {:email (:email params)
                :on-error "/signin"}}
      (biff/recaptcha-callback "submitSignin" "signin")
      [:button.text-green-400.hover:text-green-300.transition-colors.underline.g-recaptcha
       (merge (when site-key
                {:data-sitekey site-key
                 :data-callback "submitSignin"})
              {:type "submit"})
       "Send another code"])]]))

;;;;
;;;; Module Definition
;;;;
;;;; Defines the routes for this module. Note the middleware separation (some routes redirect
;;;; already-signed-in users, while others are accessible to everyone).
;;;;

;;; Module configuration combining public routes (home, about) with authentication routes that
;;; redirect signed-in users to prevent confusion.
(def module
  {:routes [["" 
             ["/" {:get main-home/home}]           ; Main home page
             ["/about" {:get main-about/about}]]   ; About page
            ["" {:middleware [mid/wrap-redirect-signed-in]} ; Redirect authenticated users
             ["/link-sent" {:get link-sent}]
             ["/verify-link" {:get verify-email-page}]
             ["/signin" {:get signin-page}]
             ["/verify-code" {:get enter-code-page}]
             ["/signup" {:get home-page}]]]})
