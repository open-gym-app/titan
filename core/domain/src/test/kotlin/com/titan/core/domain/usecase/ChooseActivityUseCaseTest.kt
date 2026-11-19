package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.ChooseActivityResult
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.repository.TitanStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChooseActivityUseCaseTest {

    private val store = OneTitan(TitanRules.starting(NOW).copy(vitals = TitanVitals(80, 12, 50)))
    private val choose = ChooseActivityUseCase(store)

    @Test
    fun `a chosen activity is stored`() =
        runTest {
            val result = choose(PET, TitanActivity.WALK)

            assertEquals(TitanActivity.WALK, (result as ChooseActivityResult.Chosen).state.activity)
            assertEquals(result.state, store.state.value)
        }

    @Test
    fun `a refused activity stores nothing`() =
        runTest {
            val before = store.state.value

            assertEquals(ChooseActivityResult.NotAffordable(15), choose(PET, TitanActivity.RUN))
            assertEquals(before, store.state.value)
        }

    @Test
    fun `a pet that is not there is not found`() =
        runTest {
            assertEquals(ChooseActivityResult.NotFound, choose(PET + 1, TitanActivity.WALK))
        }

    /** One titan, already advanced to [NOW]. */
    private class OneTitan(initial: TitanState) : TitanStateRepository {

        val state = MutableStateFlow(initial)

        override fun observe(petId: Long): Flow<TitanState?> = state

        override suspend fun <R> modify(
            petId: Long,
            change: (state: TitanState, now: Long) -> TitanChange<R>,
        ): R? {
            if (petId != PET) return null
            val decided = change(state.value, NOW)
            decided.state?.let { state.value = it }
            return decided.result
        }
    }

    private companion object {
        const val PET = 7L
        const val NOW = 1_788_603_600_000L
    }
}
