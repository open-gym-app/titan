package com.titan.core.domain.stats

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedExercise
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.template.TemplateDuration
import com.titan.core.domain.validation.BodyMeasurementValidator
import com.titan.core.domain.validation.SessionEffortValidator
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * About how many kilocalories a finished workout burned, for a user who has no watch to say.
 *
 * ### The formula
 *
 * The standard one for an activity whose cost is known in METs — multiples of the resting
 * metabolic rate, taken as 1 kcal per kilogram per hour:
 *
 * ```
 * kcal = Σ over the session's time  MET × body mass (kg) × hours
 * ```
 *
 * **The time is the time the sets kept the user working, not the clock.** It is
 * [TemplateDuration]'s, over the sets that were performed: each set's own duration where it was
 * timed, its reps at [TemplateDuration.SECONDS_PER_REP] where it was counted, and the rest the
 * workout's timer prescribes after each exercise. Start-to-finish would also price the
 * conversation by the water fountain and the phone left open on the recap; this prices the
 * workout that the sets say happened, so two sessions of the same sets cost the same however
 * long the user lingered.
 *
 * **The METs are the 2024 Adult Compendium of Physical Activities'** (Herrmann et al., *Journal
 * of Sport and Health Science* 13(1), 2024), chosen by the exercise's logging mode:
 *
 * | Type | MET | Compendium entry |
 * | --- | --- | --- |
 * | [ExerciseType.STRENGTH] | [STRENGTH_MET] | 02054, resistance training, multiple exercises, 8–15 reps |
 * | [ExerciseType.ISOMETRIC] | [ISOMETRIC_MET] | 02024, calisthenics incl. plank, light effort |
 * | [ExerciseType.ENDURANCE] | [ENDURANCE_MET] | 01014, bicycling, general (jogging is 7.5, a stationary bike 6.8) |
 *
 * ### Why rest is priced, and at what
 *
 * A resistance-training MET is measured over a *session*, rest periods included — a lifter's
 * heart rate and breathing stay well above rest between sets, and the oxygen debt of a set is
 * repaid during the rest after it. So a strength or isometric exercise's rest is priced at the
 * exercise's own MET, which is how the figure was measured; pricing only the thirty seconds
 * under the bar would put an hour of lifting at a quarter of what it costs. A cardio MET is a
 * figure for continuous effort, so the rest between intervals is priced at [RECOVERY_MET], a
 * quiet stand, instead.
 *
 * ### Gross, and approximate
 *
 * The figure is *gross*: it includes the energy the user would have spent anyway, as the
 * Compendium's METs and most watches' totals do. It is a ballpark, not a measurement — a MET
 * is a population average for the activity, and one rate for every cardio machine cannot tell
 * a brisk walk from a sprint — which is why it is written only when the user has not entered a
 * figure, and the recap lets them replace it.
 *
 * An exercise the catalogue no longer has is priced as a lift, the way the rest of the domain
 * judges an exercise of unknown type.
 */
object CalorieEstimate {

    /** 2024 Compendium 02054: resistance training, multiple exercises, 8–15 reps at varied resistance. */
    const val STRENGTH_MET = 3.5

    /** 2024 Compendium 02024: calisthenics such as curl-ups, crunches and the plank, light effort. */
    const val ISOMETRIC_MET = 2.8

    /**
     * 2024 Compendium 01014: bicycling, general. A middle figure for the cardio a gym logs: a
     * stationary bike is 6.8, jogging at a self-selected pace 7.5, a rowing ergometer 5.0 to 7.3.
     */
    const val ENDURANCE_MET = 7.0

    /**
     * Standing still between cardio intervals: between the Compendium's 1.3 for standing quietly
     * and 1.8 for standing with light movement.
     */
    const val RECOVERY_MET = 1.5

    /**
     * The body mass assumed for a user who has never logged their weight, in milligrams: 82 kg.
     *
     * The average adult man's in Europe, roughly. No survey publishes one figure for the
     * continent; national figures for its most populous countries run from about 79 kg (Italy,
     * France) to about 88 kg (Germany), and 82 kg sits near their population-weighted middle.
     * A logged weight replaces it, and is what a user who wants a better estimate should give.
     */
    const val REFERENCE_BODY_MASS_MG = 82_000_000L

