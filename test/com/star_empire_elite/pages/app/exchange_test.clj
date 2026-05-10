(ns com.star-empire-elite.pages.app.exchange-test
  (:require [clojure.test :refer :all]
            [com.star-empire-elite.pages.app.exchange :as exchange]
            [com.star-empire-elite.test-helpers :as helpers]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]
            [com.biffweb :as biff]))

;;;;
;;;; Fixtures
;;;;

(def test-player-id #uuid "00000000-0000-0000-0000-000000000042")
(def test-game-id   #uuid "00000000-1111-2222-3333-444444444444")

;; Game entity with exchange rates stored as game-level configuration.
(def test-game
  {:xt/id              test-game-id
   :game/soldier-sell     250
   :game/transport-sell  2500
   :game/general-sell    5500
   :game/fighter-sell     375
   :game/carrier-sell    7500
   :game/admiral-sell    7500
   :game/station-sell     500
   :game/cmd-ship-sell  30000
   :game/agent-sell      1000
   :game/mil-planet-sell 1000
   :game/erg-planet-sell 5500
   :game/ore-planet-sell 11000
   :game/food-buy           6
   :game/food-sell          2
   :game/fuel-buy           6
   :game/fuel-sell          3
   ;; Income constants needed by projections-section
   :game/ore-planet-credits     0
   :game/erg-planet-food       0
   :game/erg-planet-fuel       0
   :game/mil-planet-soldiers    0
   :game/mil-planet-fighters    0
   :game/mil-planet-stations    0
   :game/population-tax-credits 0
   ;; Upkeep constants needed by projections-section
   :game/planet-upkeep-credits  0
   :game/planet-upkeep-food     0
   :game/soldier-upkeep-credits 0
   :game/soldier-upkeep-food    0
   :game/fighter-upkeep-credits 0
   :game/fighter-upkeep-fuel    0
   :game/station-upkeep-credits 0
   :game/station-upkeep-fuel    0
   :game/agent-upkeep-food      0
   :game/agent-upkeep-fuel      0
   :game/population-upkeep-food 0
   :game/population-upkeep-fuel 0
   :game/expense-stability-penalty 0})

(def test-player
  {:xt/id               test-player-id
   :player/game         test-game-id
   :player/current-phase 2
   :player/empire-name  "Test Empire"
   :player/current-turn  1
   :player/current-round 1
   :player/population   6
   :player/stability    75
   :player/credits      5000
   :player/soldiers     100
   :player/transports   5
   :player/generals     2
   :player/fighters     20
   :player/carriers     1
   :player/admirals     1
   :player/stations     10
   :player/cmd-ships    0
   :player/agents       5
   :player/mil-planets  5
   :player/erg-planets 4
   :player/ore-planets  6
   :player/food         500
   :player/fuel         300
   :player/galaxars     100})

;; A baseline quantities map with all keys set to zero. Tests merge their actual
;; values on top of this to avoid NPEs from missing keys in calculation functions.
(def zero-quantities
  {:soldiers-sold    0 :transports-sold   0 :generals-sold   0
   :fighters-sold    0 :carriers-sold     0 :admirals-sold   0
   :stations-sold    0 :cmd-ships-sold    0 :agents-sold     0
   :mil-planets-sold 0 :erg-planets-sold 0 :ore-planets-sold 0
   :food-bought      0 :food-sold         0
   :fuel-bought      0 :fuel-sold         0})

;;;;
;;;; get-exchange-rates Tests
;;;;
;;;; get-exchange-rate- is a pure function that extracts rates from the game entity.
;;;;

(deftest test-get-exchange-rates
  (testing "Extracts all exchange rate fields from the game entity"
    (let [rates (exchange/get-exchange-rates test-game)]
      (is (= 250   (:soldier-sell     rates)))
      (is (= 375   (:fighter-sell     rates)))
      (is (= 500   (:station-sell     rates)))
      (is (= 1000  (:mil-planet-sell  rates)))
      (is (= 5500  (:erg-planet-sell rates)))
      (is (= 11000 (:ore-planet-sell  rates)))
      (is (= 6     (:food-buy         rates)))
      (is (= 2     (:food-sell        rates)))
      (is (= 6     (:fuel-buy         rates)))
      (is (= 3     (:fuel-sell        rates))))))

;;;;
;;;; parse-exchange-quantities Tests
;;;;
;;;; Delegates to utils/parse-numeric-input; covered in detail in utils tests.
;;;; Here we verify only that parsing is applied to all keys.
;;;;

(deftest test-parse-exchange-quantities-all-keys
  (testing "Parses every quantity key from request params"
    (let [params {:soldiers-sold "10" :transports-sold "2" :generals-sold "1"
                  :fighters-sold "5"  :carriers-sold   "1" :admirals-sold "1"
                  :stations-sold "3"  :cmd-ships-sold  "0" :agents-sold   "2"
                  :mil-planets-sold "1" :erg-planets-sold "1" :ore-planets-sold "1"
                  :food-bought "50" :food-sold "20" :fuel-bought "30" :fuel-sold "10"}
          q (exchange/parse-exchange-quantities params)]
      (is (= 10 (:soldiers-sold q)))
      (is (= 2  (:transports-sold q)))
      (is (= 2  (:agents-sold q)))
      (is (= 50 (:food-bought q)))
      (is (= 10 (:fuel-sold q)))))

  (testing "Missing params default to 0"
    (let [q (exchange/parse-exchange-quantities {})]
      (is (= 0 (:soldiers-sold q)))
      (is (= 0 (:agents-sold q)))
      (is (= 0 (:fuel-bought q))))))

;;;;
;;;; calculate-exchange-credits Tests
;;;;
;;;; calculate-exchange-credits is a pure function. Tests cover unit sales, resource
;;;; trades, and mixed exchanges independently to isolate each credit path.
;;;;

(deftest test-calculate-exchange-credits-unit-sales
  (testing "Credits from selling military units accumulate correctly"
    (let [quantities (merge zero-quantities {:soldiers-sold 10 :fighters-sold 5 :stations-sold 2
                                             :mil-planets-sold 1 :erg-planets-sold 1 :ore-planets-sold 1})
          rates (exchange/get-exchange-rates test-game)
          result (exchange/calculate-exchange-credits quantities rates)
          expected (+ (* 10 (:game/soldier-sell test-game)) (* 5 (:game/fighter-sell test-game))
                      (* 2 (:game/station-sell test-game))
                      (* 1 (:game/mil-planet-sell test-game)) (* 1 (:game/erg-planet-sell test-game)) (* 1 (:game/ore-planet-sell test-game)))]
      (is (= expected (:credits-from-sales result)))
      (is (= 0 (:credits-from-resources result)))
      (is (= expected (:total-credits result))))))

(deftest test-calculate-exchange-credits-resource-trades
  (testing "Credits from buying and selling food and fuel net correctly"
    ;; Selling earns credits; buying costs credits. The net is credits-from-resources.
    (let [quantities (merge zero-quantities {:food-bought 20 :food-sold 10 :fuel-bought 15 :fuel-sold 5})
          rates (exchange/get-exchange-rates test-game)
          result (exchange/calculate-exchange-credits quantities rates)
          expected-net (- (+ (* 10 (:game/food-sell test-game)) (* 5 (:game/fuel-sell test-game)))
                          (+ (* 20 (:game/food-buy test-game))  (* 15 (:game/fuel-buy test-game))))]
      (is (= 0 (:credits-from-sales result)))
      (is (= expected-net (:credits-from-resources result)))
      (is (= expected-net (:total-credits result))))))

(deftest test-calculate-exchange-credits-agents
  (testing "Agent sales are counted in credits-from-sales"
    (let [quantities (merge zero-quantities {:agents-sold 3})
          rates (exchange/get-exchange-rates test-game)
          result (exchange/calculate-exchange-credits quantities rates)]
      (is (= (* 3 (:game/agent-sell test-game)) (:credits-from-sales result))))))

(deftest test-calculate-exchange-credits-mixed
  (testing "Unit sales and resource trades are summed correctly into total-credits"
    (let [quantities (merge zero-quantities {:soldiers-sold 5 :fighters-sold 2 :stations-sold 1
                                             :food-sold 20 :fuel-sold 10 :food-bought 10 :fuel-bought 5})
          rates (exchange/get-exchange-rates test-game)
          result (exchange/calculate-exchange-credits quantities rates)
          expected-sales     (+ (* 5 (:game/soldier-sell test-game)) (* 2 (:game/fighter-sell test-game)) (* 1 (:game/station-sell test-game)))
          expected-resources (- (+ (* 20 (:game/food-sell test-game)) (* 10 (:game/fuel-sell test-game)))
                                (+ (* 10 (:game/food-buy test-game))  (* 5  (:game/fuel-buy test-game))))]
      (is (= expected-sales     (:credits-from-sales result)))
      (is (= expected-resources (:credits-from-resources result)))
      (is (= (+ expected-sales expected-resources) (:total-credits result))))))

(deftest test-calculate-exchange-credits-zero-quantities
  (testing "All-zero quantities produce zero credits"
    (let [result (exchange/calculate-exchange-credits zero-quantities (exchange/get-exchange-rates test-game))]
      (is (= 0 (:credits-from-sales result)))
      (is (= 0 (:credits-from-resources result)))
      (is (= 0 (:total-credits result))))))

;;;;
;;;; calculate-resources-after-exchange Tests
;;;;

(deftest test-calculate-resources-after-exchange
  (testing "All resource values are updated correctly after an exchange"
    (let [player    {:player/credits 1000 :player/food 500 :player/fuel 300 :player/galaxars 50
                     :player/soldiers 100 :player/transports 5 :player/generals 2
                     :player/fighters 20  :player/carriers 1  :player/admirals 1
                     :player/stations 10  :player/cmd-ships 0  :player/agents 5
                     :player/mil-planets 5 :player/erg-planets 4 :player/ore-planets 6}
          quantities (merge zero-quantities {:soldiers-sold 10 :fighters-sold 5 :stations-sold 2
                                             :agents-sold 1 :mil-planets-sold 1
                                             :erg-planets-sold 1 :ore-planets-sold 1
                                             :food-bought 50 :food-sold 20
                                             :fuel-bought 30 :fuel-sold 10})
          ;; Use a simplified credit-changes to keep the test deterministic.
          credit-changes {:total-credits 2000}
          r (exchange/calculate-resources-after-exchange player quantities credit-changes)]
      (is (= 3000 (:credits r)))      ; 1000 + 2000
      (is (= 90   (:soldiers r)))     ; 100 - 10
      (is (= 15   (:fighters r)))     ; 20 - 5
      (is (= 8    (:stations r)))     ; 10 - 2
      (is (= 4    (:agents r)))       ; 5 - 1
      (is (= 4    (:mil-planets r)))  ; 5 - 1
      (is (= 3    (:erg-planets r))) ; 4 - 1
      (is (= 5    (:ore-planets r)))  ; 6 - 1
      (is (= 530  (:food r)))         ; 500 + 50 - 20
      (is (= 320  (:fuel r)))         ; 300 + 30 - 10
      (is (= 50   (:galaxars r)))))   ; unchanged

  (testing "Galaxars are passed through unchanged (not tradeable)"
    (let [player    {:player/credits 0 :player/food 0 :player/fuel 0 :player/galaxars 99
                     :player/soldiers 0 :player/transports 0 :player/generals 0
                     :player/fighters 0 :player/carriers 0  :player/admirals 0
                     :player/stations 0 :player/cmd-ships 0 :player/agents 0
                     :player/mil-planets 0 :player/erg-planets 0 :player/ore-planets 0}
          r (exchange/calculate-resources-after-exchange player zero-quantities {:total-credits 0})]
      (is (= 99 (:galaxars r))))))

;;;;
;;;; valid-exchange? and identify-invalid-exchanges Tests
;;;;

(def full-resources
  {:credits 100 :soldiers 50 :transports 5 :generals 2
   :fighters 10 :carriers 1  :admirals 1  :stations 5
   :cmd-ships 0 :agents 5
   :mil-planets 2 :erg-planets 3 :ore-planets 1
   :food 1000 :fuel 500})

(deftest test-valid-exchange
  (testing "Returns true when all resources are non-negative"
    (is (true? (exchange/valid-exchange? full-resources)))
    (is (true? (exchange/valid-exchange? (zipmap (keys full-resources) (repeat 0))))))

  (testing "Returns false when any resource is negative"
    (is (false? (exchange/valid-exchange? (assoc full-resources :credits  -1))))
    (is (false? (exchange/valid-exchange? (assoc full-resources :soldiers -1))))
    (is (false? (exchange/valid-exchange? (assoc full-resources :agents   -1))))
    (is (false? (exchange/valid-exchange? (assoc full-resources :food     -1))))))

(deftest test-identify-invalid-exchanges
  (testing "Flags oversold units and unaffordable purchases independently"
    (let [resources-after {:credits -50 :soldiers -10 :transports 5 :generals 2
                           :fighters 15 :carriers 1   :admirals 1  :stations 8
                           :cmd-ships 0 :agents 5
                           :mil-planets 4 :erg-planets 3 :ore-planets 5
                           :food 530 :fuel 320}
          quantities (merge zero-quantities {:soldiers-sold 110 :food-bought 5})
          invalid (exchange/identify-invalid-exchanges resources-after quantities)]
      ;; Oversold soldiers
      (is (true?  (:invalid-soldier-sale?   invalid)))
      ;; Fighter count is fine
      (is (false? (:invalid-fighter-sale?   invalid)))
      ;; No food sold, so food-sale flag is false even though credits are negative
      (is (false? (:invalid-food-sale?      invalid)))
      ;; Credits are negative and food was bought, so food-purchase is flagged
      (is (true?  (:invalid-food-purchase?  invalid))))))

;;;;
;;;; calculate-max-buy-quantities Tests
;;;;

(deftest test-calculate-max-buy-quantities
  (testing "Max affordable quantities reflect current credits after sells"
    (let [rates (exchange/get-exchange-rates test-game)
          ;; No sells — max is based on existing credits only.
          sell-none (assoc zero-quantities :food-bought 0 :fuel-bought 0)
          {:keys [max-food max-fuel]} (exchange/calculate-max-buy-quantities test-player sell-none rates)]
      ;; Each max should equal floor(credits / rate)
      (is (= (quot (:player/credits test-player) (:food-buy rates)) max-food))
      (is (= (quot (:player/credits test-player) (:fuel-buy rates)) max-fuel))))

  (testing "Sells that earn credits increase the max purchaseable quantities"
    (let [rates (exchange/get-exchange-rates test-game)
          ;; Sell 10 soldiers to earn extra credits.
          sell-some  (assoc zero-quantities :soldiers-sold 10 :food-bought 0 :fuel-bought 0)
          base-max   (exchange/calculate-max-buy-quantities test-player (assoc zero-quantities :food-bought 0 :fuel-bought 0) rates)
          sell-max   (exchange/calculate-max-buy-quantities test-player sell-some rates)]
      ;; With extra credits from sells, max quantities must be ≥ base.
      (is (>= (:max-food sell-max) (:max-food base-max)))
      (is (>= (:max-fuel sell-max) (:max-fuel base-max)))))

  (testing "Max quantities are clamped to zero when player has no credits"
    (let [broke-player (assoc test-player :player/credits 0)
          rates (exchange/get-exchange-rates test-game)
          {:keys [max-food max-fuel]} (exchange/calculate-max-buy-quantities broke-player
                                                                             (assoc zero-quantities :food-bought 0 :fuel-bought 0)
                                                                             rates)]
      (is (= 0 max-food))
      (is (= 0 max-fuel)))))

;;;;
;;;; calculate-required-expense-reduction Tests
;;;;
;;;; Tests use a game with concrete upkeep values so that reductions are verifiable.
;;;;

;; Game with real upkeep constants (matching constants.clj defaults).
(def upkeep-game
  (merge test-game
         {:game/soldier-upkeep-credits 25
          :game/soldier-upkeep-food    10
          :game/fighter-upkeep-credits 100
          :game/fighter-upkeep-fuel    10
          :game/station-upkeep-credits 100
          :game/station-upkeep-fuel    10
          :game/agent-upkeep-food      10
          :game/agent-upkeep-fuel      10
          :game/planet-upkeep-credits  2500
          :game/planet-upkeep-food     100}))

(deftest test-calculate-required-expense-reduction-soldiers
  (testing "Selling soldiers reduces credits and food requirements"
    (let [q (merge zero-quantities {:soldiers-sold 10})
          r (exchange/calculate-required-expense-reduction q upkeep-game)]
      (is (= (* 10 25) (:credits r)))   ; 250 credits
      (is (= (* 10 10) (:food r)))      ; 100 food
      (is (= 0         (:fuel r))))))

(deftest test-calculate-required-expense-reduction-fighters
  (testing "Selling fighters reduces credits and fuel requirements"
    (let [q (merge zero-quantities {:fighters-sold 5})
          r (exchange/calculate-required-expense-reduction q upkeep-game)]
      (is (= (* 5 100) (:credits r)))   ; 500 credits
      (is (= 0         (:food r)))
      (is (= (* 5 10)  (:fuel r))))))   ; 50 fuel

(deftest test-calculate-required-expense-reduction-stations
  (testing "Selling stations reduces credits and fuel requirements"
    (let [q (merge zero-quantities {:stations-sold 3})
          r (exchange/calculate-required-expense-reduction q upkeep-game)]
      (is (= (* 3 100) (:credits r)))   ; 300 credits
      (is (= 0         (:food r)))
      (is (= (* 3 10)  (:fuel r))))))   ; 30 fuel

(deftest test-calculate-required-expense-reduction-agents
  (testing "Selling agents reduces food and fuel requirements (no credits)"
    (let [q (merge zero-quantities {:agents-sold 4})
          r (exchange/calculate-required-expense-reduction q upkeep-game)]
      (is (= 0         (:credits r)))
      (is (= (* 4 10)  (:food r)))      ; 40 food
      (is (= (* 4 10)  (:fuel r))))))   ; 40 fuel

(deftest test-calculate-required-expense-reduction-planets
  (testing "Selling mil, erg, and ore planets all reduce credits and food"
    (let [q (merge zero-quantities {:mil-planets-sold 1 :erg-planets-sold 1 :ore-planets-sold 1})
          r (exchange/calculate-required-expense-reduction q upkeep-game)]
      (is (= (* 3 2500) (:credits r)))  ; 7500 credits
      (is (= (* 3 100)  (:food r)))     ; 300 food
      (is (= 0          (:fuel r)))))
  (testing "Only sold planet types contribute"
    (let [q (merge zero-quantities {:ore-planets-sold 2})
          r (exchange/calculate-required-expense-reduction q upkeep-game)]
      (is (= (* 2 2500) (:credits r)))
      (is (= (* 2 100)  (:food r))))))

(deftest test-calculate-required-expense-reduction-combined
  (testing "All reductions accumulate correctly across unit and planet types"
    ;; Sell 10 soldiers, 5 fighters, 3 stations, 4 agents, 1 mil + 1 erg + 1 ore planet
    (let [q (merge zero-quantities {:soldiers-sold 10 :fighters-sold 5 :stations-sold 3
                                    :agents-sold 4
                                    :mil-planets-sold 1 :erg-planets-sold 1 :ore-planets-sold 1})
          r (exchange/calculate-required-expense-reduction q upkeep-game)
          expected-credits (+ (* 10 25) (* 5 100) (* 3 100) (* 3 2500))
          expected-food    (+ (* 10 10) (* 4 10)  (* 3 100))
          expected-fuel    (+ (* 5 10)  (* 3 10)  (* 4 10))]
      (is (= expected-credits (:credits r)))
      (is (= expected-food    (:food    r)))
      (is (= expected-fuel    (:fuel    r))))))

(deftest test-calculate-required-expense-reduction-no-upkeep-units
  (testing "Transports, generals, carriers, admirals, and cmd-ships produce zero reduction"
    (let [q (merge zero-quantities {:transports-sold 5 :generals-sold 2
                                    :carriers-sold 1   :admirals-sold 1
                                    :cmd-ships-sold 1})
          r (exchange/calculate-required-expense-reduction q upkeep-game)]
      (is (= 0 (:credits r)))
      (is (= 0 (:food    r)))
      (is (= 0 (:fuel    r))))))

(deftest test-calculate-required-expense-reduction-zero-quantities
  (testing "All-zero quantities produce zero reduction in all resources"
    (let [r (exchange/calculate-required-expense-reduction zero-quantities upkeep-game)]
      (is (= 0 (:credits r)))
      (is (= 0 (:food    r)))
      (is (= 0 (:fuel    r))))))

;;;;
;;;; apply-exchange Tests
;;;;
;;;; apply-exchange writes to the database and redirects, so xt/entity and
;;;; biff/submit-tx are replaced with test doubles using with-redefs.
;;;;

(deftest test-apply-exchange-player-not-found
  (testing "Returns 404 when player is not in the database"
    (with-redefs [xt/entity (fn [_ _] nil)]
      (let [result (exchange/apply-exchange {:path-params {:player-id (str test-player-id)}
                                             :params {} :biff/db nil})]
        (is (= 404 (:status result)))
        (is (= "Player not found" (:body result)))))))

(deftest test-apply-exchange-wrong-phase
  (testing "Redirects to game overview when player is not in phase 2"
    (let [player (assoc test-player :player/current-phase 1)]
      (with-redefs [xt/entity (helpers/fake-entity [player test-game])]
        (let [result (exchange/apply-exchange {:path-params {:player-id (str test-player-id)}
                                               :params {} :biff/db nil})]
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id) (get-in result [:headers "location"]))))))))

