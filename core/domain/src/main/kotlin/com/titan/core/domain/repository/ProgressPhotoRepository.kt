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
     * photo can hold the GPS position of the user's home, and nothing the app keeps should.
     *
     * @param takenAt the photo's [ProgressPhoto.takenAt], unless [preferImageTime] lets the image
     *   overrule it.
     * @param preferImageTime whether the time the image records it was taken, when it records one,
     *   wins over [takenAt]. It does for a photo picked in the app, where [takenAt] is only the
     *   moment it was added. It does not for an imported one: the import recognises a photo it
     *   already stored by the time its source declared, which every source gives and many images,
     *   re-encoded on their way out of another app, no longer carry; letting the image win would
     *   store the same photo again on every re-run. Neither answer is the obvious one, so each
     *   caller gives its own rather than inherit a default.
     * @throws java.io.IOException if the bytes cannot be read or are not an image, with nothing
     *   stored.
     */
    suspend fun add(
        source: PhotoSource,
        takenAt: Long,
        preferImageTime: Boolean,
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
