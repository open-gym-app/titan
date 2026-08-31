package com.opengym.core.domain.model

/**
 * An exercise the user has actually logged, and enough about it to pick from a list.
 *
 * What the per-exercise drill-down is chosen from when it is opened without a lift in hand.
 * Built from the history rather than from the catalogue: 873 seeded exercises is a search
 * problem, and the answer a lifter wants is nearly always in the handful they trained this
 * week.
 *
 * @property exerciseId the catalogue row.
 * @property name its name, carried rather than looked up — see
 *   `WorkoutSetDao.getTrainedExercises`.
 * @property sets completed working sets, all-time. The same figure the drill-down opens with.
 * @property lastPerformedAt when the most recent session holding it was closed out, epoch
 *   millis. What the list is ordered on.
 */
data class TrainedExercise(
    val exerciseId: Long,
    val name: String,
    val sets: Int,
    val lastPerformedAt: Long,
)
