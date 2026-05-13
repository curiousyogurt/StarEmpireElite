(ns com.star-empire-elite.ui-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.ui :as ui]))

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
      (is (= :div.text-xs.uppercase.mb-1 (first result)))
      (is (= "Sources" (last result))))))

;;;;
;;;; action-bar-link Tests
;;;;

(deftest test-action-bar-link
  (testing "Returns an anchor hiccup element with the correct href and label"
    (let [[tag attrs label] (ui/action-bar-link "/app/game/123" "Pause")]
      (is (= :a tag))
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
      (is (= :div.flex.flex-col.gap-1 (first result))))))

(deftest test-projection-pill-signed-positive
  (testing "Signed? true and a positive total produces a hiccup vector"
    (let [result (ui/projection-pill "Credits" 500 [] {:signed? true})]
      (is (vector? result))
      ;; header row is the 3rd element (index 2) — [:keyword attrs ...children...]
      (let [header-row (nth result 2)
            total-span (last header-row)]
        ;; Color for a positive signed value is not red
        (is (not= "#f87171" (get-in total-span [1 :style :color])))))))

(deftest test-projection-pill-signed-negative
  (testing "Signed? true renders the total span in red when value is negative"
    (let [result    (ui/projection-pill "Fuel" -50 [] {:signed? true})
          ;; header row is the 3rd element (index 2)
          header-row (nth result 2)
          total-span (last header-row)]
      (is (vector? total-span))
      ;; Color should be red for a negative signed total
      (is (= "#f87171" (get-in total-span [1 :style :color]))))))

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
                                          :player/incoming-espionage-fails 0})))
    (is (nil? (ui/incoming-alert-content {})))))

(deftest test-incoming-alert-content-with-attacks
  (testing "Returns hiccup when the player has incoming attacks"
    (let [result (ui/incoming-alert-content {:player/incoming-attacks        [:some-attack]
                                             :player/incoming-espionage-fails 0})]
      (is (some? result))
      (is (vector? result)))))

(deftest test-incoming-alert-content-with-esp-fails
  (testing "Returns hiccup when espionage failures are recorded"
    (let [result (ui/incoming-alert-content {:player/incoming-attacks        nil
                                             :player/incoming-espionage-fails 2})]
      (is (some? result))
      (is (vector? result)))))

(deftest test-incoming-alert-content-both-alerts
  (testing "Returns hiccup when both attacks and espionage failures are present"
    (let [result (ui/incoming-alert-content {:player/incoming-attacks        [:attack]
                                             :player/incoming-espionage-fails 1})]
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
;;;; resource-display-grid Tests
;;;;

(def ^:private sample-player
  {:player/credits     1000 :player/food      500 :player/fuel       300
   :player/galaxars    50   :player/population  6  :player/stability   80
   :player/ore-planets 3    :player/erg-planets 2  :player/mil-planets 1
   :player/soldiers    100  :player/transports  5  :player/generals     2
   :player/fighters    20   :player/carriers    1  :player/admirals     0
   :player/stations    10   :player/cmd-ships   0  :player/agents       5})

(deftest test-resource-display-grid-renders
  (testing "Returns a hiccup div for a standard player entity"
    (let [result (ui/resource-display-grid sample-player "Test Resources")]
      (is (vector? result))
      (is (= :div.border.border-green-400.p-4.mb-4.bg-green-100.bg-opacity-5 (first result))))))

(deftest test-resource-display-grid-plain-keys
  (testing "Also accepts a plain resource map using :credits, :food, :fuel, etc."
    (let [plain {:credits 999 :food 400 :fuel 200 :galaxars 10
                 :population 5 :stability 70
                 :ore-planets 2 :erg-planets 1 :mil-planets 3
                 :soldiers 50 :transports 2 :generals 1
                 :fighters 8  :carriers 0  :admirals 0
                 :stations 3  :cmd-ships 0 :agents 4}
          result (ui/resource-display-grid plain "Plain Keys")]
      (is (vector? result)))))

(deftest test-resource-display-grid-highlight-negative
  (testing "Renders without error when highlight-negative? is true and values are negative"
    (let [negative {:player/credits -100 :player/food -50 :player/fuel -200
                    :player/galaxars 0  :player/population  1 :player/stability  0
                    :player/ore-planets 0 :player/erg-planets 0 :player/mil-planets 0
                    :player/soldiers 0 :player/transports 0 :player/generals 0
                    :player/fighters 0 :player/carriers 0 :player/admirals 0
                    :player/stations 0 :player/cmd-ships 0 :player/agents 0}
          result   (ui/resource-display-grid negative "After Expenses" true)]
      (is (vector? result)))))

;;;;
;;;; snapshot-section Tests
;;;;

(deftest test-snapshot-section-renders
  (testing "Returns a hiccup div for a player with all required fields"
    (let [result (ui/snapshot-section sample-player)]
      (is (vector? result))
      (is (= :div (first result))))))

(deftest test-snapshot-section-population-formatting
  (testing "Renders without error for various population values (double formatting)"
    (doseq [pop [0 1 6 100 1000]]
      (is (vector? (ui/snapshot-section (assoc sample-player :player/population pop)))))))

;;;;
;;;; stat-pill Tests
;;;;
;;;; stat-pill renders a pill with labelled rows. Tests verify structure, value
;;;; color variants (:highlight?, :warn?, both), and the :display string override.
;;;;
;;;; Structure:
;;;;   result[0]       = :div.flex.flex-col.gap-1
;;;;   result[2]       = [:span ... title]
;;;;   result[3]       = [:div {:class ...} (for-lazy-seq-of-rows ...)]
;;;;   result[3][2]    = lazy seq of row hiccup
;;;;   first row[3]    = [:span.text-xs.font-bold {:style {:color <value-color>}} value]
;;;;

(defn- stat-pill-first-row
  "Navigate to the first data row in a stat-pill result."
  [pill]
  (first (nth (nth pill 3) 2)))

(deftest test-stat-pill-renders
  (testing "Returns a hiccup div with the correct root tag"
    (let [result (ui/stat-pill "Ground" [{:label "Soldiers" :value 100}])]
      (is (vector? result))
      (is (= :div.flex.flex-col.gap-1 (first result))))))

(deftest test-stat-pill-default-color
  (testing "Plain row (no :highlight? or :warn?) renders value in the dim color"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5}]))]
      (is (= "#9adaaa" (get-in row [3 1 :style :color]))))))

(deftest test-stat-pill-highlight-color
  (testing ":highlight? true renders the value in bright green"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5 :highlight? true}]))]
      (is (= "#4ade80" (get-in row [3 1 :style :color]))))))

(deftest test-stat-pill-warn-color
  (testing ":warn? true renders the value in yellow"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5 :warn? true}]))]
      (is (= "#facc15" (get-in row [3 1 :style :color]))))))

(deftest test-stat-pill-warn-overrides-highlight
  (testing ":warn? takes priority over :highlight? when both are set"
    (let [row (stat-pill-first-row (ui/stat-pill "T" [{:label "L" :value 5 :warn? true :highlight? true}]))]
      (is (= "#facc15" (get-in row [3 1 :style :color]))))))

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
