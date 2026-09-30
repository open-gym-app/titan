package com.titan.core.domain.load

import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.plate.PlateCalculator
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateSolution
import com.titan.core.domain.plate.Rack

/**
 * What the next set should be, from what the last ones were — the next-load engine (#M7-15).
 *
 * A pure function over three plain values: the exercise's recent history, the user's
 * preferences and the exercise itself. It reads no repository and holds no state, which is
 * what lets every branch below be pinned by a fixture of a few numbers; the reading is
 * [com.titan.core.domain.usecase.NextLoadUseCase]'s job.
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
 * replaces it. [Rack] is where that choice and the rounding below are written down, because
 * the warm-up planner rounds its ramp-up sets through the same rules.
 *
 * A plate-loaded bar's result then goes through [PlateCalculator], so what comes back is a
 * weight the rack makes exactly, never a target it cannot. That rounds *down*, which is why
 * a custom increment smaller than the rack's own step is raised to it on a bar: 1 kg on a
 * rack whose lightest pair is 1.25 kg rounds back to the weight the user just lifted, and a
 * suggestion to add weight that adds none is worse than none. The same rule covers a rack
 * that has run out: the calculator caps at what the plates can build, and a lift logged above
 * that — an inventory with no plates, a session at a better-stocked gym — gets one more rep
 * at the weight it was, rather than an "increase" to a weight under it. Other equipment adds
 * the step as it is, and a deload on it is floored to a multiple of the step so that it, too,
 * is a weight the user can pick up.
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
        val basis = history.firstOrNull() ?: return null
        val latest = basis.judge(range) ?: return null
        val rack = Rack.of(preferences, exercise)

        return when (latest.verdict) {
            Verdict.BELOW -> deload(latest, range, rack)

            Verdict.WITHIN -> addRep(latest)

            Verdict.TOP -> when {
                latest.weight == null -> addRep(latest)

                increaseEarned(preferences.fitnessLevel, latest, history.getOrNull(1)?.judge(range)) ->
                    rack.increase(latest.weight)
                        ?.let { heavier -> latest.suggest(heavier, range.min, SuggestionReason.INCREASE) }
                        // The rack cannot make anything heavier — see `Rack.increase` — so
                        // this is a bodyweight movement for the purpose of the rule: with no
                        // load to add, one more rep is the only way up.
                        ?: addRep(latest)

                else -> latest.suggest(latest.weight, range.max, SuggestionReason.REPEAT)
            }
        }
    }

    private fun addRep(latest: Judged): LoadSuggestion =
        latest.suggest(latest.weight, latest.lowestReps + 1, SuggestionReason.ADD_REP)

    private fun deload(
        latest: Judged,
        range: RepRange,
        rack: Rack,
    ): LoadSuggestion =
        latest.suggest(
            weight = latest.weight?.let { rack.scale(it, DELOAD_NUMERATOR, DELOAD_DENOMINATOR) },
            reps = range.min,
            reason = SuggestionReason.DELOAD,
        )

    /** A suggestion off this judged performance, carrying what it was read from. */
    private fun Judged.suggest(
        weight: Long?,
        reps: Int,
        reason: SuggestionReason,
    ): LoadSuggestion =
        LoadSuggestion(
            weight = weight,
            reps = reps,
            reason = reason,
            previousWeight = this.weight,
            basis = performance,
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
        return Judged(performance = this, weight = heaviest, lowestReps = reps.min(), verdict = verdict)
    }

    /** What the sets at a performance's heaviest weight said about the range. */
    private enum class Verdict { BELOW, WITHIN, TOP }

    /**
     * @property performance what was judged, handed on as the suggestion's basis.
     * @property weight the heaviest weight lifted, or null for a bodyweight performance.
     * @property lowestReps the fewest reps any judged set got.
     */
    private class Judged(
        val performance: PastPerformance,
        val weight: Long?,
        val lowestReps: Int,
        val verdict: Verdict,
    )

    /** A deload is ten percent off: `× 9 / 10`, in integer milligrams. */
    private const val DELOAD_NUMERATOR = 9L
    private const val DELOAD_DENOMINATOR = 10L
}
