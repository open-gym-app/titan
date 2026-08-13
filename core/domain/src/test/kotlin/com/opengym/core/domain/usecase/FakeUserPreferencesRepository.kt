package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/**
 * The settings store, in memory.
 *
 * Only one use case here reads it — [GetTemplateSummariesUseCase], for the rest that
 * prices a template's estimate — and what that test needs from the store is that it
 * *re-emits*: the whole point of deriving the estimate is that changing the default rest
 * changes every row without a write to any template. A stubbed flow cannot express that.
 */
class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setUnitSystem(unitSystem: UnitSystem) =
        write { it.copy(unitSystem = unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) =
        write { it.copy(defaultRestDuration = duration) }

    override suspend fun setBarWeight(barWeight: Long) = write { it.copy(barWeight = barWeight) }

    override suspend fun setPlateInventory(inventory: PlateInventory?) =
        write { it.copy(plateInventory = inventory) }

    /** Present to satisfy the interface. No use case here reads a rack. */
    override suspend fun setPlatePairs(weight: Long, pairs: Int) =
        write { it.copy(plateInventory = it.plates.withPairs(weight, pairs)) }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) =
        write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        write { it.copy(onboardingCompleted = completed) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}
