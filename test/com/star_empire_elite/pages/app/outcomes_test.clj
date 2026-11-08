(ns com.star-empire-elite.pages.app.outcomes-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.pages.app.outcomes :as outcomes]
            [com.star-empire-elite.utils :as utils]
            [com.star-empire-elite.test-helpers :as helpers]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures
;;;;

(def test-player-id #uuid "00000000-0000-0000-0000-000000000050")
(def test-game-id   #uuid "00000000-0000-0000-0000-000000000060")

;; Game with 3 turns per round (so turn 3 triggers end-of-round).
(def test-game
  (merge helpers/game-defaults
  {:xt/id                  test-game-id
   :game/turns-per-round   3
   :game/rounds-per-day    2
   :game/created-at        (java.util.Date. 0)
   :game/scheduled-end-at  (java.util.Date. (* 7 24 60 60 1000))}))

;; Player in phase 6 (outcomes), mid-round at turn 1 of 3.
(def test-player
  {:xt/id                           test-player-id
   :player/game                     test-game-id
   :player/empire-name              "Test Empire"
   :player/current-phase            6
   :player/current-turn             1
   :player/current-round            1
   :player/turns-used               0
   ;; Resources (needed for score calculation)
   :player/credits                  5000
   :player/population               0
   :player/mil-planets              3
   :player/erg-planets             2
   :player/ore-planets              1
   :player/soldiers                 100
   :player/fighters                 20
   :player/cmd-ships                1
   :player/stations                 5
   :player/generals                 2
   :player/admirals                 1
   ;; Fields cleared by apply-outcomes
   :player/last-battle-result        "some-result"
   :player/last-espionage-result     "some-result"
   :player/last-population-growth    3
   :player/pending-espionage         test-game-id ; non-nil to verify it is cleared
   ;; A real pr-str'd result, not a bare string — outcomes-page actually renders this via
   ;; incoming-card/combat-card, and a placeholder string crashes once something forces
   ;; the (otherwise-lazy) hiccup tree, e.g. str on the whole page.
   :player/incoming-attacks          [(pr-str {:mode :raid :attacker-name "Aggressor"
                                                :attacker-wins? true :defender-counts {}
                                                :attacker-losses {} :defender-losses {}
                                                :planets-transferred {:mil 0 :erg 0 :ore 0}})]
   :player/incoming-ally-battles     [(pr-str {:role :defending :kind :defence :mode :raid
                                                :attacker-name "Aggressor" :defender-name "Buddy"
                                                :attacker-wins? false
                                                :contributed {:soldiers 10} :losses {:soldiers 2}})]
   :player/incoming-espionage-fails  ["spy" "incite"]
   ;; NOT cleared by apply-outcomes — these persist until apply-diplomacy (see its comment).
   :player/incoming-treaty-proposed  ["Proposer Empire"]
   :player/incoming-treaty-accepted  ["Acceptor Empire"]
   :player/incoming-treaty-withdrawn ["Withdrawer Empire"]
   :player/incoming-treaty-declined  ["Decliner Empire"]
   :player/incoming-treaty-ended     ["Ender Empire"]})

;;;;
;;;; calculate-score Tests
;;;;

(deftest test-calculate-score-formula
  (testing "Planets dominate the score, military units are tie-breakers"
    (let [player {:player/mil-planets 2  :player/erg-planets 1  :player/ore-planets 0
                  :player/soldiers    10 :player/transports  4  :player/fighters    5
                  :player/carriers    2  :player/cmd-ships   1  :player/stations    3
                  :player/generals    1  :player/admirals    0  :player/agents      6}
          ;; Uses score weights from constants.clj
          expected (+ (* 2 const/score-mil-planet) (* 1 const/score-erg-planet) (* 0 const/score-ore-planet)
                      (* 10 const/score-soldier) (* 4 const/score-transport) (* 5 const/score-fighter)
                      (* 2 const/score-carrier) (* 1 const/score-cmd-ship) (* 3 const/score-station)
                      (* 1 const/score-general) (* 0 const/score-admiral) (* 6 const/score-agent))]
      (is (= expected (utils/calculate-score test-game player))))))

(deftest test-calculate-score-zero-player
  (testing "Returns zero when all values are zero"
    (let [player {:player/mil-planets 0 :player/erg-planets 0 :player/ore-planets 0
                  :player/soldiers 0 :player/transports 0 :player/fighters 0
                  :player/carriers 0 :player/cmd-ships 0 :player/stations 0
                  :player/generals 0 :player/admirals 0 :player/agents 0}]
      (is (= 0 (utils/calculate-score test-game player))))))

(deftest test-calculate-score-planets-dominate
  (testing "A single military planet outweighs hundreds of soldiers"
    (let [planet-player  {:player/mil-planets 1}
          ;; Use just under enough soldiers to match one mil-planet's score.
          soldier-count  (dec (quot const/score-mil-planet const/score-soldier))
          soldier-player {:player/soldiers soldier-count}]
      (is (> (utils/calculate-score test-game planet-player) (utils/calculate-score test-game soldier-player))))))

;;;;
;;;; apply-outcomes Tests
;;;;
;;;; apply-outcomes writes to the database. xt/entity and biff/submit-tx are replaced
;;;; with test doubles.
;;;;

(deftest test-apply-outcomes-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                             :biff/db nil})]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-outcomes-wrong-phase
  (testing "Redirects to game overview when player is not in phase 6"
    (let [player (assoc test-player :player/current-phase 5)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])]
        (let [result (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                               :biff/db nil})]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id) (get-in result [:headers "location"]))))))))

