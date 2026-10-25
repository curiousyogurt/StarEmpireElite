(ns com.star-empire-elite.pages.app.news-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.news :as news])
  (:import (java.util UUID)))

;;;;
;;;; event-summary — treaty-formed / treaty-dissolved / treaty-broken dispatch
;;;;

(def ^:private event-summary #'news/event-summary)

(def proposer-id  (UUID/fromString "00000000-0000-0000-0000-000000000001"))
(def recipient-id (UUID/fromString "00000000-0000-0000-0000-000000000002"))

(def treaty-formed-event
  {:event/kind          :treaty-formed
   :event/visibility    :public
   :event/attacker      proposer-id
   :event/attacker-name "Ironwall"
   :event/defender      recipient-id
   :event/defender-name "Crimson Hand"
   :event/payload-data  {}})

(def treaty-dissolved-event
  (assoc treaty-formed-event :event/kind :treaty-dissolved))

(def treaty-broken-event
  (assoc treaty-formed-event :event/kind :treaty-broken))

(deftest test-treaty-formed-is-not-unknown
  (testing "a :treaty-formed event renders real text, not the 'Unknown event' fallback"
    (let [text (pr-str (event-summary treaty-formed-event proposer-id))]
      (is (not (str/includes? text "Unknown event")))
      (is (str/includes? text "signed a peace treaty with")))))

(deftest test-treaty-dissolved-is-not-unknown
  (testing "a :treaty-dissolved event renders real text, not the 'Unknown event' fallback"
    (let [text (pr-str (event-summary treaty-dissolved-event proposer-id))]
      (is (not (str/includes? text "Unknown event")))
      (is (str/includes? text "dissolved the peace treaty with")))))

(deftest test-treaty-broken-is-not-unknown
  (testing "a :treaty-broken event renders real text, not the 'Unknown event' fallback"
    (let [text (pr-str (event-summary treaty-broken-event proposer-id))]
      (is (not (str/includes? text "Unknown event")))
      (is (str/includes? text "broke a peace treaty with")))))

(deftest test-treaty-formed-names-the-other-side
  (testing "each viewer sees themself as 'You' and the other empire named"
    (let [proposer-text  (pr-str (event-summary treaty-formed-event proposer-id))
          recipient-text (pr-str (event-summary treaty-formed-event recipient-id))]
      (is (str/includes? proposer-text "You"))
      (is (str/includes? proposer-text "Crimson Hand"))
      (is (str/includes? recipient-text "Ironwall"))
      (is (str/includes? recipient-text "you")))))
