package com.opengym.core.domain.usecase

import com.opengym.core.domain.backup.AutoBackupScheduler
import com.opengym.core.domain.repository.AutoBackupRepository
import java.io.IOException

/**
 * Turns the weekly automatic backup on or off: the stored flag and the scheduled work, together.
 *
 * A use case rather than two calls from the ViewModel, because the two halves are one fact and
 * they can disagree. Work that is enqueued while the flag says off writes files nobody asked
 * for; a flag that says on with nothing enqueued is a screen promising a backup that will never
 * happen. Neither is reachable through a screen that has to remember to make both calls in
 * order — it is reachable through the *second* screen that does, or the first one somebody
 * edits.
 *
 * ### The store is written first, and that order is the failure it chooses
 *
 * [AutoBackupRepository.setEnabled] throws when the store cannot be written, and the throw
 * propagates before anything is scheduled — so a device that cannot persist the toggle also
 * does not quietly start writing backups, and the screen, which draws the switch from the
 * stored flag, shows it snapping back. The other order buys nothing and loses that: work
 * enqueued against a flag that then failed to save would run once a week with the toggle
 * reading off, and nothing on the screen would offer a way to stop it.
 *
 * @property autoBackupRepository holds the flag.
 * @property scheduler enqueues and cancels the periodic work — see [AutoBackupScheduler] for
 *   why the platform half arrives as a port.
 */
class SetAutoBackupEnabledUseCase(
    private val autoBackupRepository: AutoBackupRepository,
    private val scheduler: AutoBackupScheduler,
) {

    /**
     * Persists [enabled], then brings the schedule into line with it.
     *
     * Safe to call with the value the store already holds: [AutoBackupScheduler.schedule] keeps
     * a running schedule as it is rather than restarting its week, and cancelling nothing is
     * not an error.
     *
     * @throws IOException if the flag cannot be stored — in which case the schedule is left
     *   exactly as it was.
     */
    suspend operator fun invoke(enabled: Boolean) {
        autoBackupRepository.setEnabled(enabled)
        if (enabled) scheduler.schedule() else scheduler.cancel()
    }
}