(deftest test-apply-outcomes-mid-round
  (testing "Mid-round: increments turn, keeps round, resets to phase 1"
    ;; Player is at turn 1 of 3, turns-used 0. After submit: turn 2, round stays 1.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                               :biff/db nil})
              tx     (first @tx-atom)]
          ;; Redirect to income
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/income") (get-in result [:headers "location"])))
          ;; Turn advances, round stays the same
          (is (= 2 (:player/current-turn  tx)))
          (is (= 1 (:player/current-round tx)))
          ;; Phase resets to 1 (income)
          (is (= 1 (:player/current-phase tx)))
          ;; Turns used increments
          (is (= 1 (:player/turns-used tx)))
          ;; last-round-completed-at is NOT set mid-round
          (is (nil? (:player/last-round-completed-at tx))))))))

(deftest test-apply-outcomes-end-of-round
  (testing "End-of-round: resets turn to 1, increments round, records completion time, resets
            turns-used, and routes to the diplomacy phase (7) instead of straight to income"
    ;; turns-per-round is 3; player at turns-used 2, so this is the last turn.
    (let [end-round-player (assoc test-player :player/current-turn 3 :player/turns-used 2)
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [end-round-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                               :biff/db nil})
              tx     (first @tx-atom)]
          ;; Redirect to diplomacy, not income
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/diplomacy") (get-in result [:headers "location"])))
          ;; Turn wraps back to 1, round increments
          (is (= 1 (:player/current-turn  tx)))
          (is (= 2 (:player/current-round tx)))
          ;; Turns used resets to 0 at round end
          (is (= 0 (:player/turns-used tx)))
          ;; Phase advances to diplomacy (7), not straight back to income (1)
          (is (= 7 (:player/current-phase tx)))
          ;; Completion timestamp is set (non-nil)
          (is (some? (:player/last-round-completed-at tx))))))))

(deftest test-apply-outcomes-clears-all-results
  (testing "Clears all battle/espionage results and incoming notifications"
    ;; test-player has non-nil values for all clearable fields.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                  :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (nil? (:player/last-battle-result       tx)))
          (is (nil? (:player/last-espionage-result    tx)))
          (is (nil? (:player/last-population-growth   tx)))
          (is (nil? (:player/pending-espionage        tx)))
          (is (nil? (:player/incoming-attacks         tx)))
          (is (nil? (:player/incoming-ally-battles    tx)))
          (is (nil? (:player/incoming-espionage-fails tx)))
          (is (nil? (:player/incoming-espionage-treaty-broken tx))))))))

(deftest test-apply-outcomes-does-not-clear-treaty-notifications
  (testing "Diplomacy notifications are left alone — they clear at apply-diplomacy instead,
            not every turn's apply-outcomes, so they survive until the Diplomacy phase"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                  :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (not (contains? tx :player/incoming-treaty-proposed)))
          (is (not (contains? tx :player/incoming-treaty-accepted)))
          (is (not (contains? tx :player/incoming-treaty-withdrawn)))
          (is (not (contains? tx :player/incoming-treaty-declined)))
          (is (not (contains? tx :player/incoming-treaty-ended))))))))

(deftest test-apply-outcomes-records-score
  (testing "Writes a computed score to the transaction"
    ;; Score is computed from the player's current resources by calculate-score.
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (outcomes/apply-outcomes {:path-params {:player-id (str test-player-id)}
                                  :biff/db nil})
        (let [tx (first @tx-atom)
              expected-score (utils/calculate-score test-game test-player)]
          (is (= expected-score (:player/score tx))))))))

;;;;
;;;; outcomes-page Tests
;;;;
;;;; outcomes-page is a pure UI function. Tests verify it renders without error across
;;;; the key combinations of battle/espionage results.
;;;;

(def sample-battle-result
  {:attacker-wins? true
   :defender-name  "Enemy Empire"
   :attacker-forces {:soldiers 50 :fighters 10 :cmd-ships 0 :transports 5
                     :carriers 1 :generals 1 :admirals 0}
   :defender-forces {:soldiers 30 :fighters 5 :cmd-ships 0 :transports 0
                     :carriers 0 :generals 0 :admirals 0 :stations 3}
   :attacker-losses {:soldiers 5 :fighters 1 :cmd-ships 0
                     :transports 0 :carriers 0 :generals 0
                     :admirals 0 :stations 0}
   :defender-losses {:soldiers 15 :fighters 3 :cmd-ships 0
                     :transports 0 :carriers 0 :generals 0
                     :admirals 0 :stations 1}
   :attacker-counts {:soldiers 50 :fighters 10 :cmd-ships 0 :transports 5
                     :carriers 1 :generals 1 :admirals 0}
   :planets-transferred {:mil 0 :erg 0 :ore 0}})