(deftest test-apply-exchange-commits-correct-tx
  (testing "Commits correct resource deltas and redirects to expenses"
    (let [params {:soldiers-sold "10" :fighters-sold "5" :agents-sold "1"
                  :stations-sold "2"  :mil-planets-sold "1" :erg-planets-sold "1" :ore-planets-sold "1"
                  :food-bought "50" :food-sold "20" :fuel-bought "30" :fuel-sold "10"}
          tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (let [result (exchange/apply-exchange {:path-params {:player-id (str test-player-id)}
                                               :params params :biff/db nil})
              tx     (first @tx-atom)
              ;; Derive expected values from the same pure functions the handler uses.
              quantities (exchange/parse-exchange-quantities params)
              rates      (exchange/get-exchange-rates test-game)
              credits    (exchange/calculate-exchange-credits quantities rates)
              expected   (exchange/calculate-resources-after-exchange test-player quantities credits)]
          ;; Redirect
          (is (= 303 (:status result)))
          (is (= (str "/app/game/" test-player-id "/expenses") (get-in result [:headers "location"])))
          ;; Transaction metadata
          (is (= :player       (:db/doc-type tx)))
          (is (= :update        (:db/op tx)))
          (is (= test-player-id (:xt/id tx)))
          ;; Resources
          (is (= (:credits      expected) (:player/credits      tx)))
          (is (= (:soldiers     expected) (:player/soldiers     tx)))
          (is (= (:fighters     expected) (:player/fighters     tx)))
          (is (= (:stations     expected) (:player/stations     tx)))
          (is (= (:agents       expected) (:player/agents       tx)))
          (is (= (:mil-planets  expected) (:player/mil-planets  tx)))
          (is (= (:erg-planets expected) (:player/erg-planets tx)))
          (is (= (:ore-planets  expected) (:player/ore-planets  tx)))
          (is (= (:food         expected) (:player/food         tx)))
          (is (= (:fuel         expected) (:player/fuel         tx))))))))

