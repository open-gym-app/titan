package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.pr.OneRepMaxCalculator
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.stats.VolumeCalculator
import kotlinx.coroutines.flow.first
import kotlin.math.roundToLong

/**
 * Detects the personal records a finished session actually broke, and writes them.
 *
 * **What calls this and when is not this class's business.** #145 decides where this sits
 * relative to the XP and streak work when a session ends, and what happens if it fails
 * halfway; this only has to be correct and idempotent whenever it *is* called with a
 * session's id — see [invoke].
 *
 * ### One exercise, every [PrType], one comparison rule
 *
 * A session's completed, non-warm-up sets are grouped by **catalogue** exercise — not by
 * `session_exercise`, so an exercise performed twice in one session (two slots of a
 * superset, say) is judged as a whole rather than as two smaller attempts that individually
 * fall short of what the pair achieved together. [SetType.WARMUP] sets are excluded before
 * grouping, the same rule [SessionRepository.getPreviousSets] applies to its own reference
 * figure and for the same reason: a warm-up is chosen to be easy, so a record built off one
 * would not be a record of anything the lifter actually tried to do. Every [PrType] is then
 * tried against that group; a type with no eligible set (no cardio logged, say) is silently
 * skipped rather than producing an empty or zeroed row.
 *
 * "Better" is a strict comparison against whatever [PersonalRecordRepository.insertIfBetter]
 * finds on file — bigger for
 * every type except [PrType.BEST_PACE], where a lower pace wins. Strict, not
 * greater-or-equal, which is what makes this idempotent for free: re-running the same
 * session finds the same candidate value it found last time, that value is no longer
 * *strictly* better than the record it already wrote, and nothing new is inserted. The same
 * rule is why tying an existing record never creates a second row.
 *
 * The read of "what's on file" and the write are one atomic operation inside
 * [PersonalRecordRepository.insertIfBetter], not two separate calls from here — two sessions
 * for the same exercise finishing at once cannot both read the same current best before
 * either writes, which a read-then-insert pair from this class could not have ruled out.
 *
 * ### Where a candidate's value comes from
 *
 * Five of the six types are a `maxByOrNull`/`minByOrNull` over the group's own sets. The
 * sixth, [PrType.MAX_VOLUME], is a session property rather than a set's — see
 * [VolumeCalculator.totalVolume] — so its `workout_set_id` anchor is the group's *last*
 * completed set rather than the one set that "achieved" a sum several sets contributed to.
 *
 * Each record's [PersonalRecord.achievedAt] is its own originating set's `completedAt` —
 * the moment the lift actually happened — rather than the moment this use case runs, so a
 * record's timestamp stays correct even if detection is invoked well after the fact (a
 * retried failure, a backfilled session).
 */
