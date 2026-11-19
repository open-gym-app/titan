package com.titan.core.domain.gamification

import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PetGain
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.WorkoutSet

/**
 * What a finished session pays the active pet — the gain table, as data and one pure function.
 *
 * ### Paid from the sets, with the user's own caps
 *
 * "Paid" is [SessionXp.isPaidSet], unchanged, under [SessionXp.MAX_PAID_SETS] and
 * [SessionXp.MIN_SETS_FOR_SESSION_XP]. Reusing them means the pet inherits the farming hardening
 * the user's XP already has (#243) — warm-ups, empty sets and a hundred-set session pay a pet
 * exactly what they pay the user: nothing extra.
 *
 * ### Not derived from the XP the cascade awarded
 *
 * A session's sets are still there on any later run. The awards are not: they are empty on a
 * re-run, and a cascade that died between its XP step and this one would pay the pet differently
 * on re-entry. Paid from the sets, a torn run recovers the full amount.
 *
 * The figures are starting defaults, like every other tuning constant here.
 */
object PetGrowthRates {

    /** Paid once for a session of at least [SessionXp.MIN_SETS_FOR_SESSION_XP] paid sets. */
    const val XP_PER_SESSION = 50

    /** Paid per paid set, up to [SessionXp.MAX_PAID_SETS]. */
    const val XP_PER_SET = 5

    /** How much one paid set grows the stat of its exercise's type. */
    const val STAT_PER_SET = 1

    /**
     * What a session of [sets] pays.
     *
     * @param sets every set of the session in the order it was performed, each with the type of
     *   the exercise it belongs to. Order matters only past the cap: the first
     *   [SessionXp.MAX_PAID_SETS] paid sets are the ones that count.
     */
    fun gainFor(sets: List<Pair<WorkoutSet, ExerciseType>>): PetGain {
        val paid = sets.filter { (set, _) -> SessionXp.isPaidSet(set) }
        val counted = paid.take(SessionXp.MAX_PAID_SETS)

        val sessionXp = if (paid.size >= SessionXp.MIN_SETS_FOR_SESSION_XP) XP_PER_SESSION else 0
        val stats = counted.fold(PetStats.ZERO) { stats, (_, type) ->
            stats.plus(PetTraining.statFor(type), STAT_PER_SET)
        }
        return PetGain(xp = sessionXp + counted.size * XP_PER_SET, stats = stats)
    }
}
