package com.titan.feature.settings.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Another app's documents provider, as the system picker hands its URIs back: `content://`,
 * an authority that is not this app's, a display name on query and the bytes on open.
 *
 * The restore reads only from somewhere like this — a path or the app's own provider is
 * refused — so the tests that restore a file serve it through one. Registered per test with
 * `Robolectric.setupContentProvider`, which returns the instance [serve] is called on.
 */
class TestDocumentsProvider : ContentProvider() {

    private val documents = mutableMapOf<String, Pair<File, String?>>()

    /**
     * Serves [file] under a fresh document id, and answers a name query with [displayName] —
     * null for a provider that does not say.
     */
    fun serve(
        file: File,
        displayName: String? = file.name,
    ): Uri {
        val id = "document-${documents.size + 1}"
        documents[id] = file to displayName
        return Uri.parse("content://$AUTHORITY/$id")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val (_, name) = documents[uri.lastPathSegment] ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val (file, _) = documents[uri.lastPathSegment] ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** Not under this app's application id, as no other app's provider is. */
        const val AUTHORITY = "org.example.documents"
    }
}
