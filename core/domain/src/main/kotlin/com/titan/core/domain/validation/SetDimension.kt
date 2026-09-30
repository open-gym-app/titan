package com.titan.core.domain.validation

import com.titan.core.domain.model.ExerciseType

/**
 * What a performed set of an exercise has to record: the dimension its logging mode is built
 * around.
 *
 * Two values rather than one per [ExerciseType], because two of the three types are logged the
 * same way and because this is not a restatement of the type — it is the answer to the one
 * question a caller asks of it. [SetValidator.requiredDimension] is where the mapping lives, and
 * it is the same mapping [SetValidator] enforces, so a screen deciding whether its entry row can
 * satisfy a set and the check that would refuse that set cannot drift apart.
 *
 * A screen that offers weight and reps can fill in [REPS] and cannot fill in [DURATION]. That is
 * the whole reason this is worth naming: without it, "can this exercise be logged here at all"
 * is a question a screen can only answer by copying the rule.
 */
enum class SetDimension {

    /** How many times it was performed. */
    REPS,

    /** How long it was held or sustained, in seconds. */
    DURATION,
}
