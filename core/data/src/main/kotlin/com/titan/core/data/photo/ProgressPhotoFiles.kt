package com.titan.core.data.photo

import android.content.Context
import java.io.File

/**
 * Where progress photos live: `files/photos/`, in the app's internal storage.
 *
 * Internal rather than the external files directory the backups may use, because no other app
 * should ever be able to read a progress photo, on any Android version or with any storage
 * permission. Excluded from Android's own backup in `data_extraction_rules.xml` and
 * `backup_rules.xml`, emptied by "Delete all data" (`LocalDataRepositoryImpl`), and never part of
 * an export — see `ProgressPhoto`.
 */
object ProgressPhotoFiles {

    /** The directory's name under `files/`, as the backup rules spell it. */
    const val DIRECTORY = "photos"

    /** The photo directory, which may not exist yet. */
    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)
}
