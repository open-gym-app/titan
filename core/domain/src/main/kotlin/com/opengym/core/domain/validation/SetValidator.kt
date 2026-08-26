package com.opengym.core.domain.validation

import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.WorkoutSet

/**
 * Bounds and shape checks on a single logged set, run before it is persisted.
 *
 * These are the rules the database cannot express — Room has no `CHECK` support,
 * so the domain layer is the only place they hold (see PERSISTENCE.md). They are
 * *not* enforced in [WorkoutSet]'s `init`: a half-filled set being edited in the
 * UI must be representable, so refusal has to be a value the caller inspects
 * rather than an exception thrown at construction. The one exception is `rpe`,
 * which `WorkoutSet` itself rejects, so it is not re-checked here.
 *
 * Every bound is expressed in the canonical storage units: weight in milligrams,
 * distance in centimeters, duration and rest in seconds.
 *
 * The ceilings are deliberately generous. They exist to catch a stray digit or a
 * unit mix-up, not to police what a user may log, so anything a real athlete does
 * has to fit under them: a multi-day ultra, an Ironman, a loaded sled push, a
 * high-rep conditioning block. When in doubt the bound goes up — a refused set
 * the user knows to be real is a worse failure than an implausible one stored.
 *
 * A null field is not a bound violation. Which dimensions a set *must* fill in
 * depends on how the exercise is logged, which the set itself does not know:
 * pass the [ExerciseType] to have that checked too, and see [validate].
 */
object SetValidator {

    /** A logged set records at least one rep; zero means the set was not performed. */
    const val REPS_MIN = 1

    /** Clears any conditioning or high-rep block; past this it is a typo, not a set. */
    const val REPS_MAX = 10_000

    /** Bodyweight movements are logged at 0, so the floor is zero rather than positive. */
    const val WEIGHT_MIN_MG = 0L

    /** 10 tonnes, in milligrams — above any loaded implement, sled or leg-press stack. */
    const val WEIGHT_MAX_MG = 10_000_000_000L

    /** A set of zero seconds was not performed, the same reasoning as [REPS_MIN]. */
    const val DURATION_MIN_SECONDS = 1L

    /**
     * 10 days, in seconds. Sized for the longest continuous efforts people actually
     * log — a 6-day race, a multi-day ultra — rather than for a single gym set.
     */
    const val DURATION_MAX_SECONDS = 864_000L

    /**
     * Zero is allowed: like a bodyweight [WEIGHT_MIN_MG], a present-but-zero
     * magnitude is legal, while "no distance to record" is expressed as null.
     */
    const val DISTANCE_MIN_CM = 0L

    /** 10,000 km, in centimeters — an order of magnitude past any single effort. */
    const val DISTANCE_MAX_CM = 1_000_000_000L

    /** Rest is a duration, so it cannot run backwards. */
    const val REST_MIN_SECONDS = 0L

    private const val MG_PER_KG = 1_000_000L
    private const val CM_PER_KM = 100_000L
    private const val SECONDS_PER_DAY = 86_400L

    /**
     * Checks [set] against every rule, reporting *all* violations in one
     * [ValidationResult.Invalid] rather than stopping at the first: a user who
     * mis-typed both the weight and the reps should see both, not fix one and be
     * refused again.
     *
     * [exerciseType] is how the exercise is logged, and it is what decides which
     * dimensions the set has to fill in — a [ExerciseType.STRENGTH] set records
     * reps, an [ExerciseType.ENDURANCE] one a duration with distance optional,
     * an [ExerciseType.ISOMETRIC] one a duration alone. Pass null (the default)
     * when the caller has not loaded the exercise and only wants the bounds
     * checked; the numeric rules are identical either way.
     *
     * Completeness is only required of a set flagged `isCompleted`. A session may
     * hold planned-but-unperformed sets whose dimensions are still empty (see
     * PERSISTENCE.md), and refusing to store those would make a workout
     * impossible to plan ahead.
     */
    fun validate(
        set: WorkoutSet,
        exerciseType: ExerciseType? = null,
    ): ValidationResult {
        val violations = set.boundViolations() + set.shapeViolations(exerciseType)

        return if (violations.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(violations.joinToString("; "))
        }
    }

    /** Range checks on whichever dimensions this set actually carries. */
    private fun WorkoutSet.boundViolations(): List<String> =
        buildList {
            reps?.let { value ->
                if (value !in REPS_MIN..REPS_MAX) {
                    add("reps must be within $REPS_MIN..$REPS_MAX, was $value")
                }
            }
            weight?.let { value ->
                if (value !in WEIGHT_MIN_MG..WEIGHT_MAX_MG) {
                    add(
                        "weight must be within ${WEIGHT_MIN_MG / MG_PER_KG} kg.." +
                            "${WEIGHT_MAX_MG / MG_PER_KG} kg " +
                            "($WEIGHT_MIN_MG..$WEIGHT_MAX_MG mg), was $value mg",
                    )
                }
            }
            duration?.let { value ->
                if (value !in DURATION_MIN_SECONDS..DURATION_MAX_SECONDS) {
                    add(
                        "duration must be within $DURATION_MIN_SECONDS s.." +
                            "${DURATION_MAX_SECONDS / SECONDS_PER_DAY} days " +
                            "($DURATION_MIN_SECONDS..$DURATION_MAX_SECONDS s), was $value s",
                    )
                }
            }
            distance?.let { value ->
                if (value !in DISTANCE_MIN_CM..DISTANCE_MAX_CM) {
                    add(
                        "distance must be within ${DISTANCE_MIN_CM / CM_PER_KM} km.." +
                            "${DISTANCE_MAX_CM / CM_PER_KM} km " +
                            "($DISTANCE_MIN_CM..$DISTANCE_MAX_CM cm), was $value cm",
                    )
                }
            }
            restAfter?.let { value ->
                if (value < REST_MIN_SECONDS) {
                    add("restAfter must be at least $REST_MIN_SECONDS s, was $value s")
                }
            }
        }

    /**
     * Which dimension a completed set of [exerciseType] has to record.
     *
     * Public because it is not only a step of [validate]: a screen has to know, *before* it
     * offers a tick, whether the fields it draws can satisfy the set it is about to write. Asking
     * here is what keeps that answer and this refusal the same answer — an entry row that
     * accepted a set the very next check refuses is the disagreement this exists to prevent.
     *
     * Distance is not among them, deliberately: a plank has none and a treadmill set logged by
     * time alone is still a complete record of what was done, so it stays optional everywhere.
     */
    fun requiredDimension(exerciseType: ExerciseType): SetDimension =
        when (exerciseType) {
            ExerciseType.STRENGTH -> SetDimension.REPS
            ExerciseType.ENDURANCE, ExerciseType.ISOMETRIC -> SetDimension.DURATION
        }

    /**
     * The dimension the logging mode is built around must be present once the set
     * is performed. Nothing here forbids the *other* dimensions — timing a set of
     * squats or loading a weighted carry is a legitimate thing to record.
     */
    private fun WorkoutSet.shapeViolations(exerciseType: ExerciseType?): List<String> {
        if (exerciseType == null || !isCompleted) return emptyList()

        val missing = when (requiredDimension(exerciseType)) {
            SetDimension.REPS -> "reps".takeIf { reps == null }
            SetDimension.DURATION -> "a duration".takeIf { duration == null }
        }
        return listOfNotNull(missing?.let { "a completed ${exerciseType.name} set must record $it" })
    }
}
