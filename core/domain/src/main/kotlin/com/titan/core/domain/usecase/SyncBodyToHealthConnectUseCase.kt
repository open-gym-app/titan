package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.repository.BodyMeasurementRepository
import kotlinx.coroutines.CancellationException

/**
 * Brings one body measurement's Health Connect records in line with its row: a `WeightRecord`
 * for its weight and a `BodyFatRecord` for its body fat, each where the entry has one and the
 * user has allowed it.
 *
 * Run from a queued background job (see
 * [com.titan.core.domain.healthconnect.HealthConnectBodySyncScheduler]), asked for by the body
 * screen whenever an entry is logged, edited, deleted or put back. It reads the row as it is
 * when it runs, so it can be called again for the same entry at any time.
 *
 * ### Each figure on its own
 *
 * Health Connect grants weight and body fat separately, and an entry carries either, both or
 * neither. For each figure whose write is granted, the figure is written when the entry holds
 * it and its record removed when it does not — so clearing a mistyped body fat in an edit takes
 * it out of Health Connect too, rather than leaving the old value there for good. A figure whose
 * write is not granted is left alone either way. The circumferences have no record type and are
 * never written.
 *
 * ### Removed with the entry
 *
 * An entry that is gone — deleted, or replaced under its id by a new one after "delete all
 * data" — has its records removed. That is why the job carries the entry's `created_at` as well
 * as its id: there is no row left to read it from, and the records are keyed by both (see
 * [HealthConnect.writeBodyReading]). An undo puts the row back under the same id and date, and
 * the job that follows writes the records back.
 *
 * ### The version is `updated_at`
 *
 * Every save stamps it, so every edit carries a later version than the write before it and
 * replaces it. Unlike a session's rename, nothing changes a measurement without stamping it, so
 * there is no edit the clock would have to stand in for. A clock set back between two saves
 * loses the second to the first — the trade every timestamp in this app makes.
 *
 * ### What is not written
 *
 * An entry imported from Health Connect ([BodyMeasurement.fromHealthConnect]). It is there
 * already, under the app that measured it.
 *
 * ### Refusals
 *
 * As [SyncToHealthConnectUseCase]: [HealthConnectError.Unavailable], and
 * [HealthConnectError.NotPermitted] naming [HealthConnectAccess.WRITE_WEIGHT] when neither figure
 * may be written, in the [Result]; anything the provider throws there too, except a
 * cancellation.
 */
class SyncBodyToHealthConnectUseCase(
    private val healthConnect: HealthConnect,
    private val measurements: BodyMeasurementRepository,
) {

    suspend operator fun invoke(
        measurementId: Long,
        measuredAt: Long,
    ): Result<HealthConnectBodySyncOutcome> {
        // `runCatching` with a cancellation put back, for `SyncToHealthConnectUseCase`'s reasons.
        return runCatching {
            val availability = healthConnect.availability()
            if (availability != HealthConnectAvailability.AVAILABLE) {
                Result.failure(HealthConnectError.Unavailable(availability))
            } else {
                sync(measurementId, measuredAt)
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            Result.failure(failure)
        }
    }

    private suspend fun sync(
        measurementId: Long,
        measuredAt: Long,
    ): Result<HealthConnectBodySyncOutcome> {
        val granted = healthConnect.grantedAccess()
        val writable = HealthConnectBodyMetric.entries.filter { it.writeAccess in granted }
        if (writable.isEmpty()) {
            return Result.failure(HealthConnectError.NotPermitted(HealthConnectAccess.WRITE_WEIGHT))
        }

        // A row under the same id with another date is a different measurement: the one the job
        // was asked about is gone, and the records it left are what this pass removes. The new
        // one has had a job of its own asked for when it was saved.
        val entry = measurements.get(measurementId)?.takeIf { it.createdAt == measuredAt }
        if (entry == null) {
            writable.forEach { healthConnect.deleteBodyReading(measurementId, measuredAt, it) }
            return Result.success(HealthConnectBodySyncOutcome.REMOVED)
        }
        if (entry.fromHealthConnect) return Result.success(HealthConnectBodySyncOutcome.IMPORTED)

        writable.forEach { metric ->
            val value = entry.valueOf(metric)
            if (value == null) {
                healthConnect.deleteBodyReading(measurementId, measuredAt, metric)
            } else {
                healthConnect.writeBodyReading(
                    measurementId = measurementId,
                    version = entry.updatedAt,
                    reading = HealthConnectBodyReading(metric = metric, measuredAt = measuredAt, value = value),
                )
            }
        }
        return Result.success(HealthConnectBodySyncOutcome.WRITTEN)
    }
}

/** What [SyncBodyToHealthConnectUseCase] did with an entry it was allowed to look at. */
enum class HealthConnectBodySyncOutcome {

    /** Each granted figure written, or removed where the entry no longer holds it. */
    WRITTEN,

    /** The entry is gone, and so are the records written for it. */
    REMOVED,

    /** Came from Health Connect in the first place. Nothing to write back. */
    IMPORTED,
}

/** [metric]'s figure on this entry, in [HealthConnectBodyReading.value]'s units, or null. */
internal fun BodyMeasurement.valueOf(metric: HealthConnectBodyMetric): Long? =
    when (metric) {
        HealthConnectBodyMetric.WEIGHT -> weight
        HealthConnectBodyMetric.BODY_FAT -> bodyFat?.toLong()
    }
