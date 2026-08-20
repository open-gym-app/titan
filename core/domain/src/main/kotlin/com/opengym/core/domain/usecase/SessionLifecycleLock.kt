package com.opengym.core.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes the writes that read a session's shape before changing it.
 *
 * Every lifecycle transition is a read-modify-write over the same two repositories — read
 * the state, decide whether it is legal, write the row and the new state — and none of
 * that is atomic on its own. Two concurrent starts (a double-tapped button is the
 * everyday case) can both observe nothing running and both insert, leaving two
 * sessions in progress and defeating the very invariant [StartSessionUseCase]
 * exists to enforce. Finish has the same shape and would close a session out twice.
 *
 * ### The layout writes take it too
 *
 * Removing an exercise and linking or breaking a superset have the same shape over
 * `session_exercise`: read every exercise of the session, compute a grouping from what
 * was read, write back the rows that moved. Two of those interleaved settle on the
 * *older* snapshot — a removal that renormalises a grouping it read before a link
 * writes `superset_group = null` over the superset the user has just made, and says
 * nothing about it. Holding this across the read and the write is what makes each one
 * see the layout the one before it left.
 *
 * The name still says lifecycle because that is what the invariant is about: there is
 * one running session, and this is the mutex over everything that reads it in order to
 * change it.
 *
 * Every one of those use cases takes the lock for its whole body, so the writes queue
 * rather than interleave. None of them calls another, so there is nothing to deadlock on.
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