class PrDetectionUseCase(
    private val sessionRepository: SessionRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    /**
     * Detects and writes every record [sessionId]'s completed sets broke, and returns them.
     *
     * A session with no completed sets — one that could not have finished, per
     * [FinishSessionUseCase]'s own refusal of that case, but a state this class does not
     * re-check — detects nothing and returns an empty list rather than failing.
     */
    suspend operator fun invoke(sessionId: Long): List<PersonalRecord> {
        val performed = sessionRepository.getSetsForSession(sessionId).first()
            .filter { it.isCompleted && it.setType != SetType.WARMUP }
        if (performed.isEmpty()) return emptyList()

        val exerciseIdBySessionExercise = sessionRepository.getExercises(sessionId).first()
            .associate { it.id to it.exerciseId }
        val formula = userPreferencesRepository.observe().first().oneRepMaxFormula

        return performed
            .groupBy { exerciseIdBySessionExercise.getValue(it.sessionExerciseId) }
            .flatMap { (exerciseId, sets) -> detect(exerciseId, sets, formula) }
    }

    private suspend fun detect(
        exerciseId: Long,
        sets: List<WorkoutSet>,
        formula: OneRepMaxFormula,
    ): List<PersonalRecord> =
        candidates(exerciseId, sets, formula).mapNotNull { candidate ->
            personalRecordRepository.insertIfBetter(exerciseId, candidate.type, candidate) { current ->
                isBetter(candidate, current)
            }
        }

    /** Every [PrType] [sets] has an eligible candidate for, one each at most. */
    private fun candidates(
        exerciseId: Long,
        sets: List<WorkoutSet>,
        formula: OneRepMaxFormula,
    ): List<PersonalRecord> =
        listOfNotNull(
            bestByWeight(sets)?.let { set ->
                PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = set.id,
                    type = PrType.MAX_WEIGHT,
                    weight = set.weight,
                    achievedAt = set.completedAt,
                )
            },
            bestByReps(sets)?.let { set ->
                PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = set.id,
                    type = PrType.MAX_REPS,
                    reps = set.reps,
                    achievedAt = set.completedAt,
                )
            },
            bestByDistance(sets)?.let { set ->
                PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = set.id,
                    type = PrType.MAX_DISTANCE,
                    distance = set.distance,
                    achievedAt = set.completedAt,
                )
            },
            // Requires both a distance and a duration, per the class doc: a set carrying only a
            // distance yields no pace to compare.
            bestByPace(sets)?.let { set ->
                PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = set.id,
                    type = PrType.BEST_PACE,
                    distance = set.distance,
                    duration = set.duration,
                    achievedAt = set.completedAt,
                )
            },
            bestVolume(sets)?.let { (anchor, volume) ->
                PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = anchor.id,
                    type = PrType.MAX_VOLUME,
                    weight = volume,
                    achievedAt = anchor.completedAt,
                )
            },
            bestEstimated1Rm(sets, formula)?.let { (set, estimate) ->
                PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = set.id,
                    type = PrType.ESTIMATED_1RM,
                    estimated1Rm = estimate,
                    achievedAt = set.completedAt,
                )
            },
        )

    private fun bestByWeight(sets: List<WorkoutSet>): WorkoutSet? =
        sets.filter { (it.weight ?: 0L) > 0L }.maxByOrNull { it.weight!! }

    private fun bestByReps(sets: List<WorkoutSet>): WorkoutSet? =
        sets.filter { (it.reps ?: 0) > 0 }.maxByOrNull { it.reps!! }

    private fun bestByDistance(sets: List<WorkoutSet>): WorkoutSet? =
        sets.filter { (it.distance ?: 0L) > 0L }.maxByOrNull { it.distance!! }

    private fun bestByPace(sets: List<WorkoutSet>): WorkoutSet? =
        sets
            .filter { (it.distance ?: 0L) > 0L && (it.duration ?: 0L) > 0L }
            .minByOrNull { it.duration!!.toDouble() / it.distance!! }

    /**
     * The session's total volume for this exercise's sets, anchored to the last of them —
     * see the class doc for why a sum has no single set to point at.
     */
    private fun bestVolume(sets: List<WorkoutSet>): Pair<WorkoutSet, Long>? {
        val volume = VolumeCalculator.totalVolume(sets)
        if (volume <= 0L) return null
        // `sets` is already completed-only and warm-up-free — see `invoke` — so no second
        // filter is needed to pick the anchor.
        val anchor = sets.maxByOrNull(WorkoutSet::completedAt) ?: return null
        return anchor to volume
    }

    private fun bestEstimated1Rm(
        sets: List<WorkoutSet>,
        formula: OneRepMaxFormula,
    ): Pair<WorkoutSet, Long>? =
        sets
            .mapNotNull { set ->
                val weight = set.weight?.takeIf { it > 0L } ?: return@mapNotNull null
                val reps = set.reps?.takeIf { it > 0 } ?: return@mapNotNull null
                set to OneRepMaxCalculator.calculate(weight, reps, formula).roundToLong()
            }
            .maxByOrNull { it.second }

    /** `true` for `null` — nothing on file yet is beaten by anything. */
    private fun isBetter(
        candidate: PersonalRecord,
        current: PersonalRecord?,
    ): Boolean {
        current ?: return true
        return if (candidate.type == PrType.BEST_PACE) {
            pace(candidate) < pace(current)
        } else {
            metric(candidate) > metric(current)
        }
    }

    private fun metric(record: PersonalRecord): Long =
        when (record.type) {
            PrType.MAX_WEIGHT, PrType.MAX_VOLUME -> record.weight
            PrType.MAX_REPS -> record.reps?.toLong()
            PrType.MAX_DISTANCE -> record.distance
            PrType.ESTIMATED_1RM -> record.estimated1Rm
            PrType.BEST_PACE -> error("BEST_PACE is compared by pace(), not metric()")
        } ?: error("a ${record.type} candidate must carry the figure it is compared on")

    private fun pace(record: PersonalRecord): Double {
        val duration = record.duration ?: error("a BEST_PACE candidate must carry a duration")
        val distance = record.distance ?: error("a BEST_PACE candidate must carry a distance")
        return duration.toDouble() / distance
    }
}