(deftest test-apply-exchange-zero-quantities
  (testing "Zero exchanges leave all resources unchanged"
    (let [tx-atom (atom nil)]
      (with-redefs [xt/entity      (helpers/fake-entity [test-player test-game])
                    biff/submit-tx (fn [_ tx] (reset! tx-atom tx) :ok)]
        (exchange/apply-exchange {:path-params {:player-id (str test-player-id)}
                                  :params {} :biff/db nil})
        (let [tx (first @tx-atom)]
          (is (= (:player/credits  test-player) (:player/credits  tx)))
          (is (= (:player/soldiers test-player) (:player/soldiers tx)))
          (is (= (:player/agents   test-player) (:player/agents   tx)))
          (is (= (:player/food     test-player) (:player/food     tx)))
          (is (= (:player/fuel     test-player) (:player/fuel     tx))))))))

;;;;
;;;; UI Component Tests
;;;;
;;;; These verify that UI functions render without error. Structural assertions are
;;;; limited to the root element — detailed layout is validated in browser/integration tests.
;;;;

(deftest test-exchange-page-renders
  (testing "Returns a hiccup vector for a standard player"
    (is (vector? (exchange/exchange-page {:player test-player :game test-game})))))

(deftest test-exchange-row-renders
  (let [rates (exchange/get-exchange-rates test-game)]
    (testing "Renders hiccup for a typical sell row"
      (is (vector? (exchange/exchange-row "Soldiers" "Soldiers" "soldiers-sold"
                                          (:soldier-sell rates) 0 100 test-player-id "form"))))
    (testing "Renders without error when max-quantity is negative (shown as 0)"
      (is (vector? (exchange/exchange-row "Soldiers" "Soldiers" "soldiers-sold"
                                          (:soldier-sell rates) 0 -5 test-player-id "form"))))
    (testing "Renders hiccup for a typical buy row"
      (is (vector? (exchange/exchange-row "Food" "Food" "food-bought"
                                          (:food-buy rates) 0 500 test-player-id "form"))))
    (testing "Renders without error when max-quantity is zero"
      (is (vector? (exchange/exchange-row "Food" "Food" "food-bought"
                                          (:food-buy rates) 0 0 test-player-id "form"))))))
