package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnectSyncScheduler
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException

/**
 * Sets — or clears — what a session is called.
 *
 * ### Why a use case for one column
 *
 * Because it is written from three screens, at three different points in a session's life: the
 * review panel before it starts, the header while it runs, and the recap afterwards, including
 * for a workout logged months ago. `StartSessionUseCase` covers the first by taking a name; this
 * covers the other two, and the rule they all apply — trim, and blank means *unnamed* — lives on
 * [WorkoutSession.normalizeName] so there is one copy of it rather than three.
 *
 * That rule is the whole reason this is not a `sessionRepository.update` at each call site.
 * `workout_session.name` is nullable and `null` is not `''`: null means genuinely unnamed, which
 * is what the date-derived fallback title keys off, and a screen persisting an untouched field
 * would title a session with nothing at all and make "unnamed" unrecoverable.
 *
 * ### It does not care whether the session is running
 *
 * Unlike every other use case here, there is no state check. Renaming is not a lifecycle
 * transition and it is as useful on a session logged last March as on the one in progress —
 * which is the case that makes the check impossible rather than merely unnecessary, since a
 * finished session is by definition not active.
 *
 * ### A finished session's new name goes to Health Connect
 *
 * The name is the title of the record the finish wrote, so renaming a finished session asks
 * [healthConnectSync] to write the record again. A session still running is left alone: its
 * record is written when it finishes, under whatever it is called then.
 *
 * @param healthConnectSync what queues the rewrite, or **null** to queue nothing. The app always
 *   passes one; null is for the tests of the rename itself.
 */
class RenameSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val healthConnectSync: HealthConnectSyncScheduler? = null,
) {

    /**
     * @param name what to call it, as typed. Blank or whitespace clears the name, which is the
     *   only way back to unnamed and is why this takes the raw string rather than a
     *   non-empty one.
     *
     * @return the name as stored, so a caller can show what it actually got — `null` when the
     *   session is now unnamed.
     *
     * `updatedAt` is deliberately left where it is, matching the recap's note save. The use
     * cases own the clock in this codebase, and more to the point a title is not a change to
     * *what was performed*, which is what a session's timestamps are read for.
     *
     * Fails with [com.titan.core.domain.model.SessionError.SessionNotFound] for an id that no
     * longer resolves — a recap left open on a session deleted from the history list.
     */
    suspend operator fun invoke(
        sessionId: Long,
        name: String?,
    ): Result<String?> {
        val session = sessionRepository.loadSession(sessionId)
            .getOrElse { return Result.failure(it) }

        val normalized = WorkoutSession.normalizeName(name)
        if (normalized == session.name) return Result.success(normalized)

        return runCatching {
            sessionRepository.update(session.copy(name = normalized))
            normalized
        }.onFailure { failure ->
            // A cancellation is not a failed rename: a caller whose scope has gone must not be
            // told the call came back.
            if (failure is CancellationException) throw failure
        }.onSuccess {
            // Best-effort: the rename is saved, and a queue that refused the request leaves an
            // old title in Health Connect rather than a rename that did not happen.
            if (session.completedAt != null) runCatching { healthConnectSync?.requestSync(sessionId) }
        }
    }
}
