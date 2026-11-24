package com.titan.feature.progression.photos

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The provider the camera app writes a new progress photo through.
 *
 * Its own subclass, rather than a second declaration of `androidx.core.content.FileProvider`,
 * because the manifest merger allows one component per class name and the settings feature
 * already declares one for sharing backups. Scoped to a single cache directory
 * (`res/xml/photo_capture_paths.xml`), with no access granted except the one-shot write the
 * capture intent carries.
 */
class PhotoCaptureProvider : FileProvider()

/** Where a capture lands before it is stored, and how the camera is handed it. */
internal object PhotoCapture {

    private const val DIRECTORY = "photo-capture"
    private const val FILE = "capture.jpg"
    private const val AUTHORITY_SUFFIX = ".photocapture"

    /**
     * A fresh, empty scratch file for the next capture, and the content URI the camera writes it
     * through. One name, reused: a capture is stored and the scratch file deleted before the next
     * can start, so there is never more than one to keep apart.
     */
    fun prepare(context: Context): Pair<File, Uri> {
        val file = File(File(context.cacheDir, DIRECTORY).apply { mkdirs() }, FILE)
        file.delete()
        return file to FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }
}
