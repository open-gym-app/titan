package com.opengym.core.domain.warmup

import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import com.opengym.core.domain.model.MobilityExercise
import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SessionMovement
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.model.WarmupSuggestion
import com.opengym.core.domain.plate.PlateSolution
import com.opengym.core.domain.plate.Rack

/**
 * What to do before a workout and what to do after it, from the muscles the workout trains —
 * the warm-up and cool-down suggestion (#214).
 *
 * A pure function over four plain values: the session's id, its exercises, the mobility half of
 * the catalogue and the user's preferences. It reads no repository and holds no state, which is
 * what lets every branch below be pinned by a fixture of a few rows;
 * [com.opengym.core.domain.usecase.SuggestWarmupUseCase] is what does the reading.
 *
 * ### What the session is taken to be training
 *
 * Every muscle any exercise links to, primary or secondary. The *primary* ones decide how many
 * movements are worth suggesting and which candidates are preferred; the whole set decides which
 * candidates are eligible at all. That widening is deliberate and it is what makes the obvious
 * case come out right: a bench-and-pushdown session has chest and triceps as its prime movers,
 * and the shoulder mobility work a lifter actually does before it matches on the bench press's
 * *secondary* link.
 *
 * ### Mobility work in the session is not "the work"
 *
 * Exercises from the two mobility disciplines are left out of that reading. A warm-up is a
 * warm-up *for* something, and stretches are not the something — but the real reason is
 * narrower and sharper: accepting a suggestion appends its movements to the session, and a
 * profile that counted them would let the act of accepting change the suggestion that was
 * accepted. Leaving them out is what makes the answer stable, which is what the hashed order
 * below is for as well. A session that is *only* stretching therefore gets nothing suggested,
 * which is the honest answer: there is no work to warm up for.
 *
 * ### The pick is stable for a given session
 *
 * The catalogue holds 87 dynamic movements and 96 static stretches, and most sessions match
 * dozens of them. Picking at random would reshuffle the card on every recomposition — and, far
 * worse, hand back a different suggestion after a rotation or a process death than the one the
 * user was reading. So the order is a *hash* of the session id and the candidate's id: fixed for
 * one session, different between two, and computed rather than remembered, so nothing has to be
 * written down for it to survive.
 *
 * ### Covering the session rather than repeating one muscle
 *
 * Within that order the pick is greedy over muscles not yet covered, so a leg-and-back session
 * gets one of each rather than three hamstring stretches. Only once every candidate left repeats
 * a muscle already covered does it fall back to taking them in order.
 *
 * ### The ramp-up
 *
 * The session's first **compound, loaded** lift gets two [SetType.WARMUP] sets at roughly half
 * and three quarters of its working weight, rounded through the user's own equipment by [Rack]
 * so both are weights they can actually load. Everything else gets none: an isolation movement
 * does not need ramping into, and bodyweight work, cardio and a lift with no weight planned
 * have nothing to take a fraction of. Sets that round to the same weight, or up to the working
 * weight itself, are dropped rather than drawn — a "warm-up" at the working weight is a working
 * set with the wrong label on it.
 */
object WarmupPlanner {

    /**
     * The suggestion for one session, or an empty one where there is nothing worth saying.
     *
     * @param sessionId the running session, used only as the seed the pick is stable on — see
     *   the class doc. Two sessions training the same muscles get different movements, and the
     *   same session gets the same ones for as long as it lasts.
     * @param movements the session's exercises **in the order they will be performed**, which is
     *   the order the ramp-up's "first compound lift" is read in.
     * @param catalogue every mobility movement to choose from. The planner filters it; a caller
     *   handing over the whole of it is the ordinary case.
     */
    fun plan(
        sessionId: Long,
        movements: List<SessionMovement>,
        catalogue: List<MobilityExercise>,
        preferences: UserPreferences,
    ): WarmupSuggestion {
        val work = movements.filterNot { it.exercise.discipline.isMobility }
        if (work.isEmpty()) return WarmupSuggestion()

        val primaryMuscles = work.muscleIds { it.isPrimary }
        val trainedMuscles = work.muscleIds { true }
        if (trainedMuscles.isEmpty()) return WarmupSuggestion()

        val wanted = primaryMuscles.size.coerceIn(MIN_MOVEMENTS, MAX_MOVEMENTS)
        val rampUp = work.rampUp(preferences)

        return WarmupSuggestion(
            warmup = catalogue
                .filter { it.isDynamic && it.matches(trainedMuscles) }
                .pick(wanted, sessionId, primaryMuscles),
            rampUp = rampUp?.sets.orEmpty(),
            rampUpExerciseId = rampUp?.exerciseId,
            rampUpSessionExerciseId = rampUp?.sessionExerciseId,
            cooldown = catalogue
                .filter { it.isStaticStretch && it.matches(trainedMuscles) }
                .pick(wanted, sessionId, primaryMuscles),
            includeGeneralNote = primaryMuscles.size > WarmupSuggestion.GENERAL_NOTE_MUSCLE_GROUPS,
        )
    }

