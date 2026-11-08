package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationTarget
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.time.Clock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant

/**
 * The reminder going off: what it says, where it leads, and the alarm it sets behind itself.
 *
 * ### The link is chosen at post time, and that is the acceptance criterion
 *
 * "Tapping a reminder during an in-progress session opens the active session" cannot be
 * answered when the alarm is *set*, up to a week earlier — the workout did not exist then. It
 * is answered here, from the store, which is also why the notification's words change with it:
 * somebody mid-session does not need telling to train.
 *
 * ### The re-arm is what keeps the feature alive
 *
 * Each alarm is a single wall-clock appointment, so a reminder that fired and did not schedule
 * the next one is a feature that worked once. Nothing else would report it — the switch stays
 * on and the schedule still reads back correctly — so it is asserted here rather than left to
 * the scheduler's own tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderBroadcastsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = RecordingNotifier()
    private val scheduler = RecordingReminderScheduler()

    // Mocked rather than hand-written, as `AndroidIdleSessionMonitorTest` does and for the same
    // reason: between them the two interfaces have thirty members and this class reads two.
    private val sessions = mockk<SessionRepository>()
    private val gamification = mockk<GamificationRepository>()

    private val broadcasts = ReminderBroadcasts(
        context = context,
        sessionRepository = sessions,
        gamificationRepository = gamification,
        notifier = notifier,
        scheduler = scheduler,
        clock = Clock { NOW },
    )

    // --- with nothing in progress -------------------------------------------

    @Test
    fun `a reminder falling due says it is time to train and links to the setup flow`() =
        runTest {
            given()

            broadcasts.handle(due())

            val note = notifier.showing!!
            assertEquals(NotificationKind.REMINDERS, note.kind)
            assertEquals("Time to train", note.title)
            assertEquals("titan://session/new", note.deepLink)
        }

    @Test
    fun `the button goes to the same screen the notification itself does`() =
        runTest {
            given()

            broadcasts.handle(due())

            assertEquals(
                listOf(NotificationTarget.Screen("titan://session/new")),
                notifier.showing!!.actions.map { it.target },
            )
        }

    @Test
    fun `an invitation is not something the user cannot dismiss`() =
        runTest {
            given()

            broadcasts.handle(due())

            // Ongoing is for a note about something the app is in the middle of. A reminder is
            // a question, and one the user is allowed to answer by ignoring it — a note they
            // cannot swipe away is one that gets the channel muted instead.
            assertEquals(false, notifier.showing!!.ongoing)
        }

    @Test
    fun `a live streak is what the second line is about`() =
        runTest {
            given(streak = StreakState(currentStreak = 12, lastCountedAt = YESTERDAY, weekGoalMet = true))

            broadcasts.handle(due())

            assertEquals("12-week streak on the line. Keep it.", notifier.showing!!.body)
        }

    @Test
    fun `a streak that has already lapsed is not mentioned`() =
        runTest {
            // The stored figure is only recomputed when a session finishes, so it still reads
            // twelve for somebody who last trained in March. Telling them they are twelve weeks
            // deep is the one thing a streak must never do — see `StreakCalculator.isLive`.
            given(streak = StreakState(currentStreak = 12, lastCountedAt = LAST_MONTH, weekGoalMet = true))

            broadcasts.handle(due())

            assertEquals(PLAIN_INVITATION, notifier.showing!!.body)
        }

    @Test
    fun `a streak whose week has already met its goal is not on the line`() =
        runTest {
            // The week joined the streak with the session an hour ago, so nothing trained today
            // can keep or lose it.
            given(streak = StreakState(currentStreak = 12, lastCountedAt = AN_HOUR_AGO, weekGoalMet = true))

            broadcasts.handle(due())

            assertEquals(PLAIN_INVITATION, notifier.showing!!.body)
        }

    @Test
    fun `a user with no streak history gets the plain invitation`() =
        runTest {
            given()

            broadcasts.handle(due())

            assertEquals(PLAIN_INVITATION, notifier.showing!!.body)
        }

    // --- with a workout already running -------------------------------------

    @Test
    fun `a reminder during a session leads back into that session instead`() =
        runTest {
            given(active = session(name = "Push day"))

            broadcasts.handle(due())

            val note = notifier.showing!!
            assertEquals("titan://session/active/7", note.deepLink)
            assertEquals(
                listOf(NotificationTarget.Screen("titan://session/active/7")),
                note.actions.map { it.target },
            )
        }

    @Test
    fun `and says something else, because nobody mid-session needs telling to train`() =
        runTest {
            given(active = session(name = "Push day"))

            broadcasts.handle(due())

            val note = notifier.showing!!
            assertEquals("Finish what you started", note.title)
            assertEquals("Push day", note.body)
        }

    @Test
    fun `a session started empty and never named still gets a body`() =
        runTest {
            given(active = session(name = null))

            broadcasts.handle(due())

            assertEquals("Your workout is still open.", notifier.showing!!.body)
        }

    // --- re-arming ----------------------------------------------------------

    @Test
    fun `firing re-arms the whole schedule rather than the day that fired`() =
        runTest {
            given()

            broadcasts.handle(due())

            // A full sync, so a schedule changed while the app was not running is picked up,
            // and a re-arm missed once is repaired by the next reminder that does fire.
            assertEquals(1, scheduler.syncs)
        }

    @Test
    fun `a scheduler that refuses does not swallow the reminder`() =
        runTest {
            given()
            scheduler.failing = true

            broadcasts.handle(due())

            // The user's reminder arriving is what the wake-up was for. Losing next week's
            // alarm is bad; losing tonight's notification as well would be worse.
            assertEquals("Time to train", notifier.showing?.title)
        }

    // --- anything else ------------------------------------------------------

    @Test
    fun `an intent this build does not understand says nothing and schedules nothing`() =
        runTest {
            // A broadcast left over from an upgrade, or one that reached the receiver by some
            // other route. Neither is worth a notification, and neither is a schedule to re-arm
            // from.
            broadcasts.handle(Intent("com.example.SOMETHING_ELSE"))

            assertNull(notifier.showing)
            assertEquals(0, scheduler.syncs)
        }

    /** The two reads the reminder makes, answered. */
    private fun given(
        active: WorkoutSession? = null,
        streak: StreakState = StreakState(),
    ) {
        every { sessions.getActive() } returns flowOf(active)
        every { gamification.observeStreak() } returns flowOf(streak)
    }

    /** A workout in progress. Only its id and its name are read here. */
    private fun session(name: String?): WorkoutSession =
        WorkoutSession(id = 7, name = name, createdAt = NOW, lastActiveAt = NOW, updatedAt = NOW)

    /** Monday's alarm going off, as the receiver would be handed it. */
    private fun due(): Intent = reminderBroadcast(context, DayOfWeek.MONDAY)

    /** Counts syncs, and can refuse one. */
    private class RecordingReminderScheduler(var failing: Boolean = false) : ReminderScheduler {
        var syncs = 0

        override suspend fun sync() {
            if (failing) throw IllegalStateException("the store is unreadable")
            syncs++
        }

        override fun isExact(): Boolean = true
    }

    private companion object {
        /**
         * A Monday evening, so [YESTERDAY] closed a week that met its goal. Nothing here depends
         * on the date beyond the streak's freshness.
         */
        val NOW: Long = Instant.parse("2026-04-27T18:00:00Z").toEpochMilli()

        val YESTERDAY: Instant = Instant.ofEpochMilli(NOW).minus(Duration.ofDays(1))

        val LAST_MONTH: Instant = Instant.ofEpochMilli(NOW).minus(Duration.ofDays(40))

        /** Earlier the same Monday, so in the week [NOW] falls in. */
        val AN_HOUR_AGO: Instant = Instant.ofEpochMilli(NOW).minus(Duration.ofHours(1))

        const val PLAIN_INVITATION = "Get it logged. It only counts if you write it down."

        init {
            // The rule this suite leans on, made visible: a streak is live while a session
            // finishing now would extend it. If `StreakCalculator` ever changes its mind about
            // either of these, the fixtures fail here rather than the assertions failing
            // somewhere that reads as a bug in the notification.
            check(StreakCalculator.isLive(YESTERDAY, weekGoalMet = true, asOf = Instant.ofEpochMilli(NOW)))
            check(!StreakCalculator.isLive(LAST_MONTH, weekGoalMet = true, asOf = Instant.ofEpochMilli(NOW)))
        }
    }
}
