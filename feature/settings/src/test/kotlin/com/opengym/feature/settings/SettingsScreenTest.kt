package com.opengym.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationPermissionState
import com.opengym.core.domain.notification.NotificationPreferences
import com.opengym.core.domain.notification.ReminderPreferences
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * The settings screen, driven stateless with hand-built states — no ViewModel, no Hilt, no
 * store. See CONTRIBUTING.md § Compose tests.
 *
 * Rows are found by the label a user reads, which is deliberate: it is the same string a
 * translator will see and a screen reader will speak, so a test that stops finding a row is
 * telling us the row's name changed rather than that a test id was renamed.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<SettingsEvent>()
    private val openedLinks = mutableListOf<String>()
    private var notificationSettingsOpened = 0
    private var remindersOpened = 0

    /** How many times the Backup & restore row asked to navigate. */
    private var backupOpened = 0

    // --- reading the stored values ----------------------------------------

    @Test
    fun `every enabled row shows the value that is stored`() {
        setContent(
            UserPreferences(
                theme = ThemePreference.DARK,
                unitSystem = UnitSystem.METRIC,
                defaultRestDuration = 150.seconds,
                barWeight = 15_000_000,
                trainingGoal = TrainingGoal.ENDURANCE,
                oneRepMaxFormula = OneRepMaxFormula.BRZYCKI,
            ),
        )

        // `performScrollTo` on each: the screen is longer than a phone, and a row that is
        // merely composed is not a row the user can read.
        listOf("Dark", "Metric", "2:30", "15 kg", "Endurance", "Brzycki").forEach { value ->
            composeTestRule.onNodeWithText(value).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `the bar weight is shown in the stored unit system`() {
        // The AC that units apply to "every displayed weight", at the one place this screen
        // displays one: nothing about the stored value changes, only how it reads.
        setContent(UserPreferences(unitSystem = UnitSystem.IMPERIAL, barWeight = 20_411_657))

        composeTestRule.onNodeWithText("45 lb").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the version row shows the running build`() {
        setContent(appVersion = "1.4.2")

        composeTestRule.onNodeWithText("1.4.2").performScrollTo().assertIsDisplayed()
    }

    // --- writing ----------------------------------------------------------

    @Test
    fun `tapping a settings row asks for its picker`() {
        setContent(UserPreferences(theme = ThemePreference.SYSTEM))

        composeTestRule.onNodeWithText("Theme").performClick()

        // The screen does not open the dialog itself. It reports the tap and renders whatever
        // state comes back, which is what lets a picker survive a rotation.
        assertEquals(listOf(SettingsEvent.DialogRequested(SettingsDialog.THEME)), events)
    }

    @Test
    fun `the theme picker reports the scheme that was chosen`() {
        setContent(UserPreferences(theme = ThemePreference.SYSTEM), dialog = SettingsDialog.THEME)

        composeTestRule.onNodeWithText("Dark").performClick()

        assertTrue(SettingsEvent.ThemeSelected(ThemePreference.DARK) in events)
    }

    @Test
    fun `the units row reports the system that was chosen`() {
        setContent(dialog = SettingsDialog.UNITS)

        composeTestRule.onNodeWithText("Imperial").performClick()

        assertTrue(SettingsEvent.UnitSystemSelected(UnitSystem.IMPERIAL) in events)
    }

    @Test
    fun `the rest timer picker reports a duration`() {
        setContent(dialog = SettingsDialog.REST_DURATION)

        composeTestRule.onNodeWithText("3:00").performScrollTo().performClick()

        assertTrue(SettingsEvent.RestDurationSelected(180.seconds) in events)
    }

    @Test
    fun `the training goal picker reports a goal`() {
        setContent(dialog = SettingsDialog.TRAINING_GOAL)

        composeTestRule.onNodeWithText("Hypertrophy").performClick()

        assertTrue(SettingsEvent.TrainingGoalSelected(TrainingGoal.HYPERTROPHY) in events)
    }

    @Test
    fun `the one-rep-max formula picker reports the formula that was chosen`() {
        setContent(dialog = SettingsDialog.ONE_REP_MAX_FORMULA)

        composeTestRule.onNodeWithText("Brzycki").performClick()

        assertTrue(SettingsEvent.OneRepMaxFormulaSelected(OneRepMaxFormula.BRZYCKI) in events)
    }

    @Test
    fun `the bar weight dialog reports milligrams, converted from what was typed`() {
        setContent(
            UserPreferences(unitSystem = UnitSystem.METRIC, barWeight = 20_000_000),
            dialog = SettingsDialog.BAR_WEIGHT,
        )

        // A preset chip rather than the field: the presets are the whole reason the dialog is
        // not a plain number entry.
        composeTestRule.onNodeWithText("15 kg").performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(listOf(SettingsEvent.BarWeightSelected(15_000_000)), events)
    }

    @Test
    fun `an imperial bar weight is converted on the way in`() {
        setContent(
            UserPreferences(unitSystem = UnitSystem.IMPERIAL, barWeight = 20_000_000),
            dialog = SettingsDialog.BAR_WEIGHT,
        )

        composeTestRule.onNodeWithText("45 lb").performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        // 45 lb, in the milligrams every weight is stored in.
        assertEquals(listOf(SettingsEvent.BarWeightSelected(20_411_657)), events)
    }

    @Test
    fun `the dialog opens usable in a locale whose digits are not ASCII`() {
        // Persian renders 20 as `۲۰`, so the field is seeded with characters `toDouble` does
        // not accept. Reading it back with the ASCII parser alone left the dialog in its own
        // error state, on a value the app itself had just written, with Save dead.
        withLocale(Locale.forLanguageTag("fa")) {
            setContent(
                UserPreferences(unitSystem = UnitSystem.METRIC, barWeight = 20_000_000),
                dialog = SettingsDialog.BAR_WEIGHT,
            )

            composeTestRule.onNodeWithText("Enter a weight above zero").assertDoesNotExist()
            composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

            // Saving without touching anything stores the weight it opened with.
            assertEquals(listOf(SettingsEvent.BarWeightSelected(20_000_000)), events)
        }
    }

    @Test
    fun `a weight that rounds away to nothing cannot be saved`() {
        setContent(
            UserPreferences(unitSystem = UnitSystem.METRIC, barWeight = 20_000_000),
            dialog = SettingsDialog.BAR_WEIGHT,
        )

        // Positive in kilograms, zero in the milligrams it is stored in — and the repository
        // rejects a bar weight of zero. The field is what has to catch it.
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("0.0000001")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    // --- rows that are not available yet ----------------------------------

    @Test
    fun `a row for an unshipped feature is announced as disabled, not merely greyed`() {
        setContent()

        // `assertIsNotEnabled` reads the semantics tree, which is what TalkBack reads. A row
        // that was only dimmed would pass a screenshot test and fail a blind user.
        listOf(
            "Health Connect",
            // "Marketplace deals" is the last row left in the notifications group that has
            // nothing behind it — it is M8 (#52). Every other row there is a live switch.
            "Marketplace deals",
            "Donate",
        ).forEach { label ->
            composeTestRule.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun `tapping a disabled row does nothing at all`() {
        setContent()

        composeTestRule.onNodeWithText("Health Connect").performScrollTo().performClick()

        assertEquals(emptyList<SettingsEvent>(), events)
    }

    /**
     * #38's first acceptance criterion, from the settings side: the row is a way in rather
     * than a promise. It navigates instead of reporting an event, because where the user goes
     * is `:app`'s decision and not the ViewModel's.
     */
    @Test
    fun `the backup row navigates rather than reporting an event`() {
        setContent()

        composeTestRule.onNodeWithText("Backup & restore").performScrollTo().performClick()

        assertEquals(1, backupOpened)
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    // --- notifications ----------------------------------------------------

    @Test
    fun `every notification kind gets a switch that reads the stored value`() {
        setContent(
            UserPreferences(
                notifications = NotificationPreferences(
                    enabled = setOf(NotificationKind.REST_TIMER, NotificationKind.PROGRESSION),
                ),
            ),
        )

        // `assertIsOn`/`assertIsOff` read the toggleable state out of the semantics tree,
        // which is what TalkBack reads. A switch drawn in the right position with no state on
        // it would pass a screenshot test and tell a blind user nothing.
        composeTestRule.onNodeWithText("Rest timer alerts").performScrollTo().assertIsOn()
        composeTestRule.onNodeWithText("Streaks and rewards").performScrollTo().assertIsOn()
        composeTestRule.onNodeWithText("Unfinished sessions").performScrollTo().assertIsOff()
    }

    @Test
    fun `flipping a switch reports the kind and the direction`() {
        setContent(UserPreferences(notifications = NotificationPreferences(enabled = emptySet())))

        composeTestRule.onNodeWithText("Unfinished sessions").performScrollTo().performClick()

        assertEquals(
            listOf(SettingsEvent.Notification.Toggled(NotificationKind.SESSION, true)),
            events,
        )
    }

    @Test
    fun `the reminders row reads back the schedule rather than carrying a switch`() =
        withLocale(Locale.UK) {
            setContent(
                UserPreferences(
                    notifications = NotificationPreferences(enabled = setOf(NotificationKind.REMINDERS)),
                    reminders = ReminderPreferences(
                        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                        timeOfDay = LocalTime.of(18, 0),
                    ),
                ),
            )

            // Pinned to a 24-hour locale, because the value is rendered through the same
            // localized pattern every other time in the app is: on an American phone the same
            // schedule reads `MWF · 6:00 PM`, and asserting one of the two would be asserting the
            // machine the test runs on.
            composeTestRule.onNodeWithText("MWF · 18:00").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `a reminder that is switched off reads as off, whatever is scheduled`() =
        withLocale(Locale.UK) {
            // The schedule is still stored and still shown on the screen behind the row. What this
            // row reports is whether anything is going to arrive.
            setContent(
                UserPreferences(
                    notifications = NotificationPreferences(enabled = emptySet()),
                    reminders = ReminderPreferences(timeOfDay = LocalTime.of(18, 0)),
                ),
            )

            composeTestRule.onNodeWithText("Off").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("MWF · 18:00").assertDoesNotExist()
        }

    @Test
    fun `the reminders row opens the reminder screen instead of toggling anything`() {
        setContent()

        composeTestRule.onNodeWithText("Workout reminders").performScrollTo().performClick()

        assertEquals(1, remindersOpened)
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    @Test
    fun `the whole row is the switch, not just the switch`() {
        // The row is a 360dp target rather than a 52dp one, and it is one node rather than a
        // label beside an unlabelled control.
        setContent(
            UserPreferences(notifications = NotificationPreferences(enabled = setOf(NotificationKind.REST_TIMER))),
        )

        composeTestRule.onNodeWithText("Rest timer alerts").performScrollTo().performClick()

        assertEquals(
            listOf(SettingsEvent.Notification.Toggled(NotificationKind.REST_TIMER, false)),
            events,
        )
    }

    @Test
    fun `a blocked permission is said out loud, and only over the switches it affects`() {
        setContent(
            UserPreferences(notifications = NotificationPreferences(enabled = setOf(NotificationKind.REST_TIMER))),
            notificationPermission = NotificationPermissionState.DENIED,
        )

        composeTestRule.onNodeWithText("Notifications are blocked").performScrollTo().assertIsDisplayed()
        // One badge, on the one switch that is on: flagging an off one would be telling the
        // user that something they turned off is not being delivered. Upper case because
        // `OpenGymBadge` treats capitals as typography rather than as content.
        composeTestRule.onAllNodesWithText("BLOCKED").assertCountEquals(1)
    }

    @Test
    fun `the blocked row leads into the system settings`() {
        setContent(notificationPermission = NotificationPermissionState.DENIED)

        composeTestRule.onNodeWithText("Notifications are blocked").performScrollTo().performClick()

        assertEquals(1, notificationSettingsOpened)
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    @Test
    fun `a permission nobody has asked for offers to ask`() {
        setContent(notificationPermission = NotificationPermissionState.NOT_REQUESTED)

        // Not the blocked wording: nothing has been refused, and telling somebody they are
        // blocked when they have never been asked is how a settings screen loses their trust.
        composeTestRule.onNodeWithText("Notifications are blocked").assertDoesNotExist()
        composeTestRule.onNodeWithText("Allow notifications").performScrollTo().performClick()

        assertEquals(listOf(SettingsEvent.Notification.PermissionRequested), events)
    }

    @Test
    fun `a granted permission says nothing at all`() {
        setContent(notificationPermission = NotificationPermissionState.GRANTED)

        composeTestRule.onNodeWithText("Notifications are blocked").assertDoesNotExist()
        composeTestRule.onNodeWithText("Allow notifications").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("BLOCKED").assertCountEquals(0)
    }

    // --- the destructive path ---------------------------------------------

    @Test
    fun `the delete row opens the confirmation`() {
        setContent()

        composeTestRule.onNodeWithText("Delete all data").performScrollTo().performClick()

        assertEquals(listOf(SettingsEvent.DialogRequested(SettingsDialog.DELETE_ALL)), events)
    }

    @Test
    fun `the confirmation says exactly what is lost`() {
        setContent(dialog = SettingsDialog.DELETE_ALL, footprint = FOOTPRINT)

        listOf("184", "sessions", "2,140", "sets", "every setting, back to its defaults")
            .forEach { line ->
                composeTestRule.onNodeWithText(line).performScrollTo().assertIsDisplayed()
            }
    }

    @Test
    fun `a kind the user has none of is not listed`() {
        setContent(
            dialog = SettingsDialog.DELETE_ALL,
            footprint = DataFootprint(sessions = 3, sets = 12),
        )

        composeTestRule.onNodeWithText("templates").assertDoesNotExist()
        composeTestRule.onNodeWithText("personal records").assertDoesNotExist()
    }

    @Test
    fun `the destructive button is inert until the word is typed`() {
        setContent(dialog = SettingsDialog.DELETE_ALL, footprint = FOOTPRINT)

        composeTestRule.onNodeWithText("Delete everything").assertIsNotEnabled()

        // A near miss is not the word. This is the whole safeguard: the button cannot be
        // reached by tapping, only by reading and typing.
        composeTestRule.onNodeWithText("Type DELETE to confirm").performScrollTo().assertIsDisplayed()
        typeConfirmation("DELET")
        composeTestRule.onNodeWithText("Delete everything").assertIsNotEnabled()
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    @Test
    fun `typing the word arms the button, and pressing it reports the deletion`() {
        setContent(dialog = SettingsDialog.DELETE_ALL, footprint = FOOTPRINT)

        typeConfirmation("DELETE")

        composeTestRule.onNodeWithText("Delete everything").assertIsEnabled().performClick()

        assertEquals(listOf(SettingsEvent.DeleteAllConfirmed), events)
    }

    @Test
    fun `the word is accepted whatever case it is typed in`() {
        setContent(dialog = SettingsDialog.DELETE_ALL, footprint = FOOTPRINT)

        typeConfirmation("delete")

        composeTestRule.onNodeWithText("Delete everything").assertIsEnabled()
    }

    @Test
    fun `cancelling reports a dismissal and never a deletion`() {
        setContent(dialog = SettingsDialog.DELETE_ALL, footprint = FOOTPRINT)

        // Armed first, so the test covers the case that actually matters: backing out *after*
        // typing the word, which must still delete nothing.
        typeConfirmation("DELETE")
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf(SettingsEvent.DialogDismissed), events)
    }

    @Test
    fun `a running wipe locks the dialog rather than letting it be started twice`() {
        setContent(dialog = SettingsDialog.DELETE_ALL, footprint = FOOTPRINT, isDeleting = true)

        composeTestRule.onNodeWithText("Deleting…").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    // --- links ------------------------------------------------------------

    @Test
    fun `the GitHub row opens the repository`() {
        setContent()

        composeTestRule.onNodeWithText("Open source · GitHub").performScrollTo().performClick()

        assertEquals(listOf("https://github.com/open-gym-app/open-gym"), openedLinks)
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    // --- helpers ----------------------------------------------------------

    /** Runs [block] with [locale] as the default, and puts the previous one back. */
    private fun withLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** The dialog has exactly one field, and it is the confirmation. */
    private fun typeConfirmation(text: String) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput(text)
    }

    private fun setContent(
        preferences: UserPreferences = UserPreferences(),
        dialog: SettingsDialog? = null,
        footprint: DataFootprint = DataFootprint(),
        isDeleting: Boolean = false,
        appVersion: String = "1.0",
        notificationPermission: NotificationPermissionState = NotificationPermissionState.GRANTED,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        preferences = preferences,
                        footprint = footprint,
                        dialog = dialog,
                        isDeleting = isDeleting,
                        notificationPermission = notificationPermission,
                    ),
                    appVersion = appVersion,
                    onEvent = events::add,
                    onBack = {},
                    onOpenBackup = { backupOpened++ },
                    onOpenLink = openedLinks::add,
                    onOpenNotificationSettings = { notificationSettingsOpened++ },
                    onOpenReminders = { remindersOpened++ },
                )
            }
        }
    }

    private companion object {
        val FOOTPRINT = DataFootprint(
            sessions = 184,
            sets = 2_140,
            templates = 6,
            personalRecords = 23,
            customExercises = 2,
            bodyMeasurements = 61,
        )
    }
}
