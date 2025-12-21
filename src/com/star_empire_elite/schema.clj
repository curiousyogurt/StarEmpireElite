(ns com.star-empire-elite.schema)

(def schema
  {:user/id :uuid
   :user [:map {:closed true}
          [:xt/id                          :user/id]
          [:user/email                     :string]
          [:user/joined-at                 inst?]
          [:user/foo {:optional true}      :string]
          [:user/bar {:optional true}      :string]]

   :game/id :uuid
   :game [:map {:closed true}
          [:xt/id                          :game/id]
          [:game/name                      :string]
          [:game/created-at                inst?]
          [:game/scheduled-end-at          inst?]
          [:game/ended-at {:optional true} inst?]
          [:game/status                    :int]
          [:game/turns-per-day             :int]
          [:game/rounds-per-day            :int]
          
          ;; Income generation constants
          [:game/ore-planet-credits        :int]
          [:game/ore-planet-fuel           :int]
          [:game/ore-planet-galaxars       :int]
          [:game/food-planet-food          :int]
          [:game/military-planet-soldiers  :int]
          [:game/military-planet-fighters  :int]
          [:game/military-planet-stations  :int]
          [:game/military-planet-agents    :int]
          
          ;; Upkeep/expense constants
          [:game/planet-upkeep-credits     :int]
          [:game/planet-upkeep-food        :int]
          [:game/soldier-upkeep-credits    :int]
          [:game/soldier-upkeep-food       :int]
          [:game/fighter-upkeep-credits    :int]
          [:game/fighter-upkeep-fuel       :int]
          [:game/station-upkeep-credits    :int]
          [:game/station-upkeep-fuel       :int]
          [:game/agent-upkeep-credits      :int]
          [:game/agent-upkeep-food         :int]
          [:game/population-upkeep-credits :int]
          [:game/population-upkeep-food    :int]
 
          ;; Building/purchase cost constants (add these to your :game schema)
          [:game/soldier-cost            :int]
          [:game/transport-cost          :int]
          [:game/general-cost            :int]
          [:game/carrier-cost            :int]
          [:game/fighter-cost            :int]
          [:game/admiral-cost            :int]
          [:game/station-cost            :int]
          [:game/command-ship-cost       :int]
          [:game/military-planet-cost    :int]
          [:game/food-planet-cost        :int]
          [:game/ore-planet-cost         :int]]

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
            [:player/military-planets      :int]
            [:player/food-planets          :int]
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
 
            ;; Leadership

            ;; Military units
            [:player/soldiers              :int]
            [:player/transports            :int]
            [:player/stations              :int]
            [:player/carriers              :int]
            [:player/fighters              :int]
            [:player/command-ships         :int]
            [:player/generals              :int]
            [:player/admirals              :int]
            [:player/agents                :int]

            ;; Diplomatic relationships
            [:player/allies {:optional true}   [:set :player/id]]
            [:player/treaties {:optional true} [:set :player/id]]
            [:player/messages {:optional true} [:vector :message/id]]]

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
