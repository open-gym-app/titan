package com.opengym.feature.settings.reminder

import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationPermissionState
import com.opengym.core.domain.notification.NotificationPreferences
import com.opengym.core.domain.notification.ReminderPreferences
import com.opengym.core.domain.notification.ReminderScheduler
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.RequestNotificationPermissionUseCase
import com.opengym.feature.settings.FakeNotificationPermission
import com.opengym.feature.settings.FakeUserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The reminder screen's ViewModel: the writes, and the alarms behind them.
 *
 * The claim asserted hardest is that **every write is followed by a sync**. A stored schedule
 * with no alarm pointed at it is the one way this feature fails silently — the screen reads
 * back correctly, the switch stays on, and nothing ever fires — so each of the four writes is
 * checked for it rather than trusting the one path they share.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow is not started and the state stays at its initial value,
 * which would make the assertions pass for the wrong reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderViewModelTest {

    private var preferences = FakeUserPreferencesRepository()
    private val notificationPermission = FakeNotificationPermission()
    private val scheduler = RecordingScheduler()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    // --- what the screen reads ----------------------------------------------

    @Test
    fun `the switch is the stored notification preference, not a second flag`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            assertTrue(viewModel.uiState.value.enabled)
            assertEquals(ReminderPreferences(), viewModel.uiState.value.reminders)
        }

    @Test
    fun `the next occurrence is named when the reminder would actually fire`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            // Monday 09:00 with a Monday-Wednesday-Friday schedule at 18:00: tonight.
            assertEquals(
                ReminderPreferences().nextOccurrence(DayOfWeek.MONDAY, NOW, ZONE),
                viewModel.uiState.value.nextOccurrence,
            )
        }

    @Test
    fun `a reminder that is switched off has no next occurrence to name`() =
        runTest {
            val viewModel = viewModel(enabled = false)
            collect(viewModel)

            // A date drawn beside "reminders are off" would be the screen contradicting itself.
            assertNull(viewModel.uiState.value.nextOccurrence)
            assertFalse(viewModel.uiState.value.hasNextOccurrence)
        }

    @Test
    fun `a schedule with no days has none either, however on the switch is`() =
        runTest {
            val viewModel = viewModel(enabled = true, reminders = ReminderPreferences(days = emptySet()))
            collect(viewModel)

            assertNull(viewModel.uiState.value.nextOccurrence)
        }

    @Test
    fun `a blocked reminder keeps its date but does not present the header as live`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            // The prompt has been up and Android has answered it with a refusal. The schedule
            // is untouched; what is gone is the delivery.
            viewModel.onEvent(ReminderEvent.PermissionRequested)

            assertEquals(NotificationPermissionState.DENIED, viewModel.uiState.value.permission)
            // The date is still a fact, and it is the one the header names again the moment the
            // permission comes back. But the card may not draw itself as live — accent border,
            // bright time — above a line that says the notification is not arriving.
            assertNotNull(viewModel.uiState.value.nextOccurrence)
            assertFalse(viewModel.uiState.value.hasNextOccurrence)
        }

    @Test
    fun `whether the alarms can be exact is read from the scheduler`() =
        runTest {
            scheduler.exact = false
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            assertFalse(viewModel.uiState.value.isExact)
        }

    // --- the writes ---------------------------------------------------------

    @Test
    fun `turning the reminder on stores the preference and arms the alarms`() =
        runTest {
            val viewModel = viewModel(enabled = false)
            collect(viewModel)

            viewModel.onEvent(ReminderEvent.Toggled(true))

            assertTrue(NotificationKind.REMINDERS in preferences.observe().first().notifications)
            assertEquals(1, scheduler.syncs)
        }

    @Test
    fun `turning it off stores that and takes the alarms down`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            viewModel.onEvent(ReminderEvent.Toggled(false))

            assertFalse(NotificationKind.REMINDERS in preferences.observe().first().notifications)
            // `sync` cancels what it does not want, which is why turning off is a sync rather
            // than a separate cancellation somebody could forget to call.
            assertEquals(1, scheduler.syncs)
        }

    @Test
    fun `a day is stored one day at a time and re-arms the schedule`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            viewModel.onEvent(ReminderEvent.DaySelected(DayOfWeek.SATURDAY, selected = true))

            assertTrue(DayOfWeek.SATURDAY in preferences.observe().first().reminders.days)
            assertEquals(1, scheduler.syncs)
        }

    @Test
    fun `a chosen time closes the picker, is stored, and re-arms`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)
            viewModel.onEvent(ReminderEvent.TimeRequested)

            viewModel.onEvent(ReminderEvent.TimeSelected(LocalTime.of(6, 30)))

            assertFalse(viewModel.uiState.value.timePickerOpen)
            assertEquals(LocalTime.of(6, 30), preferences.observe().first().reminders.timeOfDay)
            assertEquals(1, scheduler.syncs)
        }

    @Test
    fun `dismissing the picker writes nothing`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)
            viewModel.onEvent(ReminderEvent.TimeRequested)

            viewModel.onEvent(ReminderEvent.TimeDismissed)

            assertFalse(viewModel.uiState.value.timePickerOpen)
            assertEquals(LocalTime.of(18, 0), preferences.observe().first().reminders.timeOfDay)
            assertEquals(0, scheduler.syncs)
        }

    @Test
    fun `a store that refuses says so rather than crashing`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)
            preferences.writeFailure = IOException("no space")

            viewModel.onEvent(ReminderEvent.DaySelected(DayOfWeek.SATURDAY, selected = true))

            assertTrue(viewModel.uiState.value.saveFailed)
            // Nothing was stored, so nothing should have been armed either.
            assertEquals(0, scheduler.syncs)
        }

    @Test
    fun `the failure is shown once and then forgotten`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)
            preferences.writeFailure = IOException("no space")
            viewModel.onEvent(ReminderEvent.DaySelected(DayOfWeek.SATURDAY, selected = true))

            viewModel.onEvent(ReminderEvent.MessageShown)

            assertFalse(viewModel.uiState.value.saveFailed)
        }

    // --- the permission -----------------------------------------------------

    @Test
    fun `turning the reminder on for the first time asks for the permission`() =
        runTest {
            val viewModel = viewModel(enabled = false)
            collect(viewModel)

            viewModel.onEvent(ReminderEvent.Toggled(true))

            assertTrue(viewModel.uiState.value.permissionRequest)
        }

    @Test
    fun `turning it off asks nothing`() =
        runTest {
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            viewModel.onEvent(ReminderEvent.Toggled(false))

            // There is nothing left to deliver, so there is nothing to ask about.
            assertFalse(viewModel.uiState.value.permissionRequest)
        }

    @Test
    fun `the prompt goes up once and is not raised again`() =
        runTest {
            val viewModel = viewModel(enabled = false)
            collect(viewModel)
            viewModel.onEvent(ReminderEvent.Toggled(true))
            viewModel.onEvent(ReminderEvent.PermissionRequestShown)

            viewModel.onEvent(ReminderEvent.Toggled(false))
            viewModel.onEvent(ReminderEvent.Toggled(true))

            // Android answers the second prompt with the first one's refusal, so asking again
            // is how an app teaches somebody to say no.
            assertFalse(viewModel.uiState.value.permissionRequest)
            assertEquals(NotificationPermissionState.DENIED, viewModel.uiState.value.permission)
        }

    @Test
    fun `the row that offers the prompt raises it, and syncs on the way past`() =
        runTest {
            // For the user whose reminder is already on and who therefore has no switch left
            // to flip. The sync matters as much as the prompt, and not because it depends on
            // the permission: it is the rule with no exceptions in it — every event that goes
            // through `write` re-points the alarms, including the ones that store nothing.
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            viewModel.onEvent(ReminderEvent.PermissionRequested)

            assertTrue(viewModel.uiState.value.permissionRequest)
            assertEquals(1, scheduler.syncs)
        }

    @Test
    fun `coming back from system settings re-reads both of the system's answers`() =
        runTest {
            scheduler.exact = false
            val viewModel = viewModel(enabled = true)
            collect(viewModel)

            notificationPermission.allowed = true
            scheduler.exact = true
            viewModel.onEvent(ReminderEvent.PermissionRechecked)

            assertEquals(NotificationPermissionState.GRANTED, viewModel.uiState.value.permission)
            // Re-read alongside, because both are revoked in the same place by the same trip.
            assertTrue(viewModel.uiState.value.isExact)
        }

    // --- helpers ------------------------------------------------------------

    private fun viewModel(
        enabled: Boolean,
        reminders: ReminderPreferences = ReminderPreferences(),
    ): ReminderViewModel {
        preferences = FakeUserPreferencesRepository(
            UserPreferences(
                notifications = NotificationPreferences(
                    enabled = if (enabled) setOf(NotificationKind.REMINDERS) else emptySet(),
                ),
                reminders = reminders,
            ),
        )

        return ReminderViewModel(
            userPreferencesRepository = preferences,
            reminderScheduler = scheduler,
            notificationPermission = notificationPermission,
            requestNotificationPermission = RequestNotificationPermissionUseCase(
                preferences,
                notificationPermission,
            ),
            clock = Clock { NOW },
        )
    }

    /** Starts the `WhileSubscribed` flow. See the note on this class. */
    private fun TestScope.collect(viewModel: ReminderViewModel) {
        // Unconfined against the test's own scheduler, so the first value has arrived by the
        // time this returns. `backgroundScope`'s default dispatcher would queue it.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
    }

    /** Counts syncs and reports whatever exactness a test asks for. */
    private class RecordingScheduler(var exact: Boolean = true) : ReminderScheduler {
        var syncs = 0

        override suspend fun sync() {
            syncs++
        }

        override fun isExact(): Boolean = exact
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()

        /** A Monday at 09:00, so the default schedule's next occurrence is that evening. */
        val NOW: Long = Instant.parse("2026-04-27T09:00:00Z").toEpochMilli()
    }
}
