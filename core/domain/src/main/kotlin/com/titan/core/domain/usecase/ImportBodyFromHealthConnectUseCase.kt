package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyRecord
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.validation.BodyMeasurementValidator
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.days

/**
 * Brings the weights and body-fat readings other apps wrote to Health Connect in the last
 * [ImportFromHealthConnectUseCase.WINDOW_DAYS] days into the body measurement history.
 *
 * The window is the session import's, for its reason: Health Connect only lets an app read that
 * far back by default.
 *
 * ### One entry per instant
 *
 * Readings taken at the same millisecond become one entry, holding both figures. A smart scale
 * writes its weight and its body fat as two records with one timestamp, and kept apart they would
 * be two rows in the history — and no lean mass, which is only worked out from a weight and a
 * body fat on the same entry. That holds across imports too: a figure for an instant an earlier
 * import already made an entry for is added to that entry. Every imported entry is marked
 * [BodyMeasurement.fromHealthConnect], so the sync never writes it back.
 *
 * ### What is skipped
 *
 * A record is not imported when it is:
 *
 * 1. **Titan's own.** [SyncBodyToHealthConnectUseCase] wrote it from an entry in the history.
 * 2. **A duplicate by timestamp.** An entry already in the history — logged here or imported
 *    before — was measured at the same instant and holds the same figure. This is what makes a
 *    second import of the same month add nothing, with no record id stored to compare: an
 *    imported entry keeps the record's timestamp as its own. Records within one import are
 *    checked against each other the same way, so two apps that wrote one weigh-in bring it in once.
 * 3. **Out of bounds.** A figure [BodyMeasurementValidator] would refuse from the form — a weight
 *    of zero, a body fat past 100% — is not brought in through the back door either.
 *
 * Exact to the millisecond, as the session import is, and for the same reason: a looser match
 * would merge two weigh-ins somebody really did take a minute apart.
 *
 * ### Refusals
 *
 * As [ImportFromHealthConnectUseCase]: [HealthConnectError.Unavailable], and
 * [HealthConnectError.NotPermitted] naming [HealthConnectAccess.READ_WEIGHT] when neither figure
 * may be read, in the [Result]; anything the provider or the database throws there too, except a
 * cancellation. A user who allowed one figure and not the other has that one imported.
 */
