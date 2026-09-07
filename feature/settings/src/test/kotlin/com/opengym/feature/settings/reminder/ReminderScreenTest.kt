package com.opengym.feature.settings.reminder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.opengym.core.domain.notification.NotificationPermissionState
import com.opengym.core.domain.notification.ReminderPreferences
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The reminder screen, as a function of its state.
 *
 * Most of these are about the header's one line. It is the only thing on the screen that
 * answers the question somebody opened it with — *is this going to go off, and when* — and it
 * has four answers with an order between them: the switch blocks the days, the days block the
 * permission, and only when none of the three is in the way is there a date to name. Getting
 * that order wrong sends a user to system settings to fix a reminder whose switch is off.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ReminderEvent>()
    private var notificationSettingsOpened = 0
    private var exactAlarmSettingsOpened = 0

    // --- the header ---------------------------------------------------------

    @Test
    fun `a live reminder names when the next one is due`() {
        setContent(ReminderUiState(enabled = true, nextOccurrence = NEXT_MONDAY))

        // The date itself is `formatSessionDate`'s, and it is localized; what this pins is
        // that the header names one rather than saying something is in the way.
        composeTestRule.onNodeWithText("Next:", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a reminder that is off says so rather than naming a date`() {
        setContent(ReminderUiState(enabled = false))

        composeTestRule.onNodeWithText("Reminders are off. Turn them on below.").assertIsDisplayed()
    }

    @Test
    fun `an empty schedule is named before the permission is`() {
        // Both are in the way. Naming the permission would send the user to system settings to
        // fix a reminder that has no day to fire on.
        setContent(
            ReminderUiState(
                enabled = true,
                reminders = ReminderPreferences(days = emptySet()),
                permission = NotificationPermissionState.DENIED,
            ),
        )

        composeTestRule.onNodeWithText("Pick at least one day for this to fire.").assertIsDisplayed()
    }

    @Test
    fun `a blocked permission is named once nothing else is in the way`() {
        setContent(ReminderUiState(enabled = true, permission = NotificationPermissionState.DENIED))

        composeTestRule.onNodeWithText("Android is not delivering notifications.").assertIsDisplayed()
    }

    @Test
    fun `the chosen time is drawn whether or not the reminder is on`() {
        // Blanking it would make turning the switch back on feel like starting over.
        setContent(
            ReminderUiState(enabled = false, reminders = ReminderPreferences(timeOfDay = LocalTime.of(6, 30))),
        )

        // Twice: the header states the outcome and the row below is the control that
        // changes it.
        composeTestRule.onAllNodesWithText("6:30 AM").assertCountEquals(2)
    }

    // --- the controls -------------------------------------------------------

    @Test
    fun `the switch reports which way it was flipped`() {
        setContent(ReminderUiState(enabled = false))

        composeTestRule.onNodeWithText("Remind me to train").performScrollTo().performClick()

        assertEquals(listOf(ReminderEvent.Toggled(true)), events)
    }

    @Test
    fun `a day cell reports the day and the direction`() {
        setContent(ReminderUiState(enabled = true))

        composeTestRule.onNodeWithContentDescription(saturday()).performScrollTo().performClick()

        assertEquals(listOf(ReminderEvent.DaySelected(DayOfWeek.SATURDAY, selected = true)), events)
    }

    @Test
    fun `the day picker stays live while the reminder is off`() {
        // Setting the days before turning it on is the natural order to do this in, and a
        // disabled row would make the switch the only thing on the screen that works.
        setContent(ReminderUiState(enabled = false))

        composeTestRule.onNodeWithContentDescription(saturday()).performScrollTo().performClick()

        assertEquals(listOf(ReminderEvent.DaySelected(DayOfWeek.SATURDAY, selected = true)), events)
    }

    @Test
    fun `the days already chosen are drawn as chosen`() {
        setContent(ReminderUiState(enabled = true))

        composeTestRule.onNodeWithContentDescription(dayName(DayOfWeek.MONDAY)).assertIsOn()
        composeTestRule.onNodeWithContentDescription(saturday()).assertIsOff()
    }

    @Test
    fun `the time row opens the clock face`() {
        setContent(ReminderUiState(enabled = true))

        composeTestRule.onNodeWithText("Reminder time").performScrollTo().performClick()

        assertEquals(listOf(ReminderEvent.TimeRequested), events)
    }

    @Test
    fun `an empty schedule says so under the picker as well`() {
        setContent(ReminderUiState(enabled = true, reminders = ReminderPreferences(days = emptySet())))

        composeTestRule.onNodeWithText("No days chosen, so nothing is scheduled.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // --- what the platform has to say ---------------------------------------

    @Test
    fun `a blocked permission offers the way into system settings`() {
        setContent(ReminderUiState(enabled = true, permission = NotificationPermissionState.DENIED))

        composeTestRule.onNodeWithText("Notifications are blocked").performScrollTo().performClick()

        assertEquals(1, notificationSettingsOpened)
    }

    @Test
    fun `a permission never asked for offers to ask`() {
        // For the user whose reminder is already on and who therefore has no switch left to
        // flip.
        setContent(ReminderUiState(enabled = true, permission = NotificationPermissionState.NOT_REQUESTED))

        composeTestRule.onNodeWithText("Allow notifications").performScrollTo().performClick()

        assertEquals(listOf(ReminderEvent.PermissionRequested), events)
    }

    @Test
    fun `neither is drawn while the reminder is switched off`() {
        // Telling somebody that something they have turned off is also blocked is noise about
        // a non-event.
        setContent(ReminderUiState(enabled = false, permission = NotificationPermissionState.DENIED))

        composeTestRule.onNodeWithText("Notifications are blocked").assertDoesNotExist()
    }

    @Test
    fun `a device that cannot fire on the minute says the reminder may be late`() {
        setContent(ReminderUiState(enabled = true, isExact = false))

        composeTestRule.onNodeWithText("May arrive late").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `and that row leads to the exact-alarm page, not to the notification one`() {
        // Two permissions on two system screens. Sending somebody looking for the alarm switch
        // to the notification channels is a row that opens the wrong page and says nothing.
        setContent(ReminderUiState(enabled = true, isExact = false))

        composeTestRule.onNodeWithText("May arrive late").performScrollTo().performClick()

        assertEquals(1, exactAlarmSettingsOpened)
        assertEquals(0, notificationSettingsOpened)
    }

    @Test
    fun `and says nothing when it can`() {
        setContent(ReminderUiState(enabled = true, isExact = true))

        composeTestRule.onNodeWithText("May arrive late").assertDoesNotExist()
    }

    private fun setContent(state: ReminderUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                ReminderScreen(
                    state = state,
                    onEvent = events::add,
                    onBack = {},
                    onOpenNotificationSettings = { notificationSettingsOpened++ },
                    onOpenExactAlarmSettings = { exactAlarmSettingsOpened++ },
                )
            }
        }
    }

    private fun saturday(): String = dayName(DayOfWeek.SATURDAY)

    /** The day as the picker announces it. Robolectric pins the locale to en-US. */
    private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.US)

    private companion object {
        /** Monday 27 April 2026, 18:00 UTC — the date the header renders. */
        val NEXT_MONDAY: Long = Instant.parse("2026-04-27T18:00:00Z").toEpochMilli()
    }
}
