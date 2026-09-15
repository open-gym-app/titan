package com.opengym.core.domain.load

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.LoadSuggestion
import com.opengym.core.domain.model.PastPerformance
import com.opengym.core.domain.model.RepRange
import com.opengym.core.domain.model.SuggestionReason
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateCalculator
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateSolution

/**
 * What the next set should be, from what the last ones were — the next-load engine (#M7-15).
 *
 * A pure function over three plain values: the exercise's recent history, the user's
 * preferences and the exercise itself. It reads no repository and holds no state, which is
 * what lets every branch below be pinned by a fixture of a few numbers; the reading is
 * [com.opengym.core.domain.usecase.NextLoadUseCase]'s job.
 *
 * ### Double progression
 *
 * The one rule that fits every [TrainingGoal]: hold the weight and climb the rep range, and
 * when every set reaches the top of it, add weight and start again at the bottom. The goal
 * picks the range — see [UserPreferences.repRange] — and the rule reads the last performance
 * against it:
 *
 * - every set **at or above the top** → the weight goes up by the increment, the reps go
 *   back to the bottom of the range ([SuggestionReason.INCREASE]);
 * - every set **inside the range** → the same weight, one more rep than the lowest set got
 *   ([SuggestionReason.ADD_REP]);
 * - any set **under the bottom** → ten percent off, the bottom of the range
 *   ([SuggestionReason.DELOAD]).
 *
 * The deload takes precedence: a session that had one set over the top and one under the
 * bottom is a session the weight was too heavy for, whatever the first set said.
 *
 * ### Which sets are judged
 *
 * The sets at the performance's **heaviest weight**. Straight sets — the common case — are
 * all at one weight and this is all of them. A back-off set or a drop set is lighter and is
 * not: it says nothing about whether the working weight can go up, and reading its higher
 * reps as a top-of-range hit would add load on the strength of a set that was chosen to be
 * easy. A set with no rep count logged is skipped, and a performance with none to judge is
 * one this engine has no opinion on.
 *
 * ### Level
 *
 * [FitnessLevel.BEGINNER] takes the increase the first time the top is hit. The other two
 * levels take it only when the top was hit in **two consecutive performances at that weight**,
 * and hold the weight with the top of the range as the target in between
 * ([SuggestionReason.REPEAT]). *At that weight* is load-bearing: the performance before an
 * increase is by construction a top-of-range one at a lighter weight, and counting it would
 * let an intermediate progress every session after the first.
 *
 * ### The increment, and why the answer is always loadable
 *
 * The smallest step the user's rack can make — twice its smallest plate, see
 * [PlateInventory.loadStep] — for every kind of equipment: a dumbbell rack and a machine
 * stack have a step too, and a wrong one is corrected in a tap where a percentage rule's
 * 41.3 kg is not. Under [TrainingGoal.CUSTOM] the user's own [UserPreferences.customIncrement]
 * replaces it.
 *
 * A plate-loaded bar's result then goes through [PlateCalculator], so what comes back is a
 * weight the rack makes exactly, never a target it cannot. That rounds *down*, which is why
 * a custom increment smaller than the rack's own step is raised to it on a bar: 1 kg on a
 * rack whose lightest pair is 1.25 kg rounds back to the weight the user just lifted, and a
 * suggestion to add weight that adds none is worse than none. Other equipment adds the step
 * as it is, and a deload on it is floored to a multiple of the step so that it, too, is a
 * weight the user can pick up.
 *
 * ### Bodyweight
 *
 * Decided by the history rather than by [EquipmentType.BODYWEIGHT]: a weighted pull-up is
 * logged with the plate on the belt, and a press-up is logged with nothing. A performance
 * whose heaviest set carries no weight gets a reps-only suggestion — [LoadSuggestion.weight]
 * null — and with no load to add, every branch but the deload is "one more rep": the level
 * gate has no increase to withhold and does not apply.
 *
 * ### Timed work
 *
 * [ExerciseType.ENDURANCE] and [ExerciseType.ISOMETRIC] get **null**. A rep range is not a
 * reading of a run or a plank, and a suggestion that prescribed a weight and a rep count for
 * one would be a wrong answer with a confident face. A duration rule — five percent on the
 * last time — is a different suggestion with a different shape and a different row to fill,
 * and it is left for the day that row exists rather than squeezed into this one.
 */
object NextLoadEngine {

    /**
     * The suggestion for the next set of [exercise], or **null** when there is nothing to say.
     *
     * Null for: no history; a history whose latest performance has no rep-counted set at its
     * heaviest weight; an exercise measured by time. The screen draws nothing for a null
     * rather than a guess — see the class doc for each.
     *
     * @param history the exercise's recent performances, **newest first**, working sets only.
     *   Only the first two are read: the latest is judged, and the one before it is what the
     *   level gate looks back at.
     */
    fun suggest(
        history: List<PastPerformance>,
        preferences: UserPreferences,
        exercise: Exercise,
    ): LoadSuggestion? {
        if (exercise.type != ExerciseType.STRENGTH) return null

        val range = preferences.repRange
        val latest = history.firstOrNull()?.judge(range) ?: return null
        val rack = Rack.of(preferences, exercise)

        return when (latest.verdict) {
            Verdict.BELOW -> deload(latest, range, rack)

            Verdict.WITHIN -> addRep(latest)

            Verdict.TOP -> when {
                latest.weight == null -> addRep(latest)

                increaseEarned(preferences.fitnessLevel, latest, history.getOrNull(1)?.judge(range)) ->
                    LoadSuggestion(
                        weight = rack.increase(latest.weight),
                        reps = range.min,
                        reason = SuggestionReason.INCREASE,
                    )

                else -> LoadSuggestion(weight = latest.weight, reps = range.max, reason = SuggestionReason.REPEAT)
            }
        }
    }

