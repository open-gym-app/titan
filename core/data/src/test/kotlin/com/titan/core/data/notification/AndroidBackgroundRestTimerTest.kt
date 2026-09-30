package com.titan.core.data.notification

import android.app.AlarmManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.notification.NotificationCommand
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationOutcome
import com.titan.core.domain.notification.NotificationTarget
import com.titan.core.domain.timer.BackgroundRest
import com.titan.core.domain.timer.RestTimerSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * The rest as the operating system keeps it: the note, the alarm, and the rule that stops the
 * two of them announcing the same expiry twice.
 *
 * The alarm is asserted against Robolectric's own `AlarmManager`, so "an alarm is set for the
 * deadline" is a fact about the scheduler rather than about a mock having been called. That
 * matters most for the cancellations: the acceptance criterion is that a skipped or paused rest
 * leaves *nothing* pending, and only the scheduler can be asked whether anything is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBackgroundRestTimerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val notifier = RecordingNotifier()
    private val foreground = FakeForegroundState()

    private val restTimer = AndroidBackgroundRestTimer(context, notifier, foreground)

    // --- a rest in flight ---------------------------------------------------

    @Test
    fun `a running rest posts a countdown and sets an alarm for its deadline`() =
        runTest {
            restTimer.set(running(DEADLINE))

            val note = notifier.showing
            assertNotNull(note)
            assertEquals(NotificationKind.REST_TIMER, note!!.kind)
            // The chronometer is the whole design: posted once, ticked by the platform. A body
            // holding "1:29" would have to be re-posted every second.
            assertEquals(DEADLINE, note.countdownTo)
            assertTrue(note.ongoing)
            assertEquals(DEADLINE, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs)
        }

    @Test
    fun `the countdown says what the lifter comes back to`() =
        runTest {
            restTimer.set(running(DEADLINE))

            // The point of the notification: a glance from the lock screen that says what to do
            // next, without unlocking.
            assertEquals("Bench press · set 3", notifier.showing?.body)
        }

    @Test
    fun `an exercise deleted from the library still gets a set number`() =
        runTest {
            restTimer.set(running(DEADLINE).copy(exerciseName = null))

            assertEquals("Set 3", notifier.showing?.body)
        }

    @Test
    fun `the countdown carries a skip button and a way back into the session`() =
        runTest {
            restTimer.set(running(DEADLINE))

            val note = notifier.showing!!
            assertEquals("titan://session/active/7", note.deepLink)
            assertEquals(
                listOf(NotificationTarget.Command(NotificationCommand.SkipRest(SESSION_ID))),
                note.actions.map { it.target },
            )
        }

    @Test
    fun `the countdown is posted quietly, however loud its channel is`() =
        runTest {
            // It is re-posted every time the rest is adjusted. A channel built to be heard
            // across a gym would announce each of those.
            restTimer.set(running(DEADLINE))

            assertTrue(notifier.showing!!.silent)
        }

    // --- pausing ------------------------------------------------------------

    @Test
    fun `pausing takes the alarm back and leaves the note`() =
        runTest {
            restTimer.set(running(DEADLINE))

            restTimer.set(paused())

            // The worst thing this class can do is announce the end of a rest the user stopped.
            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
            assertTrue(notifier.showing!!.ongoing)
            // No chronometer: what is left does not move, so there is nothing to count to.
            assertNull(notifier.showing!!.countdownTo)
        }

    @Test
    fun `resuming sets the alarm again`() =
        runTest {
            restTimer.set(running(DEADLINE))
            restTimer.set(paused())

            restTimer.set(running(LATER_DEADLINE))

            assertEquals(LATER_DEADLINE, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs)
        }

    @Test
    fun `adjusting a running rest moves the alarm rather than adding one`() =
        runTest {
            restTimer.set(running(DEADLINE))

            restTimer.set(running(LATER_DEADLINE))

            // One pending intent, updated. Two alarms for one rest is two alerts.
            assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
            assertEquals(LATER_DEADLINE, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtMs)
        }

    // --- ending it ----------------------------------------------------------

    @Test
    fun `no rest at all takes down both`() =
        runTest {
            restTimer.set(running(DEADLINE))

            restTimer.set(null)

            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
            assertEquals(RecordingNotifier.Event.Cancelled(NotificationKind.REST_TIMER), notifier.log.last())
        }

    @Test
    fun `an idle rest is the same as no rest`() =
        runTest {
            restTimer.set(running(DEADLINE))

            restTimer.set(running(DEADLINE).copy(rest = RestTimerSnapshot.Idle))

            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
            assertEquals(RecordingNotifier.Event.Cancelled(NotificationKind.REST_TIMER), notifier.log.last())
        }

    @Test
    fun `an expiry with the app away is announced`() =
        runTest {
            foreground.isInForeground = false
            restTimer.set(running(DEADLINE))

            restTimer.set(expired())

            val note = notifier.showing!!
            assertEquals("Rest over", note.title)
            // Not ongoing and not silent: this is the announcement the countdown was building
            // towards, and it is the one post that is allowed to make a noise.
            assertFalse(note.ongoing)
            assertFalse(note.silent)
            // And nothing left pending — a rest shortened past zero finishes early and would
            // otherwise leave an alarm for the deadline it would have had.
            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
        }

    @Test
    fun `an expiry with the app in front of the user is not announced`() =
        runTest {
            foreground.isInForeground = true
            restTimer.set(running(DEADLINE))

            restTimer.set(expired())

            // `RestExpiryAlert` is ringing on the screen they are holding. A notification beside
            // it is the second half of a double alert.
            assertEquals(RecordingNotifier.Event.Cancelled(NotificationKind.REST_TIMER), notifier.log.last())
            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
        }

    @Test
    fun `the same expiry arriving twice is announced once`() =
        runTest {
            // The headline case, not a theoretical one: a phone in Doze does not run the
            // coroutine `delay` behind the countdown, so the alarm fires on time and the
            // in-process timer catches up when the device wakes. Two posts to one id alert
            // twice.
            foreground.isInForeground = false
            restTimer.set(running(DEADLINE))

            restTimer.set(expired())
            restTimer.set(expired())

            assertEquals(1, notifier.posted.count { it.title == "Rest over" })
        }

    @Test
    fun `the next rest can be announced, even where it looks the same`() =
        runTest {
            foreground.isInForeground = false
            restTimer.set(running(DEADLINE))
            restTimer.set(expired())

            // A set deleted and redone lands on the same exercise and the same number. It is a
            // different rest, and the lifter is waiting on it.
            restTimer.set(running(LATER_DEADLINE))
            restTimer.set(expired())

            assertEquals(2, notifier.posted.count { it.title == "Rest over" })
        }

    @Test
    fun `an expiry taken down in the foreground can be announced again later`() =
        runTest {
            foreground.isInForeground = true
            restTimer.set(running(DEADLINE))
            restTimer.set(expired())

            // The user puts the phone down between the two, which is exactly what the second
            // half of a superset looks like.
            foreground.isInForeground = false
            restTimer.set(expired())

            assertEquals(1, notifier.posted.count { it.title == "Rest over" })
        }

    @Test
    fun `a silenced phone is told, quietly`() =
        runTest {
            foreground.isInForeground = false
            context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_SILENT

            restTimer.set(expired())

            // The same rule the in-app alert follows, out of the same function — see
            // `RestAlertStyle`. The note still appears; it just makes no noise.
            assertTrue(notifier.showing!!.silent)
        }

    @Test
    fun `a phone on vibrate is allowed to make itself felt`() =
        runTest {
            foreground.isInForeground = false
            context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE

            restTimer.set(expired())

            assertFalse(notifier.showing!!.silent)
        }

    // --- the preference gate ------------------------------------------------

    @Test
    fun `a rest the user has silenced schedules nothing`() =
        runTest {
            // `Notifier` is the single place that knows whether the preference is on or the
            // permission granted. Waking the device for an announcement it would drop is the
            // one combination with no value at all.
            notifier.outcome = NotificationOutcome.SUPPRESSED_BY_PREFERENCE

            restTimer.set(running(DEADLINE))

            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun `a preference turned off mid-rest takes the pending alarm with it`() =
        runTest {
            restTimer.set(running(DEADLINE))

            notifier.outcome = NotificationOutcome.SUPPRESSED_BY_PERMISSION
            restTimer.set(running(LATER_DEADLINE))

            assertNull(shadowOf(alarms).peekNextScheduledAlarm())
        }

    private fun running(deadline: Long) =
        BackgroundRest(
            sessionId = SESSION_ID,
            exerciseName = "Bench press",
            setNumber = 3,
            rest = RestTimerSnapshot.Running(deadline = deadline, total = 90.seconds),
        )

    private fun paused() = running(DEADLINE).copy(rest = RestTimerSnapshot.Paused(45.seconds, 90.seconds))

    private fun expired() = running(DEADLINE).copy(rest = RestTimerSnapshot.Finished)

    /** The process lifecycle, under the test's control. */
    private class FakeForegroundState(var isInForeground: Boolean = false) : AppForegroundState {
        override suspend fun isInForeground(): Boolean = isInForeground
    }

    private companion object {
        const val SESSION_ID = 7L
        const val DEADLINE = 1_700_000_090_000L
        const val LATER_DEADLINE = 1_700_000_105_000L
    }
}
