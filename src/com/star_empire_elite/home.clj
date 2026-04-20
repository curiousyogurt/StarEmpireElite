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
;;;; Pages
;;;;

(defn home-page
  "Sign-up page with email input and reCAPTCHA. Sends a magic link to the submitted address.

  [ctx ring-ctx] -> hiccup"
  [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4

    [:div.text-center.mb-8
     [:span.star.text-2xl "☆ "]
     [:h1.text-4xl.font-bold.glow "SIGN UP"]
     [:span.star.text-2xl " ☆"]]

    [:div.w-96.border-t.border-green-400.mb-8]

    (biff/form
     {:action "/auth/send-code"
      :id "signup"
      :hidden {:on-error "/signup"}
      :class "w-full max-w-md"}
     (biff/recaptcha-callback "submitSignup" "signup")

     [:div.mb-6
      [:label.block.mb-3 {:for "email"} "Enter your email address:"]
      [:input#email.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono
       {:name "email"
        :type "email"
        :autocomplete "email"
        :placeholder "commander@bennington.edu"}]]

     (when-some [error (:error params)]
       [:<>
        [:.mb-4
         [:.text-sm.text-red-600
          (case error
            "recaptcha"     (str "You failed the recaptcha test. Try again, "
                                 "and make sure you aren't blocking scripts from Google.")
            "invalid-email" "You must have a Bennington College email address to play. If you believe you should have access, contact the game administrator."
            "send-failed"   (str "We weren't able to send an email to that address. "
                                 "If the problem persists, try another address.")
            "There was an error.")]]])

     [:button.bg-green-400.text-black.px-6.py-2.font-bold.w-full.hover:bg-green-300.transition-colors.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignup"})
             {:type "submit"})
      "Send Sign-Up Code"])

    [:div.w-96.border-t.border-green-400.my-8]

    [:div.text-center
     [:p.text-sm.mb-4 "Already have an account?"]
     [:div.flex.gap-4.justify-center
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/signin"} "Sign In"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/" :hx-boost "true"} "Home"]]]

    [:.text-xs.text-green-400.text-opacity-75.mt-8
     biff/recaptcha-disclosure]]))

(defn link-sent
  "Confirmation page shown after a sign-up link is emailed.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-xl.font-bold "Check your inbox"]
   [:p "We've sent a sign-up link to " [:span.font-bold (:email params)] "."]))

(defn verify-email-page
  "Cross-device email verification fallback — shown when a magic link is opened on a
  different device/browser than the one used to sign up.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-2xl.font-bold (str "Sign up for " settings/app-name)]
   [:.h-3]
   (biff/form
    {:action "/auth/verify-link"
     :hidden {:token (:token params)}}
    [:div [:label {:for "email"}
           "It looks like you opened this link on a different device or browser than the one "
           "you signed up on. For verification, please enter the email you signed up with:"]]
    [:.h-3]
    [:.flex
     [:input#email {:name "email" :type "email"
                    :placeholder "Enter your email address"}]
     [:.w-3]
     [:button.btn {:type "submit"}
      "Sign in"]])
   (when-some [error (:error params)]
     [:<>
      [:.h-1]
      [:.text-sm.text-red-600
       (case error
         "incorrect-email" "Incorrect email address. Try again."
         "There was an error.")]])))

(defn signin-page
  "Sign-in page with email input and reCAPTCHA. Sends a 6-digit code to the submitted address.

  [ctx ring-ctx] -> hiccup"
  [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4

    [:div.text-center.mb-8
     [:span.star.text-2xl "☆ "]
     [:h1.text-4xl.font-bold.glow "SIGN IN"]
     [:span.star.text-2xl " ☆"]]

    [:div.w-96.border-t.border-green-400.mb-8]

    (biff/form
     {:action "/auth/send-code"
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

     (when-some [error (:error params)]
       [:<>
        [:.mb-4
         [:.text-sm.text-red-600
          (case error
            "recaptcha"     (str "You failed the recaptcha test. Try again, "
                                 "and make sure you aren't blocking scripts from Google.")
            "invalid-email" "You must have a Bennington College email address to play. If you believe you should have access, contact the game administrator."
            "send-failed"   (str "We weren't able to send an email to that address. "
                                 "If the problem persists, try another address.")
            "invalid-link"  "Invalid or expired link. Sign in to get a new link."
            "not-signed-in" "You must be signed in to view that page."
            "There was an error.")]]])

     [:button.bg-green-400.text-black.px-6.py-2.font-bold.w-full.hover:bg-green-300.transition-colors.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignin"})
             {:type "submit"})
      "Send Sign-In Code"])

    [:div.w-96.border-t.border-green-400.my-8]

    [:div.text-center
     [:p.text-sm.mb-4 "Don't have an account yet?"]
     [:div.flex.gap-4.justify-center
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/signup"} "Sign Up"]
      [:a.border.border-green-400.px-6.py-2.hover:bg-green-400.hover:bg-opacity-10.transition-colors.inline-block
       {:href "/" :hx-boost "true"} "Home"]]]

    [:.text-xs.text-green-400.text-opacity-75.mt-8
     biff/recaptcha-disclosure]]))

(defn enter-code-page
  "Code entry page shown after a sign-in email is sent. Accepts the 6-digit verification code.

  [ctx ring-ctx] -> hiccup"
  [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   [:div.min-h-screen.flex.flex-col.items-center.justify-center.text-green-400.font-mono.p-4

    [:div.text-center.mb-8
     [:span.star.text-2xl "☆ "]
     [:h1.text-4xl.font-bold.glow "ENTER CODE"]
     [:span.star.text-2xl " ☆"]]

    [:div.w-96.border-t.border-green-400.mb-8]

    (biff/form
     {:action "/auth/verify-code"
      :id "code-form"
      :hidden {:email (:email params)}
      :class "w-full max-w-md"}
     (biff/recaptcha-callback "submitCode" "code-form")

     [:div.mb-6
      [:label.block.mb-3 {:for "code"}
       "We sent a 6-digit code to " [:span.font-bold (:email params)]]
      [:input#code.w-full.bg-black.border.border-green-400.text-green-400.p-2.font-mono.text-center.text-2xl.tracking-widest
       {:name "code" :type "text" :placeholder "000000"}]]

     (when-some [error (:error params)]
       [:<>
        [:.mb-4
         [:.text-sm.text-red-600
          (case error
            "invalid-code" "Invalid code. Try again."
            "There was an error.")]]])

     [:button.bg-green-400.text-black.px-6.py-2.font-bold.w-full.hover:bg-green-300.transition-colors.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitCode"})
             {:type "submit"})
      "Verify Code"])

    [:div.w-96.border-t.border-green-400.my-8]

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
;;;; Routes
;;;;

(def module
  {:routes [[""
             ["/" {:get main-home/home}]
             ["/about" {:get main-about/about}]]
            ["" {:middleware [mid/wrap-redirect-signed-in]}
             ["/link-sent"    {:get link-sent}]
             ["/verify-link"  {:get verify-email-page}]
             ["/signin"       {:get signin-page}]
             ["/verify-code"  {:get enter-code-page}]
             ["/signup"       {:get home-page}]]]})
