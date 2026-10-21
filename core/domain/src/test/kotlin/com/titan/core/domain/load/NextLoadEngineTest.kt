package com.titan.core.domain.load

import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateStock
import com.titan.core.domain.plate.kg
import com.titan.core.domain.plate.lb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every branch of the rule, named, for each mode and each default rack — the fixtures the
 * issue asks for. Weights are milligrams via `kg` and `lb`, per [NextLoadFixtures].
 */
class NextLoadEngineTest {

    // ─── STRENGTH, 3–6, on the metric rack ───────────────────────────────────────────────

    @Test
    fun `strength - every set at the top adds the rack's step and drops to the bottom`() {
        val history = listOf(performance(kg(100.0) to 6, kg(100.0) to 6, kg(100.0) to 6))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(102.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `strength - sets inside the range keep the weight and add one rep to the lowest`() {
        val history = listOf(performance(kg(100.0) to 5, kg(100.0) to 4, kg(100.0) to 4))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(100.0),
            reps = 5,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `strength - a set under the bottom takes ten percent off and restarts at the bottom`() {
        val history = listOf(performance(kg(100.0) to 6, kg(100.0) to 3, kg(100.0) to 2))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(90.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    // ─── STRENGTH on the imperial rack ───────────────────────────────────────────────────

    @Test
    fun `strength imperial - the step is a pair of 2,5 lb plates`() {
        val history = listOf(performance(lb(225.0) to 6, lb(225.0) to 6, lb(225.0) to 6))

        assertSuggestion(
            NextLoadEngine.suggest(history, IMPERIAL, BENCH_PRESS),
            weight = lb(230.0),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `strength imperial - inside the range keeps the pounds as typed`() {
        val history = listOf(performance(lb(225.0) to 5, lb(225.0) to 5, lb(225.0) to 4))

        assertSuggestion(
            NextLoadEngine.suggest(history, IMPERIAL, BENCH_PRESS),
            weight = lb(225.0),
            reps = 5,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `strength imperial - a deload lands on what the rack makes, not on the arithmetic`() {
        // 90% of 225 is 202.5, which needs a 1.25 lb plate the default rack does not stock;
        // 200 is the heaviest loading under it.
        val history = listOf(performance(lb(225.0) to 2))

        assertSuggestion(
            NextLoadEngine.suggest(history, IMPERIAL, BENCH_PRESS),
            weight = lb(200.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    // ─── HYPERTROPHY, 8–12 ───────────────────────────────────────────────────────────────

    @Test
    fun `hypertrophy - twelve on every set adds the step and prescribes eight`() {
        val history = listOf(performance(kg(60.0) to 12, kg(60.0) to 12, kg(60.0) to 12))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC.copy(trainingGoal = TrainingGoal.HYPERTROPHY), BENCH_PRESS),
            weight = kg(62.5),
            reps = 8,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `hypertrophy - ten, nine, eight asks for nine`() {
        val history = listOf(performance(kg(60.0) to 10, kg(60.0) to 9, kg(60.0) to 8))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC.copy(trainingGoal = TrainingGoal.HYPERTROPHY), BENCH_PRESS),
            weight = kg(60.0),
            reps = 9,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `hypertrophy - a deload is rounded down through the calculator`() {
        // 90% of 60 is 54, and 17 kg a side is not a loading the metric rack makes: 16.25 is.
        val history = listOf(performance(kg(60.0) to 8, kg(60.0) to 7, kg(60.0) to 6))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC.copy(trainingGoal = TrainingGoal.HYPERTROPHY), BENCH_PRESS),
            weight = kg(52.5),
            reps = 8,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `hypertrophy imperial - the three branches on the 45 lb bar`() {
        val preferences = IMPERIAL.copy(trainingGoal = TrainingGoal.HYPERTROPHY)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(135.0) to 12, lb(135.0) to 12)), preferences, BENCH_PRESS),
            weight = lb(140.0),
            reps = 8,
            reason = SuggestionReason.INCREASE,
        )
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(135.0) to 11, lb(135.0) to 9)), preferences, BENCH_PRESS),
            weight = lb(135.0),
            reps = 10,
            reason = SuggestionReason.ADD_REP,
        )
        // 90% of 135 is 121.5; 120 is what the rack makes under it.
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(135.0) to 12, lb(135.0) to 7)), preferences, BENCH_PRESS),
            weight = lb(120.0),
            reps = 8,
            reason = SuggestionReason.DELOAD,
        )
    }

