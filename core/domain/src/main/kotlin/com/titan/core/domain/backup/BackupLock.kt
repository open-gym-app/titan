package com.titan.core.domain.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises the operations that take the whole of the user's data at once: an export, manual
 * or automatic, a restore, and "delete all data".
 *
 * ### Why they cannot overlap
 *
 * Each of them reads or replaces every table, and none of them is one statement. An export
 * pages through nineteen tables and then reads the settings; a restore wipes the tables,
 * inserts the file and then writes the settings; a delete-all clears the tables, replays the
 * seed and then clears the settings. Interleave any two and the result is a state neither of
 * them meant. An export that read the sessions before a restore and the sets after it is a file
 * whose sets point at sessions it does not contain, which the next restore refuses. One that
 * read the rows after a restore and the settings before it carries the restored history in the
 * old units. A delete-all landing between a restore's rows and its settings leaves an empty
 * device with somebody else's settings.
 *
 * The weekly backup is what makes this reachable without two screens at once: it runs from
 * WorkManager whenever the phone is idle and charging, which is also when somebody leaves a
 * restore running on a charger.
 *
 * ### A mutex rather than cancelling the backup
 *
 * Cancelling the scheduled work before a restore or a wipe would stop *that* run, and then the
 * next run would start on whatever the store looked like halfway through. A lock is what makes
 * "one of these at a time" true whatever started them. The wait is the price: a restore
 * started during a weekly backup sits on "Validating" until the backup has finished writing.
 *
 * ### What it does not cover
 *
 * Ordinary writes — a set logged, a template saved — do not take it. Those are kept out of an
 * export by the read transaction `BackupRepository.readConsistently` holds, and out of a
 * restore by the restore's own transaction. This lock is about the three operations whose
 * steps span more than one store and therefore more than one transaction.
 *
 * Like `SessionLifecycleLock`, it is process-wide and not durable: the app runs in one process,
 * and WorkManager runs its workers in that same process.
 *
 * ### Taken before `SessionLifecycleLock`, never after
 *
 * A restore and a delete-all hold both: this one for their whole run, the session lock from the
 * session reset to the end of the table replace. They take this one first. No session use case
 * takes this one at all, so the order is the only one in the app and the two cannot deadlock.
 */
class BackupLock {

    private val mutex = Mutex()

    /**
     * Runs [block] with no other export, restore or delete-all running, waiting for one that is.
     *
     * Not re-entrant: [block] must not start another of the three. None of them calls another.
     */
    suspend fun <T> exclusively(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {

        /**
         * The instance the use cases default to and the graph binds. One per process is the
         * point; a second instance would serialise nothing against the first.
         */
        val SHARED = BackupLock()
    }
}
