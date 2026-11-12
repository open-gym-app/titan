package com.titan.core.data.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.AppNotification
import com.titan.core.domain.notification.NotificationAction
import com.titan.core.domain.notification.NotificationCommand
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationOutcome
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.notification.NotificationPreferences
import com.titan.core.domain.notification.NotificationTarget
import com.titan.core.domain.repository.UserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two gates, asserted rather than assumed.
 *
 * This is the whole reason `Notifier` is an abstraction and not a helper: four features are
 * going to post through it, and the guarantee they are all relying on is that neither a
 * preference the user turned off nor a permission the system never granted can reach the
 * status bar. A gate that is only checked at the call site is a gate the fourth caller
 * forgets, so the tests below check the *notifier* — the thing all four go through.
 *
 * Robolectric's `NotificationManager` is the real one over a shadow, so "nothing was posted"
 * is a fact about the system's own notification list rather than about a mock never being
 * called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `a wanted notification with the permission granted is posted`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            val outcome = notifier.post(REST_OVER)

            assertEquals(NotificationOutcome.POSTED, outcome)
            assertEquals(1, manager.activeNotifications.size)
            assertEquals("rest_timer", manager.activeNotifications.single().notification.channelId)
        }

    @Test
    fun `a kind the user turned off is not posted`() =
        runTest {
            val notifier = notifier(enabled = emptySet(), allowed = true)

            val outcome = notifier.post(REST_OVER)

            assertEquals(NotificationOutcome.SUPPRESSED_BY_PREFERENCE, outcome)
            assertEquals(0, manager.activeNotifications.size)
        }

    @Test
    fun `a kind the user wants is not posted without the permission`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = false)

            val outcome = notifier.post(REST_OVER)

            assertEquals(NotificationOutcome.SUPPRESSED_BY_PERMISSION, outcome)
            assertEquals(0, manager.activeNotifications.size)
        }

    @Test
    fun `the preference is reported first when both gates are shut`() =
        runTest {
            // The user's own decision is the more useful of the two answers, and the one a
            // caller can do nothing about — there is no point offering to fix a permission
            // for a notification they have said they do not want.
            val notifier = notifier(enabled = emptySet(), allowed = false)

            assertEquals(NotificationOutcome.SUPPRESSED_BY_PREFERENCE, notifier.post(REST_OVER))
        }

    @Test
    fun `re-posting a kind replaces what is showing rather than stacking`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            notifier.post(REST_OVER)
            notifier.post(REST_OVER.copy(title = "Rest over", body = "Set 3 of 5"))

            // One id per kind, so the second rest of a session does not leave the first one's
            // notification sitting underneath it.
            assertEquals(1, manager.activeNotifications.size)
            assertEquals(
                "Set 3 of 5",
                manager.activeNotifications.single().notification.extras.getString("android.text"),
            )
        }

    @Test
    fun `two kinds sit alongside each other`() =
        runTest {
            val notifier = notifier(
                enabled = setOf(NotificationKind.REST_TIMER, NotificationKind.SESSION),
                allowed = true,
            )

            notifier.post(REST_OVER)
            notifier.post(AppNotification(NotificationKind.SESSION, "Session running", "Started 40 minutes ago"))

            assertEquals(2, manager.activeNotifications.size)
        }

    @Test
    fun `a tag is what keeps two of one kind apart`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REMINDERS), allowed = true)

            notifier.post(AppNotification(NotificationKind.REMINDERS, "Push day", "Today", tag = "monday"))
            notifier.post(AppNotification(NotificationKind.REMINDERS, "Pull day", "Today", tag = "thursday"))

            assertEquals(2, manager.activeNotifications.size)
        }

    @Test
    fun `cancelling works even after the preference has been turned off`() =
        runTest {
            val preferences = mockk<UserPreferencesRepository>()
            val permission = FakeNotificationPermission(allowed = true)
            every { preferences.observe() } returns
                flowOf(preferencesWith(setOf(NotificationKind.SESSION)))
            val notifier = AndroidNotifier(context, preferences, permission)
            notifier.post(AppNotification(NotificationKind.SESSION, "Session running", "Started 40 minutes ago"))

            // The user silences unfinished-session notes while one is on screen. Cancelling is
            // ungated precisely so this does not strand it there for the rest of the session.
            every { preferences.observe() } returns flowOf(preferencesWith(emptySet()))
            notifier.cancel(NotificationKind.SESSION)

            assertEquals(0, manager.activeNotifications.size)
        }

    // --- what a notification can carry --------------------------------------

    @Test
    fun `a deep link becomes somewhere for a tap to land`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            notifier.post(REST_OVER.copy(deepLink = "titan://session/active/7"))

            // Without one the tap brings the app forward and lands wherever the user last was,
            // which for a notification about a specific workout is the wrong screen.
            assertNotNull(manager.activeNotifications.single().notification.contentIntent)
        }

    @Test
    fun `a notification with no deep link has nothing to open`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            notifier.post(REST_OVER)

            assertNull(manager.activeNotifications.single().notification.contentIntent)
        }

    @Test
    fun `an action becomes a button that needs no screen behind it`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            notifier.post(
                REST_OVER.copy(
                    actions = listOf(
                        NotificationAction(
                            label = "Skip rest",
                            target = NotificationTarget.Command(
                                NotificationCommand.SkipRest(SessionRef(7, createdAt = 100)),
                            ),
                        ),
                    ),
                ),
            )

            val actions = manager.activeNotifications.single().notification.actions
            assertEquals(1, actions.size)
            assertEquals("Skip rest", actions.single().title)
            // A broadcast rather than an activity: pressing it must not drag the user into a
            // screen they did not ask for.
            assertNotNull(actions.single().actionIntent)
        }

    @Test
    fun `a countdown is handed to the platform rather than redrawn`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            notifier.post(REST_OVER.copy(countdownTo = DEADLINE))

            // The reason a rest is posted once and not re-posted every second: the shade ticks
            // it, and nothing wakes up to help.
            val posted = manager.activeNotifications.single().notification
            assertEquals(DEADLINE, posted.`when`)
            assertTrue(posted.extras.getBoolean(NotificationCompat.EXTRA_SHOW_CHRONOMETER))
            assertTrue(posted.extras.getBoolean(NotificationCompat.EXTRA_CHRONOMETER_COUNT_DOWN))
        }

    @Test
    fun `an ongoing notification cannot be swiped away`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            notifier.post(REST_OVER.copy(ongoing = true))

            val posted = manager.activeNotifications.single().notification
            assertTrue(posted.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            // And a tap does not clear it either: the app takes it down when the thing it
            // describes is over.
            assertEquals(0, posted.flags and android.app.Notification.FLAG_AUTO_CANCEL)
        }

    @Test
    fun `a lock screen that hides content sees the title and the countdown, not the exercise`() =
        runTest {
            val notifier = notifier(enabled = setOf(NotificationKind.REST_TIMER), allowed = true)

            notifier.post(REST_OVER.copy(countdownTo = DEADLINE))

            val posted = manager.activeNotifications.single().notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
            val lockScreen = checkNotNull(posted.publicVersion) { "no public version to show when locked" }
            assertEquals("Rest over", lockScreen.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString())
            assertEquals(DEADLINE, lockScreen.`when`)
            assertTrue(lockScreen.extras.getBoolean(NotificationCompat.EXTRA_CHRONOMETER_COUNT_DOWN))
            // The body names the lift, so no line of the public version may carry it.
            listOf(
                NotificationCompat.EXTRA_TEXT,
                NotificationCompat.EXTRA_BIG_TEXT,
                NotificationCompat.EXTRA_SUB_TEXT,
                NotificationCompat.EXTRA_INFO_TEXT,
                NotificationCompat.EXTRA_SUMMARY_TEXT,
            ).forEach { assertNull(it, lockScreen.extras.getCharSequence(it)) }
            assertNull(lockScreen.actions)
        }

    private fun notifier(
        enabled: Set<NotificationKind>,
        allowed: Boolean,
    ): AndroidNotifier {
        val preferences = mockk<UserPreferencesRepository>()
        every { preferences.observe() } returns flowOf(preferencesWith(enabled))
        return AndroidNotifier(context, preferences, FakeNotificationPermission(allowed))
    }

    private fun preferencesWith(enabled: Set<NotificationKind>) =
        UserPreferences(notifications = NotificationPreferences(enabled = enabled))

    /** The system's answer, fixed for the length of a test. */
    private class FakeNotificationPermission(private val allowed: Boolean) : NotificationPermission {
        override fun areNotificationsAllowed(): Boolean = allowed

        override val isRuntimePermissionRequired: Boolean = true
    }

    private companion object {
        const val DEADLINE = 1_700_000_090_000L

        val REST_OVER = AppNotification(
            kind = NotificationKind.REST_TIMER,
            title = "Rest over",
            body = "Bench press, set 2 of 5",
        )
    }
}
