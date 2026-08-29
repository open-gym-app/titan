package com.opengym.feature.onboarding

import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import kotlin.time.Duration

/**
 * An in-memory stand-in for the store onboarding writes to.
 *
 * Hand-written rather than mocked, because the contract under test here is *behaviour over
 * time*: a step writes, the flow re-emits, the next step renders the value that was written.
 * A mock returning a fixed flow cannot express that. It honours the real contract — the
 * current value on subscribe, again on every change, and an [IOException] from any setter when
 * the store cannot be written.
 *
 * [writeFailure] fails every write. That is the shape of the failure that matters here: a
 * store that cannot be written is not selective about which preference it refuses.
 */
class FakeUserPreferencesRepository(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    /** When set, every write throws it — the store that cannot be written. */
    var writeFailure: IOException? = null

    /** What is stored right now, for an assertion that does not want to collect a flow. */
    val current: UserPreferences get() = preferences.value

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

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) {
        writeFailure?.let { throw it }
        preferences.update(transform)
    }
}
