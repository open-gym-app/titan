package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.model.WorkoutActivity
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.HealthConnectSessionRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.days

/**
 * Brings the last [WINDOW_DAYS] days of exercise sessions other apps wrote to Health Connect into
 * the local history.
 *
 * Each becomes a finished session with no exercises and no sets — see
 * [WorkoutSession.healthConnectId] for what that row is and what it does not earn.
 *
 * ### Thirty days
 *
 * Health Connect lets an app read, by default, only what was written in the thirty days before it
 * was first granted read access. A longer window would return the same records and suggest an
 * import that reaches further back than it can.
 *
 * ### What is skipped
 *
 * A record is not imported when it is:
 *
 * 1. **Titan's own.** [SyncToHealthConnectUseCase] wrote it from a session that is, or was, in
 *    the local history; importing it would put a hollow copy beside the real one.
 * 2. **Already imported.** Its id is on a row already — this is what makes a second import of
 *    the same month a no-op.
 * 3. **A duplicate by start time and type.** A local session, logged here or imported, started at
 *    the same instant and was the same [WorkoutActivity]. That is two apps recording one workout,
 *    and it is kept once. Records within one import are checked against each other the same way.
 *
 * Exact to the millisecond, deliberately. A looser match — the same minute — would merge two
 * short sessions somebody really did start back to back, and the cost of the opposite mistake is a
 * second row the user can delete.
 *
 * ### Refusals
 *
 * As [SyncToHealthConnectUseCase]: [HealthConnectError.Unavailable] and
 * [HealthConnectError.NotPermitted] in the [Result], and anything the provider or the database
 * throws there too, except a cancellation.
 */
class ImportFromHealthConnectUseCase(
    private val healthConnect: HealthConnect,
    private val sessions: HealthConnectSessionRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    suspend operator fun invoke(): Result<HealthConnectImport> {
        // `runCatching` for a provider's failure, with the one thing it gets wrong put right: a
        // cancellation is not a failure, and a caller whose scope has gone must not be told the
        // call came back. The availability check is inside it too — it asks the package
        // manager, which can throw, and the screen is promised a `Result`, not an exception.
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

    private suspend fun import(): Result<HealthConnectImport> {
        if (HealthConnectAccess.READ_EXERCISE !in healthConnect.grantedAccess()) {
            return Result.failure(HealthConnectError.NotPermitted(HealthConnectAccess.READ_EXERCISE))
        }

        val now = clock.nowMillis()
        val from = now - WINDOW_DAYS.days.inWholeMilliseconds
        // `now + 1` so a record starting this very millisecond is in the half-open range. The
        // local side uses the same bounds, so the two lists cover the same span.
        val records = healthConnect.readSessions(startedFrom = from, startedUntil = now + 1)
        val local = sessions.getFinishedStartedBetween(startedFrom = from, startedUntil = now + 1)

        val importedIds = local.mapNotNullTo(HashSet()) { it.session.healthConnectId }
        val seen = local.mapTo(HashSet()) { DuplicateKey(it.session.createdAt, it.activity) }

        val fresh = records
            .sortedBy { it.session.startedAt }
            .filter { record ->
                !record.writtenByThisApp &&
                    record.id !in importedIds &&
                    seen.add(DuplicateKey(record.session.startedAt, record.session.activity))
            }

        val inserted = if (fresh.isEmpty()) 0 else sessions.insertImported(fresh.map { it.toSession(now) })
        return Result.success(HealthConnectImport(imported = inserted, skipped = records.size - inserted))
    }

    private fun HealthConnectRecord.toSession(now: Long): WorkoutSession =
        WorkoutSession(
            // Normalised as every other name is: Health Connect allows a blank title, and a blank
            // name here would stop the history drawing the date-derived one.
            name = WorkoutSession.normalizeName(session.title),
            notes = session.notes?.takeIf(String::isNotBlank),
            createdAt = session.startedAt,
            completedAt = session.endedAt,
            lastActiveAt = session.endedAt,
            isInProgress = false,
            updatedAt = now,
            healthConnectId = id,
            healthConnectActivity = session.activity,
        )

    private data class DuplicateKey(
        val startedAt: Long,
        val activity: WorkoutActivity,
    )

    companion object {
        /** How far back an import reaches. See the class documentation for why this far. */
        const val WINDOW_DAYS = 30
    }
}

/**
 * What one run of [ImportFromHealthConnectUseCase] did.
 *
 * @property imported sessions added to the history.
 * @property skipped records read and not added: Titan's own, already imported, or duplicates.
 */
data class HealthConnectImport(
    val imported: Int,
    val skipped: Int,
)
