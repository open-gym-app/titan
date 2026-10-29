# `:benchmark` — on-device performance

What a user with three years of training actually waits for, measured on a phone.

The JVM already guards the *queries*: `LargeHistoryBenchmarkTest` seeds 1,000 sessions and
50,000 sets into in-memory SQLite and holds every read to a budget, on every CI run. What it
cannot see is the half of the wait that is Android — process start, class loading, composition,
layout, and the frames the compositor actually gets. That is this module.

Both halves seed from the same fixture. `core/data/src/fixtures/` is one source directory
compiled into two places: the JVM unit tests, and `:core:data`'s `benchmark` variant. So
`HistoryScales.TARGET` means the same 1,000 sessions in CI and on the phone.

## What is measured, and against what

| Benchmark | Metric | Budget |
| --- | --- | --- |
| `StartupBenchmark.startupNoCompilation` | `timeToInitialDisplay` | recorded, not bounded — it is the baseline the next row is read against |
| `StartupBenchmark.startupWithBaselineProfile` | `timeToInitialDisplay` | must beat the row above |
| `SessionsListBenchmark.firstPage` | `frameDurationCpuMs` max | < 100 ms |
| `SessionsListBenchmark.scroll` | `frameOverrunMs` P99 | ≤ 0 ms — no frame missed its deadline over a 30-row fling |
| `StatsBenchmark.overviewRender` | `frameDurationCpuMs` max | < 200 ms |
| `ExerciseProgressionBenchmark.chartRender` | `frameDurationCpuMs` max | < 200 ms |

There is no assertion in the code for any of these. Macrobenchmark reports numbers; a budget is
a thing a human reads them against, and one encoded as an assertion would fail on a slower phone
for a reason that is not a regression. `baselines/` is where a run is written down so the next
one is a diff.

The `scroll` budget needs the trace beside it on a 120 Hz phone. `frameOverrunMs` counts a frame
as late whether the app was slow or merely a buffer ahead of SurfaceFlinger, and at 8.3 ms a
frame the second state follows the first for the rest of the fling. Read the
`actual_frame_timeline_slice` jank types in the Perfetto trace: `App Deadline Missed` is the
app, `Buffer Stuffing` is the consequence. `baselines/samsung-sm-a536b-api36.md` works through
one such run.

### Why frame timings answer "renders in under 100 ms"

Each measure block is exactly one interaction — press the tab, wait for the rows — so every
frame captured inside it belongs to that render. `frameDurationCpuMs` at its maximum is
therefore the frame that composed, measured, laid out and drew the first page. No trace section
in production code, and no stopwatch that would also be timing UiAutomator.

## Running it

Needs a **physical device**, API 29 or newer, connected over adb. An emulator will run these
and report numbers that mean nothing: it shares a CPU with the machine building the APK.

```bash
make benchmark          # ./gradlew :benchmark:connectedBenchmarkAndroidTest
```

The first run of any benchmark seeds the database, which writes 50,000 rows through the DAO
layer and takes about a minute. Every run after that finds the history already there and starts
immediately — see `BenchmarkHistorySeeder`.

Results are printed to the console and written to
`benchmark/build/outputs/connected_android_test_additional_output/`.

### One test at a time

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.titan.benchmark.SessionsListBenchmark
```

### If a run fails before it measures anything

- **"The app did not answer on content://app.titan.gym.benchmark.seed"** — the installed app
  is not the `benchmark` build type. `./gradlew :app:installBenchmark`, or just run the Gradle
  task above, which installs both APKs.
- **"the sessions tab did not appear"** — the app opened on onboarding. The seeder marks
  onboarding complete, so this means the seeding call did not reach the app at all.
- **Locked or dark screen** — Macrobenchmark needs the device awake and unlocked.

## The Baseline Profile

`app/src/main/baselineProfiles/baseline-prof.txt` is a list of the classes and methods ART
should compile *before* they are first needed. Without it the first launch of an install runs
the whole of startup interpreted. It is the largest cheap win available to this app's cold
start, which is why #42 asks for one rather than for more optimisation.

```bash
make baseline-profile
```

That runs `BaselineProfileGenerator` and pulls the result into `app/src/main/baselineProfiles/`.
Commit the file — a profile is reviewed input, like a screenshot reference, and a diff in it is
how a change to the startup path becomes visible in a pull request.

`androidx.baselineprofile`, the Gradle plugin that would automate the copy, is deliberately not
applied. It creates its own `nonMinifiedRelease` and `benchmarkRelease` build types across every
existing build type, which this project already has three of, and it renames the task the
acceptance criterion names. The two variants it would create by hand are one build type here —
see `app/build.gradle.kts` for why that one has minification off.

**Order matters on a fresh checkout.** `StartupBenchmark.startupWithBaselineProfile` uses
`BaselineProfileMode.Require` and fails outright when there is no profile in the APK, rather
than quietly measuring an unprofiled build and reporting it as a profiled one. So: generate the
profile, then measure both startups.

## Recording a result

Every run that is worth keeping goes in `baselines/`, named for the device it was recorded on.
See [`baselines/README.md`](baselines/README.md) for the format and for what a run has to say
about the phone it happened on.

## The recomposition sweep

#42 also asks that no screen recompose a list item more than once per data change. That was
checked with the Compose compiler's own stability report rather than with Layout Inspector —
same question, an answer that covers every composable in the module rather than the three
screens somebody thought to open, and one a later change can be re-checked against:

```bash
./gradlew :feature:progression:compileBenchmarkKotlin -Ptitan.composeReports
less feature/progression/build/compose-reports/progression-composables.txt
```

What it found, and what was done about it:

- **The lambdas were not the problem.** Every callback into a list item already reports as
  `stable` — `NavHost` builds its graph inside a `remember`, so the navigation lambdas
  `:app` hands down have one identity for the life of the composition, and the per-item
  `onClick = { onSessionClick(session.id) }` closures capture only stable values and are
  memoised by the compiler.
- **The domain models were.** `:core:domain` is a plain Kotlin library with no Compose
  dependency — that is the point of it — so its models carry no stability metadata and every
  one of them arrived at a `@Composable` marked `unstable`: `Exercise`, `PersonalRecord`,
  `ExerciseProgression`, `RestTimerState`, and the `List<…>`s of them. Under strong skipping an
  unstable parameter is compared by *instance*, so any of those items recomposed whenever the
  ViewModel above it rebuilt an equal-but-new object — which a screen combining several flows
  does on every emission from any one of them.
- **The fix is `config/compose/stability.conf`**, not annotations: it tells the compiler that
  Kotlin's read-only collection interfaces and everything in `:core:domain` may be treated as
  values, which keeps `androidx.compose.runtime` out of the dependency-free centre. Afterwards
  no composable in `:core:ui` or any feature takes an unstable parameter other than a
  `ViewModel` — which is a `Route`'s own, taken once from `hiltViewModel()`.

The one thing the report proves is worth re-reading: `List<Path>` in `BodyGeometry` stayed
unstable even with `kotlin.collections.List` declared stable, because `Path` genuinely is
mutable. The configuration propagates through type arguments rather than blanketing them, which
is what makes it safe.
