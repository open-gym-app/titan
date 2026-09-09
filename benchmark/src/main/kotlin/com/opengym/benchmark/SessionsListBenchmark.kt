package com.opengym.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Sessions tab at 1,000 sessions: the first page, and a fling through it.
 *
 * ### How a frame metric answers "renders in under 100 ms"
 *
 * [FrameTimingMetric] reports the duration of every frame produced inside the measure block.
 * Because the block below is exactly "press the tab, wait for rows", the frames it captures
 * are the ones that composed, measured, laid out and drew the first page — so
 * `frameDurationCpuMs` at its maximum *is* the first page's render time, and the budget in the
 * acceptance criterion is read off that percentile.
 *
 * The alternative was a trace section around the list in production code. This needs none:
 * the interaction is the boundary.
 *
 * ### Why each iteration restarts the app
 *
 * A tab already visited draws from a warm ViewModel, a warm Paging source and a warm page
 * cache, and the first iteration would be the only one measuring what a user sees when they
 * open the app and go looking for last Tuesday.
 */
@RunWith(AndroidJUnit4::class)
class SessionsListBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Opening the tab: the twenty rows of the newest month, drawn over a three-year history.
     *
     * Budget: `frameDurationCpuMs` max under 100 ms.
     */
    @Test
    fun firstPage() =
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
                tabTag = TargetApp.SESSIONS_TAB,
                contentTag = TargetApp.SESSIONS_HISTORY_LIST,
                description = "the sessions history",
            )
            // The list exists as soon as the tab is composed; a row is what says it has *drawn*.
            device.awaitObject(
                By.res(TargetApp.SESSIONS_HISTORY_LIST).hasChild(By.clickable(true)),
                "a session row",
            )
        }

    /**
     * Thirty rows under a thumb.
     *
     * Budget: zero janky frames, read off `frameOverrunMs` — the platform's own "how late was
     * this frame against its deadline", which is the figure that means jank on a device whose
     * refresh rate the benchmark does not get to choose. A P99 at or below zero is a fling
     * that never missed.
     */
    @Test
    fun scroll() =
        benchmarkRule.measureRepeated(
            packageName = TargetApp.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = ITERATIONS,
            setupBlock = {
                seedTargetHistory()
                restartOnHome()
                device.selectTab(
                    tabTag = TargetApp.SESSIONS_TAB,
                    contentTag = TargetApp.SESSIONS_HISTORY_LIST,
                    description = "the sessions history",
                )
            },
        ) {
            val list = device.awaitTag(TargetApp.SESSIONS_HISTORY_LIST, "the sessions history")
            device.flingThrough(list, items = SCROLLED_ITEMS)
        }

    private companion object {
        const val ITERATIONS = 10

        /** The fling length the acceptance criterion names. */
        const val SCROLLED_ITEMS = 30
    }
}
