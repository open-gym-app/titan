package com.opengym.core.domain.backup

/**
 * The weekly automatic backup, as the screen and the worker both see it.
 *
 * Three facts and nothing else: whether the user asked for it, when it last produced a file,
 * and whether the most recent attempt gave up. That is exactly what the wireframe's
 * "Weekly auto-backup · Sundays · last: APR 26 ✓" row states, and it is all a background job
 * the user cannot watch is able to tell them.
 *
 * ### Why the two timestamps do not collapse into one
 *
 * A failure must not erase when the last good backup was taken. "The last run failed" and
 * "there is a backup from three Sundays ago" are different pieces of news and a user needs
 * both — the first says something is wrong, the second says how much it costs. So a failure
 * records [lastFailureAt] and leaves [lastSuccessAt] alone, and a later success clears
 * [lastFailureAt] because a run that worked supersedes the one that did not. That rule lives
 * in [com.opengym.core.domain.repository.AutoBackupRepository], which is what makes
 * [hasFailed] a plain null check here rather than a comparison of two instants.
 *
 * ### It is not a user preference
 *
 * It has its own store rather than a place in `UserPreferences`, and therefore does not travel
 * in an export. The reason is that half of it is not a *setting* at all: a timestamp from
 * another device's schedule says nothing true about this one. [enabled] alone could have
 * travelled, and restoring it would have been a lie of a different shape — an import writes
 * settings, it does not enqueue work, so the toggle would have come back on with nothing
 * scheduled behind it. See docs/PERSISTENCE.md.
 *
 * @property enabled whether the periodic work is meant to be scheduled. Off by default:
 *   writing files on a schedule is not something to opt somebody into silently.
 * @property lastSuccessAt when a run last wrote a backup, epoch millis UTC, or **null** while
 *   none ever has.
 * @property lastFailureAt when a run last gave up — after WorkManager's retries, not after the
 *   first stumble — or **null** if the most recent thing to happen was a success.
 */
data class AutoBackupSchedule(
    val enabled: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
) {

    /** Whether the most recent run gave up for good. See the note on the two timestamps. */
    val hasFailed: Boolean get() = lastFailureAt != null

    /** Whether a run has ever produced a file, which is what makes [lastSuccessAt] worth showing. */
    val hasRun: Boolean get() = lastSuccessAt != null
}
