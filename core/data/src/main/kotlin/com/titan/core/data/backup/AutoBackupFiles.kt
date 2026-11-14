package com.titan.core.data.backup

import com.titan.core.domain.backup.BackupDirectory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where an automatic backup goes, what it is called, and which of them are kept.
 *
 * ### The same directory the manual export writes to
 *
 * `backups/` in app-specific storage, resolved by the [BackupDirectory] the backup screen's
 * export also asks: internal storage below Android 10, the external files directory from it
 * (see [AppBackupDirectory] for why, and for the one open question on Android 10). Either way
 * it needs no permission, it is kept out of other apps' reach, and it is **deleted on
 * uninstall**, and emptied by "delete all data" (`LocalDataRepositoryImpl`, through
 * [everyDirectory]). That is the fact the backup screen has to state next to the toggle: a
 * user who believes a weekly automatic backup survives a reinstall and finds out otherwise has
 * been actively misled. The manual export (#38) is what exists for that, and it is why "Save a
 * copy…" is the first button on the card it produces.
 *
 * The two writers share the directory and name different files inside it — see [PREFIX].
 *
 * ### Automatic and manual backups are told apart by name
 *
 * Manual exports are `titan-backup-<yyyy-MM-dd-HHmm>.json`; these are
 * `titan-auto-<yyyy-MM-dd>.json`. Retention only ever looks at the second set, so a manual
 * export the user took deliberately is never deleted to make room for a scheduled one — which
 * is the whole reason the prefixes differ rather than the two sharing one.
 *
 * To the day rather than to the minute, unlike the manual export, and the coarser stamp is
 * load-bearing: a run that is retried after a failure, or a schedule that fires twice in a day
 * because the constraints finally cleared, **overwrites the day's file** instead of leaving two
 * backups of the same data behind and pushing a genuinely older one out of the retention
 * window.
 */
@Singleton
class AutoBackupFiles @Inject constructor(private val backupDirectory: BackupDirectory) {

    /**
     * `backups/`, wherever [BackupDirectory] puts it on this Android version.
     *
     * Not created here — [scratchFor] does that, at the moment something is about to be
     * written, so a device that never turns the toggle on grows no empty directory. The first
     * read in a process may move an older install's backups into place, so it belongs on a
     * background thread, which is where every caller already is.
     */
    val directory: File get() = backupDirectory.resolve()

    /**
     * Every `backups/` a backup can have been written to, for "delete all data", which has to
     * find every copy. See [BackupDirectory.everyDirectory] for why [directory] alone is not it.
     */
    val everyDirectory: List<File> get() = backupDirectory.everyDirectory()

    /** The file a run started at [at] writes, named for that day in the device's own zone. */
    fun fileFor(at: Long): File {
        val stamp = FILE_NAME_FORMAT.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        return File(directory, "$PREFIX$stamp$SUFFIX")
    }

    /**
     * The scratch sibling [destination] is written to before it is moved into place, with the
     * directory created if it is not there yet.
     *
     * The same write-then-rename the manual export uses, for the same reason: a failed export
     * leaves a truncated document by construction — the sink has already been handed everything
     * written so far — and a half-backup somebody could restore from is worse than no backup.
     * The `.tmp` name also keeps it out of [existing], so a scratch file left by a killed run is
     * never counted as one of the three kept; [prune] is what deletes it.
     */
    fun scratchFor(destination: File): File {
        directory.mkdirs()
        return File(destination.path + SCRATCH_SUFFIX)
    }

    /**
     * The finished automatic backups, newest first.
     *
     * Ordered by name, which is ordering by date: the stamp is `yyyy-MM-dd`, so lexicographic
     * and chronological are the same order, and there is exactly one file per day. Sorting by
     * modification time would instead order them by when the *filesystem* last touched them,
     * which a restore of the device's files, a copy or a clock change can each reorder.
     */
    fun existing(): List<File> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            .sortedByDescending { it.name }

    /**
     * Deletes every automatic backup but the [keep] most recent, and every scratch file a run
     * left behind, and reports how many went.
     *
     * Unbounded retention on a file that grows with the user's history is a disk leak on
     * storage they cannot browse to clear — and three weeks is long enough to notice a problem
     * and act on it, which is the only thing older copies buy. Deleting is best-effort: a file
     * that refuses to go is left where it is rather than failing a run that has already written
     * a good backup.
     *
     * The scratch files are this feature's own `titan-auto-*.json.tmp` and nothing else. A run
     * that was killed between writing one and renaming it — the OS reclaiming the process, a
     * battery that died — leaves a partial backup the size of the history behind, and no later
     * run reuses it unless it happens to fall on the same day. The caller has finished its own
     * write before it prunes, and WorkManager never runs two of the same unique work at once,
     * so every one of them is stale. The manual export's scratch file is not touched: it may
     * be one an export on the screen is writing right now.
     *
     * @param keep how many of the newest to leave behind. Zero empties the directory of
     *   automatic backups; negative is not a thing a caller means and is treated as zero.
     */
    fun prune(keep: Int): Int {
        val stale = existing().drop(keep.coerceAtLeast(0)) + scratchFiles()
        return stale.count { it.delete() }
    }

    /** The automatic backups' scratch files, finished with or abandoned. */
    private fun scratchFiles(): List<File> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX + SCRATCH_SUFFIX) }

    private companion object {

        /** What makes a file *this* feature's. Retention never looks past it. */
        private const val PREFIX = "titan-auto-"
        private const val SUFFIX = ".json"

        /**
         * Appended to the finished name, so a scratch file is `titan-auto-<date>.json.tmp`: not a
         * `.json`, so nothing offers it and no picker filtered to JSON shows it.
         */
        private const val SCRATCH_SUFFIX = ".tmp"

        private val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
