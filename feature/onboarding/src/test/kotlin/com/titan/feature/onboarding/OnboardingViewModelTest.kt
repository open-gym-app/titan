package com.titan.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.feature.onboarding.afterProcessDeath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * The onboarding ViewModel against an in-memory store.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow is not started and the state stays at its initial value,
 * which would make every assertion below pass for the wrong reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val preferences = FakeUserPreferencesRepository()

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
    fun `the flow opens on the first step and nothing is recorded yet`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)
            assertFalse(viewModel.uiState.value.isFinished)
            assertFalse(preferences.current.onboardingCompleted)
        }

    @Test
    fun `answering every step stores every answer and completes the flow`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)

            assertEquals(OnboardingStep.GOAL, viewModel.uiState.value.step)
            viewModel.onEvent(OnboardingEvent.TrainingGoalSelected(TrainingGoal.HYPERTROPHY))
            // Written as it is chosen, not banked for the end — which is what makes the app
            // killed on step three relaunch knowing the answer to step two.
            assertEquals(TrainingGoal.HYPERTROPHY, preferences.current.trainingGoal)
            viewModel.onEvent(OnboardingEvent.Continued)

            assertEquals(OnboardingStep.LEVEL, viewModel.uiState.value.step)
            viewModel.onEvent(OnboardingEvent.FitnessLevelSelected(FitnessLevel.INTERMEDIATE))
            assertEquals(FitnessLevel.INTERMEDIATE, preferences.current.fitnessLevel)
            viewModel.onEvent(OnboardingEvent.Continued)

            assertEquals(OnboardingStep.UNITS, viewModel.uiState.value.step)
            viewModel.onEvent(OnboardingEvent.UnitSystemSelected(UnitSystem.IMPERIAL))
            assertEquals(UnitSystem.IMPERIAL, preferences.current.unitSystem)
            viewModel.onEvent(OnboardingEvent.Continued)

            assertEquals(OnboardingStep.READY, viewModel.uiState.value.step)
            viewModel.onEvent(OnboardingEvent.Continued)

            assertEquals(
                UserPreferences(
                    unitSystem = UnitSystem.IMPERIAL,
                    trainingGoal = TrainingGoal.HYPERTROPHY,
                    fitnessLevel = FitnessLevel.INTERMEDIATE,
                    onboardingCompleted = true,
                ),
                preferences.current,
            )
            assertTrue(viewModel.uiState.value.isFinished)
        }

    @Test
    fun `skipping every step applies the documented defaults and still completes`() =
        runTest {
            // Started from the *opposite* of every default, so "the defaults were applied" cannot
            // pass by the store simply never having been written.
            val stored = FakeUserPreferencesRepository(
                UserPreferences(
                    unitSystem = UnitSystem.IMPERIAL,
                    trainingGoal = TrainingGoal.CUSTOM,
                    fitnessLevel = FitnessLevel.ADVANCED,
                ),
            )
            val viewModel = viewModel(stored)
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Skipped)
            viewModel.onEvent(OnboardingEvent.Skipped)
            viewModel.onEvent(OnboardingEvent.Skipped)
            viewModel.onEvent(OnboardingEvent.Skipped)

            assertEquals(OnboardingStep.READY, viewModel.uiState.value.step)
            assertEquals(OnboardingViewModel.DEFAULT_TRAINING_GOAL, stored.current.trainingGoal)
            assertEquals(OnboardingViewModel.DEFAULT_FITNESS_LEVEL, stored.current.fitnessLevel)
            assertEquals(OnboardingViewModel.DEFAULT_UNIT_SYSTEM, stored.current.unitSystem)

            viewModel.onEvent(OnboardingEvent.Continued)

            // A user who answered nothing has still been through onboarding, and is not asked again.
            assertTrue(stored.current.onboardingCompleted)
            assertTrue(viewModel.uiState.value.isFinished)
        }

    @Test
    fun `back walks the flow rather than leaving it, and stops at the first step`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            assertEquals(OnboardingStep.UNITS, viewModel.uiState.value.step)

            viewModel.onEvent(OnboardingEvent.BackPressed)
            assertEquals(OnboardingStep.LEVEL, viewModel.uiState.value.step)

            viewModel.onEvent(OnboardingEvent.BackPressed)
            assertEquals(OnboardingStep.GOAL, viewModel.uiState.value.step)

            viewModel.onEvent(OnboardingEvent.BackPressed)
            assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)

            // The screen does not register a handler on the first step, so this cannot happen
            // through the UI. It is answered anyway, because "nowhere to go" must not be a crash.
            viewModel.onEvent(OnboardingEvent.BackPressed)
            assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)
        }

    @Test
    fun `skipping a step already answered puts the default back`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.TrainingGoalSelected(TrainingGoal.ENDURANCE))
            viewModel.onEvent(OnboardingEvent.Skipped)

            // Skip means "the default", not "whatever is in there" — otherwise a user who
            // mistapped an option has no way of un-answering the question.
            assertEquals(OnboardingViewModel.DEFAULT_TRAINING_GOAL, preferences.current.trainingGoal)
        }

    // --- the level step (#M7-11) ------------------------------------------

    @Test
    fun `the level is asked after the goal and written as it is chosen`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            assertEquals(OnboardingStep.LEVEL, viewModel.uiState.value.step)

            viewModel.onEvent(OnboardingEvent.FitnessLevelSelected(FitnessLevel.ADVANCED))

            assertEquals(FitnessLevel.ADVANCED, preferences.current.fitnessLevel)
            // Choosing does not advance: the step is left for `Continue`, like every other.
            assertEquals(OnboardingStep.LEVEL, viewModel.uiState.value.step)
        }

    @Test
    fun `back from the level returns to the goal with the level kept`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.FitnessLevelSelected(FitnessLevel.INTERMEDIATE))

            viewModel.onEvent(OnboardingEvent.BackPressed)

            // Nothing is undone by going back: the answer is on disk, and coming forward again
            // shows it selected rather than the default.
            assertEquals(OnboardingStep.GOAL, viewModel.uiState.value.step)
            assertEquals(FitnessLevel.INTERMEDIATE, viewModel.uiState.value.preferences.fitnessLevel)
        }

    @Test
    fun `skipping the level stores a beginner, whatever was chosen before`() =
        runTest {
            val stored = FakeUserPreferencesRepository(UserPreferences(fitnessLevel = FitnessLevel.ADVANCED))
            val viewModel = viewModel(stored)
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Skipped)

            // The safe answer: a user who did not want to say is progressed most cautiously.
            assertEquals(FitnessLevel.BEGINNER, OnboardingViewModel.DEFAULT_FITNESS_LEVEL)
            assertEquals(FitnessLevel.BEGINNER, stored.current.fitnessLevel)
            assertEquals(OnboardingStep.UNITS, viewModel.uiState.value.step)
        }

    @Test
    fun `an answer that cannot be stored is reported, and the step still advances`() =
        runTest {
            preferences.writeFailure = IOException("no space left on device")
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.TrainingGoalSelected(TrainingGoal.ENDURANCE))
            viewModel.onEvent(OnboardingEvent.Continued)

            assertEquals(OnboardingMessage.SAVE_FAILED, viewModel.uiState.value.message)
            // Said out loud, but not a wall: the value that failed to write is the default the
            // store is already reporting, and trapping a new user in step two over it is worse.
            assertEquals(OnboardingStep.LEVEL, viewModel.uiState.value.step)
        }

    @Test
    fun `completion that cannot be recorded does not finish the flow`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Continued)
            assertEquals(OnboardingStep.READY, viewModel.uiState.value.step)
            preferences.writeFailure = IOException("read-only file system")

            viewModel.onEvent(OnboardingEvent.Continued)

            // The one failure that must stop the user: navigating to Home without the flag on disk
            // means the next launch asks all five questions over again.
            assertEquals(OnboardingMessage.FINISH_FAILED, viewModel.uiState.value.message)
            assertFalse(viewModel.uiState.value.isFinished)
            assertFalse(viewModel.uiState.value.isFinishing)
            assertFalse(preferences.current.onboardingCompleted)
        }

    @Test
    fun `a message is shown once`() =
        runTest {
            preferences.writeFailure = IOException("no space left on device")
            val viewModel = viewModel()
            collectState(viewModel)

            // Skipped on the *goal* step: the welcome step asks nothing, so skipping it writes
            // nothing and there would be no failure to report.
            viewModel.onEvent(OnboardingEvent.Continued)
            viewModel.onEvent(OnboardingEvent.Skipped)
            assertEquals(OnboardingMessage.SAVE_FAILED, viewModel.uiState.value.message)

            viewModel.onEvent(OnboardingEvent.MessageShown)
            assertNull(viewModel.uiState.value.message)
        }

    // --- process death (#183) ---------------------------------------------

    @Test
    fun `the step the user had reached comes back after process death`() =
        runTest {
            val handle = SavedStateHandle()
            val before = viewModel(savedState = handle)
            collectState(before)
            before.onEvent(OnboardingEvent.Continued)
            before.onEvent(OnboardingEvent.Continued)
            val reached = before.uiState.value.step

            val restored = viewModel(savedState = handle.afterProcessDeath())
            collectState(restored)

            assertEquals(reached, restored.uiState.value.step)
            assertFalse("a finished flow would navigate the user out", restored.uiState.value.isFinished)
        }

    @Test
    fun `a level chosen before process death is still the answer afterwards`() =
        runTest {
            val handle = SavedStateHandle()
            val before = viewModel(savedState = handle)
            collectState(before)
            before.onEvent(OnboardingEvent.Continued)
            before.onEvent(OnboardingEvent.Continued)
            before.onEvent(OnboardingEvent.FitnessLevelSelected(FitnessLevel.ADVANCED))

            // The store outlives the process where the ViewModel does not; the answer was
            // written when it was given, so the rebuilt flow reads it back rather than a draft.
            val restored = viewModel(savedState = handle.afterProcessDeath())
            collectState(restored)

            assertEquals(OnboardingStep.LEVEL, restored.uiState.value.step)
            assertEquals(FitnessLevel.ADVANCED, restored.uiState.value.preferences.fitnessLevel)
        }

    // --- helpers ----------------------------------------------------------

    private fun viewModel(
        preferences: FakeUserPreferencesRepository = this.preferences,
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = OnboardingViewModel(savedState, preferences)

    /** `WhileSubscribed` needs a subscriber, or the state never leaves its initial value. */
    private fun TestScope.collectState(viewModel: OnboardingViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }
}
