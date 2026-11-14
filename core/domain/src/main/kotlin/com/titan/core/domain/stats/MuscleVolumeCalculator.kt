package com.titan.core.domain.stats

import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.WorkoutSet

/**
 * Splits logged volume across the muscle groups the exercises trained, in
 * milligram-reps — the per-muscle breakdown behind the progression screen and the
 * muscle engagement indicators.
 *
 * A set's volume ([VolumeCalculator.setVolume]) is credited to every muscle group its
 * exercise links to: **100% to a primary link, 50% to a secondary one**, halved with
 * integer division so nothing physical becomes floating point (same reasoning as
 * [VolumeCalculator]). This is the in-memory twin of `WorkoutSetDao.getVolumeByMuscleGroup`'s
 * `weighted_volume` column and produces identical figures for identical input,
 * down to the truncation: both halve each set before summing, not after. Use the DAO
 * aggregate for a date range wider than what is already loaded — it sums in SQLite —
 * and this for a session or window the caller is already holding.
 *
 * **Attributed volume is not conserved, by design.** Because every secondary link
 * takes a full 50% regardless of how many there are, the totals sum to more than the
 * training volume that produced them, and by a factor that grows with the exercise's
 * link count: a bench press (1 primary + 2 secondary) attributes 2x its set volume,
 * while the seed's most compound movements (1 primary + 9 secondary) attribute 5.5x.
 * So these figures compare a muscle against *itself over time*, which is what the
 * progression screen asks of them. They are not a share-of-effort breakdown, and a
 * caller must not render them as one — a pie chart of this map would imply a whole
 * that does not exist.
 */
object MuscleVolumeCalculator {

    /** A secondary link earns half the set's volume; see the class doc for why halved, not shared. */
    private const val SECONDARY_DIVISOR = 2L

    /**
     * Volume per muscle group over [sets], attributed through [linksBySessionExerciseId].
     *
     * The link map is keyed by **session exercise** id, which is what a [WorkoutSet]
     * carries — the same exercise performed twice in one session is two
     * `session_exercise` rows, and both must resolve. The overload taking
     * [SessionExercise]s does that translation for a caller holding links keyed by
     * exercise id instead.
     *
     * Only completed sets count, filtered here rather than at the call site, matching
     * [VolumeCalculator.totalVolume] and the `is_completed = 1` in the DAO aggregates.
     * Sets whose session exercise is absent from the map contribute nothing: an
     * exercise with no muscle links has nowhere to attribute to, exactly as the DAO's
     * `INNER JOIN` drops it.
     *
     * Returned highest volume first, ties broken by muscle group name, so a UI can
     * render the map in iteration order. A muscle group linked to a completed set
     * appears even if that set carried no load, again mirroring the DAO.
     */
    fun volumeByMuscle(
        sets: List<WorkoutSet>,
        linksBySessionExerciseId: Map<Long, List<ExerciseMuscleGroupLink>>,
    ): Map<MuscleGroup, Long> {
        // Collapsed once here rather than per set: the database's composite primary key
        // on (exercise_id, muscle_group_id) means a real link list never repeats a
        // group, and a hand-built one that does should still credit it a single time.
        // A repeat that disagrees on the flag keeps the primary, so the result does not
        // depend on which of the two the caller happened to list first.
        val links = linksBySessionExerciseId.mapValues { (_, value) ->
            value.groupBy { it.muscleGroup.id }
                .map { (_, sameGroup) ->
                    sameGroup.firstOrNull(ExerciseMuscleGroupLink::isPrimary) ?: sameGroup.first()
                }
        }

        val totals = LinkedHashMap<Long, Long>()
        // Keyed by id so two reads of the same row - differing only in, say, a since-changed
        // icon - cannot split one muscle group across two map entries.
        val groupsById = LinkedHashMap<Long, MuscleGroup>()

        // A sequence, not a filtered list: this runs over whole-window set lists, and
        // there is no reason to materialise a second copy of one just to skip a few rows.
        for (set in sets.asSequence().filter(WorkoutSet::isCompleted)) {
            val exerciseLinks = links[set.sessionExerciseId] ?: continue
            val volume = VolumeCalculator.setVolume(set)
            for (link in exerciseLinks) {
                val group = link.muscleGroup
                groupsById.putIfAbsent(group.id, group)
                totals[group.id] = (totals[group.id] ?: 0L).saturatingPlus(attribute(volume, link.isPrimary))
            }
        }

        return totals.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, Long>> { it.value }
                    .thenBy { groupsById.getValue(it.key).name },
            )
            .associate { (groupId, volume) -> groupsById.getValue(groupId) to volume }
    }

    /**
     * Same attribution for a caller holding [linksByExerciseId] — the natural shape,
     * since muscle links belong to an exercise — plus the [sessionExercises] that map
     * this session's rows onto those exercises.
     *
     * A session exercise pointing at an exercise with no entry in [linksByExerciseId]
     * is dropped, as it is in the other overload.
     */
    fun volumeByMuscle(
        sets: List<WorkoutSet>,
        sessionExercises: List<SessionExercise>,
        linksByExerciseId: Map<Long, List<ExerciseMuscleGroupLink>>,
    ): Map<MuscleGroup, Long> {
        val bySessionExercise = sessionExercises.mapNotNull { sessionExercise ->
            linksByExerciseId[sessionExercise.exerciseId]?.let { sessionExercise.id to it }
        }.toMap()
        return volumeByMuscle(sets, bySessionExercise)
    }

    /**
     * One link's share of a set: all of [volume] for a primary mover, half of it -
     * rounded towards zero, as SQLite's integer `/` does - for a secondary.
     */
    private fun attribute(
        volume: Long,
        isPrimary: Boolean,
    ): Long = if (isPrimary) volume else volume / SECONDARY_DIVISOR
}
