package com.opengym.feature.settings

import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
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

            assertEquals(
                UserPreferences(
                    theme = ThemePreference.LIGHT,
                    unitSystem = UnitSystem.IMPERIAL,
                    defaultRestDuration = 240.seconds,
                    barWeight = 15_000_000,
                    trainingGoal = TrainingGoal.HYPERTROPHY,
                ),
                viewModel.uiState.value.preferences,
            )
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

            assertEquals(SettingsMessage.SAVE_FAILED, viewModel.uiState.value.message)
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

            assertEquals(SettingsMessage.SAVE_FAILED, viewModel.uiState.value.message)
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
            assertEquals(SettingsMessage.DELETE_SUCCEEDED, state.message)
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
            assertEquals(SettingsMessage.DELETE_FAILED, viewModel.uiState.value.message)
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

    // --- helpers ----------------------------------------------------------

    private fun viewModel(
        preferences: FakeUserPreferencesRepository = this.preferences,
        localData: FakeLocalDataRepository = this.localData,
    ) = SettingsViewModel(preferences, localData)

    /** `WhileSubscribed` needs a subscriber, or the state never leaves its initial value. */
    private fun TestScope.collectState(viewModel: SettingsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }
}
