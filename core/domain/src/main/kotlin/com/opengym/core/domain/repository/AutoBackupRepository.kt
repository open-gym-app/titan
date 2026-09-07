package com.opengym.core.domain.repository

import com.opengym.core.domain.backup.AutoBackupSchedule
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
 * - **Never empty, never failing.** Every field of [AutoBackupSchedule] has a default and the
 *   flow falls back to it — a store that cannot be read is an off toggle, which is the safe
 *   answer for a thing that writes files.
 * - **Writes throw.** [setEnabled] in particular: a toggle that moved and did not save is a
 *   user who thinks their data is being copied every week.
 */
interface AutoBackupRepository {

    /** The schedule as it stands, re-emitted on every change. */
    fun observe(): Flow<AutoBackupSchedule>

    /**
     * Records whether the user wants weekly backups.
     *
     * This only persists the flag. Enqueueing and cancelling the work is
     * [com.opengym.core.domain.backup.AutoBackupScheduler]'s, and doing both in the right order
     * is [com.opengym.core.domain.usecase.SetAutoBackupEnabledUseCase]'s — nothing should call
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
}
