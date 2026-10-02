package com.titan.core.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private const val NOW: Long = 1_700_000_000_000L

/**
 * What may be handed to a [BackgroundRestTimer], and what may not.
 *
 * The implementation of the interface is `:core:data`'s and is tested there against a real
 * `AlarmManager`. What is worth pinning here is the shape of what it is handed: a rest that
 * carries the same [RestTimerSnapshot] the draft is written from, and two invariants that are
 * cheap to break from a caller and expensive to notice afterwards — a tap on a notification
 * has to land on a session row, and the set number it names is the one the lifter reads off
 * the lock screen.
 */
class BackgroundRestTest {

    @Test
    fun `a rest carries the snapshot the draft is written from, not a second copy of it`() {
        val rest = RestTimerSnapshot.of(RestTimerState.Running(45.seconds, 90.seconds), NOW)

        val background = BackgroundRest(
            sessionId = 7,
            exerciseName = "Bench press",
            setNumber = 2,
            rest = rest,
        )

        // The same value, so the alarm and the draft cannot disagree about when the rest ends.
        assertEquals(rest, background.rest)
        assertEquals(7L, background.sessionId)
        assertEquals("Bench press", background.exerciseName)
        assertEquals(2, background.setNumber)
    }

    @Test
    fun `an exercise deleted since the session started leaves the name out`() {
        // The workout carries on past a deleted catalogue row, so the note has to as well:
        // null names nothing rather than naming nothing.
        val background = BackgroundRest(
            sessionId = 7,
            exerciseName = null,
            setNumber = 3,
            rest = RestTimerSnapshot.Idle,
        )

        assertNull(background.exerciseName)
    }

    @Test
    fun `a rest with no session behind it is refused`() {
        // A tap on the notification opens `titan://session/<id>`. An id no row can have is
        // a notification that leads nowhere, and it is better refused where it is built.
        listOf(0L, -1L).forEach { sessionId ->
            assertThrows(IllegalArgumentException::class.java) {
                BackgroundRest(sessionId, "Bench press", setNumber = 1, rest = RestTimerSnapshot.Idle)
            }
        }
    }

    @Test
    fun `sets are numbered from one, as the lifter reads them`() {
        listOf(0, -1).forEach { setNumber ->
            assertThrows(IllegalArgumentException::class.java) {
                BackgroundRest(
                    sessionId = 7,
                    exerciseName = "Bench press",
                    setNumber = setNumber,
                    rest = RestTimerSnapshot.Idle,
                )
            }
        }
    }
}
