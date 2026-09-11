# Samsung SM-A536B (Galaxy A53) — Android 16 (API 36)

Recorded 2026-09-11, working tree on top of `78c9230a` with the changes listed under Notes
applied and not yet committed, 1,000 sessions / 50,000 sets. Fourth run of the day, and the
one to compare against: the profile was regenerated from this code first (`make
baseline-profile`), then `make benchmark` measured against it. The three runs before it are
described in the Notes. Device conditions
(charging state, idle, screen brightness) not recorded for this run — confirm and amend before
treating this as the reference file.

`./gradlew :benchmark:connectedBenchmarkAndroidTest`

| Benchmark | Metric | Median | P99 | Budget | |
| --- | --- | --- | --- | --- | --- |
| `startupNoCompilation` | `timeToInitialDisplay` | 524.39 ms | — | recorded | |
| `startupWithBaselineProfile` | `timeToInitialDisplay` | 484.31 ms | — | beats the row above | ✅ |
| `firstPage` | `frameDurationCpuMs` | 4.72 ms | 38.70 ms | < 100 ms | ✅ |
| `scroll` | `frameOverrunMs` | 0.37 ms | 9.32 ms | ≤ 0 ms | ❌ see below |
| `overviewRender` | `frameDurationCpuMs` | 13.42 ms | 113.87 ms | < 200 ms | ✅ |
| `chartRender` | `frameDurationCpuMs` | 16.46 ms | 59.84 ms | < 200 ms | ✅ |

**Baseline Profile:** startup improved from 524.39 ms to 484.31 ms (-7.6%).

## Notes

### `scroll` misses its budget — what the traces say it is

The P99 alone overstates it. Reading the Perfetto frame timeline for iterations 0, 3, 5, 8
and 9 (`SessionsListBenchmark_scroll_iter*.perfetto-trace`, `actual_frame_timeline_slice`):

| Per iteration (~600 frames) | Frames |
| --- | --- |
| `Buffer Stuffing / Late Present` | 590–600 |
| `App Deadline Missed` | 4–9 |
| On time | 3–10 |

- The display runs at 120 Hz here (`expected_frame_timeline_slice` is 8.33 ms), so the
  "30-item fling" is ~600 frames and composes ~140 rows, not 30 — see `ITEMS_PER_FLING`.
- **Only 4–9 frames per run are the app being too slow.** All of them are in the *drag* phase
  of each of the three swipes (input still down), at 6.5–10.6 ms of main-thread time: ~1.5 ms
  of UiAutomator input delivery, 2–3.5 ms of draw, and the rest composing and measuring rows
  the finger is dragging in faster than the default one-item-ahead prefetch can supply them.
  `Compose:recompose` inside `measureAndLayout` (a row composed synchronously in the frame that
  first places it) ran ~194 times per iteration against ~143 prefetched — more than half the
  rows arrived the expensive way.
- **The other ~590 are the pipeline, not the app.** One missed deadline leaves the app a buffer
  ahead of SurfaceFlinger, and at 120 Hz it produces a frame every vsync for the rest of the
  fling so the queue never drains. Every such frame is stamped late by a fixed ~3 ms — the
  spike in the `frameOverrunMs` histogram — while pacing stays even. Steady-state recomposition
  during the fling is ~0.02 ms/frame: the list does not recompose while it scrolls.
- So `frameOverrunMs` P99 ≤ 0 is reachable only by making the drag frames never miss. It is not
  a "fewer slow frames" budget; it is an "no slow frame at all, ever" budget, because one
  starts the stuffing that the P99 then measures.

### What changed between the runs, and what changed after them

- Run 1 → run 2: `HistoryList.kt` `onClick` wrapped in `remember(session.id)`. Correct, and
  the P99 moved 10.02 → 9.03 ms, but the traces show it was never the cause: the rows were not
  recomposing during the fling in the first place. Kept; it is the right shape.
- Run 2 → run 3, on the drag-phase cost the traces pointed at:
  - `core/ui/format`: `NumberFormat.getInstance` was built per call (`formatNumber`), and
    `DateTimeFormatter.ofPattern` / `ofLocalizedDate` / `ofLocalizedTime` per call — a row
    went through two of the former and three of the latter to draw its rail and describe
    itself. All cached per locale now. Composition per row: 1.63 → 1.45 ms.
  - `SessionsScreen.kt`: the history list's `LazyListState` gets a `LazyLayoutCacheWindow`
    (one viewport ahead, half behind) so rows are composed in idle time rather than in the
    frame that places them. `@ExperimentalFoundationApi`, opted in at that one call site.
    **This worked completely:** in run 3 every row composition and every text measure sits
    under `compose:lazy:prefetch:*`; `Compose:recompose` inside a frame totals 1.7 ms *per
    run* (was ~215 ms). Buffer stuffing now drains mid-fling in some iterations (iter 5: 332
    stuffed / 249 on time, vs 589 / 10 in run 2), which is the P50 going 0.44 → 0.03 ms.
- **Run 3's remaining 5–7 missed frames per run are not the app.** With composition gone from
  the frame, the slow drag frames are `postAndWait` — the UI thread waiting for RenderThread —
  and RenderThread's `DrawFrames` is 8–10 ms for the first four or five frames of every swipe
  against 3–4 ms mid-fling, while `Running` for all of it (CPU-bound, not GPU-bound). The
  `cpufreq` counters say why: the big cluster (cpu6/7, 2400 MHz max) is at its **960 MHz floor**
  when a swipe begins and takes several frames to ramp. The same frame's draw is 9.3 ms at
  960 MHz and 3.0 ms at 2400 MHz (iter 9, swipe 1, frames 1 and 4). The op counts do not
  change — ~36 `FillRectOp`, ~7 `AtlasTextOp`, 6 `Clear` a frame — only the clock does. That
  is DVFS on a retail phone, and Macrobenchmark cannot lock clocks without root
  (`cpuLocked: false` in the JSON). Every app on this device pays it on the first frames of a
  touch.
- **Reading the `scroll` row honestly:** the acceptance criterion's "zero janky frames" is met
  as far as the app's own work goes — the frame timeline's app-attributable misses are the DVFS
  ramp — but the `frameOverrunMs P99 ≤ 0` encoding of it cannot be met on a 120 Hz device
  whose governor idles the big cores between gestures, because one ramp-up miss stuffs the
  queue for the rest of the fling. The number to watch for regressions on this device is the
  `App Deadline Missed` count from the trace (5–7 per ~600-frame run, all at swipe start) and
  the per-row composition cost, not the P99.
- Not changed, worth knowing: `:benchmark` measures the `benchmark` build type, which is
  release-like but **unminified** (documented in `app/build.gradle.kts` — the profile
  generator needs original names). Every number in this file is therefore a ceiling over what
  `release` ships; R8 inlining takes a real slice off Compose's per-node cost. Splitting profile
  generation (unminified) from measurement (minified) is the standard shape and would tighten
  these figures, but it is plumbing across `:benchmark`'s variant matching and the Makefile.

### Other rows

- `overviewRender` passes on the metric that gates it (`frameDurationCpuMs` P99 103–114 ms,
  budget < 200 ms) but its max single-frame sample is 121–166 ms across the four runs — worth
  watching if this regresses further, since the ceiling is close.
- `chartRender` P99 has ranged 46.7–59.8 ms across the four runs with no pattern. Each
  iteration produces only three or four frames, so its P99 is one frame's worth of noise; read
  it against the budget, not against the previous run.
- The `startupNoCompilation`/`startupWithBaselineProfile` pair came from the same
  `make benchmark` invocation as everything else above, run right after `make baseline-profile`
  produced the committed profile.
