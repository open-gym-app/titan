package com.opengym.core.data.db

import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/*
 * The two stores an import touches that are *not* the database, in memory.
 *
 * `:core:domain` has fakes of both in its own test source set, but a test source set is not a
 * published artifact and `:core:data`'s tests cannot see it — so these are deliberate
 * duplicates rather than an oversight. They are kept as small as the round trip needs: what
 * `BackupRoundTripTest` asks of them is that the settings come back and that the draft was
 * cleared, not how either is stored. The real implementations are covered by
 * `UserPreferencesRepositoryImplTest` and `InProgressSessionRepositoryImplTest`.
 */

/** The settings store, in memory, with the same read-whole/write-one-at-a-time shape as the real one. */
class InMemoryUserPreferencesRepository : UserPreferencesRepository {

    private val preferences = MutableStateFlow(UserPreferences())

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

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

    override suspend fun setOnboardingCompleted(completed: Boolean) = write { it.copy(onboardingCompleted = completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write { it.copy(notifications = it.notifications.with(kind, enabled)) }

    override suspend fun setNotificationPermissionRequested() =
        write { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}

/** The in-progress draft, in memory, counting the clears an import is supposed to perform. */
class CountingInProgressSessionRepository : InProgressSessionRepository {

    private var session: ActiveSession? = null

    /** How many times [clear] has been called, including the ones that had nothing to clear. */
    var clearCount = 0
        private set

    override suspend fun save(session: ActiveSession) {
        this.session = session
    }

    override suspend fun load(): ActiveSession? = session

    override suspend fun clear() {
        clearCount++
        session = null
    }
}
