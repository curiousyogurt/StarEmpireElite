# CONVENTIONS.md — Star Empire Elite

Read this file before making any changes to the codebase. Every rule here is derived from the existing code. When in doubt, match what's already there rather than introducing a new pattern.


## Project Structure

The project is a Clojure web app using the Biff framework, XTDB, HTMX, and Tailwind CSS.

Namespace root: `com.star-empire-elite`

Key namespaces and their roles:

- `ui` — Shared hiccup components. Display-only: no DB access, no side effects.
- `utils` — Shared utility functions: input parsing, phase validation, entity loading, flash messages, cooldown logic, player snapshots.
- `constants` — All tuneable game values. Seeded into game entities at creation time.
- `schema` — Malli schema for all entity types.
- `combat` — Pure combat and espionage resolution. No DB access.
- `settings` — App-level config (app name, etc.).
- `app` — Route definitions and phase handler wiring.
- Phase namespaces (`income`, `expenses`, `exchange`, `building`, `action`, `espionage`, `outcomes`) — Each owns its calculations, UI components, actions, and page function.

Each phase namespace follows a standard section order:

```
;;;;;
;;;;; Phase Name - Description
;;;;;

(ns ...)

;;;; Calculations     — pure functions, no DB
;;;; View Models      — data shaping for UI (when needed)
;;;; UI Components    — hiccup rendering functions
;;;; Actions          — POST handlers (DB writes, redirects)
;;;; Page             — the GET page function
```


## Naming Conventions

**General:** kebab-case for everything. No camelCase, no snake_case.

**Functions:**
- Private helpers use `defn-`, not `defn` with `^:private`.
- Boolean predicates end in `?`: `can-afford-expenses?`, `day-exhausted?`, `att-wins?`.
- Pure calculation functions are named `calculate-*` and take/return plain maps.
- Parse functions are named `parse-*`: `parse-expense-payments`, `parse-purchase-quantities`.
- Resource-after functions are named `calculate-resources-after-*`.
- Apply functions (POST handlers) are named `apply-*`: `apply-income`, `apply-expenses`.
- HTMX handler functions are named `calculate-*` (for OOB update endpoints).
- Page render functions are named `*-page`: `income-page`, `expenses-page`.

**Variables:**
- `player` — the full player entity map (keys like `:player/credits`).
- `game` — the full game entity map (keys like `:game/ore-planet-credits`).
- `player-id` — the player's UUID (not a string).
- `slug` — a URL/DOM-safe lowercase string derived from a display name: `(str/lower-case name)`. Use `slug`, not `row-id`.
- `ctx` — the Ring request context.
- `db` — the XTDB database value.
- Short bindings like `v`, `n`, `k` are fine in tight scopes (2-3 lines). Use descriptive names in longer scopes.

**DOM IDs for HTMX OOB:**
- After-value spans: `"after-{slug}"` (e.g. `"after-credits"`)
- Bar containers: `"bar-{slug}"` (e.g. `"bar-credits"`)
- Warnings: `"{phase}-warning"` (e.g. `"expense-warning"`)
- Projection pills: `"{resource}-pill-{label}"` (e.g. `"credits-pill-current"`)
- Max quantity spans: `"max-qty-{item}"` (e.g. `"max-qty-soldiers"`)
- Cost spans: `"cost-{item}"` (e.g. `"cost-soldiers"`)

**Row spec vectors:** Data-driven table definitions use vectors of maps. Established key names:
- `:label` — full display name
- `:abbrev` — short display name for mobile
- `:qty-key` — keyword for quantity in the quantities map
- `:cost-key` / `:rate-key` — keyword for looking up cost/rate in the game map
- `:player-key` — fully qualified keyword for looking up value in the player map
- `:field` — HTML input field name (string)


## Comment Hierarchy

```clojure
;;;;;  File header (title + description block)
;;;;   Major section (Calculations, UI Components, Actions, Page)
;;;    Function-level or group-level explanation
;;     Inline explanation within a function
```

