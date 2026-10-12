(ns com.star-empire-elite.schema)

(def schema
  {:user/id :uuid
   :user [:map {:closed true}
          [:xt/id                          :user/id]
          [:user/email                     :string]
          [:user/joined-at                 inst?]]

   :game/id :uuid
   :game [:map {:closed true}
          [:xt/id                          :game/id]
          [:game/name                      :string]
          [:game/created-at                inst?]
          [:game/scheduled-end-at          inst?]
          [:game/ended-at {:optional true} inst?]
          [:game/status                    :int]
          [:game/game-duration-days {:optional true} :int]
          [:game/turns-per-round           :int]
          [:game/rounds-per-day            :int]
          [:game/minutes-between-rounds    :int]

          ;; Expense underpayment penalty
          [:game/expense-stability-penalty    :int]

          ;; Acquisition penalty
          [:game/capture-stability-penalty-per-planet {:optional true} :int]
          [:game/capture-stability-penalty-cap        {:optional true} :int]

          ;; Stability breakaway and recovery
          [:game/stability-breakaway-threshold :int]
          [:game/stability-breakaway-cap       :int]
          [:game/stability-recovery-amount      :int]
          [:game/stability-recovery-floor       :int]

          ;; Combat mode multipliers
          [:game/raid-defense-multiplier   {:optional true} :double]
          [:game/raid-reward-multiplier    {:optional true} :double]  ; superseded
          [:game/invade-defense-multiplier {:optional true} :double]
          [:game/invade-reward-multiplier  {:optional true} :double]  ; superseded

          ;; Combat losses & capture
          [:game/combat-loss-floor           {:optional true} :double]
          [:game/combat-loser-cap            {:optional true} :double]
          [:game/combat-winner-max           {:optional true} :double]
          [:game/cmd-ship-loss-mult          {:optional true} :double]
          [:game/invade-planet-capture-cap   {:optional true} :double]
          [:game/invade-resource-capture-cap {:optional true} :double]
          [:game/raid-planet-capture-cap     {:optional true} :double]
          [:game/raid-resource-capture-cap   {:optional true} :double]

          ;; Strike operation constants
          [:game/strike-damage-rate       {:optional true} :double]
          [:game/strike-max-dispatch      {:optional true} :int]
          [:game/strike-interception-rate {:optional true} :double]
          [:game/strike-interception-cap  {:optional true} :double]
          [:game/strike-agent-mult        {:optional true} :double]

          ;; Defect operation constants
          [:game/defect-defense-multiplier {:optional true} :double]
          [:game/defect-transfer-rate      {:optional true} :double]

          ;; Combat multiplier constants
          [:game/general-mult-rate  {:optional true} :double]
          [:game/general-mult-cap   {:optional true} :double]
          [:game/admiral-mult-rate  {:optional true} :double]
          [:game/admiral-mult-cap   {:optional true} :double]
          [:game/agent-mult-rate    {:optional true} :double]
          [:game/agent-mult-cap     {:optional true} :double]
          [:game/cmd-ship-mult-rate {:optional true} :double]
          [:game/cmd-ship-mult-cap  {:optional true} :double]
          [:game/space-carryover    {:optional true} :double]

          ;; Combat power constants
          [:game/soldier-power             :int]
          [:game/fighter-power             :int]
          [:game/cmd-ship-power            :int]
          [:game/station-power             :int]
          [:game/general-power             :int]
          [:game/admiral-power             :int]
          [:game/combat-variance {:optional true} :double]

          ;; Command / transport capacity (divisors)
          [:game/soldiers-per-general   {:optional true} :int]
          [:game/soldiers-per-transport {:optional true} :int]
          [:game/fighters-per-admiral   {:optional true} :int]
          [:game/fighters-per-carrier   {:optional true} :int]

          ;; Population growth
          [:game/pop-growth-rate         {:optional true} :double]
          [:game/pop-growth-per-planet   {:optional true} :double]
          [:game/pop-capacity-per-planet {:optional true} :double]
          [:game/pop-random-min          {:optional true} :double]
          [:game/pop-random-max          {:optional true} :double]

          ;; Starting empire defaults (per-game player seed)
          [:game/starting-credits     {:optional true} :int]
          [:game/starting-food        {:optional true} :int]
          [:game/starting-fuel        {:optional true} :int]
          [:game/starting-galaxars    {:optional true} :int]
          [:game/starting-mil-planets {:optional true} :int]
          [:game/starting-erg-planets {:optional true} :int]
          [:game/starting-ore-planets {:optional true} :int]
          [:game/starting-population  {:optional true} :int]
          [:game/starting-stability   {:optional true} :int]
          [:game/starting-soldiers    {:optional true} :int]
          [:game/starting-transports  {:optional true} :int]
          [:game/starting-generals    {:optional true} :int]
          [:game/starting-fighters    {:optional true} :int]
          [:game/starting-carriers    {:optional true} :int]
          [:game/starting-admirals    {:optional true} :int]
          [:game/starting-stations    {:optional true} :int]
          [:game/starting-cmd-ships   {:optional true} :int]
          [:game/starting-agents      {:optional true} :int]

          ;; Income generation constants
          [:game/ore-planet-credits  :int]
          [:game/erg-planet-food     :int]
          [:game/erg-planet-fuel     :int]
          [:game/mil-planet-soldiers :int]
          [:game/mil-planet-fighters :int]
          [:game/mil-planet-stations :int]

          ;; Population tax
          [:game/population-tax-credits    :int]

          ;; Upkeep/expense constants
          [:game/planet-upkeep-credits     :int]
          [:game/planet-upkeep-food        :int]
          [:game/planet-upkeep-exponent    {:optional true} :double]
          [:game/mil-planet-upkeep-credits {:optional true} :int]
          [:game/soldier-upkeep-credits    :int]
          [:game/soldier-upkeep-food       :int]
          [:game/fighter-upkeep-credits    :int]
          [:game/fighter-upkeep-fuel       :int]
          [:game/station-upkeep-credits    :int]
          [:game/station-upkeep-fuel       :int]
          [:game/agent-upkeep-food         :int]
          [:game/agent-upkeep-fuel         :int]
          [:game/population-upkeep-food    :int]
          [:game/population-upkeep-fuel    :int]
 
          ;; Building/purchase cost constants (add these to your :game schema)
          [:game/soldier-cost            :int]
          [:game/transport-cost          :int]
          [:game/general-cost            :int]
          [:game/carrier-cost            :int]
          [:game/fighter-cost            :int]
          [:game/admiral-cost            :int]
          [:game/station-cost            :int]
          [:game/cmd-ship-cost           :int]
          [:game/agent-cost              :int]
          [:game/mil-planet-cost         :int]
          [:game/erg-planet-cost        :int]
          [:game/ore-planet-cost         :int]
          [:game/planet-cost-scale-divisor {:optional true} :int]

          ;; Exchange sell/buy rates
          [:game/soldier-sell            :int]
          [:game/transport-sell          :int]
          [:game/general-sell            :int]
          [:game/fighter-sell            :int]
          [:game/carrier-sell            :int]
          [:game/admiral-sell            :int]
          [:game/station-sell            :int]
          [:game/cmd-ship-sell           :int]
          [:game/agent-sell              :int]
          [:game/mil-planet-sell         :int]
          [:game/erg-planet-sell        :int]
          [:game/ore-planet-sell         :int]
          [:game/food-buy                :int]
          [:game/food-sell               :int]
          [:game/fuel-buy                :int]
          [:game/fuel-sell               :int]

          ;; Espionage constants
          [:game/spy-defection-rate         {:optional true} :double]
          [:game/incite-defection-rate      {:optional true} :double]
          [:game/bomb-defection-rate        {:optional true} :double]
          [:game/defect-defection-rate      {:optional true} :double]
          [:game/espionage-defection-min      {:optional true} :int]
          [:game/espionage-defense-threshold  {:optional true} :int]
          [:game/espionage-defense-exponent   {:optional true} :double]
          [:game/incite-stability-damage    {:optional true} :int]
          [:game/incite-stability-floor     {:optional true} :int]
          [:game/bomb-damage-rate           {:optional true} :double]

          ;; Fate system
          [:game/fate-probability {:optional true} :double]
          [:game/fate-rank-tilt   {:optional true} :double]
          [:game/fate-drought-lo          {:optional true} :double]
          [:game/fate-drought-hi          {:optional true} :double]
          [:game/fate-pirate-raid-lo      {:optional true} :double]
          [:game/fate-pirate-raid-hi      {:optional true} :double]
          [:game/fate-solar-flare-lo      {:optional true} :double]
          [:game/fate-solar-flare-hi      {:optional true} :double]
          [:game/fate-plague-lo           {:optional true} :double]
          [:game/fate-plague-hi           {:optional true} :double]
          [:game/fate-reactor-breach-lo   {:optional true} :double]
          [:game/fate-reactor-breach-hi   {:optional true} :double]
          [:game/fate-bumper-harvest-lo   {:optional true} :double]
          [:game/fate-bumper-harvest-hi   {:optional true} :double]
          [:game/fate-gold-rush-lo        {:optional true} :double]
          [:game/fate-gold-rush-hi        {:optional true} :double]
          [:game/fate-lottery-win-lo      {:optional true} :double]
          [:game/fate-lottery-win-hi      {:optional true} :double]
          [:game/fate-migration-wave-lo   {:optional true} :double]
          [:game/fate-migration-wave-hi   {:optional true} :double]
          [:game/fate-reactor-surge-lo    {:optional true} :double]
          [:game/fate-reactor-surge-hi    {:optional true} :double]
          [:game/fate-uncollected-stability-penalty {:optional true} :int]

          ;; Score weights
          [:game/score-population {:optional true} :int]
          [:game/score-mil-planet {:optional true} :int]
          [:game/score-erg-planet {:optional true} :int]
          [:game/score-ore-planet {:optional true} :int]
          [:game/score-soldier    {:optional true} :int]
          [:game/score-transport  {:optional true} :int]
          [:game/score-general    {:optional true} :int]
          [:game/score-fighter    {:optional true} :int]
          [:game/score-carrier    {:optional true} :int]
          [:game/score-admiral    {:optional true} :int]
          [:game/score-cmd-ship   {:optional true} :int]
          [:game/score-station    {:optional true} :int]
          [:game/score-agent      {:optional true} :int]]

   :player/id :uuid
   :player [:map {:closed true}
            [:xt/id                        :player/id]
            [:player/user                  :user/id]
            [:player/game                  :game/id]
            [:player/empire-name           :string]

            ;; Currencies
            [:player/credits               :int]
            [:player/food                  :int]
            [:player/fuel                  :int]
            [:player/galaxars              :int]
 
            ;; Resources
            [:player/mil-planets      :int]
            [:player/erg-planets          :int]
            [:player/ore-planets           :int]
            [:player/population            :int]

            ;; Status
            [:player/stability             :int]
            [:player/status                :int]
            [:player/score                 :int]

            ;; Turn/Round/Phase tracking
            [:player/current-turn          :int]
            [:player/current-round         :int]
            [:player/current-phase         :int]
            [:player/turns-used            :int]
            [:player/rounds-started-today
             {:optional true}              :int]
            [:player/last-turn-at
             {:optional true}              inst?]
            [:player/last-round-completed-at
             {:optional true}              inst?]
            [:player/last-battle-result
             {:optional true}             [:maybe :string]]
            [:player/last-espionage-result
             {:optional true}             [:maybe :string]]
            [:player/last-population-growth  [:maybe :int]]

            ;; Leadership

            ;; Military units
            [:player/soldiers              :int]
            [:player/transports            :int]
            [:player/stations              :int]
            [:player/carriers              :int]
            [:player/fighters              :int]
            [:player/cmd-ships             :int]
            [:player/generals              :int]
            [:player/admirals              :int]
            [:player/agents                :int]

            ;; Expense stability penalty
            [:player/expense-stability-penalty      {:optional true} [:maybe :int]]
            [:player/last-expense-stability-penalty {:optional true} [:maybe :int]]

            ;; Stability breakaway and recovery
            [:player/last-stability-breakaway          {:optional true} [:maybe :string]]
            [:player/last-stability-recovery           {:optional true} [:maybe :string]]
            [:player/last-capture-stability-penalty    {:optional true} [:maybe :int]]

            ;; Fate (disaster / boon) — cached edn string, nil until resolved each turn
            [:player/last-fate-result {:optional true} [:maybe :string]]

            ;; Combat
            [:player/pending-attack      {:optional true} [:maybe :player/id]]
            [:player/pending-attack-mode {:optional true} [:maybe :keyword]]

            ;; Espionage
            [:player/pending-espionage    {:optional true} [:maybe :player/id]]
            [:player/pending-espionage-op {:optional true} [:maybe :string]]

            ;; Incoming events (set by attackers, displayed on defender's outcomes page)
            [:player/incoming-attacks                {:optional true} [:maybe [:vector :string]]]
            [:player/incoming-espionage-fails        {:optional true} [:maybe [:vector :string]]]
            [:player/incoming-espionage-agents-gained  {:optional true} [:maybe :int]]
            [:player/incoming-incite-stability-lost    {:optional true} [:maybe :int]]
            [:player/incoming-bomb-result              {:optional true} [:maybe :string]]
            [:player/incoming-defect-agents-lost       {:optional true} [:maybe :int]]]

   :game-event/id :uuid
   :game-event [:map {:closed true}
                [:xt/id              :game-event/id]
                [:event/game         :game/id]
                [:event/at           inst?]
                [:event/turn         :int]
                [:event/round        :int]
                [:event/kind         :keyword]
                [:event/visibility   :keyword]
                [:event/attacker      {:optional true} [:maybe :player/id]]
                [:event/attacker-name {:optional true} [:maybe :string]]
                [:event/defender      {:optional true} [:maybe :player/id]]
                [:event/defender-name {:optional true} [:maybe :string]]
                [:event/payload      :string]]

   ;; Final standings snapshot, written when an admin clears a completed game (see
   ;; app/delete-game). Outlives the :game and :player entities, which are deleted at the
   ;; same time — this is the only durable record of who won.
   :game-result/id :uuid
   :game-result [:map {:closed true}
                 [:xt/id                   :game-result/id]
                 [:game-result/game-name   :string]
                 [:game-result/created-at  inst?]  ; when the game started
                 [:game-result/ended-at    inst?]  ; when it was archived
                 [:game-result/standings
                  [:vector [:map {:closed true}
                            [:standing/rank         :int]
                            [:standing/empire-name  :string]
                            [:standing/planets      :int]
                            [:standing/score        :int]]]]]

   })

(def module
  {:schema schema})
