package com.titan.core.domain.repository

import com.titan.core.domain.backup.AutoBackupSchedule
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * The weekly auto-backup's own durable state: the toggle, and how the last run went.
 *
 * Small enough to be a corner of [UserPreferencesRepository] and deliberately not one — see
 * [AutoBackupSchedule] for why it is not a user *setting*. It carries the same emission
 * contract as that interface, and for the same reason: the backup screen reads it on the way to
 * its first frame, and a worker running with no screen alive writes it.
 *
 * - **Current value on subscribe**, so `observe().first()` returns on a fresh install rather
 *   than suspending until something writes.
 * - **Never empty, never failing.** Every field of [AutoBackupSchedule] has a default and
 *   [observe] falls back to it — a store that cannot be read draws the toggle off, which is the
 *   safe answer for a screen that has to put something under the heading.
 * - **Except for [read].** The same value, fetched once, allowed to throw. "Off" and "I could
 *   not tell" are the same frame to a screen and opposite instructions to the worker, so the
 *   caller that acts on the flag gets a read that distinguishes them.
 * - **Writes throw.** [setEnabled] in particular: a toggle that moved and did not save is a
 *   user who thinks their data is being copied every week.
 */
interface AutoBackupRepository {

    /** The schedule as it stands, re-emitted on every change. Never fails; see [read]. */
    fun observe(): Flow<AutoBackupSchedule>

    /**
     * The schedule as it stands, read once — failing rather than falling back.
     *
     * For the caller that *acts* on the flag instead of drawing it, which today is the weekly
     * worker. To it, "the user turned it off" and "the store could not be read" are the
     * difference between a week correctly skipped and a week silently lost: taking [observe]'s
     * fallback would have it report success, write no file, ask for no retry, record no failure,
     * and leave the backup screen showing the previous week's date as though this one had gone
     * fine. Throwing puts that run into the same retry-then-report path as a failed write.
     *
     * A screen must not call this. [observe] is what draws the toggle, and a read that throws on
     * the way to a first frame is exactly what the contract above exists to prevent.
     *
     * @throws IOException if the store cannot be read.
     */
    suspend fun read(): AutoBackupSchedule

    /**
     * Records whether the user wants weekly backups.
     *
     * This only persists the flag. Enqueueing and cancelling the work is
     * [com.titan.core.domain.backup.AutoBackupScheduler]'s, and doing both in the right order
     * is [com.titan.core.domain.usecase.SetAutoBackupEnabledUseCase]'s — nothing should call
     * this on its own.
     *
     * @throws IOException if the store cannot be written.
     */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Records that a run wrote a backup at [at], and clears any earlier failure.
     *
     * Clearing is the point: a run that worked supersedes the one that did not, and a row that
     * kept saying "the last backup failed" under a fresher timestamp would be telling the user
     * about a problem that has since fixed itself.
     *
     * @throws IOException if the store cannot be written.
     */
    suspend fun recordSuccess(at: Long)

    /**
     * Records that a run gave up at [at], leaving the last success where it is.
     *
     * Called after the retries are exhausted, not after the first stumble — a transient failure
     * that the next attempt fixes is not news, and surfacing it would train the user to ignore
     * the row that matters.
     *
     * @throws IOException if the store cannot be written.
     */
    suspend fun recordFailure(at: Long)

    /**
     * Forgets when a run last wrote a backup and when one last gave up, leaving the toggle as it
     * is.
     *
     * For "delete all data", which deletes the files those dates describe. A row still reading
     * "Last backup 26 Apr" over an empty directory tells somebody who has just erased everything
     * that there is a backup to come back to. The toggle is the user's own choice and is not this
     * method's to change.
     *
     * @throws IOException if the store cannot be written.
     */
    suspend fun forgetRuns()
}
