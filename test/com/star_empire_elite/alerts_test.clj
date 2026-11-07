(ns com.star-empire-elite.alerts-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.alerts :as alerts]
            [com.star-empire-elite.constants :as const]))

;;;;
;;;; incoming-messages Tests
;;;;

(deftest test-incoming-messages-empty-player
  (testing "Returns an empty vector when the player has no incoming history"
    (is (= [] (alerts/incoming-messages {})))))

(deftest test-incoming-messages-strike
  (testing "A pr-str'd strike record becomes one ACTION row"
    (let [player {:player/incoming-attacks
                  [(pr-str {:mode :strike :attacker-name "Gurunkachunk"})]}
          [row]  (alerts/incoming-messages player)]
      (is (= :action (:alert/category row)))
      (is (= "Gurunkachunk" (:alert/sender row)))
      (is (= "launched a strike against you" (:alert/text row))))))

(deftest test-incoming-messages-invade-outcome-suffix
  (testing "Invade/raid rows include a SUCCESS/FAILURE suffix; strike rows do not"
    (let [player {:player/incoming-attacks
                  [(pr-str {:mode :invade :attacker-name "Foo" :attacker-wins? true})]}
          [row]  (alerts/incoming-messages player)]
      (is (= "invaded you (SUCCESS)" (:alert/text row))))))

(deftest test-incoming-messages-treaty-broken-adds-diplomacy-row
  (testing "An attack that broke a treaty yields both an ACTION row and a DIPLOMACY row"
    (let [player {:player/incoming-attacks
                  [(pr-str {:mode :strike :attacker-name "Foo" :treaty-broken? true})]}
          rows   (alerts/incoming-messages player)]
      (is (= 2 (count rows)))
      (is (= [:action :diplomacy] (mapv :alert/category rows))))))

(deftest test-incoming-messages-treaty-broken-names-the-kind
  (testing "The combat-break row names whichever kind was actually broken"
    (let [player {:player/incoming-attacks
                  [(pr-str {:mode :raid :attacker-name "Foo" :treaty-broken? true
                            :treaty-broken-kind :defence})]}
          rows   (alerts/incoming-messages player)]
      (is (= "broke a defence treaty with you." (:alert/text (second rows)))))))

(deftest test-incoming-messages-treaty-broken-defaults-to-peace-for-legacy-records
  (testing "A stored record from before :treaty-broken-kind existed defaults to peace"
    (let [player {:player/incoming-attacks
                  [(pr-str {:mode :raid :attacker-name "Foo" :treaty-broken? true})]}
          rows   (alerts/incoming-messages player)]
      (is (= "broke a peace treaty with you." (:alert/text (second rows)))))))

(deftest test-incoming-messages-ally-battle-success
  (testing "A defending ally-battle record where the attacker lost is a SUCCESS row"
    (let [player {:player/incoming-ally-battles
                  [(pr-str {:role :defending :kind :defence :mode :invade
                            :attacker-name "Raider" :defender-name "Buddy"
                            :attacker-wins? false})]}
          [row]  (alerts/incoming-messages player)]
      (is (= :action (:alert/category row)))
      (is (= :invade (:alert/badge row)))
      (is (= "Buddy" (:alert/sender row)))
      (is (= "You joined Buddy's defence against Raider (SUCCESS)" (:alert/text row))))))

(deftest test-incoming-messages-ally-battle-failure
  (testing "A defending ally-battle record where the attacker won is a FAILURE row"
    (let [player {:player/incoming-ally-battles
                  [(pr-str {:role :defending :kind :defence :mode :invade
                            :attacker-name "Raider" :defender-name "Buddy"
                            :attacker-wins? true})]}
          [row]  (alerts/incoming-messages player)]
      (is (= "You joined Buddy's defence against Raider (FAILURE)" (:alert/text row))))))

(deftest test-incoming-messages-ally-battle-attacking-success
  (testing "An attacking ally-battle record where the attacker won is a SUCCESS row"
    (let [player {:player/incoming-ally-battles
                  [(pr-str {:role :attacking :kind :offence :mode :invade
                            :attacker-name "Raider" :defender-name "Buddy"
                            :attacker-wins? true})]}
          [row]  (alerts/incoming-messages player)]
      (is (= :action (:alert/category row)))
      (is (= :invade (:alert/badge row)))
      (is (= "Raider" (:alert/sender row)))
      (is (= "You joined Raider's offensive against Buddy (SUCCESS)" (:alert/text row))))))

(deftest test-incoming-messages-ally-battle-attacking-failure
  (testing "An attacking ally-battle record where the attacker lost is a FAILURE row"
    (let [player {:player/incoming-ally-battles
                  [(pr-str {:role :attacking :kind :offence :mode :invade
                            :attacker-name "Raider" :defender-name "Buddy"
                            :attacker-wins? false})]}
          [row]  (alerts/incoming-messages player)]
      (is (= "You joined Raider's offensive against Buddy (FAILURE)" (:alert/text row))))))

