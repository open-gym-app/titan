package com.opengym.feature.settings

import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.RepRange
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationPermission
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
import com.opengym.core.domain.repository.LocalDataRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration

/**
 * In-memory stand-ins for the two repositories the settings ViewModel talks to.
 *
 * Hand-written rather than mocked, because both contracts are about *behaviour over time* —
 * a flow that re-emits after a write is the thing under test in half the cases below, and a
 * mock returning a fixed flow cannot express it. They honour the real contracts: the
 * preference flow emits the current value on subscribe and again on every change, and the
 * setters validate exactly what the real ones validate.
 */
class FakeUserPreferencesRepository(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    /** When set, every write throws it — the store that cannot be written. */
    var writeFailure: IOException? = null

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = write { it.copy(unitSystem = unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) {
        require(duration.isFinite() && duration.inWholeSeconds > 0) { "invalid rest: $duration" }
        write { it.copy(defaultRestDuration = duration) }
    }

    override suspend fun setBarWeight(barWeight: Long) {
        require(barWeight > 0) { "invalid bar weight: $barWeight" }
        write { it.copy(barWeight = barWeight) }
    }

    override suspend fun setPlateInventory(inventory: PlateInventory?) = write { it.copy(plateInventory = inventory) }

    /** The rack edited inside the update, the way the real one edits inside `edit`. */
    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) {
        val edited = PlateStock(weight = weight, pairs = pairs)
        write { it.copy(plateInventory = it.plates.withPairs(edited.weight, edited.pairs)) }
    }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) = write { it.copy(fitnessLevel = fitnessLevel) }

    override suspend fun setCustomRepRange(range: RepRange) = write { it.copy(customRepRange = range) }

    override suspend fun setCustomIncrement(milligrams: Long?) = write { it.copy(customIncrement = milligrams) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

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

    private fun write(transform: (UserPreferences) -> UserPreferences) {
        writeFailure?.let { throw it }
        preferences.update(transform)
    }
}

class FakeLocalDataRepository(initial: DataFootprint = DataFootprint()) : LocalDataRepository {

    private val footprint = MutableStateFlow(initial)

    var deleteCount: Int = 0
        private set

    /** When set, [deleteAll] throws it — the wipe that could not finish. */
    var deleteFailure: Throwable? = null

    override fun observeFootprint(): Flow<DataFootprint> = footprint

    override suspend fun deleteAll() {
        deleteCount++
        deleteFailure?.let { throw it }
        footprint.value = DataFootprint()
    }
}

/**
 * The system's answer about notifications, under the test's control.
 *
 * A class rather than a lambda because the answer has to be able to *change* mid-test: the
 * whole point of the permission plumbing is that a user can leave for system settings and come
 * back with a different answer, and the recheck event is what is being tested. [allowed] is
 * therefore a `var`, and every read goes through the same method the real one does.
 */
class FakeNotificationPermission(
    var allowed: Boolean = false,
    override val isRuntimePermissionRequired: Boolean = true,
) : NotificationPermission {

    override fun areNotificationsAllowed(): Boolean = allowed
}
