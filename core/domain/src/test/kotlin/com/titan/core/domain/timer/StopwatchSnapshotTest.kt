package com.titan.core.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The hold stopwatch's persisted form (#M7-17): a start instant, read back as an elapsed time
 * against whatever the clock says when the draft is loaded.
 */
class StopwatchSnapshotTest {

    @Test
    fun `the elapsed time is measured from the start, so a draft read later reads later`() {
        val snapshot = StopwatchSnapshot(sessionExerciseId = 1, startedAt = 10_000)

        assertEquals(Duration.ZERO, snapshot.elapsedAt(10_000))
        assertEquals(47.seconds, snapshot.elapsedAt(57_000))
        // A process dead for ten minutes mid-plank comes back to a plank ten minutes long — the
        // figure the lifter types over, rather than one that quietly restarted at zero.
        assertEquals(647.seconds, snapshot.elapsedAt(657_000))
    }

    @Test
    fun `negative - a clock stepped backwards reads as a hold just started, not a negative one`() {
        val snapshot = StopwatchSnapshot(sessionExerciseId = 1, startedAt = 10_000)

        assertEquals(Duration.ZERO, snapshot.elapsedAt(4_000))
    }
}
