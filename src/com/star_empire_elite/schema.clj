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
          [:game/game-duration-days        :int]
          [:game/turns-per-round           :int]
          [:game/rounds-per-day            :int]
          [:game/minutes-between-rounds    :int]
          [:game/alliance-slots            :int]

          ;; Expense underpayment penalty
          [:game/expense-stability-penalty    :int]

          ;; Acquisition penalty
          [:game/capture-stability-penalty-per-planet :int]
          [:game/capture-stability-penalty-cap        :int]

          ;; Stability breakaway and recovery
          [:game/stability-breakaway-threshold :int]
          [:game/stability-breakaway-cap       :int]
          [:game/stability-recovery-amount      :int]
          [:game/stability-recovery-floor       :int]

          ;; Combat mode multipliers
          [:game/raid-defense-multiplier   :double]
          [:game/raid-reward-multiplier    {:optional true} :double]  ; superseded
          [:game/invade-defense-multiplier :double]
          [:game/invade-reward-multiplier  {:optional true} :double]  ; superseded

          ;; Combat losses & capture
          [:game/combat-loss-floor           :double]
          [:game/combat-loser-cap            :double]
          [:game/combat-winner-max           :double]
          [:game/cmd-ship-loss-mult          :double]
          [:game/invade-planet-capture-cap   :double]
          [:game/invade-resource-capture-cap :double]
          [:game/raid-planet-capture-cap     :double]
          [:game/raid-resource-capture-cap   :double]

          ;; Strike operation constants
          [:game/strike-damage-rate       :double]
          [:game/strike-max-dispatch      :int]
          [:game/strike-interception-rate :double]
          [:game/strike-interception-cap  :double]
          [:game/strike-agent-mult        :double]

          ;; Defect operation constants
          [:game/defect-defense-multiplier :double]
          [:game/defect-transfer-rate      :double]

          ;; Combat multiplier constants
          [:game/general-mult-rate  :double]
          [:game/general-mult-cap   :double]
          [:game/admiral-mult-rate  :double]
          [:game/admiral-mult-cap   :double]
          [:game/agent-mult-rate    :double]
          [:game/agent-mult-cap     :double]
          [:game/cmd-ship-mult-rate :double]
          [:game/cmd-ship-mult-cap  :double]
          [:game/space-carryover    :double]

          ;; Combat power constants
          [:game/soldier-power             :int]
          [:game/fighter-power             :int]
          [:game/cmd-ship-power            :int]
          [:game/station-power             :int]
          [:game/general-power             :int]
          [:game/admiral-power             :int]
          [:game/combat-variance :double]

          ;; Command / transport capacity (divisors)
          [:game/soldiers-per-general   :int]
          [:game/soldiers-per-transport :int]
          [:game/fighters-per-admiral   :int]
          [:game/fighters-per-carrier   :int]

          ;; Population growth
          [:game/pop-growth-rate         :double]
          [:game/pop-growth-per-planet   :double]
          [:game/pop-capacity-per-planet :double]
          [:game/pop-random-min          :double]
          [:game/pop-random-max          :double]

          ;; Starting empire defaults (per-game player seed)
          [:game/starting-credits     :int]
          [:game/starting-food        :int]
          [:game/starting-fuel        :int]
          [:game/starting-galaxars    :int]
          [:game/starting-mil-planets :int]
          [:game/starting-erg-planets :int]
          [:game/starting-ore-planets :int]
          [:game/starting-population  :int]
          [:game/starting-stability   :int]
          [:game/starting-soldiers    :int]
          [:game/starting-transports  :int]
          [:game/starting-generals    :int]
          [:game/starting-fighters    :int]
          [:game/starting-carriers    :int]
          [:game/starting-admirals    :int]
          [:game/starting-stations    :int]
          [:game/starting-cmd-ships   :int]
          [:game/starting-agents      :int]

          ;; Income generation constants
          [:game/ore-planet-credits  :int]
          [:game/erg-planet-food     :int]
          [:game/erg-planet-fuel     :int]
          [:game/mil-planet-soldiers :int]
          [:game/mil-planet-fighters :int]
          [:game/mil-planet-stations :int]
          [:game/planet-synergy-threshold  :double]
          [:game/planet-synergy-bonus-rate :double]

          ;; Population tax
          [:game/population-tax-credits    :int]

          ;; Upkeep/expense constants
          [:game/planet-upkeep-credits     :int]
          [:game/planet-upkeep-food        :int]
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
          [:game/planet-cost-ramp-min    :int]
          [:game/planet-cost-ramp-max    :int]

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
          [:game/spy-defection-rate         :double]
          [:game/incite-defection-rate      :double]
          [:game/bomb-defection-rate        :double]
          [:game/defect-defection-rate      :double]
          [:game/espionage-defection-min      :int]
          [:game/espionage-defense-threshold  :int]
          [:game/espionage-defense-exponent   :double]
          [:game/incite-stability-damage    :int]
          [:game/incite-stability-floor     :int]
          [:game/bomb-damage-rate-lo        :double]
          [:game/bomb-damage-rate-hi        :double]

          ;; Fate system
          [:game/fate-probability :double]
          [:game/fate-rank-tilt   :double]
          [:game/fate-drought-lo          :double]
          [:game/fate-drought-hi          :double]
          [:game/fate-pirate-raid-lo      :double]
          [:game/fate-pirate-raid-hi      :double]
          [:game/fate-solar-flare-lo      :double]
          [:game/fate-solar-flare-hi      :double]
          [:game/fate-plague-lo           :double]
          [:game/fate-plague-hi           :double]
          [:game/fate-reactor-breach-lo   :double]
          [:game/fate-reactor-breach-hi   :double]
          [:game/fate-bumper-harvest-lo   :double]
          [:game/fate-bumper-harvest-hi   :double]
          [:game/fate-gold-rush-lo        :double]
          [:game/fate-gold-rush-hi        :double]
          [:game/fate-lottery-win-lo      :double]
          [:game/fate-lottery-win-hi      :double]
          [:game/fate-migration-wave-lo   :double]
          [:game/fate-migration-wave-hi   :double]
          [:game/fate-reactor-surge-lo    :double]
          [:game/fate-reactor-surge-hi    :double]
          [:game/fate-uncollected-stability-penalty :int]

          ;; Score weights
          [:game/score-population :int]
          [:game/score-mil-planet :int]
          [:game/score-erg-planet :int]
          [:game/score-ore-planet :int]
          [:game/score-soldier    :int]
          [:game/score-transport  :int]
          [:game/score-general    :int]
          [:game/score-fighter    :int]
          [:game/score-carrier    :int]
          [:game/score-admiral    :int]
          [:game/score-cmd-ship   :int]
          [:game/score-station    :int]
          [:game/score-agent      :int]]

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
            [:player/incoming-defect-agents-lost       {:optional true} [:maybe :int]]
            [:player/incoming-treaty-proposed           {:optional true} [:maybe [:vector :string]]]
            [:player/incoming-treaty-accepted          {:optional true} [:maybe [:vector :string]]]
            [:player/incoming-treaty-withdrawn         {:optional true} [:maybe [:vector :string]]]
            [:player/incoming-treaty-dissolved         {:optional true} [:maybe [:vector :string]]]

            ;; Alert chip read-state (see com.star-empire-elite.alerts). incoming-seq is a
            ;; monotonic counter bumped once per incoming message, independent of the capped
            ;; incoming-* vectors above — it stays accurate once a vector saturates its cap.
            ;; incoming-seen-seq is the incoming-seq value as of the last time the player closed
            ;; the fold-out panel; incoming-seen-groups snapshots the grouped counts at that same
            ;; moment so individual rows can be marked new vs. already-seen.
            [:player/incoming-seq           {:optional true} [:maybe :int]]
            [:player/incoming-seen-seq      {:optional true} [:maybe :int]]
            [:player/incoming-seen-groups   {:optional true} [:maybe :string]]]

   ;; A treaty between two players. Shared by both sides so the relationship can't drift —
   ;; neither player's entity carries its own copy. Lifecycle: :proposed -> :active (accepted)
   ;; | :declined | :withdrawn; :active -> :dissolved (ended by either side, from the
   ;; diplomacy phase) | :broken (ended by an attack). Proposing/accepting/declining/
   ;; withdrawing/dissolving only happen during the diplomacy phase (7); breaking happens
   ;; during combat resolution (resolution.clj). See diplomacy.clj.
   :alliance/id :uuid
   :alliance [:map {:closed true}
              [:xt/id                 :alliance/id]
              [:alliance/game         :game/id]
              [:alliance/kind         :keyword]  ; :peace (later :defensive / :offensive)
              [:alliance/proposer     :player/id]
              [:alliance/recipient    :player/id]
              [:alliance/status       :keyword]  ; :proposed :active :declined :withdrawn :dissolved :broken
              [:alliance/proposed-at  inst?]
              [:alliance/formed-at {:optional true} [:maybe inst?]]
              [:alliance/ended-at  {:optional true} [:maybe inst?]]
              [:alliance/ended-by  {:optional true} [:maybe :player/id]]]

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
