;;;;;
;;;;; Diplomacy Phase - Treaty Proposals and Management
;;;;;
;;;;; The diplomacy phase (7) opens once per round, right after the last turn's Outcomes
;;;;; phase (see outcomes/apply-outcomes, which routes here instead of back to Income only
;;;;; when a round just ended). It is the only place a treaty can be proposed, accepted,
;;;;; declined, withdrawn, or dissolved — see diplomacy.clj for why: a proposal is an envoy
;;;;; that hasn't "arrived" until the recipient reaches this phase, so it survives any
;;;;; attacks made while it's in transit. Breaking an already-active treaty happens
;;;;; elsewhere, during combat resolution (resolution.clj), not here.
;;;;;
;;;;; Every move here is deferred, exactly like Invade/Raid/Strike in the Action phase:
;;;;; Accept, Decline, Withdraw, Dissolve, and Propose are all radio buttons that only
;;;;; highlight a choice. Nothing is written to the database until the player clicks "End
;;;;; Current Round" — see apply-diplomacy, which is the one place any of them take effect.
;;;;; Multiple choices across different relationships can be queued at once (e.g. accept one
;;;;; proposal and dissolve an unrelated treaty in the same visit); only Propose is limited to
;;;;; one new offer per round, by there being a single candidate to choose from.
;;;;;
;;;;; A pair holds at most one treaty, of exactly one kind (see diplomacy/proposable-kinds).
;;;;; An existing active partner's switch-to-the-other-kind option lives on their Active
;;;;; Treaties row, next to Dissolve — accepting it replaces their current treaty
;;;;; (diplomacy/replacement?) rather than claim a new slot. Propose a Treaty therefore only
;;;;; ever lists complete strangers — an empire with an existing treaty or a pending proposal
;;;;; never appears there too, which would otherwise show the same partner twice.
;;;;;

;;;;; Logical Structure:
;;;;;
;;;;; 1)  diplomacy-page ← live-context
;;;;;     └─ ui/phase-shell
;;;;;        └─ biff/form
;;;;;           ├─ ui/phase-body
;;;;;           │  ├─ ui/snapshot-section
;;;;;           │  ├─ ui/flash-notice
;;;;;           │  ├─ slot-counter
;;;;;           │  ├─ relation-table  (Active Treaties → active-treaty-row → switch-kind radio(s) + decision-radio "Dissolve")
;;;;;           │  ├─ relation-table  (Propose a Treaty, strangers only → propose-row → ui/op-radio-btn per kind)
;;;;;           │  ├─ relation-table  (Incoming Proposals → incoming-row → decision-radio "Accept"/"Decline")
;;;;;           │  └─ relation-table  (Outgoing Proposals → outgoing-row → decision-radio "Withdraw")
;;;;;           ├─ ui/phase-warning
;;;;;           └─ ui/phase-action-bar
;;;;;              ├─ ui/action-bar-link "Pause"
;;;;;              ├─ ui/action-bar-button "Cancel Diplomacy"
;;;;;              └─ ui/submit-button "End Current Round"
;;;;;
;;;;; 2)  update-diplomacy-warning  (HTMX OOB handler — summarizes every queued selection)
;;;;;
;;;;; 3)  apply-diplomacy  (resolves every queued selection via diplomacy/resolve-decisions,
;;;;;     then advances phase 7 -> 1, redirect to income)

(ns com.star-empire-elite.pages.app.diplomacy
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [com.star-empire-elite.constants :as const]
            [com.star-empire-elite.diplomacy :as diplomacy]
            [com.star-empire-elite.events :as events]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.utils :as utils]
            [xtdb.api :as xt]))

