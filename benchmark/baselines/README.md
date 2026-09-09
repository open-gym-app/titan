# Recorded results

One file per device, named `<manufacturer>-<model>-api<level>.md`. A benchmark number without
the phone it was measured on is not a number: the same build is three times faster on a flagship
than on the mid-range phone this app's budgets are set for, so a run that does not say which it
was cannot be compared with anything.

**A result here is a claim about a device, not about the app.** Two files disagreeing is the
expected state; the same file disagreeing with itself between two commits is the regression.

## What a file has to carry

- Device: manufacturer, model, Android version and API level.
- The app: the commit the `benchmark` APK was built from.
- Every benchmark that ran, with the metric named in
  [`../README.md`](../README.md#what-is-measured-and-against-what) and the budget beside it.
- Anything unusual about the run — a phone on charge, a phone at 20% battery, a first run after
  a reboot. Thermal state moves these numbers more than most code changes do.

## The template

```markdown
# Pixel 6a — Android 14 (API 34)

Recorded 2026-01-01 against `abc1234`, device idle, unplugged, screen at minimum brightness.
`./gradlew :benchmark:connectedBenchmarkAndroidTest`, 1,000 sessions / 50,000 sets.

| Benchmark | Metric | Median | P99 | Budget | |
| --- | --- | --- | --- | --- | --- |
| `startupNoCompilation` | `timeToInitialDisplay` | — | — | recorded | |
| `startupWithBaselineProfile` | `timeToInitialDisplay` | — | — | beats the row above | |
| `firstPage` | `frameDurationCpuMs` | — | — | < 100 ms | |
| `scroll` | `frameOverrunMs` | — | — | ≤ 0 ms | |
| `overviewRender` | `frameDurationCpuMs` | — | — | < 200 ms | |
| `chartRender` | `frameDurationCpuMs` | — | — | < 200 ms | |

**Baseline Profile:** startup improved from — ms to — ms (—%).

Notes on anything that missed its budget, and what was done about it.
```

## Nothing is recorded yet

The module, the fixtures, the seeding path and the profile generator are in place and both APKs
build, but no run has happened: the numbers need a physical mid-range phone on adb, and #42's
acceptance criteria are met only once a file lands here. Run `make benchmark`, then
`make baseline-profile`, then `make benchmark` again, and write down what came out.
