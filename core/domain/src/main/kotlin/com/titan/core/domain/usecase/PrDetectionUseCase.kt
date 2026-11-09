package com.titan.core.domain.usecase

import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.paceOf
import com.titan.core.domain.model.rankedFigure
import com.titan.core.domain.pr.OneRepMaxCalculator
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.stats.VolumeCalculator
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
 * What it does owe that caller is an honest account of how far it got. The records are
 * written one candidate at a time and each is committed the moment its insert returns, so a
 * store that refuses the third of four has still written two — and this reports them, through
 * the list [invoke] is handed, rather than losing them with the throw. A caller told "nothing
 * was detected" over two rows that exist would price the session against a state the store is
 * not in, and could not recover from it: a later pass finds those rows already on file and
 * beats nothing.
 *
 * ### One exercise, every [PrType], one comparison rule
 *
 * A session's completed, non-warm-up sets are grouped by **catalogue** exercise — not by
 * `session_exercise`, so an exercise performed twice in one session (two slots of a
 * superset, say) is judged as a whole rather than as two smaller attempts that individually
 * fall short of what the pair achieved together. [SetType.WARMUP] sets are excluded before
 * grouping, the same rule [SessionRepository.getPreviousSets] applies to its own reference
 * figure and for the same reason: a warm-up is chosen to be easy, so a record built off one
 * would not be a record of anything the lifter actually tried to do. Every [PrType] the
 * exercise's [ExerciseType] admits is then tried against that group — see below — and a type
 * with no eligible set (a run logged by time alone has no distance) is silently skipped rather
 * than producing an empty or zeroed row.
 *
 * ### The exercise decides which kinds are in play, not the set
 *
 * [PrType.eligibleFor] gates the candidates on how the exercise is logged: the four lifting
 * kinds for a strength exercise, distance, pace and time for an endurance one, time and loaded
 * time for a hold. A set's figures alone cannot say this, because `SetValidator` lets a set carry
 * dimensions its mode does not ask for — a squat with a duration recorded must not earn
 * [PrType.MAX_DURATION], and the plate on a weighted plank must not earn [PrType.MAX_WEIGHT]. An
 * exercise the catalogue no longer holds is read as [ExerciseType.STRENGTH], the same fallback
 * the catalogue's own mapper gives a type it cannot read.
 *
 * "Better" is a strict comparison of each record's [rankedFigure] against whatever
 * [PersonalRecordRepository.insertIfBetter] finds on file — bigger for every type except
 * [PrType.BEST_PACE], where a lower pace wins, and a pace is whole nanoseconds per centimetre
 * whatever the distance (see `paceOf`), so a 10 km run is judged against a 5 km one by how fast
 * each went. Every figure is an integer, so no comparison here is approximate. Strict, not
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
 * Every type but one is a `maxByOrNull`/`minByOrNull` over the group's own sets — the
 * [PrType.MAX_WEIGHTED_HOLD] one over each hold's added load times its seconds, stored as that
 * product in `weight` beside the seconds in `duration`, the way [PersonalRecord] documents. The
 * exception, [PrType.MAX_VOLUME], is a session property rather than a set's — see
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
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    /**
     * Detects and writes every record [sessionId]'s completed sets broke, and returns them.
     *
     * A session with no completed sets — one that could not have finished, per
     * [FinishSessionUseCase]'s own refusal of that case, but a state this class does not
     * re-check — detects nothing and returns an empty list rather than failing.
     *
     * @param into where each record lands **as it is written**, and what is returned. A caller
     *   that owns the list keeps the rows written before a refusal, which a returned list
     *   cannot give it: the writes are one per candidate and every one that has returned is
     *   already on file, so a store that throws on the third of four leaves two records the
     *   caller would otherwise never hear about — records that are real, that count towards
     *   `first_record`, and whose XP nothing else will ever pay, since a re-run beats the rows
     *   this pass wrote and emits nothing for them. Defaulted, so a caller that does not care
     *   how far a failure got reads the return value and ignores this.
     * @param beaten where each record that **improved on one already on file** lands, as it is
     *   written — a subset of [into], filled the same way and for the same reason. The rest are
     *   baselines: the first figure of their kind the exercise ever had, written because there
     *   was nothing to compare with. Whether a row was on file is known only inside
     *   [PersonalRecordRepository.insertIfBetter], so it is reported here rather than left for
     *   a caller to read back afterwards.
     */
    suspend operator fun invoke(
        sessionId: Long,
        into: MutableList<PersonalRecord> = mutableListOf(),
        beaten: MutableList<PersonalRecord> = mutableListOf(),
    ): List<PersonalRecord> {
        val performed = sessionRepository.getSetsForSession(sessionId).first()
            .filter { it.isCompleted && it.setType != SetType.WARMUP }
        if (performed.isEmpty()) return into

        val exerciseIdBySessionExercise = sessionRepository.getExercises(sessionId).first()
            .associate { it.id to it.exerciseId }
        val formula = userPreferencesRepository.observe().first().oneRepMaxFormula

        val setsByExercise = performed.groupBy { exerciseIdBySessionExercise.getValue(it.sessionExerciseId) }
        // One read for every exercise the session touched, rather than one per exercise.
        val typeByExercise = exerciseRepository.getByIds(setsByExercise.keys).associate { it.id to it.type }
        setsByExercise.forEach { (exerciseId, sets) ->
            val type = typeByExercise[exerciseId] ?: ExerciseType.STRENGTH
            detect(exerciseId, type, sets, formula, into, beaten)
        }
        return into
    }

    private suspend fun detect(
        exerciseId: Long,
        type: ExerciseType,
        sets: List<WorkoutSet>,
        formula: OneRepMaxFormula,
        into: MutableList<PersonalRecord>,
        beaten: MutableList<PersonalRecord>,
    ) {
        val eligible = PrType.eligibleFor(type)
        candidates(exerciseId, sets, formula).filter { it.type in eligible }.forEach { candidate ->
            var hadRecord = false
            val written = personalRecordRepository.insertIfBetter(exerciseId, candidate.type, candidate) { current ->
                hadRecord = current != null
                isBetter(candidate, current)
            }
            if (written != null) {
                into += written
                if (hadRecord) beaten += written
            }
        }
    }

    /**
     * Every [PrType] [sets] has a candidate for, one each at most, before the exercise's type has
     * had its say — see [detect], which keeps only the kinds [PrType.eligibleFor] admits. Built
     * whole and then filtered rather than built per type, so each kind's rule stays one entry
     * here however many exercise types it is shared between.
     */
    private fun candidates(
        exerciseId: Long,
        sets: List<WorkoutSet>,
        formula: OneRepMaxFormula,
    ): List<PersonalRecord> =
        listOfNotNull(
            bestByWeight(sets)?.let { it.recordOf(exerciseId, PrType.MAX_WEIGHT).copy(weight = it.weight) },
            bestByReps(sets)?.let { it.recordOf(exerciseId, PrType.MAX_REPS).copy(reps = it.reps) },
            bestByDistance(sets)?.let { it.recordOf(exerciseId, PrType.MAX_DISTANCE).copy(distance = it.distance) },
            // Requires both a distance and a duration, per the class doc: a set carrying only a
            // distance yields no pace to compare.
            bestByPace(sets)?.let {
                it.recordOf(exerciseId, PrType.BEST_PACE).copy(distance = it.distance, duration = it.duration)
            },
            bestByDuration(sets)?.let { it.recordOf(exerciseId, PrType.MAX_DURATION).copy(duration = it.duration) },
            bestWeightedHold(sets)?.let { (set, loadSeconds) ->
                set.recordOf(exerciseId, PrType.MAX_WEIGHTED_HOLD).copy(weight = loadSeconds, duration = set.duration)
            },
            bestVolume(sets)?.let { (anchor, volume) ->
                anchor.recordOf(exerciseId, PrType.MAX_VOLUME).copy(weight = volume)
            },
            bestEstimated1Rm(sets, formula)?.let { (set, estimate) ->
                set.recordOf(exerciseId, PrType.ESTIMATED_1RM).copy(estimated1Rm = estimate)
            },
        )

    /**
     * A [type] record of [exerciseId] read off this set, with none of its figures filled in yet —
     * each candidate above copies in the ones its kind is about, and leaves the rest null.
     */
    private fun WorkoutSet.recordOf(
        exerciseId: Long,
        type: PrType,
    ): PersonalRecord =
        PersonalRecord(exerciseId = exerciseId, workoutSetId = id, type = type, achievedAt = completedAt)

    /*
     * Each of these reads a dimension a set may not carry — a hold has no reps, a bench press no
     * distance — so each is "the best of the sets that have one", and a set without it is not a
     * candidate rather than a zero. Paired up front rather than filtered and then forced, so a
     * timed set reaching a weight record is skipped by construction instead of by a `!!` that
     * happens to be guarded today.
     */

    private fun bestByWeight(sets: List<WorkoutSet>): WorkoutSet? =
        sets.highest { set -> set.weight?.takeIf { it > 0L } }

    private fun bestByReps(sets: List<WorkoutSet>): WorkoutSet? = sets.highest { set -> set.reps?.takeIf { it > 0 } }

    private fun bestByDistance(sets: List<WorkoutSet>): WorkoutSet? =
        sets.highest { set -> set.distance?.takeIf { it > 0L } }

    /**
     * The fewest nanoseconds per centimetre: the lower the pace figure, the faster the set. In
     * whole numbers, through the same [paceOf] the record is ranked on afterwards.
     */
    private fun bestByPace(sets: List<WorkoutSet>): WorkoutSet? =
        sets.lowest { set -> paceOf(set.duration, set.distance) }

    private fun bestByDuration(sets: List<WorkoutSet>): WorkoutSet? =
        sets.highest { set -> set.duration?.takeIf { it > 0L } }

    /**
     * The hold with the most load-seconds, and that product — milligrams added times seconds held.
     * A hold with no load is not a candidate: it is a [PrType.MAX_DURATION], not a weighted one.
     */
    private fun bestWeightedHold(sets: List<WorkoutSet>): Pair<WorkoutSet, Long>? =
        sets
            .mapNotNull { set ->
                val load = set.weight?.takeIf { it > 0L } ?: return@mapNotNull null
                val seconds = set.duration?.takeIf { it > 0L } ?: return@mapNotNull null
                set to load * seconds
            }
            .maxByOrNull { it.second }

    /** The set with the greatest [measure], among the sets that have one; ties go to the first. */
    private inline fun <T : Comparable<T>> List<WorkoutSet>.highest(measure: (WorkoutSet) -> T?): WorkoutSet? =
        measured(measure).maxByOrNull { (_, value) -> value }?.first

    /** The set with the least [measure], among the sets that have one; ties go to the first. */
    private inline fun <T : Comparable<T>> List<WorkoutSet>.lowest(measure: (WorkoutSet) -> T?): WorkoutSet? =
        measured(measure).minByOrNull { (_, value) -> value }?.first

    private inline fun <T> List<WorkoutSet>.measured(measure: (WorkoutSet) -> T?): List<Pair<WorkoutSet, T>> =
        mapNotNull { set -> measure(set)?.let { set to it } }

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
        // Which way "better" runs is [PrType]'s to say, and which figure is compared is
        // `rankedFigure`'s, not this file's: the records list asks both questions when it works
        // out how much a record improved on the one before it, and two copies of a rule are two
        // things that can disagree.
        val candidateFigure = candidate.rankedFigure
            ?: error("a ${candidate.type} candidate must carry the figure it is compared on")
        // A row on file that has lost its figure is beaten by any real one, as no row would be.
        val currentFigure = current.rankedFigure ?: return true
        return if (candidate.type.higherIsBetter) candidateFigure > currentFigure else candidateFigure < currentFigure
    }
}
