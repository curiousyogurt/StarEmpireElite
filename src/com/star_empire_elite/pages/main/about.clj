(ns com.star-empire-elite.pages.main.about
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.star-empire-elite.ui :as ui]))

;;; About pages: game background section (default) and SEE genre essay section.
;;; Each tab is a distinct URL rendered as a full page so they are directly linkable.

;;;; Components

(defn- nav-link [href label & [attrs]]
  [:a (merge {:href  href
              :class "no-underline text-game-green-soft font-mono rounded-sm border border-game-green-border bg-transparent tracking-[0.04em] py-[5px] px-4 inline-block text-[13px]"}
             attrs)
   label])

(defn- tab-button
  "Renders a tab button. active? controls filled vs. outline style."
  [href label active?]
  [:a {:href     href
       :hx-boost "true"
       :class    (if active?
                   "py-1 px-3.5 border border-green-400 bg-game-green-deep text-green-400 rounded-sm text-[13px] font-bold tracking-[0.04em] no-underline"
                   "py-1 px-3.5 border border-game-green-border bg-transparent text-game-green-soft rounded-sm text-[13px] tracking-[0.04em] no-underline")}
   label])

;;;; Essay parser
;;; Converts a Markdown-lite text file into Hiccup. Supports ## headings and
;;; *em* inline spans. Blank lines separate blocks.

(defn- parse-inline
  "Split text into plain strings, [:em ...], and [:span.font-bold ...] elements.
  **text** → bold, *text* → italic. Double-star is matched first so single-star
  cannot accidentally consume part of a double-star span.

  [text string] -> vec of (string | hiccup-element)"
  [text]
  (loop [remaining text
         result    []]
    ;; Match **bold** before *italic* so the alternation is unambiguous.
    (if-let [[full-match bold-inner italic-inner] (re-find #"\*\*([^*]+)\*\*|\*([^*]+)\*" remaining)]
      (let [idx (.indexOf remaining full-match)]
        (recur (subs remaining (+ idx (count full-match)))
               (cond-> result
                 (pos? idx)    (conj (subs remaining 0 idx))
                 bold-inner    (conj [:span.font-bold bold-inner])
                 italic-inner  (conj [:em italic-inner]))))
      (if (seq remaining)
        (conj result remaining)
        result))))

(defn- parse-essay
  "Read a Markdown-lite essay resource and return a Hiccup fragment.
  Paragraphs are separated by blank lines; ## starts a heading.

  [resource-path string] -> hiccup-fragment"
  [resource-path]
  (let [text   (slurp (io/resource resource-path))
        blocks (str/split text #"\n\n+")]
    (into [:<>]
          (for [block blocks
                :let  [trimmed (str/trim block)]]
            (if (str/starts-with? trimmed "## ")
              (into [:h2.font-bold.text-green-400 {:class "text-lg mb-5"}]
                    (parse-inline (subs trimmed 3)))
              (into [:p.text-left.text-game-green-soft {:class "text-[13px] mb-5 leading-[1.7] max-w-[48rem]"}]
                    (parse-inline trimmed)))))))

;;;; Content sections

(defn about-content  [] (parse-essay "overview.md"))
(defn essay-content  [] (parse-essay "history.md"))
(defn design-content [] (parse-essay "design.md"))

;;;;
;;;; Pages
;;;;

(defn about-page
  "Renders the full about page. tab is :about, :essay, or :design."
  [tab]
  (ui/page
    {}
    [:div.text-base.w-full.max-w-3xl.mx-auto.overflow-hidden.relative
     {:class "border-[1.5px] border-game-green-border rounded bg-game-bg text-green-400 font-mono"}
     (ui/scanline-overlay)

     ;; Page header
     [:div.bg-game-surface.border-b.border-game-green-border
      {:class "py-[7px] px-3.5"}
      [:div.font-bold.text-green-400 {:class "text-[22px] tracking-wider"} "ABOUT"]]

     [:div {:class "py-4 px-5"}

      ;; Tab bar
      [:div.flex.gap-2.mb-6
       (tab-button "/about"        "Overview" (= tab :about))
       (tab-button "/about/essay"  "History"  (= tab :essay))
       (tab-button "/about/design" "Design"   (= tab :design))]

      ;; Tab content
      (case tab
        :about  (about-content)
        :essay  (essay-content)
        :design (design-content))

      ;; Navigation links
      [:div.border-t.border-game-green-border {:class "pt-3 mt-2"}
       [:div.flex.gap-3
        (nav-link "/signup" "Sign In / Sign Up")
        (nav-link "/" "Home" {:hx-boost "true"})]]]]))

;;;;
;;;; Handlers
;;;;

(defn about [_ctx]
  (about-page :about))

(defn essay [_ctx]
  (about-page :essay))

(defn design [_ctx]
  (about-page :design))
