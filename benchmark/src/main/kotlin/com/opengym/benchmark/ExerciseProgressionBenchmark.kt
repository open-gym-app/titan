package com.opengym.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One lift's progression chart, over every session it appears in.
 *
 * The seeded catalogue puts each exercise in one session out of twenty (see
 * `WorkoutHistoryFixture.exerciseSlotOf`), so at target scale a lift has a few hundred
 * completed sets behind it — several hundred marks to project, bucket and plot, which is what
 * the 200 ms budget is set against.
 *
 * ### The chooser is setup, not measurement
 *
 * Reaching the screen means the Stats tab, an explore row and then a list of lifts. Only the
 * last step draws a chart, so everything before it happens in [setupBlock] and the measured
 * block is one tap and the render it causes. A measure block that also navigated would report
 * the navigation.
 */
@RunWith(AndroidJUnit4::class)
class ExerciseProgressionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun chartRender() =
        benchmarkRule.measureRepeated(
            packageName = TargetApp.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = ITERATIONS,
            setupBlock = {
                seedTargetHistory()
                restartOnHome()
                openExerciseChooser()
            },
        ) {
            // A lift by the name the fixture gave it. The one text match in this module, and the
            // safe kind: it is a row the fixture wrote, not a string resource that changes with
            // the phone's language. See `TargetApp.SEEDED_EXERCISE_PREFIX`.
            //
            // Searched *inside the chooser*, not the window: on an English phone the screen's
            // own bar title, "Exercise progression", starts with the same prefix and sits above
            // the list, and a window-wide search would tap it instead of a row.
            device.awaitTag(TargetApp.EXERCISE_CHOOSER, "the exercise chooser")
                .awaitDescendant(
                    By.textStartsWith(TargetApp.SEEDED_EXERCISE_PREFIX),
                    "a seeded exercise in the chooser",
                )
                .click()

            device.awaitTag(TargetApp.EXERCISE_PROGRESSION_CHART, "the progression chart")
        }

    /** Stats tab, explore row, and stop at the list of lifts. */
    private fun MacrobenchmarkScope.openExerciseChooser() {
        device.selectTab(
            tabTag = TargetApp.STATS_TAB,
            contentTag = TargetApp.STATS_OVERVIEW,
            description = "the stats hub",
        )
        // The explore rows are below the fold on a phone: the hub is a scrolling column with
        // the hero, the grid and the muscle map above them.
        device.awaitTag(TargetApp.STATS_OVERVIEW, "the stats hub")
            .scrollUntil(Direction.DOWN, Until.findObject(By.res(TargetApp.STATS_EXPLORE_EXERCISE)))
        device.awaitTag(TargetApp.STATS_EXPLORE_EXERCISE, "the exercise progression row").click()
        device.awaitTag(TargetApp.EXERCISE_CHOOSER, "the exercise chooser")
    }

    private companion object {
        const val ITERATIONS = 10
    }
}
