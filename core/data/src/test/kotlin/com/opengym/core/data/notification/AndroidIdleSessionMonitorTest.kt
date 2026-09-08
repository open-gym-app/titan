package com.opengym.core.data.notification

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.session.IdleSessionMonitor
import com.opengym.core.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

/**
 * When the idle nudge is pointed at a session, and — more importantly — when it is not.
 *
 * The alarm is asserted against Robolectric's own `AlarmManager`, because every claim here is
 * about what is *pending*: "fires once per idle period" is not a fact about a notification, it
 * is a fact about there being no second wake-up scheduled, and only the scheduler can be asked.
 *
 * `SessionRepository` is mocked rather than hand-written. It has twenty-two members and this
 * class reads two of them; a fake would be twenty stubs saying `error("unused")` around the
 * two lines that matter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidIdleSessionMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val notifier = RecordingNotifier()
    private val sessions = mockk<SessionRepository>()
    private var now = STARTED_AT + 5.minutes.inWholeMilliseconds

    private val monitor = AndroidIdleSessionMonitor(
        context = context,
        sessionRepository = sessions,
        notifier = notifier,
        clock = Clock { now },
    )

    // --- arming -------------------------------------------------------------

    @Test
    fun `the deadline is thirty minutes after the last completed set`() =
        runTest {
            // Not thirty minutes after the session started: a workout open for two hours with
            // sets logged throughout is not abandoned.
            val lastSetAt = STARTED_AT + 4.minutes.inWholeMilliseconds
            open(sets = listOf(completedSet(at = STARTED_AT), completedSet(at = lastSetAt)))

            monitor.arm()

            assertEquals(lastSetAt + THRESHOLD, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs)
        }

    @Test
    fun `a session with nothing logged is measured from when it started`() =
        runTest {
            // The case the nudge matters most for: a workout opened by accident has no set to
            // measure from, and would otherwise stay open forever with nothing to measure.
            open(sets = emptyList())

            monitor.arm()

            assertEquals(STARTED_AT + THRESHOLD, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs)
        }

    @Test
    fun `a set that was logged but never ticked does not count as activity`() =
        runTest {
            // "Time since the last *completed* set" — an un-ticked row is a correction to the
            // table, not a set somebody performed.
            open(sets = listOf(completedSet(at = STARTED_AT + 20.minutes.inWholeMilliseconds, done = false)))

            monitor.arm()

            assertEquals(STARTED_AT + THRESHOLD, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs)
        }

    @Test
    fun `re-arming replaces the alarm rather than adding one`() =
        runTest {
            open(sets = listOf(completedSet(at = STARTED_AT)))
            monitor.arm()

            open(sets = listOf(completedSet(at = STARTED_AT + 4.minutes.inWholeMilliseconds)))
            monitor.arm()

            // Two alarms for one session is two nudges.
            assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
            assertEquals(
                STARTED_AT + 4.minutes.inWholeMilliseconds + THRESHOLD,
                shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs,
            )
        }

    @Test
    fun `arming clears whatever was already said about the session`() =
        runTest {
            // Either a set has been logged, which makes a nudge saying nothing has happened
            // false, or the user has left a screen that already cleared it.
            open(sets = listOf(completedSet(at = STARTED_AT)))

            monitor.arm()

            assertEquals(
                RecordingNotifier.Event.Cancelled(NotificationKind.SESSION),
                notifier.log.single(),
            )
        }

    // --- once per idle period -----------------------------------------------

    @Test
    fun `a deadline that has already passed schedules nothing`() =
        runTest {
            // The whole of "fires once": after the nudge has been said the deadline stays
            // behind us, so coming back to the app and leaving again arms nothing. It re-arms
            // only when a set moves the deadline forward.
            open(sets = listOf(completedSet(at = STARTED_AT)))
            now = STARTED_AT + THRESHOLD + 1

            monitor.arm()

            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
        }

    @Test
    fun `a set logged after the nudge arms it again`() =
        runTest {
            open(sets = listOf(completedSet(at = STARTED_AT)))
            now = STARTED_AT + THRESHOLD + 1
            monitor.arm()

            val resumedAt = now + 1.minutes.inWholeMilliseconds
            open(sets = listOf(completedSet(at = STARTED_AT), completedSet(at = resumedAt)))
            monitor.arm()

            assertEquals(resumedAt + THRESHOLD, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs)
        }

    // --- nothing to nudge about ---------------------------------------------

    @Test
    fun `no session in progress takes the alarm down`() =
        runTest {
            open(sets = listOf(completedSet(at = STARTED_AT)))
            monitor.arm()

            every { sessions.getActive() } returns flowOf(null)
            monitor.arm()

            // Whatever was pending belonged to a workout that is over.
            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
        }

    @Test
    fun `disarming takes down both the alarm and the notification`() =
        runTest {
            open(sets = listOf(completedSet(at = STARTED_AT)))
            monitor.arm()

            monitor.disarm()

            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
            assertEquals(
                RecordingNotifier.Event.Cancelled(NotificationKind.SESSION),
                notifier.log.last(),
            )
        }

    /** Puts a session in progress, holding [sets]. */
    private fun open(sets: List<WorkoutSet>) {
        every { sessions.getActive() } returns flowOf(SESSION)
        every { sessions.getSetsForSession(SESSION_ID) } returns flowOf(sets)
    }

    private fun completedSet(
        at: Long,
        done: Boolean = true,
    ) = WorkoutSet(
        sessionExerciseId = 1L,
        setNumber = 1,
        completedAt = at,
        isCompleted = done,
        createdAt = at,
        updatedAt = at,
    )

    private companion object {
        const val SESSION_ID = 7L
        const val STARTED_AT = 1_700_000_000_000L
        val THRESHOLD = IdleSessionMonitor.IDLE_THRESHOLD.inWholeMilliseconds

        val SESSION = WorkoutSession(
            id = SESSION_ID,
            name = "Push day",
            lastActiveAt = STARTED_AT,
            createdAt = STARTED_AT,
            updatedAt = STARTED_AT,
        )
    }
}
