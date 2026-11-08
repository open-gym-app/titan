package com.titan.app

import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration

/**
 * The root state: the preferences the whole app renders against, and the one field that must
 * *not* follow them.
 *
 * The theme and the unit system are live — a write has to repaint the app, which is the whole
 * reason this ViewModel exists. [MainUiState.Ready.startsOnOnboarding] is the exception, and
 * the two tests below are the pair that make the distinction real rather than incidental.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a device that has not been through onboarding starts there`() =
        runTest {
            val viewModel = MainViewModel(FakePreferences(UserPreferences()))
            collectState(viewModel)

            assertTrue(ready(viewModel).startsOnOnboarding)
        }

    @Test
    fun `a device that has starts on the tabs`() =
        runTest {
            val viewModel = MainViewModel(FakePreferences(UserPreferences(onboardingCompleted = true)))
            collectState(viewModel)

            assertFalse(ready(viewModel).startsOnOnboarding)
        }

    @Test
    fun `completing onboarding does not change where this launch started`() =
        runTest {
            val store = FakePreferences(UserPreferences())
            val viewModel = MainViewModel(store)
            collectState(viewModel)

            store.setOnboardingCompleted(true)

            // The field is the `NavHost`'s start destination, and Navigation Compose rebuilds the
            // graph — emptying the back stack — when it changes. Following the flag here would
            // therefore tear the graph down underneath the navigation onboarding makes as it
            // records its own completion.
            assertTrue(ready(viewModel).startsOnOnboarding)
            // The preferences themselves *do* follow, which is the distinction under test.
            assertTrue(ready(viewModel).preferences.onboardingCompleted)
        }

    @Test
    fun `a preference change is published`() =
        runTest {
            val store = FakePreferences(UserPreferences())
            val viewModel = MainViewModel(store)
            collectState(viewModel)

            store.setTheme(ThemePreference.DARK)

            assertEquals(ThemePreference.DARK, ready(viewModel).preferences.theme)
        }

    // --- helpers ----------------------------------------------------------

    private fun ready(viewModel: MainViewModel): MainUiState.Ready = viewModel.uiState.value as MainUiState.Ready

    /** `Eagerly` still needs the flow to have run; collecting is what pumps the fold. */
    private fun TestScope.collectState(viewModel: MainViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    /**
     * The store as a `MutableStateFlow`: every setter writes, and the flow re-emits.
     *
     * Every setter is implemented rather than only the two these tests call. A `TODO()` in the
     * rest would be a landmine for the next test added here, and this ViewModel's whole
     * subject is what the *flow* does after a write — so a fake that could not be written to
     * would be a fake that cannot express the thing under test.
     */
    private class FakePreferences(initial: UserPreferences) : UserPreferencesRepository {

        private val preferences = MutableStateFlow(initial)

        override fun observe(): Flow<UserPreferences> = preferences

        override suspend fun setTheme(theme: ThemePreference) {
            preferences.update { it.copy(theme = theme) }
        }

        override suspend fun setOnboardingCompleted(completed: Boolean) {
            preferences.update { it.copy(onboardingCompleted = completed) }
        }

        override suspend fun setUnitSystem(unitSystem: UnitSystem) {
            preferences.update { it.copy(unitSystem = unitSystem) }
        }

        override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) {
            preferences.update { it.copy(trainingGoal = trainingGoal) }
        }

        override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) {
            preferences.update { it.copy(fitnessLevel = fitnessLevel) }
        }

        override suspend fun setCustomRepRange(range: RepRange) {
            preferences.update { it.copy(customRepRange = range) }
        }

        override suspend fun setCustomIncrement(milligrams: Long?) {
            preferences.update { it.copy(customIncrement = milligrams) }
        }

        override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) {
            preferences.update { it.copy(oneRepMaxFormula = formula) }
        }

        override suspend fun setWarmupSuggestions(enabled: Boolean) {
            preferences.update { it.copy(warmupSuggestions = enabled) }
        }

        override suspend fun setWeeklyWorkoutGoal(goal: Int) {
            preferences.update { it.copy(weeklyWorkoutGoal = goal) }
        }

        override suspend fun setDefaultRestDuration(duration: Duration) {
            preferences.update { it.copy(defaultRestDuration = duration) }
        }

        override suspend fun setBarWeight(barWeight: Long) {
            preferences.update { it.copy(barWeight = barWeight) }
        }

        override suspend fun setPlateInventory(inventory: PlateInventory?) {
            preferences.update { it.copy(plateInventory = inventory) }
        }

        override suspend fun setPlatePairs(
            weight: Long,
            pairs: Int,
        ) {
            preferences.update { it.copy(plateInventory = it.plates.withPairs(weight, pairs)) }
        }

        override suspend fun setNotificationEnabled(
            kind: NotificationKind,
            enabled: Boolean,
        ) {
            preferences.update { it.copy(notifications = it.notifications.with(kind, enabled)) }
        }

        override suspend fun setNotificationPermissionRequested() {
            preferences.update { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }
        }

        override suspend fun setReminderDay(
            day: DayOfWeek,
            enabled: Boolean,
        ) {
            preferences.update {
                val days = it.reminders.days
                it.copy(reminders = it.reminders.copy(days = if (enabled) days + day else days - day))
            }
        }

        override suspend fun setReminderTime(time: LocalTime) {
            preferences.update { it.copy(reminders = it.reminders.copy(timeOfDay = time)) }
        }

        override suspend fun clear() {
            preferences.value = UserPreferences()
        }
    }
}
