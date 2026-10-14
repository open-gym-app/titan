package com.titan.core.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import java.time.Instant
import java.time.ZoneId

/** `HealthWorkout` ⇄ `ExerciseSessionRecord`. */
internal object HealthConnectMapper {

    /**
     * A strength-training record. When [HealthWorkout.externalId] is set it becomes the record's
     * client id, with [version] as its client version, which makes a second write of the same
     * session an update in place instead of a second record.
     */
    fun toRecord(
        workout: HealthWorkout,
        version: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ExerciseSessionRecord {
        val start = Instant.ofEpochMilli(workout.startMillis)
        val end = Instant.ofEpochMilli(workout.endMillis)
        val externalId = workout.externalId
        return ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            metadata = if (externalId == null) {
                Metadata.manualEntry()
            } else {
                Metadata.manualEntry(externalId, version)
            },
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = workout.title,
            notes = workout.notes,
        )
    }

    /** A record shorter than a millisecond is rounded up to one, so it still passes the workout's own check. */
    fun toWorkout(record: ExerciseSessionRecord): HealthWorkout {
        val start = record.startTime.toEpochMilli()
        return HealthWorkout(
            startMillis = start,
            endMillis = maxOf(record.endTime.toEpochMilli(), start + 1),
            type = when (record.exerciseType) {
                ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
                -> HealthWorkoutType.STRENGTH_TRAINING

                else -> HealthWorkoutType.OTHER
            },
            title = record.title,
            notes = record.notes,
            externalId = record.metadata.clientRecordId,
        )
    }
}