    /** The muscle ids these movements link to through the links [selector] admits. */
    private fun List<SessionMovement>.muscleIds(selector: (ExerciseMuscleGroupLink) -> Boolean): Set<Long> =
        flatMapTo(mutableSetOf()) { movement ->
            movement.muscleGroups.filter(selector).map { it.muscleGroup.id }
        }

    /**
     * Whether this is a stretch to be *held* — the only thing a cool-down is built from.
     *
     * The discipline as well as the tag, because the upstream data has one plyometric movement
     * whose force reads as static, and a box jump belongs in no cool-down whatever its tag says.
     */
    private val MobilityExercise.isStaticStretch: Boolean
        get() = !isDynamic && exercise.discipline == Discipline.STRETCHING

    /** Whether this movement's prime mover is one of the muscles the session touches. */
    private fun MobilityExercise.matches(trainedMuscles: Set<Long>): Boolean =
        primaryMuscleGroupIds.any(trainedMuscles::contains)

    /**
     * Up to [wanted] of these candidates: the ones that cover the most muscles first, in an
     * order fixed by [sessionId].
     *
     * Two passes over the same ordering rather than a sort with a third key, because "covers a
     * muscle nothing else has covered yet" is a property of the *selection so far* and not of a
     * candidate — the second stretch for a muscle is only a repeat because the first was taken.
     */
    private fun List<MobilityExercise>.pick(
        wanted: Int,
        sessionId: Long,
        primaryMuscles: Set<Long>,
    ): List<MobilityExercise> {
        val ordered = sortedWith(
            // A candidate that moves one of the session's prime movers first, then the hash, then
            // the id — the last of which only ever breaks a collision, and keeps the answer a
            // function of the data rather than of the list order it happened to arrive in.
            compareBy<MobilityExercise> { if (it.primaryMuscleGroupIds.any(primaryMuscles::contains)) 0 else 1 }
                .thenBy { shuffleKey(sessionId, it.exercise.id) }
                .thenBy { it.exercise.id },
        )

        val covered = mutableSetOf<Long>()
        val covering = buildList {
            for (candidate in ordered) {
                if (candidate.primaryMuscleGroupIds.all { it in covered }) continue
                add(candidate)
                covered += candidate.primaryMuscleGroupIds
            }
        }
        // The covering ones first and then everything else, de-duplicated and cut to length.
        // Computed in full rather than stopped at [wanted], because the whole candidate list is
        // a few dozen rows and an early exit here is a second loop condition to get wrong.
        return (covering + ordered).distinct().take(wanted)
    }

    /**
     * The ramp-up for the first compound lift that has a weight to ramp into, or **null**.
     *
     * "Loaded" is decided by [SessionMovement.workingWeight] rather than by the equipment type,
     * for the reason the next-load engine decides bodyweight by the history: a weighted dip is
     * a bodyweight exercise carrying a plate, and a barbell lift nobody has planned a weight for
     * has nothing to take half of.
     */
    private fun List<SessionMovement>.rampUp(preferences: UserPreferences): RampUp? =
        asSequence()
            .filter { it.exercise.type == ExerciseType.STRENGTH }
            .filter { it.exercise.mechanicType == MechanicType.COMPOUND }
            .mapNotNull { movement ->
                val working = movement.workingWeight?.takeIf { it > 0 } ?: return@mapNotNull null
                val sets = rampUpSets(working, Rack.of(preferences, movement.exercise))
                // A lift whose ramp rounds away is passed over rather than answered with an
                // empty one: the *next* compound lift may still have something to ramp into.
                sets.takeIf { it.isNotEmpty() }?.let {
                    RampUp(exerciseId = movement.exercise.id, sessionExerciseId = movement.sessionExerciseId, sets = it)
                }
            }
            // A sequence, so the search stops at the first lift that has an answer rather than
            // pricing every compound movement in the workout.
            .firstOrNull()