(deftest test-incoming-messages-bomb-success
  (testing "A bomb result with nonzero losses is a SUCCESS row"
    (let [player {:player/incoming-bomb-result (pr-str {:soldiers 3})}
          [row]  (alerts/incoming-messages player)]
      (is (= :espionage (:alert/category row)))
      (is (= "bombed you (SUCCESS)" (:alert/text row))))))

(deftest test-incoming-messages-bomb-failure
  (testing "A bomb result with zero losses is a FAILURE row"
    (let [player {:player/incoming-bomb-result (pr-str {:soldiers 0 :transports 0
                                                          :fighters 0 :carriers 0})}
          [row]  (alerts/incoming-messages player)]
      (is (= "bombed you (FAILURE)" (:alert/text row))))))

(deftest test-incoming-messages-espionage-fail-verbs
  (testing "Each failed-espionage op maps to its own verb"
    (let [player {:player/incoming-espionage-fails ["spy" "incite" "bomb" "defect"]}
          rows   (alerts/incoming-messages player)]
      (is (= ["spied on you (FAILED)" "incited unrest in you (FAILED)"
              "bombed you (FAILED)" "turned agents from you (FAILED)"]
             (mapv :alert/text rows))))))

(deftest test-incoming-messages-treaty-vectors
  (testing "Each treaty-lifecycle vector contributes rows with the right text and badge"
    (let [player {:player/incoming-treaty-proposed  ["A"]
                  :player/incoming-treaty-accepted  ["B"]
                  :player/incoming-treaty-withdrawn ["C"]
                  :player/incoming-treaty-declined  ["D"]
                  :player/incoming-treaty-ended      ["E"]}
          rows   (alerts/incoming-messages player)]
      (is (every? #(= :diplomacy (:alert/category %)) rows))
      (is (= ["A" "B" "C" "D" "E"] (mapv :alert/sender rows))))))

(deftest test-incoming-messages-treaty-declined
  (testing "A declined proposal is an instant message to the proposer, naming the decliner"
    (let [player {:player/incoming-treaty-declined ["Decliner Empire"]}
          [row]  (alerts/incoming-messages player)]
      (is (= :diplomacy (:alert/category row)))
      (is (= :treaty-ended (:alert/badge row)))
      (is (= "Decliner Empire" (:alert/sender row)))
      (is (= "declined your treaty proposal." (:alert/text row))))))

(deftest test-incoming-messages-espionage-treaty-broken
  (testing "A treaty broken by someone else's espionage against you is its own DIPLOMACY
            row, ordered right after espionage-fail rows and before the five treaty-
            lifecycle vectors — mirrors the ACTION+DIPLOMACY pairing attack-messages
            already does for a combat-broken treaty"
    (let [player {:player/incoming-espionage-treaty-broken ["Spymaster Empire"]
                  :player/incoming-treaty-proposed         ["Proposer"]}
          rows   (alerts/incoming-messages player)]
      (is (= [:diplomacy :diplomacy] (mapv :alert/category rows)))
      (is (= :treaty-broken (:alert/badge (first rows))))
      (is (= "Spymaster Empire" (:alert/sender (first rows))))
      (is (= "broke a treaty with you (espionage)." (:alert/text (first rows)))
          "no kind is shown here — :player/incoming-espionage-treaty-broken only ever
           stored an empire name, never the kind")
      (is (= "Proposer" (:alert/sender (second rows)))
          "the espionage-broken row comes before the treaty-lifecycle vectors"))))

;;;;
;;;; group-messages Tests
;;;;

(deftest test-group-messages-dedupe-and-count
  (testing "Identical (category, sender, text) rows collapse with the right count"
    (let [msgs   [{:alert/category :action :alert/sender "X" :alert/text "hit you"}
                  {:alert/category :action :alert/sender "X" :alert/text "hit you"}
                  {:alert/category :action :alert/sender "X" :alert/text "hit you"}]
          groups (alerts/group-messages msgs nil)]
      (is (= 1 (count groups)))
      (is (= 3 (:alert/count (first groups)))))))

(deftest test-group-messages-distinct-sender-not-merged
  (testing "Rows with different senders stay separate groups"
    (let [msgs   [{:alert/category :action :alert/sender "X" :alert/text "hit you"}
                  {:alert/category :action :alert/sender "Y" :alert/text "hit you"}]
          groups (alerts/group-messages msgs nil)]
      (is (= 2 (count groups))))))

(deftest test-group-messages-sort-by-category-then-arrival
  (testing "Groups sort ACTION -> ESPIONAGE -> DIPLOMACY, then by first-arrival order"
    (let [msgs   [{:alert/category :diplomacy :alert/sender "A" :alert/text "t1"}
                  {:alert/category :action    :alert/sender "B" :alert/text "t2"}
                  {:alert/category :espionage :alert/sender "C" :alert/text "t3"}
                  {:alert/category :action    :alert/sender "D" :alert/text "t4"}]
          groups (alerts/group-messages msgs nil)]
      (is (= [:action :action :espionage :diplomacy] (mapv :alert/category groups)))
      ;; The two ACTION groups keep their original arrival order (B before D).
      (is (= ["B" "D"] (mapv :alert/sender (take 2 groups)))))))

(deftest test-group-messages-new-flag
  (testing "A group is :new? only when it has more copies than the seen-groups snapshot"
    (let [msgs        [{:alert/category :action :alert/sender "X" :alert/text "hit you"}
                        {:alert/category :action :alert/sender "X" :alert/text "hit you"}
                        {:alert/category :action :alert/sender "Y" :alert/text "hit you"}]
          seen-groups {[:action "X" "hit you"] 2}
          groups      (alerts/group-messages msgs seen-groups)
          by-sender   (into {} (map (juxt :alert/sender identity)) groups)]
      (is (false? (:alert/new? (by-sender "X"))))
      (is (true?  (:alert/new? (by-sender "Y")))))))

(deftest test-group-messages-empty
  (testing "No messages yields no groups"
    (is (= [] (alerts/group-messages [] nil)))))

;;;;
;;;; group-counts Tests
;;;;

(deftest test-group-counts-roundtrips-with-seen-groups
  (testing "group-counts produces a snapshot that marks the same groups as seen"
    (let [msgs    [{:alert/category :action :alert/sender "X" :alert/text "hit you"}
                   {:alert/category :action :alert/sender "X" :alert/text "hit you"}]
          counts  (alerts/group-counts msgs)
          groups  (alerts/group-messages msgs counts)]
      (is (= {[:action "X" "hit you"] 2} counts))
      (is (false? (:alert/new? (first groups)))))))

;;;;
;;;; cap Tests
;;;;

(deftest test-alert-max-rows-cap
  (testing "group-messages returns one group per unique row, and the cap is applied by callers"
    (let [msgs   (for [i (range 12)]
                   {:alert/category :action :alert/sender (str "X" i) :alert/text "hit you"})
          groups (alerts/group-messages msgs nil)]
      (is (= 12 (count groups)))
      (is (= const/alert-max-rows 8))
      (is (= const/alert-max-rows (count (take const/alert-max-rows groups)))))))

;;;;
;;;; summary Tests
;;;;

(deftest test-summary-joins-nonzero-categories
  (testing "Summary lists only categories with messages, in category order"
    (let [msgs [{:alert/category :action} {:alert/category :action}
                {:alert/category :diplomacy}]]
      (is (= "2 attacks · 1 diplomacy" (alerts/summary msgs))))))

(deftest test-summary-singular-attack
  (testing "A single ACTION message is singular: '1 attack'"
    (is (= "1 attack" (alerts/summary [{:alert/category :action}])))))

(deftest test-summary-empty
  (testing "No messages yields an empty string"
    (is (= "" (alerts/summary [])))))

;;;;
;;;; chip-state Tests
;;;;

(deftest test-chip-state-empty
  (testing "Zero total messages is always :empty, regardless of open/unread"
    (is (= :empty (alerts/chip-state 0 0 false)))
    (is (= :empty (alerts/chip-state 0 0 true)))))

(deftest test-chip-state-open
  (testing "Open takes priority over unread when total > 0"
    (is (= :open (alerts/chip-state 5 3 true)))))

(deftest test-chip-state-unread
  (testing "Unread when closed with unseen messages"
    (is (= :unread (alerts/chip-state 5 3 false)))))

(deftest test-chip-state-read
  (testing "Read when closed with no unseen messages"
    (is (= :read (alerts/chip-state 5 0 false)))))

;;;;
;;;; unread-count Tests
;;;;

(deftest test-unread-count-basic
  (testing "unread-count is incoming-seq minus incoming-seen-seq"
    (is (= 4 (alerts/unread-count {:player/incoming-seq 10 :player/incoming-seen-seq 6})))))

(deftest test-unread-count-nil-fields
  (testing "Missing fields default to 0"
    (is (= 0 (alerts/unread-count {})))))

(deftest test-unread-count-never-negative
  (testing "A stale seen-seq above the current seq clamps to 0 rather than going negative"
    (is (= 0 (alerts/unread-count {:player/incoming-seq 2 :player/incoming-seen-seq 10})))))

;;;;
;;;; seen-groups Tests
;;;;

(deftest test-seen-groups-nil-when-absent
  (testing "Returns nil when the player has no snapshot yet"
    (is (nil? (alerts/seen-groups {})))))

(deftest test-seen-groups-parses-stored-snapshot
  (testing "Parses a pr-str'd snapshot back into a map"
    (let [player {:player/incoming-seen-groups (pr-str {[:action "X" "hit you"] 2})}]
      (is (= {[:action "X" "hit you"] 2} (alerts/seen-groups player))))))
