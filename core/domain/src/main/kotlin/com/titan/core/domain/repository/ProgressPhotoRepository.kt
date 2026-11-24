package com.titan.core.domain.repository

import com.titan.core.domain.model.ProgressPhoto
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * The user's progress photos — see [ProgressPhoto] for where they live and why they never leave
 * the device.
 *
 * The images themselves are the store's business: a caller hands over bytes through a
 * [PhotoSource] and reads back a [ProgressPhoto.path], and how the picture is resized, re-encoded
 * and named is not something the domain needs to know.
 */
interface ProgressPhotoRepository {

    /** Every photo, most recently taken first, re-emitted as the collection changes. */
    fun getAll(): Flow<List<ProgressPhoto>>

    /**
     * Stores the image [source] holds and returns the new photo's id.
     *
     * The image is re-encoded on the way in: scaled down to a size a phone screen can use, turned
     * the right way up, and stripped of every piece of metadata it carried — a phone camera's
     * photo can hold the GPS position of the user's home, and nothing the app keeps should. When
     * the image records when it was taken, that is the photo's [ProgressPhoto.takenAt]; otherwise
     * [takenAt] is.
     *
     * @throws java.io.IOException if the bytes cannot be read or are not an image, with nothing
     *   stored.
     */
    suspend fun add(
        source: PhotoSource,
        takenAt: Long,
    ): Long

    /** Removes the photo and its image, or does nothing when no photo has that id. */
    suspend fun delete(id: Long)
}

/**
 * Where a photo's bytes come from — a camera capture or a picked image — one stream at a time.
 *
 * A source rather than a stream for the reason `ImportSource` is one: the store reads the bytes
 * more than once (the dimensions, the metadata, then the image), so [open] must return a fresh
 * stream over the same bytes on every call. It blocks, and throws an [java.io.IOException] when
 * the bytes cannot be read.
 */
fun interface PhotoSource {

    /** Opens a fresh stream over the image's bytes. The caller closes what it opens. */
    fun open(): InputStream
}
