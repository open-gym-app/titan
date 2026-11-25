package com.titan.core.data.healthconnect

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectBodyRecord
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.WorkoutActivity
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/*
 * The domain's [HealthConnectSession] to an `ExerciseSessionRecord` and back, and its
 * [HealthConnectBodyReading] to a `WeightRecord` or a `BodyFatRecord` and back.
 *
 * Top-level functions with no client behind them, so the mapping is tested on the JVM and the
 * instrumented test only has to prove the provider stores what these build.
 */

/**
 * The client record id a Titan session is written under.
 *
 * Health Connect upserts on it: a second write with the same id replaces the first, as long as
 * its version is not lower. Prefixed so that nothing about it can be mistaken for another app's
 * id in a log, and stable for the life of the row — neither the primary key nor `created_at`
 * ever changes.
 *
 * The id alone is not enough, because it is not unique over time. "Delete all data" resets the
 * id counters, and a reinstall that restores an older backup resumes them below ids already
 * used, while the records written under those ids stay in Health Connect. Keyed on the id only,
 * the next workout to be given an old number would be written over the record of the one that
 * had it — a newer version, so Health Connect would accept it. The start time is what the
 * session's other out-of-process references pair the id with for the same reason (see
 * `SessionRef`), and a new workout under an old number does not share it.
 */
internal fun clientRecordIdFor(
    sessionId: Long,
    startedAt: Long,
): String = "$CLIENT_RECORD_PREFIX$sessionId-$startedAt"

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
            clientRecordId = clientRecordIdFor(sessionId, startedAt),
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

/**
 * The client record id one figure of a Titan body measurement is written under.
 *
 * The measurement's id and `created_at`, for [clientRecordIdFor]'s reason — an id can be handed
 * out again, and a new entry under an old number must not replace the old entry's record — and
 * `created_at` is as fixed as the id: the form shows an entry's date and does not edit it. The
 * figure is in the prefix, so a weight and a body fat taken together are two ids, not one.
 */
internal fun bodyClientRecordIdFor(
    metric: HealthConnectBodyMetric,
    measurementId: Long,
    measuredAt: Long,
): String =
    when (metric) {
        HealthConnectBodyMetric.WEIGHT -> WEIGHT_RECORD_PREFIX
        HealthConnectBodyMetric.BODY_FAT -> BODY_FAT_RECORD_PREFIX
    } + "$measurementId-$measuredAt"

/**
 * The metadata of one figure Titan writes.
 *
 * `manualEntry`, unlike a session's `activelyRecorded`: a body measurement is a figure the user
 * read off a scale or a tape and typed in, which is exactly what Health Connect means by it.
 */
private fun bodyMetadata(
    metric: HealthConnectBodyMetric,
    measurementId: Long,
    measuredAt: Long,
    version: Long,
): Metadata =
    Metadata.manualEntry(
        clientRecordId = bodyClientRecordIdFor(metric, measurementId, measuredAt),
        clientRecordVersion = version,
        device = Device(type = Device.TYPE_PHONE),
    )

/** A weight reading as the `WeightRecord` Titan writes for it, recorded in [zone]. */
internal fun HealthConnectBodyReading.toWeightRecord(
    measurementId: Long,
    version: Long,
    zone: ZoneId,
): WeightRecord {
    require(metric == HealthConnectBodyMetric.WEIGHT) { "not a weight: $metric" }
    val time = Instant.ofEpochMilli(measuredAt)
    return WeightRecord(
        time = time,
        zoneOffset = zone.rules.getOffset(time),
        // Milligrams, the stored unit, so nothing is lost on the way out.
        weight = Mass.milligrams(value.toDouble()),
        metadata = bodyMetadata(metric, measurementId, measuredAt, version),
    )
}

/** A body-fat reading as the `BodyFatRecord` Titan writes for it, recorded in [zone]. */
internal fun HealthConnectBodyReading.toBodyFatRecord(
    measurementId: Long,
    version: Long,
    zone: ZoneId,
): BodyFatRecord {
    require(metric == HealthConnectBodyMetric.BODY_FAT) { "not a body fat: $metric" }
    val time = Instant.ofEpochMilli(measuredAt)
    return BodyFatRecord(
        time = time,
        zoneOffset = zone.rules.getOffset(time),
        percentage = Percentage(value / TENTHS_PER_PERCENT),
        metadata = bodyMetadata(metric, measurementId, measuredAt, version),
    )
}

/**
 * A `WeightRecord` read back, whoever wrote it, rounded to the milligram the history stores — a
 * double from another app's kilograms is rarely a whole number of them.
 */
internal fun WeightRecord.toDomain(ownPackage: String): HealthConnectBodyRecord =
    HealthConnectBodyRecord(
        id = metadata.id,
        reading = HealthConnectBodyReading(
            metric = HealthConnectBodyMetric.WEIGHT,
            measuredAt = time.toEpochMilli(),
            value = weight.inMilligrams.roundToLong(),
        ),
        writtenByThisApp = metadata.dataOrigin.packageName == ownPackage,
    )

/** A `BodyFatRecord` read back, whoever wrote it, rounded to the tenth of a percent stored. */
internal fun BodyFatRecord.toDomain(ownPackage: String): HealthConnectBodyRecord =
    HealthConnectBodyRecord(
        id = metadata.id,
        reading = HealthConnectBodyReading(
            metric = HealthConnectBodyMetric.BODY_FAT,
            measuredAt = time.toEpochMilli(),
            value = (percentage.value * TENTHS_PER_PERCENT).roundToLong(),
        ),
        writtenByThisApp = metadata.dataOrigin.packageName == ownPackage,
    )

private const val WEIGHT_RECORD_PREFIX = "titan-weight-"

private const val BODY_FAT_RECORD_PREFIX = "titan-body-fat-"

/** `body_fat` is stored in tenths of a percent; Health Connect's `Percentage` is in percent. */
private const val TENTHS_PER_PERCENT = 10.0