    /** The weights `BodyMeasurementValidator` would accept — see [bodyMassAt]. */
    private val PLAUSIBLE_WEIGHT = BodyMeasurementValidator.WEIGHT_MIN_MG..BodyMeasurementValidator.WEIGHT_MAX_MG

    private const val MG_PER_KG = 1_000_000.0
    private const val SECONDS_PER_HOUR = 3_600.0

    /**
     * The estimate for a finished session, in whole kilocalories, or null when there is nothing
     * to price or the figure falls outside [SessionEffortValidator]'s bounds.
     *
     * [exercises] are the session's, in any order — they are put in position order here — and
     * [sets] its sets, of which only the completed ones count. [defaultRest] and [catalogue] are
     * [TemplateDuration.estimate]'s, with its reasons for being required. [bodyMass] is in
     * milligrams; see [bodyMassAt] for where it comes from.
     */
    fun of(
        exercises: List<SessionExercise>,
        sets: List<WorkoutSet>,
        defaultRest: Duration,
        catalogue: Map<Long, Exercise>,
        bodyMass: Long,
    ): Int? {
        val performed = performed(exercises, sets)
        val breakdown = TemplateDuration.breakdown(performed, defaultRest, catalogue)

        // MET-hours, summed in floating point and rounded once at the end.
        val metHours = breakdown.sumOf { exercise ->
            val (workMet, restMet) = metsOf(exercise.type)
            workMet * exercise.work.hours + restMet * exercise.rest.hours
        }
        val kilocalories = (metHours * bodyMass / MG_PER_KG).roundToInt()

        return kilocalories.takeIf { it in SessionEffortValidator.CALORIES_MIN..SessionEffortValidator.CALORIES_MAX }
    }

    /**
     * The body mass to price a session finished at [at] with, in milligrams: the weight of the
     * most recent of [measurements] taken at or before [at] that records one, or
     * [REFERENCE_BODY_MASS_MG] when none does.
     *
     * At or before rather than simply the latest, so that the weight is the one the user had
     * that day. A weight `BodyMeasurementValidator` would refuse — which only an import can
     * write — is passed over, for the reason [TemplateDuration] passes over such a duration.
     */
    fun bodyMassAt(
        measurements: List<BodyMeasurement>,
        at: Long,
    ): Long =
        measurements
            .filter { it.createdAt <= at && it.weight in PLAUSIBLE_WEIGHT }
            .maxByOrNull(BodyMeasurement::createdAt)
            ?.weight
            ?: REFERENCE_BODY_MASS_MG

    /** The (work, rest) METs for an exercise of [type]; see the class note. */
    private fun metsOf(type: ExerciseType?): Pair<Double, Double> =
        when (type) {
            ExerciseType.STRENGTH, null -> STRENGTH_MET to STRENGTH_MET
            ExerciseType.ISOMETRIC -> ISOMETRIC_MET to ISOMETRIC_MET
            ExerciseType.ENDURANCE -> ENDURANCE_MET to RECOVERY_MET
        }

    /**
     * The session as [TemplateDuration] reads a plan: each exercise in position order, with the
     * sets it was performed with. An exercise with no completed set plans none, and the estimate
     * drops it — it was not performed.
     */
    private fun performed(
        exercises: List<SessionExercise>,
        sets: List<WorkoutSet>,
    ): List<PlannedExercise> {
        val byExercise = sets.filter(WorkoutSet::isCompleted).groupBy(WorkoutSet::sessionExerciseId)
        return exercises.sortedBy(SessionExercise::position).map { exercise ->
            Performed(
                exerciseId = exercise.exerciseId,
                sets = byExercise[exercise.id].orEmpty()
                    .sortedBy(WorkoutSet::setNumber)
                    .map { set ->
                        PlannedSet(
                            setType = set.setType,
                            reps = set.reps,
                            weight = set.weight,
                            duration = set.duration,
                            distance = set.distance,
                        )
                    },
                supersetGroup = exercise.supersetGroup,
            )
        }
    }

    private val Duration.hours: Double get() = toDouble(DurationUnit.SECONDS) / SECONDS_PER_HOUR

    /** One performed exercise, in the shape [TemplateDuration] prices. */
    private data class Performed(
        override val exerciseId: Long,
        override val sets: List<PlannedSet>,
        override val supersetGroup: Int?,
    ) : PlannedExercise
}
