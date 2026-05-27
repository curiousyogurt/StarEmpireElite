(ns com.star-empire-elite.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [com.star-empire-elite.ui :as ui]))

;; Private sub-function access via var reference
(def ^:private manifest-item        #'ui/snapshot-manifest-item)
(def ^:private manifest-row         #'ui/snapshot-manifest-row)
(def ^:private hero                  #'ui/snapshot-hero)
(def ^:private manifest              #'ui/snapshot-manifest)
(def ^:private projections-section   ui/projection-grid)

;;;;
;;;; format-number Tests
;;;;
;;;; format-number returns a plain string for small values and a hiccup [:span ...]
;;;; with a tooltip for abbreviated values (100K+).
;;;;

(deftest test-format-number-nil
  (testing "Returns nil when given nil"
    (is (nil? (ui/format-number nil)))))

(deftest test-format-number-zero
  (testing "Returns the string \"0\" for zero"
    (is (= "0" (ui/format-number 0)))))

(deftest test-format-number-small-values
  (testing "Returns plain string for values below 100K"
    (is (= "1"     (ui/format-number 1)))
    (is (= "999"   (ui/format-number 999)))
    (is (= "99999" (ui/format-number 99999)))))

(deftest test-format-number-abbreviated-100k
  (testing "Returns a [:span ...] hiccup element for 100K+"
    (let [result (ui/format-number 100000)]
      (is (vector? result))
      (is (= :span (first result)))
      ;; Second element is the attrs map with :title
      (is (= "100000" (:title (second result))))
      ;; Third element is the abbreviated string
      (is (= "100.0K" (nth result 2))))))

(deftest test-format-number-millions
  (testing "Abbreviates millions correctly"
    (let [result (ui/format-number 1000000)]
      (is (vector? result))
      (is (= "1.0M" (nth result 2))))))

(deftest test-format-number-billions
  (testing "Abbreviates billions correctly"
    (let [result (ui/format-number 1000000000)]
      (is (vector? result))
      (is (= "1.0B" (nth result 2))))))

(deftest test-format-number-negative-large
  (testing "Negative large numbers are prefixed with a minus sign"
    (let [result (ui/format-number -200000)]
      (is (vector? result))
      (is (= "-200.0K" (nth result 2))))))

;;;;
;;;; format-number-str Tests
;;;;

(deftest test-format-number-str-nil
  (testing "Returns nil when given nil"
    (is (nil? (ui/format-number-str nil)))))

(deftest test-format-number-str-small
  (testing "Returns plain string for small values"
    (is (= "0"    (ui/format-number-str 0)))
    (is (= "9999" (ui/format-number-str 9999)))))

(deftest test-format-number-str-abbreviated
  (testing "Returns abbreviated string (no hiccup wrapping)"
    (is (= "100.0K" (ui/format-number-str 100000)))
    (is (= "1.0M"   (ui/format-number-str 1000000)))
    (is (string? (ui/format-number-str 100000)))))

;;;;
;;;; format-scale-tick-str Tests
;;;;

(deftest test-format-scale-tick-str-nil
  (testing "Returns nil when given nil"
    (is (nil? (ui/format-scale-tick-str nil)))))

(deftest test-format-scale-tick-str-zero
  (testing "Returns \"0\" for zero"
    (is (= "0" (ui/format-scale-tick-str 0)))))

(deftest test-format-scale-tick-str-small
  (testing "Returns plain integer string for values below 1000"
    (is (= "1"   (ui/format-scale-tick-str 1)))
    (is (= "999" (ui/format-scale-tick-str 999)))))

(deftest test-format-scale-tick-str-thousands
  (testing "Abbreviates thousands with a K suffix"
    (is (= "1K" (ui/format-scale-tick-str 1000)))
    (is (= "2K" (ui/format-scale-tick-str 2000)))))

(deftest test-format-scale-tick-str-large
  (testing "Delegates to format-number-str for values >= 1M"
    (is (= "1.0M" (ui/format-scale-tick-str 1000000)))))

;;;;
;;;; svg-indicator-bar Tests
;;;;

(deftest test-svg-indicator-bar-gain
  (testing ":gain direction returns a hiccup div"
    (let [result (ui/svg-indicator-bar :gain 100 200 "glow-test")]
      (is (vector? result))
      (is (= :div.flex.flex-col.justify-center.h-full.px-8 (first result))))))

(deftest test-svg-indicator-bar-loss
  (testing ":loss direction returns a hiccup div"
    (let [result (ui/svg-indicator-bar :loss 500 200 "glow-loss")]
      (is (vector? result))
      (is (= :div.flex.flex-col.justify-center.h-full.px-8 (first result))))))

(deftest test-svg-indicator-bar-zero-before-and-after
  (testing "Renders without error when before and after are both zero"
    (is (vector? (ui/svg-indicator-bar :gain 0 0 "glow-zero")))
    (is (vector? (ui/svg-indicator-bar :loss 0 0 "glow-zero")))))

(deftest test-svg-indicator-bar-no-change
  (testing "Renders without error when there is no change (before == after)"
    (is (vector? (ui/svg-indicator-bar :gain 100 100 "glow-flat")))))

;;;;
;;;; submit-button Tests
;;;;

(deftest test-submit-button-enabled
  (testing "Enabled button has no :disabled attribute"
    (let [btn (ui/submit-button true "Continue")]
      (is (vector? btn))
      (is (not (:disabled (second btn)))))))

(deftest test-submit-button-disabled
  (testing "Disabled button has :disabled set to true"
    (let [btn (ui/submit-button false "Continue")]
      (is (vector? btn))
      (is (true? (:disabled (second btn)))))))

(deftest test-submit-button-extra-attrs
  (testing "extra-attrs map is merged into the button element"
    (let [btn (ui/submit-button true "Go" {:hx-swap-oob "true"})]
      (is (= "true" (:hx-swap-oob (second btn)))))))

(deftest test-submit-button-label
  (testing "The label string appears as the button text"
    (let [btn (ui/submit-button true "My Label")]
      (is (= "My Label" (last btn))))))

;;;;
;;;; section-label Tests
;;;;

(deftest test-section-label
  (testing "Returns a div hiccup element containing the label text"
    (let [result (ui/section-label "Sources")]
      (is (vector? result))
      (is (= :div.text-xs.uppercase.my-1 (first result)))
      (is (= "Sources" (nth result 2))))))

;;;;
;;;; action-bar-link Tests
;;;;

(deftest test-action-bar-link
  (testing "Returns an anchor hiccup element with the correct href and label"
    (let [[tag attrs label] (ui/action-bar-link "/app/game/123" "Pause")]
      (is (keyword? tag))
      (is (clojure.string/starts-with? (name tag) "a"))
      (is (= "/app/game/123" (:href attrs)))
      (is (= "Pause" label)))))

;;;;
;;;; phase-stepper Tests
;;;;

(deftest test-phase-stepper-renders
  (testing "Returns a hiccup div for any current phase"
    (doseq [phase (range 1 7)]
      (let [result (ui/phase-stepper phase)]
        (is (vector? result))
        (is (= :div.flex.items-center.gap-1 (first result)))))))

;;;;
;;;; projection-pill Tests
;;;;

(deftest test-projection-pill-renders
  (testing "Returns a hiccup div with title and rows"
    (let [result (ui/projection-pill "Credits" 1000
                                     [{:label "Planets" :value 200 :suffix "cr"}
                                      {:label "Taxes"   :value 100 :suffix "cr"}])]
      (is (vector? result))
      (is (clojure.string/starts-with? (name (first result)) "div")))))

(deftest test-projection-pill-signed-positive
  (testing "Signed? true and a positive total produces a hiccup vector"
    (let [result (ui/projection-pill "Credits" 500 [] {:signed? true})]
      (is (vector? result))
      ;; header row is the 3rd element (index 2) — [:keyword attrs ...children...]
      (let [header-row (nth result 2)
            total-span (last header-row)]
        ;; Class for a positive signed value is not red
        (is (not= "text-red-400" (get-in total-span [1 :class])))))))

(deftest test-projection-pill-signed-negative
  (testing "Signed? true renders the total span in red when value is negative"
    (let [result    (ui/projection-pill "Fuel" -50 [] {:signed? true})
          ;; header row is the 3rd element (index 2)
          header-row (nth result 2)
          total-span (last header-row)]
      (is (vector? total-span))
      ;; Class should be red for a negative signed total
      (is (= "text-red-400" (get-in total-span [1 :class]))))))

(deftest test-projection-pill-total-id
  (testing "total-id option assigns an :id to the total span"
    (let [result     (ui/projection-pill "Food" 200 [] {:total-id "food-total"})
          header-row (nth result 2)
          total-span (last header-row)]
      (is (= "food-total" (get-in total-span [1 :id]))))))

;;;;
;;;; oob-pill Tests
;;;;

(deftest test-oob-pill-renders
  (testing "Returns a span hiccup element"
    (let [result (ui/oob-pill "my-id" "Planets" 300 "cr")]
      (is (vector? result))
      (is (= :span.text-xs.inline-block.rounded-sm.text-green-400 (first result))))))

(deftest test-oob-pill-id-and-oob
  (testing "Sets :id and :hx-swap-oob attributes"
    (let [result (ui/oob-pill "credits-pill" "Taxes" 500 "cr")
          attrs  (second result)]
      (is (= "credits-pill" (:id attrs)))
      (is (= "true" (:hx-swap-oob attrs))))))

(deftest test-oob-pill-positive-sign
  (testing "Positive value gets a + prefix"
    (let [result (ui/oob-pill "id" "Label" 100 "cr")]
      (is (some #(= "+" %) (rest result))))))

(deftest test-oob-pill-negative-sign
  (testing "Negative value gets a - prefix"
    (let [result (ui/oob-pill "id" "Label" -50 "fuel")]
      (is (some #(= "-" %) (rest result))))))

;;;;
;;;; incoming-alert-content Tests
;;;;

(deftest test-incoming-alert-content-no-alerts
  (testing "Returns nil when there are no attacks and zero espionage failures"
    (is (nil? (ui/incoming-alert-content {:player/incoming-attacks        nil
                                          :player/incoming-espionage-fails nil})))
    (is (nil? (ui/incoming-alert-content {})))))

(deftest test-incoming-alert-content-with-attacks
  (testing "Returns hiccup when the player has incoming attacks"
    (let [result (ui/incoming-alert-content {:player/incoming-attacks        [:some-attack]
                                             :player/incoming-espionage-fails nil})]
      (is (some? result))
      (is (vector? result)))))

(deftest test-incoming-alert-content-with-esp-fails
  (testing "Returns hiccup when espionage failures are recorded"
    (let [result (ui/incoming-alert-content {:player/incoming-attacks        nil
                                             :player/incoming-espionage-fails ["spy" "incite"]})]
      (is (some? result))
      (is (vector? result)))))

(deftest test-incoming-alert-content-both-alerts
  (testing "Returns hiccup when both attacks and espionage failures are present"
    (let [result (ui/incoming-alert-content {:player/incoming-attacks        [:attack]
                                             :player/incoming-espionage-fails ["spy"]})]
      (is (some? result))
      (is (vector? result)))))

;;;;
;;;; deduction-table-header Tests
;;;;

(deftest test-deduction-table-header-renders
  (testing "Returns a :<> fragment containing mobile and desktop rows"
    (let [result (ui/deduction-table-header)]
      (is (vector? result))
      (is (= :<> (first result))))))

(deftest test-deduction-table-header-has-two-rows
  (testing "Fragment contains exactly two row variants (mobile + desktop)"
    (let [result   (ui/deduction-table-header)
          children (rest result)]
      (is (= 2 (count children))))))

;;;;
;;;; snapshot-section Tests
;;;;

(def ^:private sample-player
  {:player/credits     1000 :player/food      500 :player/fuel       300
   :player/galaxars    50   :player/population  6  :player/stability   80
   :player/ore-planets 3    :player/erg-planets 2  :player/mil-planets 1
   :player/soldiers    100  :player/transports  5  :player/generals     2
   :player/fighters    20   :player/carriers    1  :player/admirals     0
   :player/stations    10   :player/cmd-ships   0  :player/agents       5})

(deftest test-snapshot-section-renders
  (testing "Returns a hiccup div for a player with all required fields"
    (let [result (ui/snapshot-section sample-player)]
      (is (vector? result))
      (is (clojure.string/starts-with? (name (first result)) "div")))))

(deftest test-snapshot-section-population-formatting
  (testing "Renders without error for various population values (double formatting)"
    (doseq [pop [0 1 6 100 1000]]
      (is (vector? (ui/snapshot-section (assoc sample-player :player/population pop)))))))

(deftest test-snapshot-section-no-projections
  (testing "Projections section is absent when :projections opt is not passed"
    (let [result (str (ui/snapshot-section sample-player))]
      (is (not (str/includes? result "PROJECTIONS"))))))



;;;;
;;;; snapshot-manifest-item Tests
;;;;

(deftest test-manifest-item-nonzero-color
  (testing "Non-zero value renders value span in bright green"
    (let [[_ _ _ value-span] (manifest-item "soldiers" "100" false)]
      (is (str/includes? (:class (second value-span)) "text-green-400")))))

(deftest test-manifest-item-zero-color
  (testing "Zero value renders value span dim and non-bold"
    (let [[_ _ _ value-span] (manifest-item "soldiers" "0" true)]
      (is (str/includes? (:class (second value-span)) "text-game-green-dim"))
      (is (str/includes? (:class (second value-span)) "font-normal")))))

(deftest test-manifest-item-label-text
  (testing "Label text is rendered in the label span"
    (let [[_ _ label-span] (manifest-item "fighters" "50" false)]
      (is (= "fighters" (last label-span))))))

;;;;
;;;; snapshot-manifest-row Tests
;;;;

(deftest test-manifest-row-not-last-has-divider
  (testing "Non-last row includes a bottom dashed divider class"
    (let [[_ attrs] (manifest-row "EMPIRE" [] false)]
      (is (str/includes? (:class attrs) "border-b")))))

(deftest test-manifest-row-last-no-divider
  (testing "Last row omits the bottom dashed divider class"
    (let [[_ attrs] (manifest-row "EMPIRE" [] true)]
      (is (not (str/includes? (:class attrs) "border-b"))))))

(deftest test-manifest-row-tag-prefix
  (testing "Tag label includes the '› ' prefix"
    (let [tag-div (nth (manifest-row "GROUND" [] false) 2)]
      (is (str/includes? (last tag-div) "GROUND")))))

;;;;
;;;; snapshot-hero Tests
;;;;

(deftest test-snapshot-hero-renders
  (testing "Returns a 4-column grid hiccup div"
    (let [result (hero sample-player)]
      (is (vector? result))
      (is (= :div.grid.grid-cols-4 (first result))))))

(deftest test-snapshot-hero-shows-credits
  (testing "Credits value appears in the hero strip"
    (let [result (str (hero (assoc sample-player :player/credits 42)))]
      (is (str/includes? result "42")))))

;;;;
;;;; projection-grid Tests
;;;;

(deftest test-projection-grid-accepts-opts
  (testing "Accepts optional opts map without error (callers may still pass one)"
    (let [proj [{:name "Credits" :total 100 :rows []}]]
      (is (some? (projections-section proj {:projection-turn "THIS TURN"}))))))

(deftest test-projection-grid-negative-total-amber
  (testing "Negative total renders amber color class"
    (let [result (str (projections-section [{:name "Credits" :total -50 :rows []}]))]
      (is (str/includes? result "text-amber-400")))))

(deftest test-projection-grid-positive-total-green
  (testing "Positive total renders green color class"
    (let [result (str (projections-section [{:name "Credits" :total 100 :rows []}]))]
      (is (str/includes? result "text-green-400")))))

(deftest test-projection-grid-total-id
  (testing "total-id is set as the :id attribute on the total span"
    (let [result (projections-section [{:name "Credits" :total 100 :total-id "my-id" :rows []}])
          result-str (str result)]
      (is (str/includes? result-str "my-id")))))

(deftest test-projection-grid-card-count
  (testing "Renders one card per entry in the projections vector"
    (let [proj  [{:name "A" :total 1 :rows []}
                 {:name "B" :total 2 :rows []}
                 {:name "C" :total 3 :rows []}]
          ;; result = [:div {:class ...} (map-seq)]
          ;; the map-seq is a single lazy-seq child at index 2
          result (projections-section proj)
          cards  (nth result 2)]
      (is (= 3 (count cards))))))

;;;;
;;;; stat-pill Tests
;;;;
;;;; stat-pill renders a pill with labelled rows. Tests verify structure, value
;;;; color variants (:highlight?, :warn?, both), and the :display string override.
;;;;
;;;; Structure:
;;;;   result[0]       = :div.flex.flex-col.gap-1.rounded-game.bg-game-card
;;;;   result[2]       = [:span ... title]
;;;;   result[3]       = [:div {:class ...} (for-lazy-seq-of-rows ...)]
;;;;   result[3][2]    = lazy seq of row hiccup
;;;;   first row[3]    = [:span.text-xs.font-bold {:class <tailwind-color-class>} value]
;;;;

(defn- stat-pill-first-row
  "Navigate to the first data row in a stat-pill result."
  [pill]
  (first (nth (nth pill 3) 2)))

(deftest test-stat-pill-renders
  (testing "Returns a hiccup div with the correct root tag"
    (let [result (ui/stat-pill "Ground" [{:label "Soldiers" :value 100}])]
      (is (vector? result))
      (is (clojure.string/starts-with? (name (first result)) "div")))))

(deftest test-stat-pill-default-color
  (testing "Plain row (no :highlight? or :warn?) renders value in the soft color class"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5}]))]
      (is (str/includes? (get-in row [3 1 :class]) "text-game-green-soft")))))

(deftest test-stat-pill-highlight-color
  (testing ":highlight? true renders the value in bright green class"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5 :highlight? true}]))]
      (is (str/includes? (get-in row [3 1 :class]) "text-green-400")))))

(deftest test-stat-pill-warn-color
  (testing ":warn? true renders the value in yellow class"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5 :warn? true}]))]
      (is (str/includes? (get-in row [3 1 :class]) "text-yellow-400")))))

(deftest test-stat-pill-warn-overrides-highlight
  (testing ":warn? takes priority over :highlight? when both are set"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5 :warn? true :highlight? true}]))]
      (is (str/includes? (get-in row [3 1 :class]) "text-yellow-400")))))

(deftest test-stat-pill-display-override
  (testing ":display renders a custom string instead of format-number"
    ;; row = [:div attrs label-span value-span]
    ;; value-span = [:span attrs display-string]
    (let [row        (stat-pill-first-row (ui/stat-pill "T" [{:label "Limit" :display "Trans" :value 0}]))
          value-span (nth row 3)]
      (is (= "Trans" (last value-span))))))

(deftest test-stat-pill-row-count
  (testing "Renders one hiccup div per entry in the rows collection"
    (let [rows-seq (nth (nth (ui/stat-pill "T"
                               [{:label "A" :value 1}
                                {:label "B" :value 2}
                                {:label "C" :value 3}])
                             3) 2)]
      (is (= 3 (count rows-seq))))))
