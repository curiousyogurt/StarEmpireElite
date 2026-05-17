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
          [:game/turns-per-round           :int]
          [:game/rounds-per-day            :int]
          [:game/hours-between-rounds      :int]

          ;; Expense underpayment penalty
          [:game/expense-stability-penalty    :int]

          ;; Stability breakaway and recovery
          [:game/stability-breakaway-threshold :int]
          [:game/stability-breakaway-cap       :int]
          [:game/stability-recovery-amount      :int]
          [:game/stability-recovery-floor       :int]

          ;; Combat mode multipliers
          [:game/raid-defense-multiplier   {:optional true} :double]
          [:game/raid-reward-multiplier    {:optional true} :double]
          [:game/invade-defense-multiplier {:optional true} :double]
          [:game/invade-reward-multiplier  {:optional true} :double]

          ;; Strike operation constants
          [:game/strike-damage-rate       {:optional true} :double]
          [:game/strike-max-dispatch      {:optional true} :int]
          [:game/strike-interception-rate {:optional true} :double]
          [:game/strike-interception-cap  {:optional true} :double]

          ;; Defect operation constants
          [:game/defect-defense-multiplier {:optional true} :double]
          [:game/defect-transfer-rate      {:optional true} :double]
          [:game/defect-transfer-cap       {:optional true} :int]

          ;; Combat power constants
          [:game/soldier-power             :int]
          [:game/fighter-power             :int]
          [:game/cmd-ship-power            :int]
          [:game/station-power             :int]
          [:game/general-power             :int]
          [:game/admiral-power             :int]

          ;; Income generation constants
          [:game/ore-planet-credits        :int]
          [:game/erg-planet-food          :int]
          [:game/erg-planet-fuel          :int]
          [:game/mil-planet-soldiers       :int]
          [:game/mil-planet-fighters       :int]
          [:game/mil-planet-stations       :int]
          
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
          [:game/fuel-sell               :int]]

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

            ;; Diplomatic relationships
            [:player/allies {:optional true}   [:set :player/id]]
            [:player/treaties {:optional true} [:set :player/id]]
            [:player/messages {:optional true} [:vector :message/id]]

            ;; Expense stability penalty
            [:player/expense-stability-penalty      {:optional true} [:maybe :int]]
            [:player/last-expense-stability-penalty {:optional true} [:maybe :int]]

            ;; Stability breakaway and recovery
            [:player/last-stability-breakaway       {:optional true} [:maybe :string]]
            [:player/last-stability-recovery        {:optional true} [:maybe :string]]

            ;; Combat
            [:player/pending-attack      {:optional true} [:maybe :player/id]]
            [:player/pending-attack-mode {:optional true} [:maybe :keyword]]

            ;; Espionage
            [:player/pending-espionage    {:optional true} [:maybe :player/id]]
            [:player/pending-espionage-op {:optional true} [:maybe :string]]

            ;; Incoming events (set by attackers, displayed on defender's outcomes page)
            [:player/incoming-attacks                {:optional true} [:maybe [:vector :string]]]
            [:player/incoming-espionage-fails        {:optional true} [:maybe :int]]
            [:player/incoming-espionage-agents-gained  {:optional true} [:maybe :int]]
            [:player/incoming-incite-stability-lost    {:optional true} [:maybe :int]]
            [:player/incoming-bomb-result              {:optional true} [:maybe :string]]]

   :message/id :uuid
   :message [:map {:closed true}
             [:xt/id              :message/id]
             [:message/game       :game/id]
             [:message/from       :player/id]
             [:message/to         :player/id]
             [:message/text       :string]
             [:message/sent-at    inst?]
             [:message/is-anonymous :boolean]]})

(def module
  {:schema schema})
