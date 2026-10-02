package com.titan.core.domain.load

import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.plate.PlateCalculator
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateSolution
import com.titan.core.domain.plate.lb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The property the issue asks for: whatever the history, a plate-loaded suggestion is a weight
 * [PlateCalculator] makes exactly on the user's rack. Plus the two the rule implies — an
 * increase goes up and a deload comes down — since a loadable weight that moved the wrong way
 * is loadable and wrong.
 *
 * Seeded, so a failure is a fixture rather than a rumour: the seed is in the assertion message.
 */
class NextLoadLoadabilityTest {

    @Test
    fun `a plate-loaded suggestion is always a solution on the user's rack`() {
        repeat(TRIALS) { seed ->
            val random = Random(seed)
            val preferences = randomPreferences(random)
            val history = List(random.nextInt(1, 3)) { randomPerformance(random, preferences) }

            val suggestion = NextLoadEngine.suggest(history, preferences, BENCH_PRESS) ?: return@repeat
            val weight = checkNotNull(suggestion.weight) { "seed $seed: a loaded history gave a bodyweight suggestion" }

            val solution = PlateCalculator.solve(weight, preferences.barWeight, preferences.plates)
            assertTrue(
                "seed $seed: $weight mg is not loadable on the rack — $solution",
                solution is PlateSolution.Loaded && solution.isExact,
            )
        }
    }

    @Test
    fun `an increase is heavier than last time and a deload is lighter, on every rack`() {
        repeat(TRIALS) { seed ->
            val random = Random(seed)
            val preferences = randomPreferences(random)
            val history = List(random.nextInt(1, 3)) { randomPerformance(random, preferences) }
            val last = checkNotNull(history.first().sets.maxOf { it.weight ?: 0L })

            val suggestion = NextLoadEngine.suggest(history, preferences, BENCH_PRESS) ?: return@repeat
            val weight = checkNotNull(suggestion.weight)

            when (suggestion.reason) {
                // Never "what the rack has": a rack out of plates for the next step answers
                // with one more rep at the same weight, which the ADD_REP branch checks.
                SuggestionReason.INCREASE -> assertTrue(
                    "seed $seed: an increase from $last mg suggested $weight mg",
                    weight > last,
                )

                SuggestionReason.DELOAD -> assertTrue(
                    "seed $seed: a deload from $last mg suggested $weight mg",
                    weight < last || weight <= preferences.barWeight + PlateSolution.WEIGHT_TOLERANCE,
                )

                SuggestionReason.ADD_REP, SuggestionReason.REPEAT ->
                    assertEquals("seed $seed: the weight should not have moved", last, weight)
            }
        }
    }

    /** Either default rack, any goal, any level — and under CUSTOM, any range and any step. */
    private fun randomPreferences(random: Random): UserPreferences {
        val unitSystem = UnitSystem.entries.random(random)
        val goal = TrainingGoal.entries.random(random)
        val min = random.nextInt(RepRange.MIN_REPS, 20)
        return UserPreferences(
            unitSystem = unitSystem,
            barWeight = if (unitSystem == UnitSystem.METRIC) UserPreferences.DEFAULT_BAR_WEIGHT else lb(45.0),
            trainingGoal = goal,
            fitnessLevel = FitnessLevel.entries.random(random),
            customRepRange = RepRange(min = min, max = random.nextInt(min, 30)),
            // Anything from half a plate to five kilos, in odd milligrams: the engine has to
            // make a loadable weight out of a step that is not.
            customIncrement = random.nextLong(500_000L, 5_000_000L),
        )
    }

    /**
     * One to five straight sets at a weight the rack makes — a real history is one the
     * user actually loaded — for a spread of rep counts around every range.
     */
    private fun randomPerformance(
        random: Random,
        preferences: UserPreferences,
    ) = performance(
        *List(random.nextInt(1, 6)) {
            preferences.plates.loadableWeight(random, preferences.barWeight) to random.nextInt(1, 25)
        }.toTypedArray(),
    )

    /** A weight on this rack: the bar plus some whole number of its smallest steps. */
    private fun PlateInventory.loadableWeight(
        random: Random,
        bar: Long,
    ): Long = bar + checkNotNull(loadStep) * random.nextLong(0, 40)

    private companion object {
        const val TRIALS = 2_000
    }
}
