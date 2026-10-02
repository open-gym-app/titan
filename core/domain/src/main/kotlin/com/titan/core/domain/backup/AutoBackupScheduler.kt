package com.titan.core.domain.backup

/**
 * Whatever the platform uses to run the weekly backup, as the domain sees it: two verbs.
 *
 * ### Why this is a port rather than a call to WorkManager
 *
 * `:core:domain` is a plain Kotlin library with no Android on its classpath, and the toggle
 * that turns the schedule on has to do two things at once — persist the flag and enqueue the
 * work — which is a rule, not a screen concern. So the rule lives in
 * [com.titan.core.domain.usecase.SetAutoBackupEnabledUseCase] and the platform half arrives
 * through here. `:core:data` implements it over `WorkManager`; a test implements it as a pair
 * of counters.
 *
 * ### Deferrable, and that is the whole design
 *
 * The implementation enqueues *periodic* work with constraints, not an alarm. A backup does not
 * care whether it runs at 03:00 or at 09:00 on Sunday — it cares that it eventually runs, that
 * it survives a reboot and an app update without anything re-registering it, and that it does
 * not spin the disk while the user is mid-workout on 6% battery. The rest timer's alarm (#39)
 * is the opposite call for the opposite reason, and the two are deliberately different.
 */
interface AutoBackupScheduler {

    /**
     * Makes sure the weekly work is enqueued, and leaves an already-running schedule alone.
     *
     * Idempotent by contract: calling it on every toggle-on, and on a device where the work is
     * already pending, must not reset the period. A schedule that restarted its week every time
     * something touched it would be a backup that never ran on a phone opened often enough.
     */
    fun schedule()

    /** Cancels the weekly work. Idempotent: cancelling what is not scheduled is not an error. */
    fun cancel()
}
