package com.titan.core.domain.model

/**
 * Canonical storage units (see docs/db-schema): [weight] in milligrams,
 * [distance] and [elevationGain] in centimeters, [duration] and [restAfter] in seconds,
 * [steps] as a count, [rpe] as RPE x 10 (65 = RPE 6.5). Unit conversions happen in the UI
 * layer.
 *
 * [elevationGain] and [steps] describe a piece of cardio — the climb of a hill run, the steps
 * of a walk — and stay null on every other set, the way [distance] does on a bench press.
 */
data class WorkoutSet(
    val id: Long = 0,
    val sessionExerciseId: Long,
    val setNumber: Int,
    val reps: Int? = null,
    val weight: Long? = null,
    val duration: Long? = null,
    val distance: Long? = null,
    val heartRateAvg: Int? = null,
    val heartRateMax: Int? = null,
    val elevationGain: Long? = null,
    val steps: Int? = null,
    val restAfter: Long? = null,
    val rpe: Int? = null,
    val setType: SetType = SetType.NORMAL,
    val completedAt: Long,
    val isCompleted: Boolean = false,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(rpe == null || rpe in RPE_MIN..RPE_MAX) {
            "rpe must be within $RPE_MIN..$RPE_MAX (RPE x 10), was $rpe"
        }
    }

    companion object {
        const val RPE_MIN = 10
        const val RPE_MAX = 100
    }
}