    /**
     * [working]'s ramp as [rack] can load it: the fractions in [RAMP_UP_STEPS], lightest first,
     * with everything that is not a lighter set than the one before it left out.
     *
     * Three things get dropped, and all three are the same mistake drawn differently: a set at
     * the working weight, a set heavier than the one after it, and two sets at the same weight.
     * A rack with one denomination rounds half and three quarters of a light lift to the same
     * number, and a card offering "40 × 5 then 40 × 3" before a 45 kg working set is offering
     * the working set twice.
     */
    private fun rampUpSets(
        working: Long,
        rack: Rack,
    ): List<PlannedSet> {
        var previous = 0L
        return RAMP_UP_STEPS.mapNotNull { step ->
            val weight = rack.scale(working, step.numerator, step.denominator)
            if (weight <= previous + PlateSolution.WEIGHT_TOLERANCE) return@mapNotNull null
            if (weight >= working - PlateSolution.WEIGHT_TOLERANCE) return@mapNotNull null
            previous = weight
            PlannedSet(setType = SetType.WARMUP, reps = step.reps, weight = weight)
        }
    }

    /**
     * A number fixed by the session and the candidate, and spread evenly enough to order by.
     *
     * The multiply-and-xor of a 64-bit mixer, written out rather than reached for through
     * `kotlin.random.Random(seed)`: a generator would have to be constructed, drawn from in a
     * fixed order and kept in step with a candidate list whose *length* changes as the catalogue
     * does, where this is a function of the two ids and nothing else. A session with one more
     * exercise in it therefore keeps the movements it was already showing.
     */
    private fun shuffleKey(
        sessionId: Long,
        exerciseId: Long,
    ): Long {
        var key = sessionId * SHUFFLE_MULTIPLIER + exerciseId
        key = (key xor (key ushr SHUFFLE_SHIFT_HIGH)) * SHUFFLE_MULTIPLIER
        return key xor (key ushr SHUFFLE_SHIFT_LOW)
    }

    /** Whether a discipline is mobility work rather than the training a warm-up prepares for. */
    private val Discipline?.isMobility: Boolean
        get() = this == Discipline.STRETCHING || this == Discipline.PLYOMETRICS

    /** One ramp-up set as a fraction of the working weight, and how many reps of it to do. */
    private class Step(
        val numerator: Long,
        val denominator: Long,
        val reps: Int,
    )

    /** The ramp-up, and the row it hangs off — the halves [WarmupSuggestion] holds. */
    private class RampUp(
        val exerciseId: Long,
        val sessionExerciseId: Long,
        val sets: List<PlannedSet>,
    )

    /**
     * Half the working weight for five, then three quarters of it for three.
     *
     * The ordinary two-set ramp: enough to move the joint through the range under load and put
     * the bar on the lifter's back twice, few enough reps that it costs nothing from the working
     * sets. A third, heavier set is what a 150 kg squatter wants and a 40 kg one does not, and
     * guessing which from a catalogue row is a worse answer than letting them add it themselves.
     */
    private val RAMP_UP_STEPS = listOf(
        Step(numerator = 1, denominator = 2, reps = 5),
        Step(numerator = 3, denominator = 4, reps = 3),
    )

    /** Never fewer than two movements: one stretch is not a warm-up. */
    private const val MIN_MOVEMENTS = 2

    /** Never more than three: a card longer than that is a workout in front of the workout. */
    private const val MAX_MOVEMENTS = 3

    /** An odd 64-bit constant with a well-spread bit pattern — splitmix64's. See [shuffleKey]. */
    private const val SHUFFLE_MULTIPLIER = -0x61c8_8646_80b5_83ebL

    private const val SHUFFLE_SHIFT_HIGH = 33
    private const val SHUFFLE_SHIFT_LOW = 29
}
