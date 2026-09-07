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
 */
internal fun Context.shareBackup(fileName: String) {
    val file = File(File(getExternalFilesDir(null) ?: filesDir, BACKUP_DIRECTORY), fileName)
    val uri: Uri = FileProvider.getUriForFile(this, "$packageName$FILE_PROVIDER_SUFFIX", file)

    val send = Intent(Intent.ACTION_SEND).apply {
        type = BACKUP_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, getString(R.string.backup_share_title)))
}

/** What the exporter writes, and what the restore picker is filtered to. */
internal const val BACKUP_MIME_TYPE = "application/json"

/** Matches the `<provider>` authority in `:app`'s manifest. */
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

/** Matches `BackupViewModel.BACKUP_DIRECTORY`; the two are one directory and this is the reader. */
private const val BACKUP_DIRECTORY = "backups"