Every public function has a docstring. Docstrings end with a contract line:

```clojure
"Description of what the function does.

 [arg1 type, arg2 type] -> return-type"
```

The contract line uses informal type names: `int`, `str`, `bool`, `uuid`, `map`, `seq`, `hiccup`, `keyword`. Specific map shapes are described with inline key listings or named references like `player-map`, `game-map`, `resource-map`, `force-map`.


## Function Design

**Pure functions first.** Every calculation should be extractable as a pure function taking maps and returning maps, with no DB or Ring context dependencies. This is both good architecture and good pedagogy.

**Standard parameter patterns:**
- Calculation functions take `player` and/or `game` maps.
- POST handlers take `ctx` (the Ring context) and use `utils/with-player-and-game` to extract entities.
- Page functions take a map: `{:keys [player game flash]}` or `{:keys [player game db]}`.

**`utils/with-player-and-game` macro:** Use this in every handler that needs player and game entities. Don't inline the entity loading pattern.

```clojure
(utils/with-player-and-game [player game player-id] ctx
  ...)
```

**`utils/player-snapshot`:** Use this whenever you need an unqualified resource map from a player entity. Don't manually strip `:player/` prefixes.

**`utils/parse-numeric-input`:** Use this for all user input parsing. Don't write custom parsing.

**`utils/validate-phase`:** Use this at the top of every phase handler. It returns nil on success or a redirect response on failure.

**`utils/flash` / `utils/take-flash`:** Use for all flash messages. Don't invent a different session-messaging pattern.


## UI Patterns

**`ui/format-number` returns hiccup (a `[:span ...]` vector), not a string.** Never wrap its output in `str` or string concatenation. When you need a plain string, use `ui/format-number-str`.

**`ui/format-population`** multiplies by 1M before formatting. Don't do the multiplication yourself.

**`ui/numeric-input`** is the single input component for all form fields. Don't create raw `<input>` elements for numeric game inputs. Use it with its options map:
- `:input-class` — extra CSS classes
- `:input-style` — inline style map
- `:prefix` — overlaid prefix text (e.g. `"-"` for expenses)
- `:display-only?` — strips name/HTMX (for mirrors in dual-row layouts; prefer single-row CSS-only layouts instead)
- `:mirror-of` — syncs value to the named input
- `:sync-key` — JS key for cross-view syncing

**Shared table headers:** Use `ui/deduction-table-header` for Item/Before/Change/After tables (expenses, building impact). Use `ui/purchase-table-header` for Item/Rate/Max/Action/Total tables (exchange, building orders).

**Phase page structure:** Every phase page follows this layout:

```clojure
(ui/phase-shell player game "PHASE NAME"
  (biff/form {...}
    (ui/phase-body player
      (ui/flash-notice flash)
      (ui/snapshot-section player {opts})
      ... phase-specific content ...)
    (ui/phase-warning "phase-warning-id")
    (ui/phase-action-bar
      (ui/action-bar-link ... "Pause")
      ... other buttons ...
      (ui/submit-button affordable? "Continue to Next"))))
```

**Section labels:** Use `(ui/section-label "Title")` or `(ui/section-label "Title" "subtitle text")` above every content section.


## Responsive Layout

**Single-row CSS-only responsive layouts are preferred.** Don't render duplicate mobile/desktop rows with separate inputs. Instead, render one row and use CSS Grid with `hidden md:block` on the bar column to show/hide it by breakpoint.

Grid column definitions live in `tailwind.css` as custom utility classes (e.g. `.expense-row`, `.building-purchase-grid`, `.phase-row-grid`).

When you must have two DOM elements for the same logical value (e.g. OOB swap targets), give them distinct IDs with `-m` / `-d` suffixes. But prefer the single-row approach to avoid this entirely.


## HTMX Patterns

