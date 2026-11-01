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
   :event/payload-data  {:alliance/kind :peace}})

(def treaty-dissolved-event
  (assoc treaty-formed-event :event/kind :treaty-dissolved))

(def treaty-broken-event
  (assoc treaty-formed-event :event/kind :treaty-broken))

(def treaty-formed-defence-event
  (assoc treaty-formed-event :event/payload-data {:alliance/kind :defence}))

(def treaty-formed-unknown-kind-event
  (assoc treaty-formed-event :event/payload-data {:alliance/kind :made-up}))

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

(deftest test-treaty-formed-names-the-defence-kind
  (testing "a :defence treaty reads its own label from const/treaty-kinds, not peace's"
    (let [text (pr-str (event-summary treaty-formed-defence-event proposer-id))]
      (is (str/includes? text "signed a defence treaty with")))))

(deftest test-treaty-formed-falls-back-to-kind-neutral-for-an-unrecognized-kind
  (testing "a stale or unrecognized kind on an old stored event says just 'a treaty',
            rather than guessing or crashing"
    (let [text (pr-str (event-summary treaty-formed-unknown-kind-event proposer-id))]
      (is (str/includes? text "signed a treaty with")))))

(deftest test-treaty-formed-names-the-other-side
  (testing "each viewer sees themself as 'You' and the other empire named"
    (let [proposer-text  (pr-str (event-summary treaty-formed-event proposer-id))
          recipient-text (pr-str (event-summary treaty-formed-event recipient-id))]
      (is (str/includes? proposer-text "You"))
      (is (str/includes? proposer-text "Crimson Hand"))
      (is (str/includes? recipient-text "Ironwall"))
      (is (str/includes? recipient-text "you")))))
