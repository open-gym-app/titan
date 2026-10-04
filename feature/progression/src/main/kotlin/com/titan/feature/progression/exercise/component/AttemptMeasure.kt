package com.titan.feature.progression.exercise.component

import com.titan.core.domain.model.ExerciseAttempt

/**
 * What an exercise's attempts are compared by: the volume of a lift, or the distance or the time
 * of a timed exercise (#M7-17).
 *
 * A run or a plank moves nothing, so every surface of this screen that quotes volume — the bars,
 * the last column of the attempts table — would be a row of zeroes saying nothing happened. This
 * is the one decision all of them take instead, read off the attempts on screen, so the chart and
 * the table under it cannot disagree about what the exercise is measured in.
 */
internal enum class AttemptMeasure {
    VOLUME,
    DISTANCE,
    TIME,
    ;

    companion object {
        /**
         * Volume, unless no attempt lifted anything and at least one was timed — then the ground
         * covered where any attempt covered some, and how long they lasted otherwise. A run is
         * compared by how far it went; a hold by how long.
         */
        fun of(attempts: List<ExerciseAttempt>): AttemptMeasure =
            when {
                attempts.any { it.volume > 0 } -> VOLUME
                attempts.any { it.distance > 0 } -> DISTANCE
                attempts.any { it.timedSeconds > 0 } -> TIME
                else -> VOLUME
            }
    }
}
