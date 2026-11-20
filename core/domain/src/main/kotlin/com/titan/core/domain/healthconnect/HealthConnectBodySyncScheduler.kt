package com.titan.core.domain.healthconnect

/**
 * Asks for one body measurement's Health Connect records to be brought up to date, later and
 * somewhere else — the body screen's counterpart of [HealthConnectSyncScheduler], for that
 * interface's reasons: a save should not wait on another app's provider, and the write has to
 * outlive the screen that asked for it.
 *
 * Asked for whenever an entry is logged, edited, deleted or put back. What it ends in is
 * [com.titan.core.domain.usecase.SyncBodyToHealthConnectUseCase]'s decision when the work runs:
 * a write of each figure the entry holds, a removal of each it no longer does, or nothing at all
 * where nothing is granted.
 */
fun interface HealthConnectBodySyncScheduler {

    /**
     * Brings the records for [measurementId] in line with the row as it stands when the work runs.
     *
     * [measuredAt] is the entry's `created_at`, which is half of the records' key: it is what lets
     * the work remove the records of an entry that has since been deleted, when there is no row
     * left to read it from. A second request for the same entry before the first has run
     * replaces it, so a delete and its undo end in one pass over the entry as it stands.
     */
    fun requestSync(
        measurementId: Long,
        measuredAt: Long,
    )
}
