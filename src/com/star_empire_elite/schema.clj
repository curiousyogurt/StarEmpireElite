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
          [:game/rounds-per-day            :int]]

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
            [:player/defence-stations      :int]
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
