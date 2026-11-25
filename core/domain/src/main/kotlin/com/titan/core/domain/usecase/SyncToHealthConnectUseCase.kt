package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.repository.HealthConnectSessionRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Writes one finished session to Health Connect as an `ExerciseSessionRecord`.
 *
 * Run from a queued background job (see [com.titan.core.domain.healthconnect.HealthConnectSyncScheduler]),
 * which is asked for by every place a session's record goes out of date: [FinishSessionUseCase]
 * when a workout is closed out, whichever way it was closed — the workout screen's END or the
 * idle nudge's "Finish now" — and [RenameSessionUseCase] and the recap's note when what the
 * record says changes afterwards. It can be called again for the same session at any time: the
 * record is keyed by the session, so a second write replaces the first rather than adding a
 * copy.
 *
 * ### The version is the time of the write
 *
 * Health Connect keeps the write with the higher version, so the version has to grow with every
 * write that should win — and every write here should, since each one reads the row as it is
 * now. The row's `updated_at` does not grow with a rename or a note: both leave it alone on
 * purpose, as a change to how the workout is described rather than to what was performed (see
 * [RenameSessionUseCase]). The clock does. It is held to at least `updated_at`, so the first
 * write is never below what the row itself says. What it cannot cover is a clock set back
 * between two writes: Health Connect ignores the second, and the record stays stale until an
 * edit after the clock has caught up — the same trade every timestamp in this app makes.
 *
 * ### What is written
 *
 * The session's span, its name as the record's title, its notes, and what kind of workout it was
 * ([com.titan.core.domain.model.WorkoutActivity.of], from the disciplines of the exercises with
 * a completed set). Not the sets: an `ExerciseSessionRecord` has segments and laps, but no slot
 * for a weight or a rep count, and a segment per exercise would be a timeline Titan never
 * recorded — sets are logged when they are done, not timed from a start.
 *
 * ### What is not written
 *
 * - A session imported *from* Health Connect. It is already there, under the id of the app that
 *   recorded it, and writing it back would put the same workout in twice.
 * - A session still in progress, or one that does not exist. Neither is a failure: there is
 *   nothing to write yet, or any more.
 *
 * ### Refusals
 *
 * [HealthConnectError.Unavailable] and [HealthConnectError.NotPermitted] come back in the
 * [Result] rather than as exceptions — both are the ordinary state of an app whose user never
 * connected it, and the finish path drops them without a word. Anything the provider throws comes
 * back in the [Result] too, except a cancellation, which is not a failure and is rethrown.
 */
class SyncToHealthConnectUseCase(
    private val healthConnect: HealthConnect,
    private val sessions: HealthConnectSessionRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    suspend operator fun invoke(sessionId: Long): Result<HealthConnectSyncOutcome> {
        // `runCatching` for a provider's failure, with the one thing it gets wrong put right: a
        // cancellation is not a failure, and a caller whose scope has gone must not be told the
        // call came back. The availability check is inside it too — it asks the package
        // manager, which can throw, and a throw from here would escape a finish that has
        // already been saved.
        return runCatching {
            val availability = healthConnect.availability()
            if (availability != HealthConnectAvailability.AVAILABLE) {
                Result.failure(HealthConnectError.Unavailable(availability))
            } else {
                write(sessionId)
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            Result.failure(failure)
        }
    }

    private suspend fun write(sessionId: Long): Result<HealthConnectSyncOutcome> {
        if (HealthConnectAccess.WRITE_EXERCISE !in healthConnect.grantedAccess()) {
            return Result.failure(HealthConnectError.NotPermitted(HealthConnectAccess.WRITE_EXERCISE))
        }

        val finished = sessions.getFinished(sessionId)
            ?: return Result.success(HealthConnectSyncOutcome.NOT_FINISHED)
        val session = finished.session
        if (session.isImported) return Result.success(HealthConnectSyncOutcome.IMPORTED)

        // Non-null for a finished row — `getFinished` reads only those.
        val completedAt = checkNotNull(session.completedAt) { "finished session $sessionId has no completed_at" }
        healthConnect.writeSession(
            sessionId = session.id,
            version = clock.nowMillis().coerceAtLeast(session.updatedAt),
            session = HealthConnectSession(
                startedAt = session.createdAt,
                // At least a second long. Health Connect refuses a session that ends where it
                // starts, and a backwards clock adjustment mid-workout can leave a finish stamped
                // before its start — `FinishSessionUseCase` clamps the duration it reports for
                // the same reason. The workout happened; a second-long record of it is closer to
                // the truth than no record at all.
                endedAt = completedAt.coerceAtLeast(session.createdAt + MIN_DURATION_MILLIS),
                activity = finished.activity,
                title = session.name,
                notes = session.notes,
            ),
        )
        return Result.success(HealthConnectSyncOutcome.WRITTEN)
    }

    private companion object {
        const val MIN_DURATION_MILLIS = 1_000L
    }
}

/** What [SyncToHealthConnectUseCase] did with a session it was allowed to look at. */
enum class HealthConnectSyncOutcome {

    /** Written, or rewritten over an earlier write of the same session. */
    WRITTEN,

    /** Still in progress, or gone. Nothing to write. */
    NOT_FINISHED,

    /** Came from Health Connect in the first place. Nothing to write back. */
    IMPORTED,
}