(def sample-espionage-result
  {:attacker-wins? true
   :defender-name  "Enemy Empire"
   :intel {:soldiers 30 :fighters 5 :cmd-ships 0 :transports 0
           :carriers 0 :generals 0 :admirals 0 :stations 3 :agents 1}})

(deftest test-outcomes-page-no-results
  (testing "Renders without error when no battle or espionage result"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil})))))

(deftest test-outcomes-page-with-battle-result
  (testing "Renders without error when a battle result is present"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result sample-battle-result
                                          :espionage-result nil})))))

(deftest test-outcomes-page-with-espionage-result
  (testing "Renders without error when an espionage result is present"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil
                                          :espionage-result sample-espionage-result})))))

(deftest test-outcomes-page-with-all-results
  (testing "Renders without error when both battle and espionage results are present"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result sample-battle-result
                                          :espionage-result sample-espionage-result})))))

(deftest test-outcomes-page-with-incoming-attacks
  (testing "Renders without error when player received incoming attacks this round"
    ;; Incoming attacks are stored as pr-str'd maps in :player/incoming-attacks.
    (let [attacker-result {:attacker-wins? true
                           :attacker-name  "Aggressor"
                           :defender-counts {:soldiers 50 :fighters 5 :cmd-ships 0 :transports 2
                                             :carriers 0 :generals 1 :admirals 0 :stations 3}
                           :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-losses {:soldiers 10 :fighters 2 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 1}
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player-with-attacks (assoc test-player
                                     :player/incoming-attacks [(pr-str attacker-result)]
                                     :player/incoming-espionage-fails ["spy"])]
      (is (vector? (outcomes/outcomes-page {:player player-with-attacks :game test-game
                                            :battle-result nil :espionage-result nil}))))))

(deftest test-incoming-card-shows-the-defenders-own-counts-and-losses-when-allied
  (testing "A fresh incoming-attack result, with a defence ally, still reports THIS
            player's own counts/losses as the main figure on their incoming card — never
            pooled with the ally — labeling the column 'YOU (ALLIES)' only so the reader
            knows an ally's own lumped contribution/loss follows in parentheses (see the
            separate allies-breakdown test), not because the main number itself changed."
    (let [attacker-result {:mode :invade :attacker-wins? true :attacker-name "Aggressor"
                           ;; Pooled — what the attacker actually fought (defender + ally);
                           ;; no longer what's shown as the main figure here.
                           :defender-counts {:soldiers 1100 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-losses {:soldiers 770 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           ;; Own — this player's real share; the main figure shown.
                           :defender-own-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                                 :transports 0 :carriers 0 :generals 0
                                                 :admirals 0 :stations 0}
                           :defender-own-losses {:soldiers 700 :fighters 0 :cmd-ships 0
                                                 :transports 0 :carriers 0 :generals 0
                                                 :admirals 0 :stations 0}
                           :defender-allies [{:ally-id (random-uuid) :ally-name "Buddy"
                                              :contributed {:soldiers 100} :losses {:soldiers 70}}]
                           :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player (assoc test-player :player/incoming-attacks [(pr-str attacker-result)]
                        :player/incoming-ally-battles nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "YOU (ALLIES)"))
      ;; 1000 (own) with a −700 loss shown — not 1100/−770 (the pooled totals).
      (is (clojure.string/includes? html "\"1000\""))
      (is (clojure.string/includes? html "\"700\""))
      (is (not (clojure.string/includes? html "\"1100\""))))))

(deftest test-incoming-card-shows-the-allies-own-lumped-contribution-and-loss
  (testing "Beyond the pooled total and the ally's name, the defender's incoming card also
            reports how many units the defence allies themselves contributed and lost, in
            muted-green parentheses, same as the attacker's outgoing card."
    (let [attacker-result {:mode :invade :attacker-wins? true :attacker-name "Aggressor"
                           :defender-counts {:soldiers 1100 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-losses {:soldiers 770 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-allies [{:ally-id (random-uuid) :ally-name "Buddy"
                                              :contributed {:soldiers 100} :losses {:soldiers 70}}]
                           :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player (assoc test-player :player/incoming-attacks [(pr-str attacker-result)]
                        :player/incoming-ally-battles nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      ;; The ally's own 100 contributed / 70 lost, with a space before the opening paren.
      (is (clojure.string/includes? html "\" (\""))
      (is (clojure.string/includes? html "\"100\""))
      (is (clojure.string/includes? html "\")\""))
      ;; A deliberate :class attribute, not Hiccup's dot-shorthand — "ml-1.5" as a tag-dot
      ;; class would silently split into "ml-1" + a bogus "5" class (see this user's own
      ;; memory note on the Hiccup dot-class decimal trap).
      (is (clojure.string/includes? html "text-game-green-muted"))
      (is (clojure.string/includes? html "\"ml-1.5\""))
      ;; The Alliance line is part of the combat card's own report, not a separate notice.
      (is (clojure.string/includes? html "» DEFENCE: Joined by Buddy.")))))

(deftest test-incoming-card-plain-you-header-with-no-defence-allies
  (testing "With no defence allies, the defender's incoming card header stays the plain
            'YOU' — no '(ALLIES)' suffix"
    (let [attacker-result {:mode :invade :attacker-wins? true :attacker-name "Aggressor"
                           :defender-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player (assoc test-player :player/incoming-attacks [(pr-str attacker-result)]
                        :player/incoming-ally-battles nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (not (clojure.string/includes? html "(ALLIES)"))))))

(deftest test-incoming-card-labels-the-attacker-column-when-the-attacker-is-allied
  (testing "On the DEFENDER's own incoming card, the right header names the attacker with
            its own '(ALLIES)' suffix when the ATTACKER brought offence allies — independent
            of whether the defender has allies of their own"
    (let [attacker-result {:mode :invade :attacker-wins? true :attacker-name "Orion's Belt"
                           :defender-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :attacker-allies [{:ally-id (random-uuid) :ally-name "Raider Pals"
                                              :contributed {:soldiers 50} :losses {:soldiers 0}}]
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player (assoc test-player :player/incoming-attacks [(pr-str attacker-result)]
                        :player/incoming-ally-battles nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "ORION'S BELT (ALLIES) ►"))
      ;; The defender's own column has no allies here, so it stays plain "YOU".
      (is (not (clojure.string/includes? html "◄ YOU (ALLIES)"))))))

(deftest test-outgoing-card-labels-the-defender-column-when-the-defender-is-allied
  (testing "On the ATTACKER's own outgoing card, the right header names the defender with
            its own '(ALLIES)' suffix when the DEFENDER brought defence allies"
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Crimson Hand"
                         :attacker-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-allies [{:ally-id (random-uuid) :ally-name "Shield Buddies"
                                            :contributed {:soldiers 50} :losses {:soldiers 0}}]
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          ;; test-player's default fixture carries its own ally-battle-card record, which
          ;; always shows "YOU (ALLIES)" now too — excluded here so the assertion below is
          ;; about THIS combat-card's left header, not an unrelated card on the same page.
          player (assoc test-player :player/incoming-ally-battles nil)
          html (str (outcomes/outcomes-page {:player player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      (is (clojure.string/includes? html "CRIMSON HAND (ALLIES) ►"))
      (is (not (clojure.string/includes? html "◄ YOU (ALLIES)"))))))

(deftest test-incoming-card-shows-the-attackers-allies-own-loss-share-in-the-right-column
  (testing "Not just the header — the right column's own cells also show how much the
            attacker's allies themselves lost, in muted-green parentheses, alongside the
            attacker's OWN loss (never pooled with their allies) as the main figure — same
            principle as the left column's own-figure-plus-allies-parenthetical"
    (let [attacker-result {:mode :invade :attacker-wins? true :attacker-name "Orion's Belt"
                           :defender-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           ;; Pooled — not what's shown as the main figure here.
                           :attacker-losses {:soldiers 300 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           ;; Own — the real attacker's share alone; the main figure shown.
                           :attacker-own-losses {:soldiers 50 :fighters 0 :cmd-ships 0
                                                 :transports 0 :carriers 0 :generals 0
                                                 :admirals 0 :stations 0}
                           :attacker-allies [{:ally-id (random-uuid) :ally-name "Raider Pals"
                                              :contributed {:soldiers 500} :losses {:soldiers 250}}]
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player (assoc test-player :player/incoming-attacks [(pr-str attacker-result)]
                        :player/incoming-ally-battles nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "ORION'S BELT (ALLIES) ►"))
      ;; 50 (the attacker's own loss) shown — not 300 (the pooled total).
      (is (clojure.string/includes? html "\"50\""))
      (is (not (clojure.string/includes? html "\"300\""))))))

(deftest test-incoming-card-labels-the-ally-breakdown-does-not-pool-opp-losses
  (testing "The ally's own 250-loss share is shown separately, parenthesized with a leading
            '−' — it is not added into the attacker's own main loss figure"
    (let [attacker-result {:mode :invade :attacker-wins? true :attacker-name "Orion's Belt"
                           :defender-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :attacker-losses {:soldiers 300 :fighters 0 :cmd-ships 0
                                             :transports 0 :carriers 0 :generals 0
                                             :admirals 0 :stations 0}
                           :attacker-own-losses {:soldiers 50 :fighters 0 :cmd-ships 0
                                                 :transports 0 :carriers 0 :generals 0
                                                 :admirals 0 :stations 0}
                           :attacker-allies [{:ally-id (random-uuid) :ally-name "Raider Pals"
                                              :contributed {:soldiers 500} :losses {:soldiers 250}}]
                           :planets-transferred {:mil 0 :erg 0 :ore 0}}
          player (assoc test-player :player/incoming-attacks [(pr-str attacker-result)]
                        :player/incoming-ally-battles nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "\" (\""))
      (is (clojure.string/includes? html "\"−\" \"250\""))
      (is (clojure.string/includes? html "\"250\"")))))

(deftest test-ally-battle-card-renders-contribution-and-losses
  (testing "This player's own incoming-ally-battles history renders a card naming who they
            defended, who attacked, the outcome, and their own contribution/losses"
    (let [record {:role :defending :kind :defence :mode :raid
                  :attacker-name "Raider" :defender-name "Buddy" :attacker-wins? false
                  :contributed {:soldiers 100 :transports 10 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 25 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "Buddy's defence against Raider"))
      (is (clojure.string/includes? html "SUCCESS"))
      (is (clojure.string/includes? html "100"))
      (is (clojure.string/includes? html "25")))))

(deftest test-ally-battle-card-shows-a-3-column-table-like-the-real-combatants-see
  (testing "the ally's own card has a '◄ YOU' / UNIT / '<ATTACKER> ►' table, same shape as
            combat-card — YOU is the ally's contribution with any loss shown inline,
            ATTACKER is the attacker's own losses for that unit"
    (let [record {:role :defending :kind :defence :mode :invade
                  :attacker-name "Raider" :defender-name "Buddy" :attacker-wins? false
                  :contributed {:soldiers 100 :transports 10 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 25 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}
                  :attacker-losses {:soldiers 300 :transports 5 :generals 1
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "RAIDER"))  ; combat-table-header upper-cases it
      (is (clojure.string/includes? html "YOU"))
      (is (clojure.string/includes? html "soldiers"))
      (is (clojure.string/includes? html "100"))
      (is (clojure.string/includes? html "300"))
      (is (clojure.string/includes? html "25")))))

(deftest test-ally-battle-card-attacker-losses-falls-back-to-zero-for-an-old-record
  (testing "a record stored before :attacker-losses existed renders every attacker-side
            cell as 0 instead of erroring"
    (let [record {:role :defending :kind :defence :mode :invade
                  :attacker-name "Raider" :defender-name "Buddy" :attacker-wins? false
                  :contributed {:soldiers 100 :transports 0 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 0 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)]
      (is (vector? (outcomes/outcomes-page {:player player :game test-game
                                            :battle-result nil :espionage-result nil}))))))

(deftest test-ally-battle-card-renders-an-attacking-ally-against-the-defender
  (testing "An :attacking ally-battle record names the attacker they joined and the
            defender they fought, with the defender's own losses as the opponent column"
    (let [record {:role :attacking :kind :offence :mode :raid
                  :attacker-name "Raider" :defender-name "Victim" :attacker-wins? true
                  :contributed {:soldiers 100 :transports 10 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 5 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}
                  :defender-losses {:soldiers 200 :transports 3 :generals 0
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "You joined Raider's offensive against Victim"))
      (is (clojure.string/includes? html "SUCCESS"))
      (is (clojure.string/includes? html "VICTIM"))  ; combat-table-header upper-cases it
      (is (clojure.string/includes? html "100"))
      (is (clojure.string/includes? html "200")))))

(deftest test-ally-battle-card-header-and-teammates-breakdown
  (testing "Rachel's Raiders joins Orion's Belt in attacking Polaris: the header reads
            'YOU (ALLIES)', the main figure is Rachel's Raiders' own contribution/loss, and
            a muted-green parenthetical lumps together everyone ELSE on the attacking side
            (Orion's Belt plus any co-allies) — found by subtracting Rachel's Raiders' own
            share from the whole side's pooled totals"
    (let [record {:role :attacking :kind :offence :mode :invade
                  :attacker-name "Orion's Belt" :defender-name "Polaris" :attacker-wins? true
                  ;; Rachel's Raiders' own contribution/loss.
                  :contributed {:soldiers 100 :transports 0 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 10 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}
                  ;; The whole attacking side's pooled totals — Orion's Belt's full stock
                  ;; plus Rachel's Raiders' 100 contributed; 60 total lost, 10 of it Rachel's.
                  :attacker-counts {:soldiers 1000 :transports 0 :generals 0
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}
                  :attacker-losses {:soldiers 60 :transports 0 :generals 0
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}
                  :defender-losses {:soldiers 0 :transports 0 :generals 0
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "◄ YOU (ALLIES)"))
      ;; Rachel's Raiders' own 100/−10 shown as the main figure.
      (is (clojure.string/includes? html "\"100\""))
      (is (clojure.string/includes? html "\"10\""))
      ;; Teammates (1000−100=900 contributed, 60−10=50 lost) lumped in muted-green parens.
      (is (clojure.string/includes? html "\" (\""))
      (is (clojure.string/includes? html "\"900\""))
      (is (clojure.string/includes? html "\"50\""))
      (is (clojure.string/includes? html "text-game-green-muted"))
      (is (clojure.string/includes? html "\"ml-1.5\"")))))

(deftest test-ally-battle-card-omits-teammates-breakdown-for-an-old-record
  (testing "a record stored before :attacker-counts/:defender-counts existed omits the
            teammates parenthetical rather than showing a bogus (negative) number — the
            main contribution/loss figure still renders fine"
    (let [record {:role :attacking :kind :offence :mode :invade
                  :attacker-name "Orion's Belt" :defender-name "Polaris" :attacker-wins? true
                  :contributed {:soldiers 100 :transports 0 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 10 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}
                  :defender-losses {:soldiers 0 :transports 0 :generals 0
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "\"100\""))
      (is (not (clojure.string/includes? html "\" (\""))))))

(deftest test-ally-battle-card-shows-opponents-own-loss-and-their-allies-share
  (testing "Rachel's Raiders joins Orion's Belt in attacking Polaris, and Polaris itself has
            a defence ally: the right column reports Polaris's OWN loss (never pooled with
            Polaris's own ally) as the main figure, the opponent header gets its own
            '(ALLIES)' suffix, and Polaris's ally's lumped loss share follows in its own
            muted-green parenthetical — mirroring combat-card's make-right exactly"
    (let [record {:role :attacking :kind :offence :mode :invade
                  :attacker-name "Orion's Belt" :defender-name "Polaris" :attacker-wins? true
                  :contributed {:soldiers 100 :transports 0 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 10 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}
                  ;; Polaris's side: pooled (Polaris + their own defence ally) 300, Polaris's
                  ;; own share 200 — their ally absorbed the other 100.
                  :defender-losses {:soldiers 300 :transports 0 :generals 0
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}
                  :defender-own-losses {:soldiers 200 :transports 0 :generals 0
                                        :fighters 0 :carriers 0 :admirals 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "POLARIS (ALLIES) ►"))
      ;; 200 (Polaris's own loss) shown as the main figure — not 300 (the pooled total).
      (is (clojure.string/includes? html "\"200\""))
      (is (not (clojure.string/includes? html "\"300\"")))
      ;; Polaris's ally's own 100-loss share, parenthesized with a leading "−".
      (is (clojure.string/includes? html "\" (\""))
      (is (clojure.string/includes? html "\"−\" \"100\"")))))

(deftest test-ally-battle-card-opponent-header-stays-plain-with-no-opponent-allies
  (testing "When the opponent has no treaty allies of their own, the right header stays
            plain (no '(ALLIES)' suffix), even though :defender-losses is technically
            still present — own equals pooled here, same as combat-card's own-vs-pooled
            no-op case"
    (let [record {:role :attacking :kind :offence :mode :invade
                  :attacker-name "Orion's Belt" :defender-name "Polaris" :attacker-wins? true
                  :contributed {:soldiers 100 :transports 0 :generals 0
                               :fighters 0 :carriers 0 :admirals 0}
                  :losses {:soldiers 10 :transports 0 :generals 0
                          :fighters 0 :carriers 0 :admirals 0}
                  :defender-losses {:soldiers 200 :transports 0 :generals 0
                                    :fighters 0 :carriers 0 :admirals 0 :cmd-ships 0}
                  :defender-own-losses {:soldiers 200 :transports 0 :generals 0
                                        :fighters 0 :carriers 0 :admirals 0}}
          player (assoc test-player :player/incoming-ally-battles [(pr-str record)]
                        :player/incoming-attacks nil :player/incoming-espionage-fails nil)
          html   (str (outcomes/outcomes-page {:player player :game test-game
                                               :battle-result nil :espionage-result nil}))]
      (is (not (clojure.string/includes? html "POLARIS (ALLIES)"))))))

(deftest test-outgoing-battle-card-shows-reinforced-by-when-allies-helped
  (testing "The attacker's own outgoing battle card names the defence allies who joined the
            defender's side, when there were any"
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         :attacker-counts {:soldiers 100 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-allies [{:ally-id (random-uuid) :ally-name "Ally One"
                                            :contributed {:soldiers 10} :losses {:soldiers 1}}]
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      (is (clojure.string/includes? html "» DEFENCE: Joined by Ally One.")))))

(deftest test-outgoing-battle-card-shows-offence-allies-too
  (testing "The attacker's own outgoing battle card can show BOTH an offence-ally line (who
            joined the attacker) and a defence-ally line (who joined the defender) at once"
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         :attacker-counts {:soldiers 110 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-allies [{:ally-id (random-uuid) :ally-name "Offence Buddy"
                                            :contributed {:soldiers 10} :losses {:soldiers 0}}]
                         :defender-allies [{:ally-id (random-uuid) :ally-name "Defence Buddy"
                                            :contributed {:soldiers 10} :losses {:soldiers 1}}]
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      (is (clojure.string/includes? html "» OFFENCE: Joined by Offence Buddy."))
      (is (clojure.string/includes? html "» DEFENCE: Joined by Defence Buddy.")))))

(deftest test-outgoing-battle-card-shows-pooled-counts-and-losses-when-allied
  (testing "The attacker's own outgoing battle card, with an offence ally, still reports
            THIS player's own counts/losses as the main figure — never pooled with the
            ally — labeling the column 'YOU (ALLIES)' only so the reader knows an ally's
            own lumped contribution/loss follows in parentheses (see the separate
            allies-breakdown test), not because the main number itself changed."
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         ;; Pooled — what the defender actually fought (attacker + ally);
                         ;; no longer what's shown as the main figure here.
                         :attacker-counts {:soldiers 1100 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 55 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         ;; Own — this player's real share; the main figure shown.
                         :attacker-own-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                               :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-own-losses {:soldiers 50 :fighters 0 :cmd-ships 0
                                               :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-allies [{:ally-id (random-uuid) :ally-name "Buddy"
                                            :contributed {:soldiers 100} :losses {:soldiers 5}}]
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      (is (clojure.string/includes? html "YOU (ALLIES)"))
      ;; 1000 (own) with a −50 loss shown — not 1100/−55 (the pooled totals).
      (is (clojure.string/includes? html "\"1000\""))
      (is (clojure.string/includes? html "\"50\""))
      (is (not (clojure.string/includes? html "\"1100\""))))))

(deftest test-outgoing-battle-card-shows-the-allies-own-lumped-contribution-and-loss
  (testing "Beyond this player's own figure and the ally's name, the row also reports how
            many units the allies themselves contributed and lost, in parentheses"
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         :attacker-counts {:soldiers 1100 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 55 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-allies [{:ally-id (random-uuid) :ally-name "Buddy"
                                            :contributed {:soldiers 100} :losses {:soldiers 5}}]
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      ;; The ally's own 100 contributed / 5 lost, shown alongside this player's own figure,
      ;; with a space before the opening parenthesis.
      (is (clojure.string/includes? html "\" (\""))
      (is (clojure.string/includes? html "\"100\""))
      (is (clojure.string/includes? html "\")\""))
      ;; Both the ally's contribution and loss are muted green, not red — a loss the ally
      ;; absorbed is not a loss to the viewer.
      (is (clojure.string/includes? html "text-game-green-muted"))
      (is (clojure.string/includes? html "\"ml-1.5\""))
      (is (not (clojure.string/includes? html "[:span.text-red-400 \"−\" \"5\"]"))
          "the ally's loss is no longer wrapped in its own red span"))))

(deftest test-outgoing-battle-card-omits-ally-breakdown-for-a-unit-allies-never-contribute
  (testing "cmd-ships are never contributed by a treaty ally (see combat/contributable-
            units), so that row shows no parenthetical even when there are offence allies —
            only the one soldiers row (what the ally actually contributed) gets one"
    (let [paren-count (fn [s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote "\" (\"")) s)))
          battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         :attacker-counts {:soldiers 1100 :fighters 0 :cmd-ships 3
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 55 :fighters 0 :cmd-ships 1
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-allies [{:ally-id (random-uuid) :ally-name "Buddy"
                                            :contributed {:soldiers 100} :losses {:soldiers 5}}]
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      ;; cmd-ships: pooled 3 with a −1 loss shown, same as any other row.
      (is (clojure.string/includes? html "\"3\""))
      (is (= 1 (paren-count html))
          "exactly one ally parenthetical on the whole card — soldiers, not cmd-ships"))))

(deftest test-outgoing-battle-card-lumps-two-or-more-allies-into-one-figure
  (testing "with two offence allies, the main figure stays this player's own, and their
            TWO allies' contributions/losses are lumped into ONE combined parenthetical,
            not broken out per ally"
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         ;; Pooled — not what's shown as the main figure here.
                         :attacker-counts {:soldiers 1300 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 65 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         ;; Own — this player's real share; the main figure shown.
                         :attacker-own-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                               :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-own-losses {:soldiers 50 :fighters 0 :cmd-ships 0
                                               :transports 0 :carriers 0 :generals 0 :admirals 0}
                         ;; TWO allies' contributions (100+200) and losses (5+10).
                         :attacker-allies [{:ally-id (random-uuid) :ally-name "Buddy One"
                                            :contributed {:soldiers 100} :losses {:soldiers 5}}
                                           {:ally-id (random-uuid) :ally-name "Buddy Two"
                                            :contributed {:soldiers 200} :losses {:soldiers 10}}]
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      (is (clojure.string/includes? html "YOU (ALLIES)"))
      (is (clojure.string/includes? html "\"1000\""))
      ;; The two allies' own contributions (100+200) and losses (5+10) are lumped into
      ;; one combined parenthetical — 300/15 — not shown as two separate ally figures.
      ;; Exactly one occurrence of that combined figure — it isn't broken out per ally.
      (is (= 1 (count (re-seq #"\"300\"" html))))
      (is (clojure.string/includes? html "» OFFENCE: Joined by Buddy One, Buddy Two.")))))

(deftest test-outgoing-battle-card-plain-you-header-with-no-offence-allies
  (testing "With no offence allies, the header stays the plain 'YOU' — no '(ALLIES)' suffix,
            even though :attacker-counts/-losses are technically still the 'pooled' keys
            (pool-forces of an empty seq is the identity, so pooled == own here anyway)"
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         :attacker-counts {:soldiers 1000 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          ;; Excluded for the same reason as above — the default fixture's own ally-battle
          ;; record would otherwise leak its own "(ALLIES)" text onto this page too.
          player (assoc test-player :player/incoming-ally-battles nil)
          html (str (outcomes/outcomes-page {:player player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))]
      (is (not (clojure.string/includes? html "(ALLIES)"))))))

(deftest test-combat-card-alliance-line-follows-orbit-and-ground
  (testing "the Alliance line is the combat card's own third result line, reading after
            Orbit and Ground as one connected report — not a separate card appended below"
    (let [battle-result {:mode :invade :attacker-wins? true :defender-name "Defender"
                         :attacker-counts {:soldiers 100 :fighters 10 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :attacker-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-losses {:soldiers 0 :fighters 0 :cmd-ships 0
                                           :transports 0 :carriers 0 :generals 0 :admirals 0}
                         :defender-allies [{:ally-id (random-uuid) :ally-name "Grunk"
                                            :contributed {:soldiers 10} :losses {:soldiers 1}}]
                         :space-result {:att-wins? true}
                         :space-carryover 0.2
                         :planets-transferred {:mil 0 :erg 0 :ore 0}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result battle-result
                                             :espionage-result nil}))
          orbit-at     (.indexOf html "» ORBIT")
          ground-at    (.indexOf html "» GROUND")
          alliance-at  (.indexOf html "» DEFENCE")]
      (is (every? #(>= % 0) [orbit-at ground-at alliance-at]))
      (is (< orbit-at ground-at alliance-at)
          "Orbit, then Ground, then Alliance, in that order within the same card"))))

(deftest test-outcomes-page-never-shows-proposed-accepted-withdrawn-declined-ended-notices
  (testing "A proposed/accepted/withdrawn/declined/ended treaty is never shown on
            Outcomes — that's handled exclusively by the status-line alert chip's instant
            messages (alerts.clj). test-player carries all five incoming-treaty-* fields
            (see fixture above) so this genuinely exercises the omission, not an
            empty-fixture false negative."
    (let [page (outcomes/outcomes-page {:player test-player :game test-game
                                        :battle-result nil :espionage-result nil})
          html (str page)]
      (is (vector? page))
      (is (not (clojure.string/includes? html "Proposer Empire")))
      (is (not (clojure.string/includes? html "proposed a peace treaty with you")))
      (is (not (clojure.string/includes? html "Acceptor Empire")))
      (is (not (clojure.string/includes? html "accepted your peace treaty proposal")))
      (is (not (clojure.string/includes? html "Withdrawer Empire")))
      (is (not (clojure.string/includes? html "withdrew their peace treaty proposal")))
      (is (not (clojure.string/includes? html "Decliner Empire")))
      (is (not (clojure.string/includes? html "declined your treaty proposal")))
      (is (not (clojure.string/includes? html "Ender Empire")))
      (is (not (clojure.string/includes? html "ended your peace treaty"))))))

(deftest test-outcomes-page-shows-incoming-espionage-treaty-broken
  (testing "A treaty broken by someone else's espionage against this player IS shown — it's a
            direct consequence of this turn's incoming espionage, not a lifecycle notice"
    (let [player-with-broken (assoc test-player
                                     :player/incoming-espionage-treaty-broken ["Spymaster Empire"])
          html (str (outcomes/outcomes-page {:player player-with-broken :game test-game
                                             :battle-result nil :espionage-result nil}))]
      (is (clojure.string/includes? html "Spymaster Empire"))
      ;; Kind-neutral — :player/incoming-espionage-treaty-broken only ever stored an empire
      ;; name, never the kind (see alerts/incoming-messages for the same reasoning).
      (is (clojure.string/includes? html "broke their treaty with you")))))

(deftest test-outcomes-page-shows-outgoing-espionage-treaty-broken
  (testing "When the player's own espionage attempt broke an active treaty, the outgoing
            espionage card is followed by a treaty-broken notice from their perspective,
            naming whichever kind :treaty-broken-kind says was actually broken"
    (let [espionage-result {:op "spy" :attacker-wins? true :defender-name "Target Empire"
                            :agents-lost 0 :treaty-broken? true :treaty-broken-kind :defence
                            :intel {:soldiers 1 :transports 1 :generals 1 :fighters 1
                                    :carriers 1 :admirals 1 :stations 1 :cmd-ships 1 :agents 1}}
          html (str (outcomes/outcomes-page {:player test-player :game test-game
                                             :battle-result nil :espionage-result espionage-result}))]
      (is (clojure.string/includes? html "Target Empire"))
      (is (clojure.string/includes? html "You broke your defence treaty with Target Empire")))))

(deftest test-outcomes-page-with-pop-growth
  (testing "Renders population growth message when pop-growth is positive"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil
                                          :pop-growth 2}))))
  (testing "Renders held-steady message when pop-growth is zero"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil
                                          :pop-growth 0}))))
  (testing "Does not render population section when pop-growth is nil (mid-round)"
    (is (vector? (outcomes/outcomes-page {:player test-player :game test-game
                                          :battle-result nil :espionage-result nil
                                          :pop-growth nil})))))