;;; Display label for a treaty kind ("Peace", "Defence", …), or nil for an unrecognized
;;; kind — see const/treaty-kinds, the single source of truth every treaty-kind-aware piece
;;; of UI here draws from.
(defn- kind-label [kind]
  (:label (first (filter #(= kind (:kind %)) const/treaty-kinds))))

;;;;
;;;; Shared Data Loading
;;;;

;;; Both the page (GET) and the submit (POST apply-diplomacy) need the same picture of where
;;; this player stands: who else is still in the game, which of their alliances still have a
;;; live partner, and a player-map/name to show for everyone involved. Centralised here so
;;; the two call sites can't drift.
(defn- live-context
  "[db xtdb-db, game-id uuid, player player-map]
   -> {:others seq-of-player-map, :live-ids set-of-uuid, :players {uuid player-map},
       :names {uuid empire-name}, :alliances seq-of-alliance-map, :live seq-of-alliance-map}"
  [db game-id player]
  (let [player-id (:xt/id player)
        others    (utils/get-other-players db game-id player-id)
        live-ids  (set (map :xt/id others))
        ;; :players carries the full player-map (not just the name) so relation rows can
        ;; render the same Empire/Planets/Score columns everywhere a partner is shown —
        ;; see ui/target-operation-row below.
        players   (into {player-id player} (map (juxt :xt/id identity)) others)
        names     (into {} (map (fn [[id p]] [id (:player/empire-name p)])) players)
        alliances (diplomacy/alliances-for-player db game-id player-id)
        live      (diplomacy/only-live-partners
                    (diplomacy/for-player alliances player-id) player-id live-ids)]
    {:others others :live-ids live-ids :players players :names names
     :alliances alliances :live live}))

(defn- event-meta
  "[player player-map, game game-map, now Date] -> {:keys [game-id turn round at]}"
  [player game now]
  {:game-id (:player/game player)
   :turn    (:player/current-turn player)
   :round   (:player/current-round player)
   :at      now})

;;;;
;;;; UI Components
;;;; - decision-radio:    a deferred radio for one relationship's Accept/Decline/Withdraw/
;;;;                      Dissolve choice — same ui/op-radio-btn Invade/Raid/Strike use
;;;; - relation-table:    the Empire/Planets/Score/<last-column> table shared by every
;;;;                      relationship list (active treaties, incoming/outgoing proposals,
;;;;                      and candidates) — the last header reads "Operations" except for
;;;;                      Active Treaties, which overrides it to "Change Treaty"
;;;; - active-treaty-row: an Active Treaties row — a switch-kind radio per OTHER treaty kind,
;;;;                      a Dissolve radio, and a boxed treaty-kind marker
;;;; - incoming-row:      an Incoming Proposals row — Accept and Decline radios, sharing one
;;;;                      radio group so picking one deselects the other
;;;; - outgoing-row:      an Outgoing Proposals row — a Withdraw radio
;;;; - propose-row:       a stranger-candidate row — one radio per treaty kind, or "No slots
;;;;                      free"
;;;;

(defn- decision-radio
  "One radio for a deferred per-relationship decision (Accept, Decline, Withdraw, or
  Dissolve) — selecting it only highlights the choice; nothing happens to alliance until
  End Current Round is submitted (see apply-diplomacy). Every option offered for the SAME
  alliance shares a radio-name, so e.g. Accept and Decline on one incoming proposal are
  mutually exclusive, but a different alliance's radios never compete with these.

  opts (optional) passes straight through to ui/op-radio-btn — :checked? and :sticky? are
  used by incoming-row to make Decline the forced, non-deselectable default; :id and
  :also-clear are used by active-treaty-row to make Dissolve mutually exclusive with a
  switch-kind radio even though the two live in different radio-name groups.

  [player-id uuid, alliance-id uuid, op str, label str, opts map|nil] -> hiccup"
  [player-id alliance-id op label & [opts]]
  (ui/op-radio-btn
    (str "treaty-" alliance-id)
    op
    label
    (str "/app/game/" player-id "/diplomacy-warning")
    "diplomacy-warning"
    opts))

(def ^:private th-cls "text-green-400 text-[11px] tracking-[0.08em] uppercase")

;;; Cell padding for a kind-button column (Active Treaties' switch/Dissolve cells, Propose a
;;; Treaty's Peace/Defence cells). The actual WIDTH is set once per table by relation-table's
;;; <colgroup> below, not here — a width on the <td> itself is only ever a hint under the
;;; browser's default table-layout: auto, which still redistributes a table's leftover space
;;; across columns based on THAT table's own content, so an identical width can still render
;;; at a different pixel size in two different tables (Active Treaties vs Propose a Treaty).
;;; table-layout: fixed + an explicit <colgroup> is the one mechanism that pins every
;;; column's width deterministically, independent of content and independent of which table
;;; it's in.
(def ^:private kind-col-cls "py-1 px-2")

;;; The four columns every relation-table shares — Empire/Planets/Score/Operations — at
;;; approximately 40/15/15/30% of the table's width. Operations' 30% is split evenly across
;;; op-cols sub-columns (so 15% each for every table today, since op-cols is 1 or 2 and 30
;;; divides evenly by both); see relation-table's <colgroup>.
(def ^:private empire-col-pct  40)
(def ^:private planets-col-pct 15)
(def ^:private score-col-pct   15)
(def ^:private ops-col-pct     30)

(defn- relation-table
  "Render the shared Empire/Planets/Score/<last-column> table used by every relationship
  list — same header/column layout as action.clj's target table, including the centered
  last header. op-cols is how many columns this table's rows have under that last header —
  1 for Active/Outgoing/Propose, 2 for Incoming's Accept+Decline — used for both the
  header's colspan and the <colgroup> below, which is what actually pins Empire/Planets/
  Score/Operations to the same ~40/15/15/30% split (see empire-col-pct and friends) across
  every one of these tables, regardless of table-layout: auto's usual per-table
  redistribution. last-col-label defaults to \"Operations\"; Active Treaties overrides it
  to \"Change Treaty\", since that's exactly what its columns let the player do.

  [op-cols int, rows seq-of-hiccup-tr, & [last-col-label str]] -> hiccup"
  [op-cols rows & [last-col-label]]
  [:div.overflow-x-auto
   [:table.text-sm {:class "w-full table-fixed border border-game-border border-collapse"}
    [:colgroup
     [:col {:style {:width (str empire-col-pct "%")}}]
     [:col {:style {:width (str planets-col-pct "%")}}]
     [:col {:style {:width (str score-col-pct "%")}}]
     (for [_ (range op-cols)]
       [:col {:style {:width (str (quot ops-col-pct op-cols) "%")}}])]
    [:thead
     [:tr.bg-game-header.border-b.border-game-border
      [:th.text-left.px-3.py-1  {:class th-cls} "Empire"]
      [:th.text-right.px-3.py-1 {:class th-cls} "Planets"]
      [:th.text-right.px-3.py-1 {:class th-cls} "Score"]
      [:th.px-3.py-1.text-center {:colspan op-cols :class th-cls} (or last-col-label "Operations")]]]
    [:tbody rows]]])

(defn- active-treaty-row
  "One row in the Active Treaties table — a boxed marker for the treaty's kind, a radio for
  each OTHER kind offering to switch to it (sharing the page-wide 'treaty-proposal' group,
  same composite 'partner-id:kind' value as propose-row), and a Dissolve radio. Switching
  kind is a replacement (see diplomacy/replacement?): the partner must still accept it from
  their own diplomacy phase, same as any proposal, but it needs no free slot and — once
  accepted — replaces this treaty instead of claiming a new one. Because every option for
  this partner lives here, they never also appear in Propose a Treaty (see diplomacy-page's
  candidates, which excludes anyone diplomacy/has-relationship? is true for).

  The switch radio(s) and Dissolve are two different radio-name groups — 'treaty-proposal'
  is shared page-wide (so at most one new proposal/switch total can be queued per round),
  while Dissolve's group is private to this one alliance — so plain HTML radio semantics
  don't make them mutually exclusive on their own. Each one's :also-clear option (see
  ui/op-radio-btn) closes that gap: selecting either one here un-checks the other, by :id,
  so the two always resolve to a single choice for this relationship — switch, dissolve, or
  neither — never both at once.

  [player-id uuid, alliance alliance-map, partner player-map] -> hiccup"
  [player-id alliance partner]
  (let [kind        (:alliance/kind alliance)
        other-kinds (remove #{kind} (map :kind const/treaty-kinds))
        dissolve-id (str "treaty-dissolve-" (:xt/id alliance))
        switch-id   (fn [other-kind] (str "treaty-switch-" (:xt/id alliance) "-" (name other-kind)))
        switch-sel  (str/join ", " (map #(str "#" (switch-id %)) other-kinds))
        switch-btn  (fn [other-kind]
                      (ui/op-radio-btn "treaty-proposal"
                        (str (:xt/id partner) ":" (name other-kind))
                        (kind-label other-kind)
                        (str "/app/game/" player-id "/diplomacy-warning")
                        "diplomacy-warning"
                        {:id (switch-id other-kind) :also-clear (str "#" dissolve-id)}))]
    (ui/target-operation-row
      partner
      (conj (mapv switch-btn other-kinds)
            (decision-radio player-id (:xt/id alliance) "dissolve" "Dissolve"
                             {:id dissolve-id :also-clear switch-sel}))
      ;; kind-col-cls (padding only — the width is relation-table's <colgroup>, shared with
      ;; Propose a Treaty's kind columns, so "Change Treaty" and "Operations" match exactly).
      kind-col-cls
      {:name-suffix (ui/treaty-indicator kind)})))

(defn- incoming-row
  "One row in the Incoming Proposals table — Accept and Decline radios sharing one group,
  mirroring Invade/Raid/Strike's 'one mode per target' pattern. Unlike every other deferred
  choice on this page, an incoming proposal can never be left undecided: Decline is
  pre-selected (:checked?) and both radios are :sticky? (no toggle-off), so the player can
  switch between Accept and Decline but can never get back to 'nothing selected' — see
  apply-diplomacy, which also enforces this server-side regardless of what the form sends.
  Accept is drawn disabled (ui/disabled-radio-btn), the same way Propose falls back to plain
  text, when the player has no free slot at page-load time AND this isn't a replacement
  (see diplomacy/replacement?, whose whole point is to need no free slot) — Decline is still
  the only live choice then. Carries the same boxed marker active treaties do (with a
  different tooltip, since this one isn't active yet), so the recipient can see at a glance
  what kind of treaty is being offered.

  [player-id uuid, alliance alliance-map, partner player-map, free? bool] -> hiccup"
  [player-id alliance partner free?]
  (let [radio-name (str "treaty-" (:xt/id alliance))
        kind       (:alliance/kind alliance)]
    (ui/target-operation-row
      partner
      [(if free?
         (decision-radio player-id (:xt/id alliance) "accept" "Accept" {:sticky? true})
         (ui/disabled-radio-btn radio-name "Accept"))
       (decision-radio player-id (:xt/id alliance) "decline" "Decline" {:checked? true :sticky? true})]
      "py-1 px-3"
      {:name-suffix (ui/treaty-indicator kind (str (kind-label kind) " treaty proposed"))})))

(defn- outgoing-row
  "One row in the Outgoing Proposals table — a Withdraw radio, plus the same boxed marker
  incoming-row carries, so the proposer can see at a glance what kind of treaty is pending.

  [player-id uuid, alliance alliance-map, partner player-map] -> hiccup"
  [player-id alliance partner]
  (let [kind (:alliance/kind alliance)]
    (ui/target-operation-row
      partner
      [(decision-radio player-id (:xt/id alliance) "withdraw" "Withdraw")]
      "py-1 px-3"
      {:name-suffix (ui/treaty-indicator kind (str (kind-label kind) " treaty proposed"))})))

(defn- propose-row
  "One candidate row in the Propose a Treaty table — one radio per treaty kind, each its own
  Operations column so they sit side by side (not stacked), sharing the page-wide
  'treaty-proposal' group with a composite 'target-id:kind' value, mirroring action.clj's
  'player-id:mode'. Every candidate here is a complete stranger (see diplomacy-page's
  candidates) so always offers every kind, and always needs a free slot — an existing
  partner's options live on their own Active Treaties row instead (see active-treaty-row).
  When the proposer has no free slot, the first column shows 'No slots free' and the rest
  render empty, keeping every row's column count consistent.

  Like every other decision here, selecting it only highlights the choice; nothing is
  created until End Current Round is submitted (see apply-diplomacy).

  [player-id uuid, free? bool, candidate player-map] -> hiccup"
  [player-id free? candidate]
  (ui/target-operation-row
    candidate
    (if free?
      (for [kind (map :kind const/treaty-kinds)]
        (ui/op-radio-btn "treaty-proposal"
          (str (:xt/id candidate) ":" (name kind))
          (kind-label kind)
          (str "/app/game/" player-id "/diplomacy-warning")
          "diplomacy-warning"))
      (cons [:span.text-game-green-dim.text-xs "No slots free"]
            (repeat (dec (count const/treaty-kinds)) nil)))
    ;; kind-col-cls (padding only — the width is relation-table's <colgroup>, shared with
    ;; active-treaty-row's switch/Dissolve columns).
    kind-col-cls))

;;;;
;;;; Actions
;;;;

;;; Every selected radio's value is one of these op strings; the matching label is what
;;; update-diplomacy-warning shows when exactly one decision is queued.
(def ^:private op-labels
  {"dissolve" "Treaty will be dissolved."
   "accept"   "Treaty proposal will be accepted."
   "decline"  "Treaty proposal will be declined."
   "withdraw" "Treaty proposal will be withdrawn."})

(defn- alliance-kind-by-id
  "A {alliance-id kind} map for every alliance in alliances — e.g. active treaties, so a
  dissolve decision's warning text can name which kind is being dissolved without a second
  DB round-trip per lookup.

  [alliances seq-of-alliance-map] -> {uuid keyword}"
  [alliances]
  (into {} (map (juxt :xt/id :alliance/kind)) alliances))

(defn- queued-decisions
  "Every diplomacy radio currently selected, read straight off a params map — one
  [op-str label-str] pair per selection, covering both the per-relationship 'treaty-<id>'
  groups and the separate 'treaty-proposal' group. An unchecked radio group simply has no
  key in params at all (standard HTML radio behavior), so presence alone means 'selected'.
  kind-by-id (see alliance-kind-by-id) names the kind being dissolved, if any — a dissolve
  decision's label is otherwise generic ('Treaty will be dissolved.', op-labels' fallback).

  [params ring-params-map, kind-by-id {uuid keyword}] -> seq-of-[op-str label-str]"
  [params kind-by-id]
  (let [[_target kind-str] (utils/parse-choice-value (:treaty-proposal params))
        label (kind-label (keyword kind-str))
        dissolve-label (fn [alliance-id-str]
                         (if-let [dissolved-label (kind-label (get kind-by-id (parse-uuid alliance-id-str)))]
                           (str dissolved-label " treaty will be dissolved.")
                           (op-labels "dissolve")))
        per-relationship (for [[k v] params
                                :when (and (not= k :treaty-proposal)
                                           (str/starts-with? (name k) "treaty-")
                                           (contains? op-labels v))]
                            [v (if (= v "dissolve")
                                 (dissolve-label (subs (name k) (count "treaty-")))
                                 (op-labels v))])]
    (cond-> (vec per-relationship)
      label (conj ["propose" (str label " treaty will be offered.")]))))

(defn- decisions-message
  "The warning text for a set of queued decisions — the specific label when exactly one is
  queued, otherwise just a count. Shared by the page's initial render (whose only queue, if
  any, is each incoming proposal's forced Decline default — see default-decision-params)
  and update-diplomacy-warning's live HTMX refresh.

  [queued seq-of-[op-str label-str]] -> string|nil"
  [queued]
  (case (count queued)
    0 nil
    1 (str "ⓘ " (second (first queued)))
    (str "ⓘ " (count queued) " diplomatic actions will be taken.")))

(defn- default-decision-params
  "The implicit param map matching the page's default radio state. Every other radio here
  starts unchecked, but an incoming proposal can't — Decline is pre-selected (see
  incoming-row) so the player is never silently defaulting to 'nothing chosen'. Feeding this
  through queued-decisions lets the page's initial warning reuse the exact same message
  logic as a live HTMX update, instead of duplicating it.

  [incoming seq-of-alliance-map] -> params-map"
  [incoming]
  (into {} (map (fn [a] [(keyword (str "treaty-" (:xt/id a))) "decline"])) incoming))

(defn update-diplomacy-warning
  "Return a phase-warning-div for direct outerHTML swap, summarizing every diplomacy radio
  currently selected across the whole form — mirrors action.clj's update-action-warning, but
  there can be several queued at once here (one per relationship), so the message names the
  specific action only when there's exactly one, and otherwise just gives a count.

  [ctx ring-ctx] -> hiccup"
  [{:keys [params] :as ctx}]
  (utils/with-player-and-game [player _game _player-id] ctx
    (let [db         (:biff/db ctx)
          active     (diplomacy/active-treaties (:live (live-context db (:player/game player) player))
                                                 (:xt/id player))
          kind-by-id (alliance-kind-by-id active)]
      (biff/render
        (ui/phase-warning-div "diplomacy-warning"
          (decisions-message (queued-decisions params kind-by-id))
          {:color "text-yellow-400"})))))

(defn apply-diplomacy
  "End the diplomacy window: resolve every queued decision — Accept/Decline/Withdraw/
  Dissolve on an existing relationship, plus a Propose a Treaty selection — then advance to
  the next round's income phase. None of these were committed when clicked; each was only
  ever highlighted on screen (see diplomacy-page), exactly like Invade/Raid/Strike at
  apply-action. This is the one place any of them actually takes effect.

  Each per-relationship decision is read off a param named 'treaty-<alliance-id>' (see
  decision-radio); Propose still comes through 'treaty-proposal'. An active treaty or
  outgoing proposal only gets a decision when its radio was actually selected. An incoming
  proposal is different and can never be left pending: it always gets a decision, Accept
  only when that param is exactly \"accept\", Decline for anything else (missing, stale,
  tampered) — capped here at the source, not just in the UI (see incoming-row), so a
  stripped form field can't be used to dodge the choice. diplomacy/resolve-decisions — a pure
  function — applies them in order: releases (decline/withdraw/dissolve) first, since they
  can free a slot; then accepts, each re-checked against the slots freed so far, in the
  order their alliances were loaded (which filter preserves through to the order each list
  is rendered in — see live-context/diplomacy-page — so ties among several accepts break in
  the same top-to-bottom order the player saw them; a loser falls back to :decline rather
  than staying pending); then the one Propose, last of all. A decision on an active treaty
  or outgoing proposal that's no longer valid by submit time (stale alliance, eliminated
  partner, …) is silently dropped rather than rejecting the whole submission — the same
  philosophy a stale Propose target already followed.

  [ctx ring-ctx] -> ring-response (303 redirect to income)"
  [{:keys [params biff/db] :as ctx}]
  (utils/with-player-and-game [player game player-id] ctx
    (if-let [redirect (utils/validate-phase player 7 player-id)]
      redirect
      (let [now         (java.util.Date.)
            {:keys [live-ids names live]} (live-context db (:player/game player) player)
            incoming-ids (set (map :xt/id (diplomacy/incoming-proposals live player-id)))
            decisions   (for [a live
                              :let [id (:xt/id a)
                                    op (get params (keyword (str "treaty-" id)))]
                              :when (or (contains? op-labels op) (incoming-ids id))]
                          [id (keyword (if (incoming-ids id)
                                         (if (= op "accept") "accept" "decline")
                                         op))])
            [target-id-str kind-str] (utils/parse-choice-value (:treaty-proposal params))
            target-id (when target-id-str (parse-uuid target-id-str))
            ;; Only a still-live candidate counts — live-ids already excludes eliminated
            ;; players, so this also covers the "target got eliminated" drop case.
            propose-choice (when (live-ids target-id) [target-id (keyword kind-str)])
            resolved  (->> (diplomacy/resolve-decisions game live player-id decisions
                                                         propose-choice now)
                           (map (fn [{:keys [op alliance] :as r}]
                                  (cond-> r
                                    (= op :propose) (assoc :alliance (assoc alliance :xt/id (random-uuid)))))))
            alliance-tx (for [{:keys [alliance]} resolved]
                          (merge {:db/doc-type :alliance} alliance))
            ;; Tells the other side's alert banner about anything that changed even if they
            ;; never revisit this page themselves — same reasoning throughout this file.
            notifications (for [{:keys [op alliance]} resolved
                                :let [[field other-id]
                                      (case op
                                        :propose   [:player/incoming-treaty-proposed  (:alliance/recipient alliance)]
                                        :accept    [:player/incoming-treaty-accepted  (:alliance/proposer alliance)]
                                        :withdraw  [:player/incoming-treaty-withdrawn (:alliance/recipient alliance)]
                                        :dissolve  [:player/incoming-treaty-dissolved (diplomacy/partner-id alliance player-id)]
                                        ;; Notifies the proposer, not player-id (the
                                        ;; recipient who declined, including the automatic
                                        ;; no-free-slot fallback — see resolve-decisions).
                                        :decline   [:player/incoming-treaty-declined (:alliance/proposer alliance)]
                                        ;; A replacement's :accept above already notifies the
                                        ;; other side about the NEW treaty — the superseded
                                        ;; one needs no notification of its own.
                                        :supersede [nil nil])]
                                :when field]
                            {:field field :other-id other-id})
            ;; Two decisions in one submission (e.g. dissolving an active treaty with X while
            ;; also accepting a separate pending proposal from X) can both notify the same
            ;; opponent — group by recipient so each gets exactly one :db/op :update per
            ;; submit-tx. biff's :db/op :update builds one match+put pair per map; two separate
            ;; maps updating the same entity in one tx would have the second map's match
            ;; checked against a stale pre-tx snapshot the first map's put already changed — a
            ;; deterministic failure, not a race, that retries can't fix.
            notify-tx (for [[other-id entries] (group-by :other-id notifications)
                            :let [other (xt/entity db other-id)]]
                        (merge
                          {:db/doc-type :player :db/op :update :xt/id other-id
                           :player/incoming-seq (utils/next-incoming-seq other (count entries))}
                          (into {}
                                (map (fn [[field field-entries]]
                                       [field (reduce (fn [v _] (utils/capped-conj v (:player/empire-name player)))
                                                       (get other field) field-entries)]))
                                (group-by :field entries))))
            tx-events (for [{:keys [op alliance]} resolved
                            :when (#{:accept :dissolve} op)]
                        (events/event-of-treaty alliance
                          (if (= op :accept) :treaty-formed :treaty-dissolved)
                          names (event-meta player game now)))]
        (biff/submit-tx ctx
          (into [{:db/doc-type :player :db/op :update :xt/id player-id :player/current-phase 1
                  ;; Diplomacy notifications are cleared here, not at apply-outcomes — see the
                  ;; comment there. By now the player has seen and acted on every incoming
                  ;; proposal/accept/withdraw/dissolve (they're the same relationships just
                  ;; resolved above), so it's safe to clear them.
                  :player/incoming-treaty-proposed  nil
                  :player/incoming-treaty-accepted  nil
                  :player/incoming-treaty-withdrawn nil
                  :player/incoming-treaty-declined  nil
                  :player/incoming-treaty-dissolved nil}]
                (concat alliance-tx notify-tx)))
        (events/record-events! ctx tx-events)
        {:status 303 :headers {"location" (str "/app/game/" player-id "/income")}}))))

;;;;
;;;; Page
;;;;

(defn diplomacy-page
  "Show the diplomacy window: active treaties (each with its own switch-kind and Dissolve
  options — see active-treaty-row), pending proposals in both directions, and a Propose a
  Treaty table of every other live empire with NO existing relationship at all — a partner
  or pending-proposal counterpart always has their own options elsewhere on this page
  instead, so they never show up twice. Every relationship offers a deferred radio choice
  (see apply-diplomacy); the whole page is one biff/form, same as action-page.

  [{:keys [player game db flash]}] -> hiccup"
  [{:keys [player game db flash]}]
  (let [player-id   (:xt/id player)
        game-id     (:player/game player)
        {:keys [others players live]} (live-context db game-id player)
        partner     (fn [a] (get players (diplomacy/partner-id a player-id)))
        active      (diplomacy/active-treaties    live player-id)
        incoming    (diplomacy/incoming-proposals live player-id)
        outgoing    (diplomacy/outgoing-proposals live player-id)
        used        (diplomacy/slots-used live player-id)
        total       (:game/alliance-slots game)
        free?       (pos? (diplomacy/free-slots game live player-id))
        candidates  (remove #(diplomacy/has-relationship? live player-id (:xt/id %)) others)
        ;; Shared by Active Treaties (one column per OTHER kind's switch option, plus one
        ;; for Dissolve) and Propose a Treaty (one column per kind) — both equal exactly the
        ;; total number of treaty kinds.
        kind-cols   (count const/treaty-kinds)]
    (ui/phase-shell player game "Diplomacy Phase"
      (biff/form
        {:action (str "/app/game/" player-id "/apply-diplomacy") :method "post" :class "m-0"}
        (ui/phase-body player
          (ui/snapshot-section player)
          (ui/flash-notice flash)

          (ui/section-label "Treaty Slots" (str used " / " total " used"))

          (ui/section-label "Active Treaties")
          (if (seq active)
            (relation-table kind-cols
              (for [a active] (active-treaty-row player-id a (partner a)))
              "Change Treaty")
            [:p.text-xs.text-game-green-muted "No active treaties."])

          (ui/section-label "Propose a Treaty")
          (if (empty? candidates)
            [:p.text-xs.text-game-green-soft
             "There are no other empires in the galaxy to propose a treaty with."]
            (relation-table kind-cols
              (for [c candidates] (propose-row player-id free? c))))

          (when (seq incoming)
            [:div
             (ui/section-label "Incoming Proposals")
             (relation-table 2
               (for [a incoming]
                 ;; A replacement (see diplomacy/replacement?) needs no free slot, so Accept
                 ;; is live for it even when free? is false.
                 (incoming-row player-id a (partner a) (or free? (diplomacy/replacement? live a)))))])

          (when (seq outgoing)
            [:div
             (ui/section-label "Outgoing Proposals")
             (relation-table 1
               (for [a outgoing] (outgoing-row player-id a (partner a))))]))
        (let [initial-message (decisions-message
                                 (queued-decisions (default-decision-params incoming)
                                                    (alliance-kind-by-id active)))]
          (ui/phase-warning-div "diplomacy-warning" initial-message
            {:color (if initial-message "text-yellow-400" "text-game-green-soft")}))
        (ui/phase-action-bar
          (ui/action-bar-link (str "/app/game/" player-id) "Pause")
          (ui/action-bar-button "Cancel Diplomacy"
            {:class      "cancel-target"
             :hx-post    (str "/app/game/" player-id "/diplomacy-warning")
             :hx-target  "#diplomacy-warning"
             :hx-swap    "outerHTML"
             ;; Cancel only clears the OPTIONAL treaty-* radios — dissolve/withdraw/propose,
             ;; the only ones the cancel-target CSS rule ever shows this button for (see
             ;; tailwind.css). It must never touch an incoming proposal's Accept/Decline:
             ;; that's a forced choice, not a queued action, so whatever the player picked
             ;; there (or the Decline default, if they picked nothing) is left exactly as
             ;; is. hx-params is left at its default ('include the whole form'), so the POST
             ;; below picks up that now-mutated DOM state — same ordering op-radio-btn's own
             ;; toggle-off onclick already relies on (mutate, then let the same click's HTMX
             ;; request read the result).
             :onclick    "document.querySelectorAll('[name^=treaty-]').forEach(function(r){if(r.value!=='decline'&&r.value!=='accept'){r.checked=false;r.dataset.was='false';}});"})
          (ui/submit-button true "End Current Round"))))))
