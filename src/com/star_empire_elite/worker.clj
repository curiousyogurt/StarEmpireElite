(ns com.star-empire-elite.worker
  (:require [clojure.tools.logging :as log]
            [com.biffweb :as biff :refer [q]]
            [xtdb.api :as xt]))

(defn every-n-minutes [n]
  (iterate #(biff/add-seconds % (* 60 n)) (java.util.Date.)))

(defn print-usage [{:keys [biff/db]}]
  ;; For a real app, you can have this run once per day and send you the output in an email.
  (let [n-users (nth (q db
                        '{:find (count user)
                          :where [[user :user/email]]})
                     0
                     0)]
    (log/info "There are" n-users "users.")))

(defn alert-new-user [{:keys [biff.xtdb/node]} tx]
  ;; Only open a historical db snapshot when this tx actually contains a :user/email put —
  ;; the vast majority of transactions (player stat updates, game events) don't, and this
  ;; handler runs on every single submit-tx across the whole app, so skipping the snapshot
  ;; for the common case avoids a per-transaction xt/db call during turn resolution.
  (doseq [[op & args] (::xt/tx-ops tx)
          :when (= op ::xt/put)
          :let [[doc] args]
          :when (contains? doc :user/email)
          :let [db-before (xt/db node {::xt/tx-id (dec (::xt/tx-id tx))})]
          :when (nil? (xt/entity db-before (:xt/id doc)))]
    ;; You could send this as an email instead of printing.
    (log/info "WOAH there's a new user")))

(def module
  {:tasks [{:task #'print-usage
            :schedule #(every-n-minutes 5)}]
   :on-tx alert-new-user})