class ImportBodyFromHealthConnectUseCase(
    private val healthConnect: HealthConnect,
    private val measurements: BodyMeasurementRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    suspend operator fun invoke(): Result<HealthConnectBodyImport> {
        // `runCatching` with a cancellation put back, for `ImportFromHealthConnectUseCase`'s reasons.
        return runCatching {
            val availability = healthConnect.availability()
            if (availability != HealthConnectAvailability.AVAILABLE) {
                Result.failure(HealthConnectError.Unavailable(availability))
            } else {
                import()
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            Result.failure(failure)
        }
    }

    private suspend fun import(): Result<HealthConnectBodyImport> {
        val granted = healthConnect.grantedAccess()
        val readable = HealthConnectBodyMetric.entries.filter { it.readAccess in granted }
        if (readable.isEmpty()) {
            return Result.failure(HealthConnectError.NotPermitted(HealthConnectAccess.READ_WEIGHT))
        }

        val now = clock.nowMillis()
        val from = now - ImportFromHealthConnectUseCase.WINDOW_DAYS.days.inWholeMilliseconds
        // `now + 1` so a reading taken this very millisecond is in the half-open range, and the
        // same bounds on both sides so the two lists cover the same span.
        val records = readable.flatMap {
            healthConnect.readBodyReadings(
                it,
                measuredFrom = from,
                measuredUntil = now + 1,
            )
        }
        val local = measurements.getMeasuredBetween(measuredFrom = from, measuredUntil = now + 1)

        val seen = HashSet<DuplicateKey>()
        local.forEach { entry ->
            HealthConnectBodyMetric.entries.forEach { metric ->
                if (entry.valueOf(metric) != null) seen += DuplicateKey(entry.createdAt, metric)
            }
        }

        val fresh = records
            .sortedBy { it.reading.measuredAt }
            .filter { record ->
                !record.writtenByThisApp &&
                    record.isInBounds() &&
                    seen.add(DuplicateKey(record.reading.measuredAt, record.reading.metric))
            }

        // An instant an earlier import already made an entry for — when only one figure could be
        // read then, or the second record was written after it ran — gets the new figure on that
        // entry rather than a second entry beside it. An entry logged here is left as it is: a
        // figure added to it would be written back to Health Connect as Titan's.
        val importedAt = local.filter { it.fromHealthConnect }.associateBy { it.createdAt }
        val (completing, adding) = fresh
            .groupBy { it.reading.measuredAt }
            .entries
            .partition { it.key in importedAt }

        // The inserts first, in their one transaction. A completion that fails after them is
        // retried by the next import, which still finds its figure missing from the entry.
        val entries = adding.map { (measuredAt, readings) -> readings.toEntry(measuredAt, now) }
        if (entries.isNotEmpty()) measurements.insertAll(entries)
        completing.forEach { (measuredAt, readings) ->
            measurements.update(importedAt.getValue(measuredAt).completedWith(readings, now))
        }
        return Result.success(
            HealthConnectBodyImport(
                imported = entries.size + completing.size,
                skipped = records.size - fresh.size,
            ),
        )
    }

    /** Whether the form would accept this figure, by [BodyMeasurementValidator]'s own bounds. */
    private fun HealthConnectBodyRecord.isInBounds(): Boolean =
        when (reading.metric) {
            HealthConnectBodyMetric.WEIGHT ->
                reading.value in BodyMeasurementValidator.WEIGHT_MIN_MG..BodyMeasurementValidator.WEIGHT_MAX_MG

            HealthConnectBodyMetric.BODY_FAT ->
                reading.value in
                    BodyMeasurementValidator.BODY_FAT_MIN.toLong()..BodyMeasurementValidator.BODY_FAT_MAX.toLong()
        }

    /**
     * One instant's readings as one entry. At most one of each figure: the duplicate check has
     * already kept the first of any two.
     */
    private fun List<HealthConnectBodyRecord>.toEntry(
        measuredAt: Long,
        now: Long,
    ): BodyMeasurement =
        BodyMeasurement(
            weight = valueOf(HealthConnectBodyMetric.WEIGHT),
            bodyFat = valueOf(HealthConnectBodyMetric.BODY_FAT)?.toInt(),
            createdAt = measuredAt,
            updatedAt = now,
            fromHealthConnect = true,
        )

    /**
     * An entry an earlier import made, with the figures [readings] add to it. The duplicate check
     * has already dropped any figure the entry holds, so nothing it has is overwritten.
     */
    private fun BodyMeasurement.completedWith(
        readings: List<HealthConnectBodyRecord>,
        now: Long,
    ): BodyMeasurement =
        copy(
            weight = weight ?: readings.valueOf(HealthConnectBodyMetric.WEIGHT),
            bodyFat = bodyFat ?: readings.valueOf(HealthConnectBodyMetric.BODY_FAT)?.toInt(),
            updatedAt = now,
        )

    /** The first of these records' [metric] figures, or null when none holds one. */
    private fun List<HealthConnectBodyRecord>.valueOf(metric: HealthConnectBodyMetric): Long? =
        firstOrNull { it.reading.metric == metric }?.reading?.value

    private data class DuplicateKey(
        val measuredAt: Long,
        val metric: HealthConnectBodyMetric,
    )
}

/**
 * What one run of [ImportBodyFromHealthConnectUseCase] did.
 *
 * @property imported entries added to the history — one per instant, which can hold a weight and
 *   a body fat both — plus earlier imported entries given a figure they lacked.
 * @property skipped records read and not added: Titan's own, duplicates, or out of bounds.
 */
data class HealthConnectBodyImport(
    val imported: Int,
    val skipped: Int,
)
