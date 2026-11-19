package com.titan.core.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.WorkoutActivity
import java.time.Instant
import java.time.ZoneId

/*
 * The domain's [HealthConnectSession] to an `ExerciseSessionRecord` and back.
 *
 * Top-level functions with no client behind them, so the mapping is tested on the JVM and the
 * instrumented test only has to prove the provider stores what these build.
 */

/**
 * The client record id a Titan session is written under.
 *
 * Health Connect upserts on it: a second write with the same id replaces the first, as long as
 * its version is not lower. Prefixed so that nothing about it can be mistaken for another app's
 * id in a log, and stable for the life of the row — the row's primary key never changes.
 */
internal fun clientRecordIdFor(sessionId: Long): String = "$CLIENT_RECORD_PREFIX$sessionId"

/**
 * The record for one session, recorded on this phone in [zone].
 *
 * `activelyRecorded` because that is what a Titan session is: started by the user on the device
 * when the workout began, and closed out when it ended — not typed in after the fact, which is
 * what `manualEntry` would claim.
 */
internal fun HealthConnectSession.toRecord(
    sessionId: Long,
    version: Long,
    zone: ZoneId,
): ExerciseSessionRecord {
    val start = Instant.ofEpochMilli(startedAt)
    val end = Instant.ofEpochMilli(endedAt)
    return ExerciseSessionRecord(
        startTime = start,
        startZoneOffset = zone.rules.getOffset(start),
        endTime = end,
        endZoneOffset = zone.rules.getOffset(end),
        metadata = Metadata.activelyRecorded(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = clientRecordIdFor(sessionId),
            clientRecordVersion = version,
        ),
        exerciseType = activity.toExerciseType(),
        title = title,
        notes = notes,
    )
}

/**
 * A record read back, whoever wrote it.
 *
 * @param ownPackage this app's package name, which is what a record Titan wrote carries as its
 *   data origin.
 */
internal fun ExerciseSessionRecord.toDomain(ownPackage: String): HealthConnectRecord =
    HealthConnectRecord(
        id = metadata.id,
        session = HealthConnectSession(
            startedAt = startTime.toEpochMilli(),
            // Health Connect holds a session to ending after it starts, but at nanosecond
            // precision, and two instants a few microseconds apart are the same millisecond.
            endedAt = endTime.toEpochMilli().coerceAtLeast(startTime.toEpochMilli() + 1),
            activity = exerciseType.toWorkoutActivity(),
            title = title,
            notes = notes,
        ),
        writtenByThisApp = metadata.dataOrigin.packageName == ownPackage,
    )

/** Each [WorkoutActivity] as the one Health Connect type it is written as. */
internal fun WorkoutActivity.toExerciseType(): Int =
    when (this) {
        WorkoutActivity.STRENGTH_TRAINING -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING

        WorkoutActivity.WEIGHTLIFTING -> ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING

        WorkoutActivity.CALISTHENICS -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS

        WorkoutActivity.STRETCHING -> ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING

        WorkoutActivity.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA

        WorkoutActivity.PILATES -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES

        WorkoutActivity.HIGH_INTENSITY_INTERVAL_TRAINING ->
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING

        WorkoutActivity.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING

        WorkoutActivity.WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING

        WorkoutActivity.HIKING -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING

        WorkoutActivity.BIKING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING

        WorkoutActivity.SWIMMING -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL

        WorkoutActivity.ROWING -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING

        WorkoutActivity.ELLIPTICAL -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL

        WorkoutActivity.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

/**
 * A Health Connect type as the [WorkoutActivity] it is imported as.
 *
 * Where Health Connect splits one activity by where it happened — a treadmill run, a pool swim,
 * a rowing machine — the split is folded back: the history has no use for it, and a duplicate
 * check that told a run from a treadmill run would let one workout in twice from two apps that
 * filed it differently. Every type not named is [WorkoutActivity.OTHER].
 */
internal fun Int.toWorkoutActivity(): WorkoutActivity =
    when (this) {
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> WorkoutActivity.STRENGTH_TRAINING

        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> WorkoutActivity.WEIGHTLIFTING

        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> WorkoutActivity.CALISTHENICS

        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> WorkoutActivity.STRETCHING

        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> WorkoutActivity.YOGA

        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> WorkoutActivity.PILATES

        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING ->
            WorkoutActivity.HIGH_INTENSITY_INTERVAL_TRAINING

        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        -> WorkoutActivity.RUNNING

        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WorkoutActivity.WALKING

        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> WorkoutActivity.HIKING

        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        -> WorkoutActivity.BIKING

        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        -> WorkoutActivity.SWIMMING

        ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        -> WorkoutActivity.ROWING

        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> WorkoutActivity.ELLIPTICAL

        else -> WorkoutActivity.OTHER
    }

private const val CLIENT_RECORD_PREFIX = "titan-session-"