    private fun addRep(latest: Judged): LoadSuggestion =
        LoadSuggestion(weight = latest.weight, reps = latest.lowestReps + 1, reason = SuggestionReason.ADD_REP)

    private fun deload(
        latest: Judged,
        range: RepRange,
        rack: Rack,
    ): LoadSuggestion =
        LoadSuggestion(
            weight = latest.weight?.let(rack::deload),
            reps = range.min,
            reason = SuggestionReason.DELOAD,
        )

    /**
     * Whether a top-of-range [latest] has earned the increase at [level].
     *
     * A beginner always has. Anybody else needs [previous] to have hit the top too, at a weight
     * no lighter than [latest]'s — compared within [PlateSolution.WEIGHT_TOLERANCE], because
     * an imperial weight typed twice is the same number and not always the same milligram.
     */
    private fun increaseEarned(
        level: FitnessLevel,
        latest: Judged,
        previous: Judged?,
    ): Boolean {
        if (level == FitnessLevel.BEGINNER) return true
        previous ?: return false
        val previousWeight = previous.weight ?: return false
        val latestWeight = latest.weight ?: return false
        return previous.verdict == Verdict.TOP && previousWeight >= latestWeight - PlateSolution.WEIGHT_TOLERANCE
    }

    /**
     * A performance read against [range]: its heaviest weight, and how the sets at that
     * weight did. Null when no set at that weight has a rep count to judge.
     */
    private fun PastPerformance.judge(range: RepRange): Judged? {
        val counted = sets.filter { set -> set.reps != null && set.reps >= 1 }
        val heaviest = counted.maxOfOrNull { set -> set.weight ?: 0L }?.takeIf { it > 0L }
        val reps = counted
            .filter { set -> heaviest == null || (set.weight ?: 0L) >= heaviest - PlateSolution.WEIGHT_TOLERANCE }
            .map { set -> checkNotNull(set.reps) }
        if (reps.isEmpty()) return null

        val verdict = when {
            reps.any { it < range.min } -> Verdict.BELOW
            reps.all { it >= range.max } -> Verdict.TOP
            else -> Verdict.WITHIN
        }
        return Judged(weight = heaviest, lowestReps = reps.min(), verdict = verdict)
    }

    /** What the sets at a performance's heaviest weight said about the range. */
    private enum class Verdict { BELOW, WITHIN, TOP }

    /**
     * @property weight the heaviest weight lifted, or null for a bodyweight performance.
     * @property lowestReps the fewest reps any judged set got.
     */
    private class Judged(
        val weight: Long?,
        val lowestReps: Int,
        val verdict: Verdict,
    )

    /**
     * How a weight moves for one exercise on one user's equipment: the step, and the rounding
     * that makes the answer loadable.
     *
     * @property step what an increase adds, in milligrams.
     * @property bar the empty bar for a plate-loaded exercise, or null for anything else — the
     *   flag that decides whether [PlateCalculator] gets the last word.
     */
    private class Rack(
        private val step: Long,
        private val bar: Long?,
        private val plates: PlateInventory,
    ) {

        fun increase(weight: Long): Long = loadable(weight + step)

        /**
         * Ten percent off, rounded to something the equipment can be: the calculator's own
         * answer for a bar, and the step below for anything else — never less than one step,
         * because a deload to nothing is not a set.
         *
         * The floor is taken a gram above the target, for the reason [PlateSolution.WEIGHT_TOLERANCE]
         * exists: ninety percent of 50 lb is nine steps of 5 lb exactly, and in rounded
         * milligrams it is nine steps less one milligram, which an honest floor turns into
         * eight — a 40 lb dumbbell for a lifter who should be reaching for the 45.
         */
        fun deload(weight: Long): Long {
            val target = weight * DELOAD_NUMERATOR / DELOAD_DENOMINATOR
            if (bar != null) return loadable(target)
            return ((target + PlateSolution.WEIGHT_TOLERANCE) / step * step).coerceAtLeast(step)
        }

        /** [target] as the bar can be loaded, or the empty bar for a target under it. */
        private fun loadable(target: Long): Long {
            bar ?: return target
            return when (val solution = PlateCalculator.solve(target = target, barWeight = bar, inventory = plates)) {
                is PlateSolution.BelowBar -> bar
                is PlateSolution.Loaded -> solution.achieved
            }
        }

        companion object {

            fun of(
                preferences: UserPreferences,
                exercise: Exercise,
            ): Rack {
                val plates = preferences.plates
                // The user's rack, or the standard one for their units when theirs holds nothing:
                // a bar with no plates has no step, and a step is needed even for the dumbbells.
                val rackStep = plates.loadStep ?: checkNotNull(
                    PlateInventory.defaultFor(preferences.unitSystem).loadStep,
                )
                val custom = preferences.customIncrement?.takeIf { preferences.trainingGoal == TrainingGoal.CUSTOM }
                val plateLoaded = exercise.equipmentType?.isPlateLoaded == true
                return Rack(
                    // On a bar the rack's own step is the floor, for the reason the class doc
                    // gives: a smaller one rounds back to the weight just lifted.
                    step = if (plateLoaded) (custom ?: rackStep).coerceAtLeast(rackStep) else custom ?: rackStep,
                    bar = preferences.barWeight.takeIf { plateLoaded },
                    plates = plates,
                )
            }
        }
    }

    /** A deload is ten percent off: `× 9 / 10`, in integer milligrams. */
    private const val DELOAD_NUMERATOR = 9L
    private const val DELOAD_DENOMINATOR = 10L
}
