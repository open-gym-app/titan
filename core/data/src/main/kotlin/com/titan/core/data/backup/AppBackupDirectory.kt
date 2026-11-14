package com.titan.core.data.backup

import android.content.Context
import android.os.Build
import com.titan.core.domain.backup.BackupDirectory
import com.titan.core.domain.backup.ExportFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `backups/` under app-private internal storage below Android 10, and under the app's external
 * files directory from Android 10 on (#259).
 *
 * ### Why the Android version decides
 *
 * The files are the whole training history and the body log as plain JSON, and a restore source
 * the app trusts. On Android 8 and 9 the app's external files directory
 * (`Android/data/<package>/files`) is readable and writable by any app holding
 * `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`, which gallery, file-manager and messaging
 * apps commonly do. From Android 10 the platform scopes that directory to its owner (see
 * [FIRST_SCOPED_SDK] for the one open question), and it has the advantages that made it the
 * choice in the first place: it is listed under the app in the system's storage screen, and a
 * computer over USB can still reach it. Below Android 10 those advantages cost the sandbox, so
 * the files stay in `filesDir`, which no other app can read on any version. They leave the
 * phone through the backup screen's "Save a copy…" and "Share"; `file_paths.xml` serves the
 * share from both roots.
 *
 * From Android 10, a device with no external volume mounted has a null `getExternalFilesDir`,
 * and the directory falls back to `filesDir` there too.
 *
 * ### Backups written under the other root are moved here
 *
 * A build older than this rule left its backups in shared storage on Android 8 and 9, and a
 * phone upgraded to Android 10 has its backups in `filesDir`. Either way they are in a directory
 * that no export, share or retention pass looks in any more; only "Delete all data", which
 * empties both roots through [everyDirectory], still finds them. So the first [resolve] in a
 * process moves every finished backup (`titan-*.json`, manual and
 * automatic alike) from the other root's `backups/` into this one, deletes the scratch files
 * (`titan-*.json.tmp`) an interrupted export left there, and removes the old directory if that
 * leaves it empty. Anything else in it is not the app's and is left alone, and so is a backup
 * larger than [ExportFormat.MAX_DOCUMENT_BYTES]: no restore would read it, and on Android 8 and 9
 * the old directory is one other apps can write to, so a file that size is as likely planted as
 * the app's, and copying it would hold up the first export for nothing. A forged backup under
 * the limit does come across; it is no more trusted here than it was where the older build
 * offered it, and a restore decodes and validates it like any other document.
 *
 * **On first use rather than at startup**, because that is the one hook that costs nothing to
 * anybody who never touches a backup, needs no code in `:app`, and still runs before anything
 * can look at the directory: every reader and writer — the export, the weekly backup, its
 * retention, "Delete all data" and the share — asks this class first. A startup hook would
 * instead put a directory listing, and on an old phone tens of megabytes of copying, on every
 * cold start's path. Every caller but the share reaches here on a background thread; the share
 * only offers a file an export wrote earlier in the same process, so by then the move is done.
 *
 * **Not when [everyDirectory] comes first.** Its callers look in both roots themselves, so a
 * move would only shuffle files they reach anyway, and for "Delete all data" — the caller that
 * can come first, on a phone upgraded and wiped before any export — it would copy tens of
 * megabytes only to delete them a moment later. So [everyDirectory] settles the process without
 * moving anything. It takes the same lock as the move: one another thread has started finishes
 * before the directories are listed, and none can start after, so no copy lands behind a wipe.
 * If the wipe then fails, what it left under the old root waits for the next process.
 *
 * **Once per process, and idempotent across processes.** A file is moved only when its source is
 * still there, a name already present here is kept (it is the copy the app has been using since
 * the move, and the other holds the same day's or the same minute's data) and the source is
 * deleted, and a file that cannot be moved is left where it was for the next process to retry.
 * A failure never fails [resolve]: an export that cannot tidy up an old directory can still
 * write a good backup.
 *
 * A move between the two roots crosses filesystems on most devices, where `renameTo` fails, so
 * the fallback copies into a `.tmp` sibling, renames that into place and only then deletes the
 * source. A copy cut short leaves the source intact and no half-file under a finished name.
 */
@Singleton
class AppBackupDirectory @Inject constructor(@param:ApplicationContext private val context: Context) :
    BackupDirectory {

    private val lock = Any()

    @Volatile
    private var settled = false

    override fun resolve(): File {
        val directory = File(root(), BackupDirectory.NAME)
        if (!settled) settle(directory)
        return directory
    }

    /** Settles the process without moving anything; see the class doc for why. */
    override fun everyDirectory(): List<File> {
        synchronized(lock) { settled = true }
        val current = File(root(), BackupDirectory.NAME)
        return (listOf(current) + roots().map { File(it, BackupDirectory.NAME) }).distinct()
    }

    private fun roots(): List<File> = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)

    /** `filesDir` below Android 10; from it, the external files directory when there is one. */
    private fun root(): File =
        if (Build.VERSION.SDK_INT >= FIRST_SCOPED_SDK) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            context.filesDir
        }

    /** Moves into [directory] whatever is in the other root's `backups/`, once per process. */
    private fun settle(directory: File) {
        synchronized(lock) {
            if (settled) return
            roots()
                .map { File(it, BackupDirectory.NAME) }
                .filter { it != directory }
                .forEach { moveBackups(from = it, into = directory) }
            settled = true
        }
    }

    private fun moveBackups(
        from: File,
        into: File,
    ) {
        val files = from.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(PREFIX) }
        for (file in files) {
            when {
                file.name.endsWith(SUFFIX) -> move(file, File(into, file.name))
                file.name.endsWith(SUFFIX + SCRATCH_SUFFIX) -> file.delete()
            }
        }
        // Succeeds only on an empty directory, which is the point: a file that would not move,
        // or one that is not the app's, keeps it.
        from.delete()
    }

    private fun move(
        source: File,
        target: File,
    ) {
        if (target.exists()) {
            source.delete()
            return
        }
        if (source.length() > ExportFormat.MAX_DOCUMENT_BYTES) return
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        val scratch = File(target.path + SCRATCH_SUFFIX)
        // Broad, as the export's own write is: a disk that fills mid-copy and a volume that goes
        // away are both "not this time", and neither may fail the caller. The source is left
        // where it was for the next process to try again; see the class doc.
        runCatching {
            source.copyTo(scratch, overwrite = true)
            if (!scratch.renameTo(target)) throw IOException("could not move ${scratch.name} into place")
            source.delete()
        }.onFailure { scratch.delete() }
    }

    private companion object {

        /**
         * The first Android version whose external files directory is the app's alone: Android
         * 10, where scoped storage arrived. Below it the backups are in `filesDir`.
         *
         * The one number the rule turns on. Android 10 still let an app in legacy storage mode
         * (targeting Android 9 or below, or opting out) see the whole of shared storage, and
         * Android's documentation lists other apps' app-specific directories among what
         * Android 11 closed; whether such an app can read this directory on Android 10 is an
         * open device check, recorded in `release/security-audit.md` (#259). If it can, this
         * becomes `Build.VERSION_CODES.R`.
         */
        const val FIRST_SCOPED_SDK = Build.VERSION_CODES.Q

        /**
         * What every backup file the app writes starts with: `titan-auto-` for the weekly ones
         * (`AutoBackupFiles`) and `titan-backup-` for the manual export (`BackupViewModel`).
         * The common part rather than both, so a third kind of backup is moved too.
         */
        const val PREFIX = "titan-"
        const val SUFFIX = ".json"

        /** The scratch suffix both writers append while a file is being written. */
        const val SCRATCH_SUFFIX = ".tmp"
    }
}
