package com.titan.core.domain.model

/**
 * The training tradition a movement comes from. Stored in the
 * exercise.discipline column, nullable because a user-created exercise need not
 * belong to one.
 *
 * Distinct from [ExerciseType], which is a *logging mode* - how a set is
 * recorded. Five of these disciplines are all logged as
 * [ExerciseType.STRENGTH], so the two columns cannot be derived from each
 * other: without [Discipline] a plyometric box jump and a bench press are
 * indistinguishable rows.
 */
enum class Discipline {
    STRENGTH,
    POWERLIFTING,
    OLYMPIC_WEIGHTLIFTING,
    STRONGMAN,
    PLYOMETRICS,
    STRETCHING,
    CARDIO,
}
