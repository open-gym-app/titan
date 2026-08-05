package com.opengym.core.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes session lifecycle transitions against each other.
 *
 * Every transition is a read-modify-write over the same two repositories — read the
 * state, decide whether it is legal, write the row and the new state — and none of
 * that is atomic on its own. Two concurrent starts (a double-tapped button is the
 * everyday case) can both observe nothing running and both insert, leaving two
 * sessions in progress and defeating the very invariant [StartSessionUseCase]
 * exists to enforce. Finish has the same shape and would close a session out twice.
 *
 * All four use cases take the lock for their whole body, so transitions queue rather
 * than interleave. None of them calls another, so there is nothing to deadlock on.
 *
 * ### What this does not cover
 *
 * A mutex is process-wide. It is sufficient here because the app runs in one process
 * and this is the only writer, but it is not a durable constraint: the airtight
 * version is a partial unique index on `workout_session(is_in_progress)` so SQLite
 * itself refuses the second row. That belongs with the persistence contract in
 * M3-07, and this lock is what holds the invariant until then.
 */
class SessionLifecycleLock {

    private val mutex = Mutex()

    suspend fun <T> withTransition(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        /**
         * The lock the use cases default to. A single instance is the point — one
         * session lifecycle per app process — so a caller wiring these up through DI
         * should bind this, or bind [SessionLifecycleLock] as a `@Singleton`. Giving
         * each use case its own instance would serialize nothing.
         */
        val SHARED = SessionLifecycleLock()
    }
}
