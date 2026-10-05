package com.titan.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPermissionState
import com.titan.core.domain.notification.NotificationPreferences
import com.titan.core.domain.usecase.CreateTemplateUseCase
import com.titan.core.domain.usecase.RequestNotificationPermissionUseCase
import com.titan.core.domain.usecase.RestoreDefaultTemplatesUseCase
import com.titan.feature.settings.afterProcessDeath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The settings ViewModel against in-memory stores.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow is not started and the state stays at its initial value,
 * which would make every assertion below pass for the wrong reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val preferences = FakeUserPreferencesRepository()
    private val localData = FakeLocalDataRepository()
    private val notificationPermission = FakeNotificationPermission()
    private val templates = FakeTemplateRepository("Push", "Legs", "Arms")
    private val defaults = FakeDefaultTemplateCatalogue("Push", "Legs", "Arms")

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so a launched write has run by the
        // time the call that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the state is what the stores say`() =
        runTest {
            val viewModel = viewModel(
                preferences = FakeUserPreferencesRepository(
                    UserPreferences(theme = ThemePreference.DARK, barWeight = 15_000_000),
                ),
                localData = FakeLocalDataRepository(DataFootprint(sessions = 12, sets = 140)),
            )
            collectState(viewModel)

            val state = viewModel.uiState.value
            assertEquals(ThemePreference.DARK, state.preferences.theme)
            assertEquals(15_000_000L, state.preferences.barWeight)
            assertEquals(DataFootprint(sessions = 12, sets = 140), state.footprint)
        }

    @Test
    fun `a row's event opens its dialog, and dismissing closes it`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.DialogRequested(SettingsDialog.THEME))
            assertEquals(SettingsDialog.THEME, viewModel.uiState.value.dialog)

            viewModel.onEvent(SettingsEvent.DialogDismissed)
            assertNull(viewModel.uiState.value.dialog)
        }

    @Test
    fun `every setting is written through, and the state follows the store`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.ThemeSelected(ThemePreference.LIGHT))
            viewModel.onEvent(SettingsEvent.UnitSystemSelected(UnitSystem.IMPERIAL))
            viewModel.onEvent(SettingsEvent.RestDurationSelected(240.seconds))
            viewModel.onEvent(SettingsEvent.BarWeightSelected(15_000_000))
            viewModel.onEvent(SettingsEvent.TrainingGoalSelected(TrainingGoal.HYPERTROPHY))
            viewModel.onEvent(SettingsEvent.FitnessLevelSelected(FitnessLevel.ADVANCED))
            viewModel.onEvent(SettingsEvent.OneRepMaxFormulaSelected(OneRepMaxFormula.BRZYCKI))
            viewModel.onEvent(SettingsEvent.WarmupSuggestionsToggled(false))
            viewModel.onEvent(SettingsEvent.WeeklyGoalSelected(3))

            assertEquals(
                UserPreferences(
                    theme = ThemePreference.LIGHT,
                    unitSystem = UnitSystem.IMPERIAL,
                    defaultRestDuration = 240.seconds,
                    barWeight = 15_000_000,
                    trainingGoal = TrainingGoal.HYPERTROPHY,
                    fitnessLevel = FitnessLevel.ADVANCED,
                    oneRepMaxFormula = OneRepMaxFormula.BRZYCKI,
                    warmupSuggestions = false,
                    weeklyWorkoutGoal = 3,
                ),
                viewModel.uiState.value.preferences,
            )
        }

    @Test
    fun `the warm-up switch goes back on without a dialog in the way`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.WarmupSuggestionsToggled(false))
            viewModel.onEvent(SettingsEvent.WarmupSuggestionsToggled(true))

            assertTrue(viewModel.uiState.value.preferences.warmupSuggestions)
            assertNull(viewModel.uiState.value.dialog)
        }

    @Test
    fun `a CUSTOM goal writes its range and step beside it`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(
                SettingsEvent.TrainingGoalSelected(
                    TrainingGoal.CUSTOM,
                    customRepRange = RepRange(6, 10),
                    customIncrement = 1_250_000,
                ),
            )

            val preferences = viewModel.uiState.value.preferences
            assertEquals(TrainingGoal.CUSTOM, preferences.trainingGoal)
            assertEquals(RepRange(6, 10), preferences.customRepRange)
            assertEquals(1_250_000L, preferences.customIncrement)
        }

    @Test
    fun `a named goal leaves the CUSTOM range and step as they were`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            viewModel.onEvent(
                SettingsEvent.TrainingGoalSelected(TrainingGoal.CUSTOM, RepRange(6, 10), 1_250_000),
            )

            viewModel.onEvent(SettingsEvent.TrainingGoalSelected(TrainingGoal.STRENGTH))

            // Kept, so a month on HYPERTROPHY does not cost the user the range they typed.
            val preferences = viewModel.uiState.value.preferences
            assertEquals(TrainingGoal.STRENGTH, preferences.trainingGoal)
            assertEquals(RepRange(6, 10), preferences.customRepRange)
            assertEquals(1_250_000L, preferences.customIncrement)
        }

    @Test
    fun `choosing a value closes the picker`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.DialogRequested(SettingsDialog.THEME))
            viewModel.onEvent(SettingsEvent.ThemeSelected(ThemePreference.LIGHT))

            assertNull(viewModel.uiState.value.dialog)
        }

    @Test
    fun `a write that fails is reported instead of being swallowed`() =
        runTest {
            preferences.writeFailure = IOException("no space left on device")
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.ThemeSelected(ThemePreference.LIGHT))

            assertEquals(SettingsMessage.SaveFailed, viewModel.uiState.value.message)
            // And the row still shows the stored value, because nothing was changed optimistically.
            assertEquals(ThemePreference.SYSTEM, viewModel.uiState.value.preferences.theme)
        }

    @Test
    fun `a value the store refuses is reported, not thrown at the user`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            // `setBarWeight` requires a positive weight, and the dialog converts to milligrams
            // before it validates — but a rejected write is a rejected write however it is
            // rejected, and an `IllegalArgumentException` out of `viewModelScope` is a crash.
            viewModel.onEvent(SettingsEvent.BarWeightSelected(0))

            assertEquals(SettingsMessage.SaveFailed, viewModel.uiState.value.message)
            assertEquals(UserPreferences().barWeight, viewModel.uiState.value.preferences.barWeight)
        }

    @Test
    fun `a message is shown once and then dropped`() =
        runTest {
            preferences.writeFailure = IOException("no space left on device")
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.ThemeSelected(ThemePreference.LIGHT))
            viewModel.onEvent(SettingsEvent.MessageShown)

            assertNull(viewModel.uiState.value.message)
        }

    // --- restoring the default templates ----------------------------------

    @Test
    fun `the state names the defaults the user has deleted, and follows the template list`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            assertEquals(emptyList<String>(), viewModel.uiState.value.missingDefaultTemplates)

            templates.delete(2L)

            assertEquals(listOf("Legs"), viewModel.uiState.value.missingDefaultTemplates)
        }

    @Test
    fun `confirming the restore adds the missing defaults, closes the dialog and reports the count`() =
        runTest {
            val templates = FakeTemplateRepository("Push")
            val viewModel = viewModel(templates = templates)
            collectState(viewModel)
            viewModel.onEvent(SettingsEvent.DialogRequested(SettingsDialog.RESTORE_TEMPLATES))

            viewModel.onEvent(SettingsEvent.RestoreDefaultTemplatesConfirmed)

            val state = viewModel.uiState.value
            assertEquals(listOf("Push", "Legs", "Arms"), templates.names)
            assertNull(state.dialog)
            assertEquals(SettingsMessage.TemplatesRestored(2), state.message)
            assertEquals(emptyList<String>(), state.missingDefaultTemplates)
        }

    @Test
    fun `an unreadable catalogue leaves the screen working, with nothing to restore`() =
        runTest {
            defaults.readFailure = IOException("database is locked")
            val viewModel = viewModel(
                preferences = FakeUserPreferencesRepository(UserPreferences(theme = ThemePreference.DARK)),
            )
            collectState(viewModel)

            assertEquals(ThemePreference.DARK, viewModel.uiState.value.preferences.theme)
            assertEquals(emptyList<String>(), viewModel.uiState.value.missingDefaultTemplates)
        }

    @Test
    fun `a restore that cannot write says so`() =
        runTest {
            val templates = FakeTemplateRepository()
            templates.insertFailure = IOException("disk full")
            val viewModel = viewModel(templates = templates)
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.RestoreDefaultTemplatesConfirmed)

            assertEquals(SettingsMessage.RestoreFailed, viewModel.uiState.value.message)
        }

    // --- the destructive path ---------------------------------------------

    @Test
    fun `confirming the deletion wipes everything and says so`() =
        runTest {
            val viewModel = viewModel(
                localData = FakeLocalDataRepository(DataFootprint(sessions = 12, sets = 140)),
            )
            collectState(viewModel)
            viewModel.onEvent(SettingsEvent.DialogRequested(SettingsDialog.DELETE_ALL))

            viewModel.onEvent(SettingsEvent.DeleteAllConfirmed)

            val state = viewModel.uiState.value
            assertEquals(DataFootprint(), state.footprint)
            assertEquals(SettingsMessage.DeleteSucceeded, state.message)
            // The dialog closes itself when the wipe is over, not when it is started.
            assertNull(state.dialog)
            assertEquals(false, state.isDeleting)
        }

    @Test
    fun `a wipe that fails says so rather than reporting success`() =
        runTest {
            val failing = FakeLocalDataRepository(DataFootprint(sessions = 12))
            failing.deleteFailure = IllegalStateException("the database is locked")
            val viewModel = viewModel(localData = failing)
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.DeleteAllConfirmed)

            // Not a crash, and not a success: this is the one action where the user has to be told
            // that what they were promised did not happen.
            assertEquals(SettingsMessage.DeleteFailed, viewModel.uiState.value.message)
            assertEquals(false, viewModel.uiState.value.isDeleting)
        }

    @Test
    fun `a second confirmation while a wipe is running is ignored`() =
        runTest {
            // The standard dispatcher, deliberately: the unconfined one used everywhere else runs
            // the wipe to completion inside `onEvent`, so there is no window in which a second
            // confirmation could arrive — which is precisely the window under test here.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.DeleteAllConfirmed)
            viewModel.onEvent(SettingsEvent.DeleteAllConfirmed)

            advanceUntilIdle()

            // The guard reads the ViewModel's own state, which is updated before the coroutine is
            // even dispatched — so the second confirmation is dropped rather than queued.
            assertEquals(1, localData.deleteCount)
            assertEquals(false, viewModel.uiState.value.isDeleting)
        }

    // --- notifications ----------------------------------------------------

    @Test
    fun `enabling nothing asks for nothing`() =
        runTest {
            // The acceptance criterion, stated as a test: the permission prompt is not part of
            // opening settings, and it is not part of changing a setting that has nothing to
            // do with notifications. A prompt with no context attached is the one most
            // reliably refused, and a refusal cannot be taken back.
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.ThemeSelected(ThemePreference.DARK))
            viewModel.onEvent(SettingsEvent.UnitSystemSelected(UnitSystem.IMPERIAL))
            viewModel.onEvent(SettingsEvent.DialogRequested(SettingsDialog.DELETE_ALL))

            assertFalse(viewModel.uiState.value.notificationPermissionRequest)
            assertFalse(preferences.observe().first().notifications.permissionRequested)
        }

    @Test
    fun `turning a notification off asks for nothing either`() =
        runTest {
            // The rest timer is the one kind that starts on, so turning it *off* is the first
            // notification event most users produce. There is nothing to deliver, so there is
            // nothing to ask for.
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.REST_TIMER, false))

            assertFalse(viewModel.uiState.value.notificationPermissionRequest)
            assertFalse(preferences.observe().first().notifications.permissionRequested)
        }

    @Test
    fun `turning the first notification on is what raises the prompt`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.REMINDERS, true))

            val state = viewModel.uiState.value
            assertTrue(state.notificationPermissionRequest)
            // The preference is stored whatever the permission turns out to be: it is what the
            // user wants, not a promise that anything will arrive.
            assertTrue(NotificationKind.REMINDERS in state.preferences.notifications)
        }

    @Test
    fun `the prompt goes up once, however many switches are turned on`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.REMINDERS, true))
            viewModel.onEvent(SettingsEvent.Notification.PermissionRequestShown)
            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.SESSION, true))

            // Android shows it once and answers for the user thereafter, so a second request
            // would be a no-op the user never sees — and the row that leads into the system
            // settings is what the screen offers instead.
            assertFalse(viewModel.uiState.value.notificationPermissionRequest)
            assertEquals(NotificationPermissionState.DENIED, viewModel.uiState.value.notificationPermission)
        }

    @Test
    fun `a permission that is already granted is not asked for`() =
        runTest {
            val viewModel = viewModel(notificationPermission = FakeNotificationPermission(allowed = true))
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.PROGRESSION, true))

            assertFalse(viewModel.uiState.value.notificationPermissionRequest)
            assertEquals(NotificationPermissionState.GRANTED, viewModel.uiState.value.notificationPermission)
        }

    @Test
    fun `there is nothing to prompt for below Android 13, so the state is denied outright`() =
        runTest {
            // No runtime permission to ask for: notifications were granted at install and the
            // user has since switched them off by hand. Only system settings can undo that.
            val viewModel = viewModel(
                notificationPermission = FakeNotificationPermission(
                    allowed = false,
                    isRuntimePermissionRequired = false,
                ),
            )
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.REMINDERS, true))

            assertFalse(viewModel.uiState.value.notificationPermissionRequest)
            assertEquals(NotificationPermissionState.DENIED, viewModel.uiState.value.notificationPermission)
        }

    @Test
    fun `the state follows the system when the screen comes back`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.REMINDERS, true))
            viewModel.onEvent(SettingsEvent.Notification.PermissionRequestShown)

            // The user answers the prompt, or comes back from the system settings the blocked
            // row leads to. Nothing tells the app but the recheck.
            notificationPermission.allowed = true
            viewModel.onEvent(SettingsEvent.Notification.PermissionRechecked)

            assertEquals(NotificationPermissionState.GRANTED, viewModel.uiState.value.notificationPermission)
        }

    @Test
    fun `the row that offers the prompt raises it without a switch being touched`() =
        runTest {
            // The fresh-install case the switches cannot reach: the rest timer is already on,
            // so there is nothing left to enable, and the permission has still never been asked
            // for.
            val viewModel = viewModel()
            collectState(viewModel)
            assertEquals(NotificationPermissionState.NOT_REQUESTED, viewModel.uiState.value.notificationPermission)

            viewModel.onEvent(SettingsEvent.Notification.PermissionRequested)

            assertTrue(viewModel.uiState.value.notificationPermissionRequest)
        }

    @Test
    fun `the request is cleared once the screen has raised it`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            viewModel.onEvent(SettingsEvent.Notification.PermissionRequested)

            viewModel.onEvent(SettingsEvent.Notification.PermissionRequestShown)

            // Or the next recomposition raises a second prompt over the first.
            assertFalse(viewModel.uiState.value.notificationPermissionRequest)
        }

    @Test
    fun `a notification write that fails says so`() =
        runTest {
            val failing = FakeUserPreferencesRepository()
            failing.writeFailure = IOException("no space left on device")
            val viewModel = viewModel(preferences = failing)
            collectState(viewModel)

            viewModel.onEvent(SettingsEvent.Notification.Toggled(NotificationKind.REMINDERS, true))

            assertEquals(SettingsMessage.SaveFailed, viewModel.uiState.value.message)
        }

    @Test
    fun `a store that already knows the answer renders the switches from it`() =
        runTest {
            val viewModel = viewModel(
                preferences = FakeUserPreferencesRepository(
                    UserPreferences(
                        notifications = NotificationPreferences(
                            enabled = setOf(NotificationKind.SESSION),
                            permissionRequested = true,
                        ),
                    ),
                ),
            )
            collectState(viewModel)

            val notifications = viewModel.uiState.value.preferences.notifications
            assertEquals(setOf(NotificationKind.SESSION), notifications.enabled)
            assertEquals(NotificationPermissionState.DENIED, viewModel.uiState.value.notificationPermission)
        }

    // --- process death (#183) ---------------------------------------------

    @Test
    fun `an open dialog comes back after process death`() =
        runTest {
            val handle = SavedStateHandle()
            val before = viewModel(savedState = handle)
            collectState(before)
            before.onEvent(SettingsEvent.DialogRequested(SettingsDialog.REST_DURATION))

            val restored = viewModel(savedState = handle.afterProcessDeath())
            collectState(restored)

            assertEquals(SettingsDialog.REST_DURATION, restored.uiState.value.dialog)
        }

    // --- helpers ----------------------------------------------------------

    private fun viewModel(
        preferences: FakeUserPreferencesRepository = this.preferences,
        localData: FakeLocalDataRepository = this.localData,
        notificationPermission: FakeNotificationPermission = this.notificationPermission,
        templates: FakeTemplateRepository = this.templates,
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = SettingsViewModel(
        savedState,
        preferences,
        localData,
        notificationPermission,
        RequestNotificationPermissionUseCase(preferences, notificationPermission),
        RestoreDefaultTemplatesUseCase(defaults, templates, CreateTemplateUseCase(templates)),
    )

    /** `WhileSubscribed` needs a subscriber, or the state never leaves its initial value. */
    private fun TestScope.collectState(viewModel: SettingsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }
}
