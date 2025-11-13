(ns com.star-empire-elite.ui
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [com.star-empire-elite.settings :as settings]
            [com.biffweb :as biff]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.response :as ring-response]
            [rum.core :as rum]))

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

;;; Phase progress indicator showing current position in the 6-phase turn cycle. Uses filled circles
;;; for completed phases, a highlighted circle for current phase, and empty circles for future phases.
(defn phase-indicator [current-phase]
  [:div.flex.items-center.gap-2
   ;; Phase progress circles
   (for [phase (range 1 7)]
     [:div.flex.items-center.gap-1 {:key phase}
      ;; Circle indicator with larger numbers
      [:div.w-5.h-5.rounded-full.border.border-green-400.flex.items-center.justify-center
       {:class (if (= phase current-phase) 
                 "bg-green-400 ring-2 ring-green-300"            ; Current phase only
                 "bg-transparent")}                               ; All other phases
       ;; Larger, non-bold phase number inside circle
       [:span.text-base
        {:class (if (= phase current-phase) "text-black" "text-green-400")}
        phase]]
      ;; Arrow between phases (except after last phase)
      (when (< phase 6)
        [:span.text-green-400.text-xs.ml-1 "→"])])])

;;; Complete phase header with title on the left and progress indicator on the right. Takes current 
;;; phase number and phase name string to generate the full header with progress visualization.
(defn phase-header [current-phase phase-name]
  [:div.flex.items-center.justify-between.mb-6
   ;; Phase title on the left
   [:h2.text-xl.font-bold (str "PHASE " current-phase ": " phase-name)]
   ;; Phase indicator on the right
   (phase-indicator current-phase)])

(defn base [{:keys [::recaptcha] :as ctx} & body]
  (apply
    biff/base-html
    (-> ctx
        (merge #:base{:title settings/app-name
                      :lang "en-US"
                      :icon "/img/glider.png"
                      :description "Shape destiny. Write history. Become legend."
                      :image "https://clojure.org/images/clojure-logo-120b.png"})
        (update :base/head (fn [head]
                             (concat [[:link {:rel "stylesheet" :href (static-path "/css/main.css")}]
                                      [:script {:src (static-path "/js/main.js")}]
                                      [:script {:src "https://unpkg.com/htmx.org@2.0.7"}]
                                      [:script {:src "https://unpkg.com/htmx-ext-ws@2.0.2/ws.js"}]
                                      [:script {:src "https://unpkg.com/hyperscript.org@0.9.14"}]
                                      (when recaptcha
                                        [:script {:src "https://www.google.com/recaptcha/api.js"
                                                  :async "async" :defer "defer"}])]
                                     head))))
    body))

(defn page [ctx & body]
  (base
    ctx
    [:.flex-grow]
    ;; CORRECTED: All classes are now on one line, attached to :div
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.mx-auto.text-green-400.font-mono.p-4.border-8.border-green-400.rounded-lg.bg-black.bg-opacity-10
     (merge
       {:class "m-2 w-full sm:m-4 md:m-10 md:w-11/12"}
       (when (bound? #'csrf/*anti-forgery-token*)
         {:hx-headers (cheshire/generate-string
                        {:x-csrf-token csrf/*anti-forgery-token*})}))
     body]
    [:.flex-grow]
    [:.flex-grow]))

(defn on-error [{:keys [status ex] :as ctx}]
  {:status status
   :headers {"content-type" "text/html"}
   :body (rum/render-static-markup
           (page
             ctx
             [:h1.text-lg.font-bold
              (if (= status 404)
                "Page not found."
                "Something went wrong.")]))})
