package com.titan.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to first frame, twice: with nothing precompiled, and with the committed Baseline
 * Profile.
 *
 * The pair is the point. A single startup figure says how fast this phone is; the difference
 * between these two says what the profile in `app/src/main/baselineProfiles/` is worth, which
 * is the only way to keep a committed profile honest — a stale one that has stopped helping
 * looks exactly like a fresh one until somebody measures both.
 *
 * `timeToInitialDisplay` is the number the acceptance criterion means by "cold start to first
 * frame": the app process starting to the first frame the compositor puts on the glass.
 * `timeToFullDisplay` is also reported and is the more honest figure for a user, but the app
 * does not call `reportFullyDrawn`, so nothing distinguishes the two here yet.
 *
 * ### Running these the first time
 *
 * [startupWithBaselineProfile] fails until a profile exists, by design — `BaselineProfileMode
 * .Require` refuses to quietly measure an unprofiled build and report it as a profiled one. On
 * a checkout with no profile committed yet, run [startupNoCompilation] and
 * `BaselineProfileGenerator` first; see `benchmark/README.md`.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** The floor: a fresh install on a phone that has never run the app. */
    @Test
    fun startupNoCompilation() = measureStartup(CompilationMode.None())

    /** The same launch with the committed profile installed — what a real first run gets. */
    @Test
    fun startupWithBaselineProfile() =
        measureStartup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun measureStartup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = TargetApp.PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = {
                // Startup is measured against the history the app will actually open with.
                // An empty database would make this the startup of a fresh install, which is
                // the one case #42 is not about.
                seedTargetHistory()
                pressHome()
            },
        ) {
            startActivityAndWait()
        }

    private companion object {
        /**
         * Ten launches per compilation mode.
         *
         * Startup is the noisiest thing measured here — it competes with whatever else the
         * phone decided to do when the screen came on — so it gets more iterations than the
         * render benchmarks, which are quiet by comparison.
         */
        const val ITERATIONS = 10
    }
}
