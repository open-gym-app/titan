package com.titan.feature.progression.stats

import androidx.lifecycle.SavedStateHandle
import com.titan.feature.progression.afterProcessDeath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared selection's own rules, tested without a screen around them.
 *
 * Every progression ViewModel hands this its own handle, so what matters here is what happens
 * when *several* of them do — which is the case a single ViewModel's test cannot reach.
 */
class ProgressionTimeframeTest {

    @Test
    fun `a handle holding a selection puts it back`() =
        runTest {
            val handle = savedStateHolding(StatsTimeframe.YEAR)

            // A new process, so a new holder on its default: the handle is all that crossed.
            val restored = ProgressionTimeframe()
            restored.restoreThrough(handle, mirror())

            assertEquals(StatsTimeframe.YEAR, restored.selected.value)
        }

    @Test
    fun `a screen rebuilt after a later choice does not walk it back`() =
        runTest {
            // Two screens, each with its own handle, both filed away holding what was selected
            // when the process died — the drill-down on top, the hub underneath it.
            val hub = savedStateHolding(StatsTimeframe.YEAR)
            val drillDown = savedStateHolding(StatsTimeframe.YEAR)

            val timeframe = ProgressionTimeframe()
            // The restore lands on the drill-down, and the user moves the segments there.
            timeframe.restoreThrough(drillDown, mirror())
            timeframe.select(StatsTimeframe.QUARTER)

            // Then they press back, and the hub is built for the first time in this process.
            timeframe.restoreThrough(hub, mirror())

            assertEquals(StatsTimeframe.QUARTER, timeframe.selected.value)
        }

    /**
     * A handle as a screen that had [timeframe] selected leaves it behind.
     *
     * Written by a holder of its own rather than by a literal key, so the name the mirror uses
     * stays [ProgressionTimeframe]'s business.
     */
    private fun TestScope.savedStateHolding(timeframe: StatsTimeframe): SavedStateHandle {
        val handle = SavedStateHandle()
        ProgressionTimeframe().apply {
            restoreThrough(handle, mirror())
            select(timeframe)
        }
        return handle.afterProcessDeath()
    }

    /** A scope for the mirror, unconfined so the write into the handle has already happened. */
    private fun TestScope.mirror() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
}
