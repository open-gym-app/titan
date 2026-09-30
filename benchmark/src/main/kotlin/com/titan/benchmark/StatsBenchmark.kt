package com.titan.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Stats hub, drawn over three years of history.
 *
 * The hub is the heaviest read in the app: the volume hero, the summary grid and the muscle
 * map are three aggregates over the whole selected window, and the muscle map's is the one
 * `LargeHistoryBenchmarkTest` gives its own budget because the join fans 50,000 sets out
 * across `exercise_muscle_group` before it counts anything.
 *
 * That test measures the *query* on a JVM. This measures what the user waits for: the tap, the
 * queries, and the frame the hero lands on.
 *
 * The measure block ends on the hero rather than on the screen's own root, and the difference
 * matters — the root composes while the reads are still in flight and draws a selector over an
 * empty column. Waiting for the hero is waiting for an answer.
 */
@RunWith(AndroidJUnit4::class)
class StatsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun overviewRender() =
        benchmarkRule.measureRepeated(
            packageName = TargetApp.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = ITERATIONS,
            setupBlock = {
                seedTargetHistory()
                restartOnHome()
            },
        ) {
            device.selectTab(
                tabTag = TargetApp.STATS_TAB,
                contentTag = TargetApp.STATS_OVERVIEW,
                description = "the stats hub",
            )
            device.awaitTag(TargetApp.STATS_VOLUME_HERO, "the volume hero")
        }

    private companion object {
        const val ITERATIONS = 10
    }
}
