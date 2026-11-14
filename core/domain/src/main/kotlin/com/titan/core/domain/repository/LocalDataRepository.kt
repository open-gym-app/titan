package com.titan.core.domain.repository

import com.titan.core.domain.model.DataFootprint
import kotlinx.coroutines.flow.Flow

/**
 * Everything the app has stored on this device, taken as a whole rather than a table at a
 * time.
 *
 * The other repositories each own one kind of record and none of them owns *all of them*,
 * which is precisely what the two operations here need. Settings' "delete all data" is not
 * six deletes a caller is trusted to remember — the one it forgets is the one that
 * resurrects a session list the user was told had been erased — and the footprint behind
 * its confirmation dialog is one read across the same set.
 *
 * ### Why this is a repository and not a use case
 *
 * There is no rule to apply. A use case would exist to sequence calls to six repository
 * interfaces the domain can see, and that sequencing is exactly the part that has to be
 * atomic and storage-aware: which order the tables go in under foreign keys, what a
 * half-finished wipe leaves behind, whether the catalogue is restored by re-seeding or by
 * never having been deleted. All of that is knowledge the data layer has and the domain
 * does not, so the contract states the outcome and lets `:core:data` own the mechanics.
 */
interface LocalDataRepository {

    /**
     * How much of the user's own work is stored, re-emitted as it changes.
     *
     * Emits the current counts on subscribe and never fails — it is read to *render* a
     * dialog, and a confirmation that cannot draw itself is worse than one with unhelpful
     * numbers in it. A store that cannot be read reports [DataFootprint] zeroes, which the
     * dialog renders as "nothing to lose"; the delete action stays available regardless,
     * so a wrong zero costs an accurate sentence and nothing else.
     *
     * Not failing is not the same as giving up: a store that becomes readable again is
     * re-read and the real counts published, so a single transient failure costs one
     * inaccurate rendering rather than every subsequent one.
     */
    fun observeFootprint(): Flow<DataFootprint>

    /**
     * Erases every trace of the user from this device and leaves the app as a fresh
     * install: no sessions, no sets, no templates, no personal records, no custom
     * exercises, no body weight log, no in-progress workout, no backup file in the app's own
     * storage, no rest or idle-session notification or alarm, and every setting back at its
     * default — including [com.titan.core.domain.model.UserPreferences.onboardingCompleted],
     * so the next launch asks the first-run questions again.
     *
     * The weekly backup's toggle is not a setting in that sense and stays as the user left it;
     * only the record of its past runs goes, with the files it describes.
     *
     * **The seeded exercise catalogue survives.** It is app content rather than the user's,
     * and an app whose exercise library is empty is broken rather than clean. Implementations
     * that clear it must put it back before returning.
     *
     * There is no confirmation here and no undo anywhere: by the time this is called the
     * user has been told what is lost and has said the word. Asking twice is the caller's
     * job — see #M4-23 — and this method's job is to leave nothing behind.
     *
     * It waits for an export, a weekly backup or a restore that is running to finish before it
     * touches anything, and session transitions wait for it in turn — see
     * [com.titan.core.domain.backup.BackupLock]. A call cancelled while it waits has deleted
     * nothing.
     *
     * A wipe that fails throws, and the caller must surface it: silently leaving history on
     * a device the user has asked to clear is the one outcome this operation cannot have.
     * The exception is whatever the failing store raises — an [java.io.IOException] from the
     * settings file, a database exception from the rows — so a caller catches broadly rather
     * than by type, and treats the result as indeterminate: some of the four stores may
     * already be empty. Implementations order the work so that what survives a failure is
     * the least alarming thing to survive it.
     */
    suspend fun deleteAll()
}