**Server-authoritative:** All calculations happen server-side. No client-side game logic.

**OOB swap pattern:** HTMX calculate endpoints return a container div plus OOB-swapped fragments:

```clojure
(biff/render
  [:div
   ;; Renew the swap target
   [:div#resources-after]
   ;; OOB fragments
   (oob-span "after-credits" (:credits resources-after))
   ...
   ;; Warning and submit button
   (ui/phase-warning-div "phase-warning" message {:oob? true})
   (ui/submit-button affordable? "Label" {:hx-swap-oob "true"})])
```

**`hx-include`:** Build the include selector as a `str/join` of `[name='field-name']` selectors covering all inputs that should be sent with each calculation request.

**Warning divs:** Use `ui/phase-warning` for the initial empty placeholder. Use `ui/phase-warning-div` in the HTMX response for OOB updates.

**Submit buttons:** Use `ui/submit-button` everywhere. It handles enabled/disabled styling and the `hx-swap-oob` attribute.


## CSS and Styling

**Tailwind classes in hiccup.** Use Tailwind's dot-joined class syntax on elements: `[:div.text-base.font-bold.text-green-400]`.

**Custom grids in `tailwind.css`.** Don't inline `grid-template-columns` in hiccup style maps for responsive table layouts. Define a CSS class and use media queries.

**Game theme colors:** Use the custom color tokens defined in `tailwind_config.js`:
- `text-green-400` — primary text
- `text-game-green-muted` — secondary text
- `text-game-green-soft` — tertiary text
- `text-game-green-dim` — very dim text
- `bg-game-bg` — card background
- `bg-game-surface` — table surface
- `bg-game-row` — table row
- `bg-game-header` — table header
- `bg-game-card` — card interior
- `bg-game-green-deep` — pill/badge background
- `border-game-border` — standard borders
- `border-game-divider` — lighter row dividers
- `border-game-green-border` — prominent green borders
- `text-red-400` — negative values, errors
- `text-amber-400` / `text-yellow-400` — warnings

**Inline styles:** Use sparingly — only for values Tailwind can't express (specific pixel values, text-shadow, letter-spacing). Use Tailwind arbitrary values `class="w-[140px]"` when possible.


## Data Flow

**Game constants are stored per-game instance** in the database, not read from `constants.clj` at runtime. `constants.clj` defines defaults; `app/game-defaults` snapshots them into a game entity at creation. Phase code reads constants from the `game` map (e.g. `(:game/ore-planet-credits game)`), never from `const/` directly during gameplay.

The one exception: `combat.clj` references some constants directly (`const/combat-variance`, `const/soldiers-per-transport`, etc.) for values that are structural rather than tuneable.

**Player resources use qualified keys** in the database (`:player/credits`) but **unqualified keys** in snapshot maps (`:credits`). Use `utils/player-snapshot` to convert.

**Row spec vectors** drive table rendering data-declaratively. The same spec vector is used for parsing inputs, calculating costs, rendering rows, and emitting OOB updates. Don't duplicate the item list — reference the spec.


## Change Discipline

Before any fix:

1. Check current state — read the relevant code.
2. Identify what's actually broken — don't assume.
3. Make only the minimal changes needed.
4. Never modify working code that isn't related to the fix.

When refactoring:
- Extract shared patterns to `utils.clj` (cross-namespace) or a local helper (single-namespace).
- Don't extract a helper unless it's used in two or more places or the extraction makes the call site meaningfully clearer.
- Prefer `cond->` for conditional map building.
- Prefer `for` with `:let`/`:when` for filtered iteration over `filter`/`map` chains.
- Keep strings inline. No i18n extraction.


## Testing

Pure functions should be testable in isolation: they take maps and return maps with no DB dependency. Mock DB interactions in tests rather than requiring a live connection.


## Licensing

AGPL-3. Establish contributor copyright policy (CLA / CONTRIBUTING.md) before accepting the first outside contribution.
