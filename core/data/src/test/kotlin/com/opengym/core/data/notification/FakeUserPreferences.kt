package com.opengym.core.data.notification

import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.RepRange
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration

/**
 * The settings store, in memory.
 *
 * The reminder scheduler and the reminder broadcast both read the whole [UserPreferences] and
 * write nothing, so what these tests need from a store is a value they can set and a flow that
 * emits it. The real `UserPreferencesRepositoryImpl` has its own test over a real DataStore;
 * driving it from here would be testing DataStore twice and the scheduler once.
 */
class FakeUserPreferences(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = write { it.copy(unitSystem = unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) = write { it.copy(defaultRestDuration = duration) }

    override suspend fun setBarWeight(barWeight: Long) = write { it.copy(barWeight = barWeight) }

    override suspend fun setPlateInventory(inventory: PlateInventory?) = write { it.copy(plateInventory = inventory) }

    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) = write { it.copy(plateInventory = it.plates.withPairs(weight, pairs)) }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) = write { it.copy(fitnessLevel = fitnessLevel) }

    override suspend fun setCustomRepRange(range: RepRange) = write { it.copy(customRepRange = range) }

    override suspend fun setCustomIncrement(milligrams: Long?) = write { it.copy(customIncrement = milligrams) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

    override suspend fun setWarmupSuggestions(enabled: Boolean) = write { it.copy(warmupSuggestions = enabled) }

    override suspend fun setOnboardingCompleted(completed: Boolean) = write { it.copy(onboardingCompleted = completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write { it.copy(notifications = it.notifications.with(kind, enabled)) }

    override suspend fun setNotificationPermissionRequested() =
        write { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }

    override suspend fun setReminderDay(
        day: DayOfWeek,
        enabled: Boolean,
    ) = write {
        val days = it.reminders.days
        it.copy(reminders = it.reminders.copy(days = if (enabled) days + day else days - day))
    }

    override suspend fun setReminderTime(time: LocalTime) =
        write { it.copy(reminders = it.reminders.copy(timeOfDay = time)) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}