    // ─── ENDURANCE, 15–20 ────────────────────────────────────────────────────────────────

    @Test
    fun `endurance - twenty on every set adds the step and prescribes fifteen`() {
        val history = listOf(performance(kg(40.0) to 20, kg(40.0) to 20))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC.copy(trainingGoal = TrainingGoal.ENDURANCE), BENCH_PRESS),
            weight = kg(42.5),
            reps = 15,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `endurance - seventeen and sixteen asks for seventeen`() {
        val history = listOf(performance(kg(40.0) to 17, kg(40.0) to 16))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC.copy(trainingGoal = TrainingGoal.ENDURANCE), BENCH_PRESS),
            weight = kg(40.0),
            reps = 17,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `endurance - fourteen deloads to what the rack makes of 36`() {
        val history = listOf(performance(kg(40.0) to 15, kg(40.0) to 14))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC.copy(trainingGoal = TrainingGoal.ENDURANCE), BENCH_PRESS),
            weight = kg(35.0),
            reps = 15,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `endurance imperial - the three branches on the 45 lb bar`() {
        val preferences = IMPERIAL.copy(trainingGoal = TrainingGoal.ENDURANCE)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(95.0) to 20, lb(95.0) to 20)), preferences, BENCH_PRESS),
            weight = lb(100.0),
            reps = 15,
            reason = SuggestionReason.INCREASE,
        )
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(95.0) to 18, lb(95.0) to 15)), preferences, BENCH_PRESS),
            weight = lb(95.0),
            reps = 16,
            reason = SuggestionReason.ADD_REP,
        )
        // 90% of 95 is 85.5; 85 is what the rack makes under it.
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(95.0) to 20, lb(95.0) to 12)), preferences, BENCH_PRESS),
            weight = lb(85.0),
            reps = 15,
            reason = SuggestionReason.DELOAD,
        )
    }

    // ─── CUSTOM: the user's own range and step ───────────────────────────────────────────

    private val custom = METRIC.copy(
        trainingGoal = TrainingGoal.CUSTOM,
        customRepRange = RepRange(min = 6, max = 10),
        customIncrement = kg(1.25),
    )

    @Test
    fun `custom - the typed range and step are what a machine suggestion uses`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(50.0) to 10, kg(50.0) to 10)), custom, SEATED_ROW),
            weight = kg(51.25),
            reps = 6,
            reason = SuggestionReason.INCREASE,
        )
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(50.0) to 9, kg(50.0) to 7)), custom, SEATED_ROW),
            weight = kg(50.0),
            reps = 8,
            reason = SuggestionReason.ADD_REP,
        )
        // 90% of 50 is 45, a whole number of 1.25 kg steps.
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(50.0) to 10, kg(50.0) to 5)), custom, SEATED_ROW),
            weight = kg(45.0),
            reps = 6,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `custom - a step the bar cannot make is raised to the rack's own`() {
        // 1.25 kg on a bar is a pair of 0.625 kg plates, which the metric rack does not hold:
        // rounding 101.25 down through the calculator gives back 100, so the step becomes 2.5.
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(100.0) to 10, kg(100.0) to 10)), custom, BENCH_PRESS),
            weight = kg(102.5),
            reps = 6,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `custom - a step the bar can make is used as typed`() {
        val fiveKilos = custom.copy(customIncrement = kg(5.0))

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(100.0) to 10, kg(100.0) to 10)), fiveKilos, BENCH_PRESS),
            weight = kg(105.0),
            reps = 6,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `custom - with no step of their own the user gets the rack's`() {
        val unset = custom.copy(customIncrement = null)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(50.0) to 10, kg(50.0) to 10)), unset, SEATED_ROW),
            weight = kg(52.5),
            reps = 6,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `custom - the typed step is ignored under any other goal`() {
        val strength = custom.copy(trainingGoal = TrainingGoal.STRENGTH)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(50.0) to 6, kg(50.0) to 6)), strength, SEATED_ROW),
            weight = kg(52.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `custom imperial - a 2,5 lb step on a dumbbell, and the range as typed`() {
        val preferences = IMPERIAL.copy(
            trainingGoal = TrainingGoal.CUSTOM,
            customRepRange = RepRange(min = 6, max = 10),
            customIncrement = lb(2.5),
        )

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(50.0) to 10, lb(50.0) to 10)), preferences, DUMBBELL_PRESS),
            weight = lb(52.5),
            reps = 6,
            reason = SuggestionReason.INCREASE,
        )
    }

    // ─── Level: the second look back ─────────────────────────────────────────────────────

    private val topSession = performance(kg(100.0) to 6, kg(100.0) to 6, kg(100.0) to 6)

    @Test
    fun `an intermediate who hit the top once is asked to hit it again`() {
        val intermediate = METRIC.copy(fitnessLevel = FitnessLevel.INTERMEDIATE)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(topSession), intermediate, BENCH_PRESS),
            weight = kg(100.0),
            reps = 6,
            reason = SuggestionReason.REPEAT,
        )
    }

    @Test
    fun `an intermediate who hit the top twice at the same weight gets the increase`() {
        val intermediate = METRIC.copy(fitnessLevel = FitnessLevel.INTERMEDIATE)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(topSession, topSession), intermediate, BENCH_PRESS),
            weight = kg(102.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `an advanced lifter is paced like an intermediate`() {
        val advanced = METRIC.copy(fitnessLevel = FitnessLevel.ADVANCED)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(topSession), advanced, BENCH_PRESS),
            weight = kg(100.0),
            reps = 6,
            reason = SuggestionReason.REPEAT,
        )
        assertSuggestion(
            NextLoadEngine.suggest(listOf(topSession, topSession), advanced, BENCH_PRESS),
            weight = kg(102.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `a top-of-range session at a lighter weight does not count as the first of two`() {
        // The session before an increase is *always* a top-of-range one at the weight below;
        // if it counted, an intermediate would progress every session after the first.
        val intermediate = METRIC.copy(fitnessLevel = FitnessLevel.INTERMEDIATE)
        val before = performance(kg(97.5) to 6, kg(97.5) to 6, kg(97.5) to 6)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(topSession, before), intermediate, BENCH_PRESS),
            weight = kg(100.0),
            reps = 6,
            reason = SuggestionReason.REPEAT,
        )
    }

    @Test
    fun `a session before that fell short of the top does not count either`() {
        val intermediate = METRIC.copy(fitnessLevel = FitnessLevel.INTERMEDIATE)
        val before = performance(kg(100.0) to 6, kg(100.0) to 5, kg(100.0) to 5)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(topSession, before), intermediate, BENCH_PRESS),
            weight = kg(100.0),
            reps = 6,
            reason = SuggestionReason.REPEAT,
        )
    }

    @Test
    fun `a beginner takes the increase on the first top-of-range session`() {
        val before = performance(kg(97.5) to 6, kg(97.5) to 6, kg(97.5) to 6)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(topSession, before), METRIC, BENCH_PRESS),
            weight = kg(102.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `the level gate does not hold back an add-rep or a deload`() {
        val intermediate = METRIC.copy(fitnessLevel = FitnessLevel.INTERMEDIATE)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(100.0) to 5)), intermediate, BENCH_PRESS),
            weight = kg(100.0),
            reps = 6,
            reason = SuggestionReason.ADD_REP,
        )
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(100.0) to 2)), intermediate, BENCH_PRESS),
            weight = kg(90.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    // ─── Bodyweight: reps only ───────────────────────────────────────────────────────────

    private val hypertrophy = METRIC.copy(trainingGoal = TrainingGoal.HYPERTROPHY)

    @Test
    fun `bodyweight at the top of the range asks for one more rep, with no weight`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(null to 12, null to 12)), hypertrophy, PRESS_UP),
            weight = null,
            reps = 13,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `bodyweight inside the range asks for one more than the lowest set`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(null to 10, null to 9)), hypertrophy, PRESS_UP),
            weight = null,
            reps = 10,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `bodyweight under the bottom restarts at the bottom, with no weight to take off`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(null to 12, null to 7)), hypertrophy, PRESS_UP),
            weight = null,
            reps = 8,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `bodyweight is not gated by the level, since there is no increase to withhold`() {
        val intermediate = hypertrophy.copy(fitnessLevel = FitnessLevel.INTERMEDIATE)

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(null to 12, null to 12)), intermediate, PRESS_UP),
            weight = null,
            reps = 13,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `a weighted pull-up is a loaded movement whatever its equipment says`() {
        val pullUp = PRESS_UP.copy(name = "Weighted pull-up")

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(20.0) to 12, kg(20.0) to 12)), hypertrophy, pullUp),
            weight = kg(22.5),
            reps = 8,
            reason = SuggestionReason.INCREASE,
        )
    }

    // ─── Equipment that is not a bar ─────────────────────────────────────────────────────

    @Test
    fun `a dumbbell adds the step as it is and floors a deload to the step`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(22.5) to 6, kg(22.5) to 6)), METRIC, DUMBBELL_PRESS),
            weight = kg(25.0),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
        // 90% of 22.5 is 20.25; 20 is the step under it.
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(22.5) to 5, kg(22.5) to 2)), METRIC, DUMBBELL_PRESS),
            weight = kg(20.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `an imperial dumbbell deload that is exactly nine steps stays nine steps`() {
        // 90% of 50 lb is 45 lb: nine 5 lb steps in pounds, and in rounded milligrams nine
        // steps less one — which a floor without the gram of tolerance turns into eight.
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(lb(50.0) to 2)), IMPERIAL, DUMBBELL_PRESS),
            weight = lb(45.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `a deload never goes under one step`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(2.5) to 1)), METRIC, DUMBBELL_PRESS),
            weight = kg(2.5),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `a deload on a bar never goes under the bar`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(20.0) to 2)), METRIC, BENCH_PRESS),
            weight = kg(20.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `a deload never goes above what was lifted, even when that is under one step`() {
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(1.0) to 1)), METRIC, DUMBBELL_PRESS),
            weight = kg(1.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(15.0) to 1)), METRIC, BENCH_PRESS),
            weight = kg(15.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `a bar the rack cannot load any heavier asks for one more rep instead of an increase`() {
        // Two pairs of 20 kg: the ceiling is 100 kg, and the lift was logged above it.
        val small = METRIC.copy(plateInventory = PlateInventory(listOf(PlateStock(weight = kg(20.0), pairs = 2))))

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(120.0) to 6)), small, BENCH_PRESS),
            weight = kg(120.0),
            reps = 7,
            reason = SuggestionReason.ADD_REP,
        )
        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(100.0) to 6)), small, BENCH_PRESS),
            weight = kg(100.0),
            reps = 7,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `a rack with no plates cannot add to a bar, so the bar gets one more rep`() {
        val barOnly = METRIC.copy(
            plateInventory = PlateInventory(listOf(PlateStock(weight = kg(20.0), pairs = 0))),
        )

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(20.0) to 6)), barOnly, BENCH_PRESS),
            weight = kg(20.0),
            reps = 7,
            reason = SuggestionReason.ADD_REP,
        )
    }

    @Test
    fun `a rack with no plates falls back to the standard rack's step for the dumbbells`() {
        val barOnly = METRIC.copy(
            plateInventory = PlateInventory(listOf(PlateStock(weight = kg(20.0), pairs = 0))),
        )

        assertSuggestion(
            NextLoadEngine.suggest(listOf(performance(kg(22.5) to 6)), barOnly, DUMBBELL_PRESS),
            weight = kg(25.0),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    // ─── Which sets are judged ───────────────────────────────────────────────────────────

    @Test
    fun `a lighter back-off set is not judged against the working weight`() {
        // All sets at 100 hit the top; the 80 kg back-off got five. Judged over every set this
        // would be an add-rep at 100 — for a lifter who has already done 100 × 6 twice.
        val history = listOf(performance(kg(100.0) to 6, kg(100.0) to 6, kg(80.0) to 5))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(102.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `a drop set is not a set under the bottom of the range`() {
        val history = listOf(
            PastPerformance(
                listOf(
                    workingSet(setNumber = 1, weight = kg(100.0), reps = 6),
                    workingSet(setNumber = 2, weight = kg(100.0), reps = 6),
                    workingSet(setNumber = 3, weight = kg(70.0), reps = 3, setType = SetType.DROP_SET),
                ),
            ),
        )

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(102.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    @Test
    fun `a failure set under the bottom deloads like any other`() {
        val history = listOf(
            PastPerformance(
                listOf(
                    workingSet(setNumber = 1, weight = kg(100.0), reps = 6),
                    workingSet(setNumber = 2, weight = kg(100.0), reps = 2, setType = SetType.FAILURE),
                ),
            ),
        )

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(90.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `one set over the top and one under the bottom is a deload`() {
        val history = listOf(performance(kg(100.0) to 8, kg(100.0) to 2))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(90.0),
            reps = 3,
            reason = SuggestionReason.DELOAD,
        )
    }

    @Test
    fun `a set logged without reps is skipped and the rest are judged`() {
        val history = listOf(performance(kg(100.0) to null, kg(100.0) to 6))

        assertSuggestion(
            NextLoadEngine.suggest(history, METRIC, BENCH_PRESS),
            weight = kg(102.5),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }

    // ─── What the suggestion says it was read from ───────────────────────────────────────

    @Test
    fun `a suggestion carries the performance it judged and the weight it moves from`() {
        val latest = performance(kg(100.0) to 6, kg(100.0) to 6, kg(80.0) to 5)
        val history = listOf(latest, performance(kg(97.5) to 6))

        val suggestion = checkNotNull(NextLoadEngine.suggest(history, METRIC, BENCH_PRESS))

        // The latest performance, whole — back-off set included, since the screen lists what
        // was done — and the working weight it was judged at, which is what "+2.5 kg" is
        // measured against.
        assertEquals(latest, suggestion.basis)
        assertEquals(kg(100.0), suggestion.previousWeight)
        assertEquals(kg(2.5), suggestion.weightChange)
    }

    @Test
    fun `a bodyweight suggestion moves from no weight`() {
        val suggestion = checkNotNull(NextLoadEngine.suggest(listOf(performance(null to 6)), METRIC, PRESS_UP))

        assertNull(suggestion.previousWeight)
        assertNull(suggestion.weightChange)
    }

    // ─── Nothing to say ──────────────────────────────────────────────────────────────────

    @Test
    fun `no history is no suggestion`() {
        assertNull(NextLoadEngine.suggest(emptyList(), METRIC, BENCH_PRESS))
    }

    @Test
    fun `a performance with no rep-counted set is no suggestion`() {
        assertNull(NextLoadEngine.suggest(listOf(performance(kg(100.0) to null)), METRIC, BENCH_PRESS))
    }

    @Test
    fun `timed work gets no suggestion, however it was logged`() {
        assertNull(NextLoadEngine.suggest(listOf(performance(null to 30)), METRIC, PLANK))
        assertNull(NextLoadEngine.suggest(listOf(performance(null to 1)), METRIC, TREADMILL))
    }

    @Test
    fun `the unit system alone does not change a metric-stored history's arithmetic`() {
        // A lifter who switched the display to pounds without touching the rack: the stored
        // rack is still null, so the imperial default rack takes over — and a 100 kg bar on it
        // steps by 5 lb, which is the truth about what they can now load.
        val switched = METRIC.copy(unitSystem = UnitSystem.IMPERIAL, barWeight = lb(45.0))
        val history = listOf(performance(kg(100.0) to 6))

        assertSuggestion(
            NextLoadEngine.suggest(history, switched, DUMBBELL_PRESS),
            weight = kg(100.0) + lb(5.0),
            reps = 3,
            reason = SuggestionReason.INCREASE,
        )
    }
}
