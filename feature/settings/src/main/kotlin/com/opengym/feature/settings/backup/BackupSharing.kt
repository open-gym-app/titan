package com.opengym.feature.settings.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.opengym.feature.settings.R
import java.io.File

/**
 * Hands [fileName] to the system share sheet through the app's `FileProvider`.
 *
 * The authority is derived from the running package rather than written down, so it follows
 * the `applicationIdSuffix` the debug and staging builds add — a literal would be wrong on two
 * of the three variants. `FLAG_GRANT_READ_URI_PERMISSION` is what lets the app the user picks
 * actually open the file; without it the sheet appears and the target sees nothing.
 *
 * ### `FLAG_ACTIVITY_NEW_TASK`, and why it is not optional
 *
 * This is an extension on `Context`, and `Context.startActivity` throws
 * `AndroidRuntimeException` unless the receiver is an `Activity` or the intent carries this
 * flag. In the app it *is* an activity — `LocalContext.current` inside `BackupRoute` — so the
 * flag changes nothing there and the omission would never have shown up on a phone. It would
 * have shown up the first time this was called from anywhere else, a second after a successful
 * backup, as a crash while handing the file over. A `Context` extension that only works for one
 * kind of context is a signature that lies; `BackupSharingTest` is what caught it.
 */
internal fun Context.shareBackup(fileName: String) {
    val file = File(File(getExternalFilesDir(null) ?: filesDir, BACKUP_DIRECTORY), fileName)
    val uri: Uri = FileProvider.getUriForFile(this, "$packageName$FILE_PROVIDER_SUFFIX", file)

    val send = Intent(Intent.ACTION_SEND).apply {
        type = BACKUP_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, getString(R.string.backup_share_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(chooser)
}

/** What the exporter writes, and what the share sheet offers it as. */
internal const val BACKUP_MIME_TYPE = "application/json"

/**
 * What the restore picker will let the user choose — deliberately wider than what we write.
 *
 * `OpenDocument` greys out every file whose *provider* reports a type outside this list, and a
 * backup that has been anywhere reports whatever that trip decided: a messenger attachment and
 * a re-download both commonly arrive as `application/octet-stream`, some providers sniff a
 * `.json` file as `text/plain`, and a user staring at their own greyed-out backup has no way to
 * widen the filter from inside the picker. Nothing is lost by admitting them — the file is
 * parsed and version-checked before a single row is written, so a wrong pick fails as a typed
 * `ImportError` with the database untouched, which is a better answer than an unselectable file.
 *
 * Still a list rather than the wildcard type: naming the shapes keeps the picker's own "recent"
 * view useful, and the four below are the ones a real backup actually turns up as.
 */
internal val RESTORE_MIME_TYPES = arrayOf(
    BACKUP_MIME_TYPE,
    "text/json",
    "text/plain",
    "application/octet-stream",
)

/** Matches the `<provider>` authority this module's manifest declares. */
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

/** Matches `BackupViewModel.BACKUP_DIRECTORY`; the two are one directory and this is the reader. */
private const val BACKUP_DIRECTORY = "backups"
