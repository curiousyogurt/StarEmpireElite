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

(defn starfield-style []
  [:style "
   body {
     background-color: #111;
     background-image: 
       radial-gradient(2px 2px at  5%  8%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 12% 15%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 18% 22%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 25% 30%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 32% 38%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 38% 45%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 45% 52%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 52% 60%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 58% 68%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 65% 75%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 72% 82%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 78% 90%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 85% 12%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 92% 35%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at  8% 42%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 15% 58%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 22% 70%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 35% 18%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 42% 88%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 55% 25%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 62% 48%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 68% 12%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 75% 62%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 88% 55%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at  3% 95%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 28%  5%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 48% 78%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 82% 38%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 18% 82%, #fff, rgba(0,0,0,0)),
       radial-gradient(2px 2px at 72% 28%, #fff, rgba(0,0,0,0));
     background-size: 100% 100%;
     background-attachment: fixed;
     font-family: 'IBM Plex Mono', 'Menlo', 'Courier', monospace;
   }
   
  @media (max-width: 439px) {
    .border-green-400 {
      border: none !important;
      background-color: transparent !important;
    }
    body {
      font-size: 0.75rem;
    }
    h1 {
      font-size: 1.5rem;
      word-wrap: break-word;
      overflow-wrap: break-word;
    }
    h2 {
      font-size: 1.125rem;
      word-wrap: break-word;
      overflow-wrap: break-word;
    }
    h3 {
      font-size: 1rem;
    }
    p {
      font-size: 0.75rem;
      word-wrap: break-word;
      overflow-wrap: break-word;
      padding: 0 4px;
    }
    div {
      padding: 4px !important;
      margin: 2px !important;
    }
  }
  "])

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
                                      (starfield-style)
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
    [:div.min-h-screen.flex.flex-col.items-center.justify-center.mx-auto.bg-black.bg-opacity-10.text-green-400.font-mono.p-4.border-8.border-green-400.rounded-lg
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
