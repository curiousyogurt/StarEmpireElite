http://www-cs-students.stanford.edu/~amitp/Articles/SRE-Clones.html
http://www-cs-students.stanford.edu/~amitp/Articles/SRE-Design.html

  create_table "users", force: :cascade do |t|
    t.string   "empire_name"
    t.integer  "score",                       limit: 8, default: 0
    t.integer  "shield",                                default: 0
    t.integer  "current_round"
    t.integer  "current_turn"
    t.integer  "current_phase"
    t.integer  "total_turns",                           default: 0
    t.integer  "credits",                     limit: 8, default: 0
    t.integer  "galaxars",                              default: 0
    t.integer  "population",                            default: 0
    t.integer  "food",                        limit: 8, default: 0
    t.integer  "planets_food",                limit: 8, default: 0
    t.integer  "planets_ore",                 limit: 8, default: 0
    t.integer  "planets_military",            limit: 8, default: 0
    t.integer  "units_soldier",               limit: 8, default: 0
    t.integer  "units_fighter",               limit: 8, default: 0
    t.integer  "units_station",               limit: 8, default: 0
    t.integer  "units_cruiser",               limit: 8, default: 0
    t.integer  "units_agent",                 limit: 8, default: 0
    t.integer  "units_general",               limit: 8, default: 0
    t.integer  "units_carrier",               limit: 8, default: 0
    t.integer  "units_admiral",               limit: 8, default: 0
    t.integer  "units_command_ship",                    default: 0
    t.integer  "empire_status",                         default: 0
    t.float    "planetary_maintenance_ratio",           default: 1.0
    t.float    "military_maintenance_ratio",            default: 1.0
    t.float    "population_food_ratio",                 default: 1.0
    t.float    "military_food_ratio",                   default: 1.0
    t.datetime "created_at",                                          null: false
    t.datetime "updated_at",                                          null: false
    t.string   "email",                                 default: "",  null: false
    t.string   "encrypted_password",                    default: "",  null: false
    t.string   "reset_password_token"
    t.datetime "reset_password_sent_at"
    t.datetime "remember_created_at"
    t.integer  "sign_in_count",                         default: 0,   null: false
    t.datetime "current_sign_in_at"
    t.datetime "last_sign_in_at"
    t.string   "current_sign_in_ip"
    t.string   "last_sign_in_ip"
