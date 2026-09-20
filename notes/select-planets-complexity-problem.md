# `select-planets`: how a random-sampling helper became an O(n) memory bomb

## What it did

When an attacker wins an invade/raid, the game needs to decide which of the defender's
planets get captured — some mix of military, energy, and ore worlds, summing to a capped
count `n`. The original implementation:

```clojure
(defn- select-planets [defender n]
  (let [pool  (shuffle (concat (repeat (:player/mil-planets defender) :mil)
                               (repeat (:player/erg-planets defender) :erg)
                               (repeat (:player/ore-planets defender) :ore)))
        taken (frequencies (take n pool))]
    {:mil (get taken :mil 0)
     :erg (get taken :erg 0)
     :ore (get taken :ore 0)}))
```

The idea is intuitive and reads correctly at a glance: build a "bag" containing one token
per planet the defender owns (`:mil` repeated `mil-planets` times, etc.), shuffle the bag,
draw `n` tokens out, count how many of each type came up. That's a textbook way to do
sampling-without-replacement, and it's *correct* — it gives an unbiased hypergeometric
sample. For a defender with, say, 50 planets, this builds a 50-element vector and shuffles
it. Trivial.

## Why it broke: the cost is in the input size you don't control, not the output size you asked for

The function returns three integers. Nothing about its *signature* suggests it could be
expensive. But `pool`'s size isn't `n` — it's `mil-planets + erg-planets + ore-planets`,
i.e. the defender's **entire planet count**, regardless of how many are actually being
captured. `shuffle` forces full realization of that sequence into an array before it can
return anything (you can't randomly shuffle a lazy sequence lazily — it has to see every
element to permute them). So the actual cost is:

- **Time:** O(total planets) to build and shuffle the pool, versus O(n) if you only cared
  about the output.
- **Space:** one full keyword reference per planet, live in memory simultaneously, versus
  a 3-key map.

For a defender with 50 planets this difference is invisible — O(50) and O(3) both finish
instantly. The bug is dormant precisely *because* it looks fine in every small test case,
every unit test, every manual playtest. It only becomes visible when the input crosses a
threshold nobody had reason to test at, which is exactly what happened here: a player
reached **24,376,570 planets** through legitimate organic play.

At that point:

- The seq/array holds 24.3 million keyword slots. Even at a lean ~4-8 bytes per
  compressed-oop reference, that's roughly **100-200 MB for one intermediate array**,
  built and discarded on a single function call, on a JVM capped at `-Xmx512m`.
- `shuffle` internally does a Fisher-Yates pass over the whole array — real CPU work, not
  just allocation.
- This all happens inside a single `GET /outcomes` request, synchronously, on the request
  thread, with no way to bail out partway.

## How this actually presented as a crash (and why it was hard to pin down)

1. The huge array allocation either fails outright (`OutOfMemoryError: Java heap space`
   directly at that allocation site) or — as actually happened in production — succeeds by
   forcing the collector into a series of increasingly desperate full GCs trying to find
   100+ MB of contiguous free space, each pass taking real wall-clock time, before finally
   giving up.
2. Because the failure isn't tied to *where* the memory eventually runs out, the JVM's
   fatal-error thread dump showed the crash happening in **unrelated** code — first an
   HTTP connection-accept thread, later an XTDB internal query-planning call
   (`eid_cardinality`). Neither of those did anything wrong; they were just the next thing
   unlucky enough to ask for heap space after `select-planets` had already used it all up.
3. This is why two earlier rounds of fixes (switching to Serial GC, capping the
   `:player/incoming-attacks` history field) didn't resolve it — the crash symptom (heap
   exhaustion, OOM abort) was identical each time, but the evidence pointing at the actual
   allocator was one level removed from where the crash report appeared. It took pulling
   real player data off the live server to see the 24-million and 1-million planet counts
   and connect them to the one function in the whole codebase whose cost scales with a raw
   game quantity instead of its output.

## The fix: same answer shape, O(1) instead of O(total planets)

Rather than sampling individual planets, the fix computes the proportional split directly
— how many of `n` should be mil/erg/ore, given the defender's mil:erg:ore ratio — using the
largest-remainder method:

```clojure
(let [mil-frac (* n (/ (double mil) total))       ; expected share, as a fraction
      ...
      mil-base (long mil-frac)                    ; floor of each share
      remainder (- n mil-base erg-base ore-base)   ; leftover from flooring, distribute one
      ...]                                         ; each to whichever types had the
  {:mil (min mil (+ mil-base ...)) ...})           ; largest fractional part
```

This is exactly the technique `resolution/distribute-breakaway-planets` already used
elsewhere in this codebase for the equivalent problem (splitting a planet *loss* count
across types) — so it's not a new pattern, just the same one applied here. Properties that
matter:

- **O(1)**: only ever touches three numbers, never builds a collection sized by planet
  count.
- **Exact**: the three outputs always sum to exactly `n` (that's what the
  remainder-distribution step guarantees), and never exceed the defender's actual holdings
  of each type (the `min` guards).
- **Behavior trade-off, stated plainly**: this trades *random sampling without
  replacement* for a *deterministic proportional split*. For small-to-moderate planet
  counts the two are statistically almost indistinguishable (law of large numbers — random
  sampling converges to the proportional average). For very small counts (a defender with,
  say, 3 total planets) the old version had more sample-to-sample variance; the new version
  always gives the same answer for the same inputs. This is an acceptable, minor flavor
  change given the alternative is an unbounded crash risk — but it is a real behavior
  change, not pure hardening, and is worth knowing about.

## Verification

- Ran it against the actual production numbers (24,376,570 total planets, capturing
  6,000,000): 100 calls completed in ~1ms combined, versus the old version which would
  attempt a 24-million-element allocation on the first call.
- Output summed to exactly 6,000,000 as required.
- Full test suite (414 tests, 4808 assertions) still passes unchanged — nothing exercised
  the removed randomness directly.

## The general lesson

The tell for this class of bug is: **a function's cost is driven by an input magnitude
that isn't visible in its type signature or its return value.** `select-planets` takes a
player map and an int, returns a 3-key map — nothing there hints that its runtime depends
on a number buried three fields deep inside that player map. Because of this, the whole
codebase was re-checked for every other `repeat`/`repeatedly`/`range`/`dotimes` call after
confirming the million-planet counts were real rather than test noise — the concern
generalizes to any place building a collection sized by a resource count rather than by a
bounded game constant. That search came back clean; `select-planets` was the only
offender.
