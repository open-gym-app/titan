package com.opengym.feature.history.plate

import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import kotlin.time.Duration

/**
 * The settings store, in memory.
 *
 * Hand-written rather than mocked, because what the calculator's ViewModel does with it is
 * *behaviour over time*: it writes and then waits for the store to re-emit, and a mock handing
 * back a fixed flow cannot express the half of that which matters. The setters validate what the
 * real ones validate, so a ViewModel that writes a value the store would refuse fails here too.
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

    /**
     * The rack edited *inside* the update, the way the real one edits inside `edit`.
     *
     * Modelled rather than simplified to a `setPlateInventory` of a rack read off the current
     * value, because the composing is the property under test: a caller that does its own
     * read-modify-write reverts an edit that has not been published yet, and a fake that read
     * the rack out here first would agree with it.
     */
    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) {
        val edited = PlateStock(weight = weight, pairs = pairs)
        write { it.copy(plateInventory = it.plates.withPairs(edited.weight, edited.pairs)) }
    }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

    override suspend fun setOnboardingCompleted(completed: Boolean) = write { it.copy(onboardingCompleted = completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write { it.copy(notifications = it.notifications.with(kind, enabled)) }

    override suspend fun setNotificationPermissionRequested() =
        write { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) {
        writeFailure?.let { throw it }
        preferences.update(transform)
    }
}
