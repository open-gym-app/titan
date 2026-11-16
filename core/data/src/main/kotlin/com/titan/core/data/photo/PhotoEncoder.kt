package com.titan.core.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.titan.core.domain.repository.PhotoSource
import java.io.IOException
import java.io.OutputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns whatever image a camera or the photo picker hands over into the one Titan stores: a JPEG
 * at most [MAX_EDGE] pixels on its longer side, the right way up, with no metadata at all.
 *
 * ### Why re-encode rather than copy
 *
 * - **Size.** A modern phone photo is twelve to fifty megapixels and several megabytes; a progress
 *   photo is looked at on a phone screen, a year's weekly photos would otherwise be a gigabyte,
 *   and a 2048-pixel JPEG is sharper than any screen it is shown on.
 * - **Orientation.** Cameras store the sensor's pixels and a tag saying which way is up. Honouring
 *   the tag here, once, means every reader can draw the pixels as they are.
 * - **Privacy.** A camera's metadata routinely includes the GPS position the photo was taken at —
 *   for a progress photo, usually the user's bedroom. A fresh encode writes none of it, so nothing
 *   the app keeps can leak it, even to a user who later shares the file.
 *
 * The one piece of metadata that *is* read is when the photo was taken, so a picture from last
 * spring lands in last spring rather than on the day it was imported.
 */
internal object PhotoEncoder {

    /** The longer edge, in pixels, a stored photo is scaled down to. */
    const val MAX_EDGE = 2048

    /** JPEG quality: visually lossless for a photo of a person, at a fraction of the size. */
    const val QUALITY = 85

    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * The offset the original timestamp was taken in, by its tag name rather than the platform
     * constant, which only exists from API 30: a platform that does not know the tag simply reads
     * it as absent, and the photo is read in the device's zone instead.
     */
    private const val TAG_OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal"

    /**
     * What [source]'s image says about itself, read in one pass over its metadata: when it was
     * taken and which way is up. One read rather than one per tag, because a picked photo can be
     * backed by a cloud provider, where every [PhotoSource.open] may fetch the bytes again.
     */
    fun metadata(
        source: PhotoSource,
        zone: ZoneId,
    ): PhotoMetadata {
        val exif = runCatching { source.open().use { ExifInterface(it) } }.getOrNull()
            ?: return PhotoMetadata(takenAt = null, orientation = ExifInterface.ORIENTATION_NORMAL)
        return PhotoMetadata(
            takenAt = exif.takenAt(zone),
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
        )
    }

    /**
     * When the image says it was taken, in epoch milliseconds, or null when it does not say or
     * says something unreadable. The camera's local time, read in its own recorded offset when
     * there is one it can parse and in [zone] otherwise: a malformed offset loses the offset, not
     * the date.
     */
    private fun ExifInterface.takenAt(zone: ZoneId): Long? {
        val local = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val dateTime = try {
            LocalDateTime.parse(local.trim(), EXIF_DATE)
        } catch (_: DateTimeParseException) {
            return null
        }
        val offset = getAttribute(TAG_OFFSET_TIME_ORIGINAL)?.let {
            try {
                ZoneOffset.of(it.trim())
            } catch (_: DateTimeException) {
                null
            }
        }
        return dateTime.atZone(offset ?: zone).toInstant().toEpochMilli()
    }

    /**
     * Decodes [source], scales it, turns it the way [orientation] (from [metadata]) says is up,
     * and writes it to [out] as a JPEG.
     *
     * @throws IOException if the bytes cannot be read or are not an image.
     */
    fun encode(
        source: PhotoSource,
        out: OutputStream,
        orientation: Int,
    ) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")

        val decoded = source.open().use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) },
            )
        } ?: throw IOException("not an image")

        val finished = decoded.fitted().oriented(orientation)
        try {
            if (!finished.compress(
                    Bitmap.CompressFormat.JPEG,
                    QUALITY,
                    out,
                )
            ) {
                throw IOException("could not encode the photo")
            }
        } finally {
            finished.recycle()
        }
    }

    /**
     * The largest power of two that still leaves the decoded image at least [MAX_EDGE] on its
     * longer side — decoding a fifty-megapixel photo whole only to throw most of it away is how a
     * phone runs out of memory.
     */
    internal fun sampleSize(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    /** This bitmap scaled so its longer edge is at most [MAX_EDGE]; itself when it already is. */
    private fun Bitmap.fitted(): Bitmap {
        val longest = max(width, height)
        if (longest <= MAX_EDGE) return this
        val scale = MAX_EDGE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(this, (width * scale).roundToInt(), (height * scale).roundToInt(), true)
        if (scaled !== this) recycle()
        return scaled
    }

    /** This bitmap turned the way the camera's [orientation] tag says is up. */
    private fun Bitmap.oriented(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(QUARTER)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(HALF)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(THREE_QUARTERS)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { postRotate(QUARTER) }.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { postRotate(THREE_QUARTERS) }.postScale(-1f, 1f)
            else -> return this
        }
        val turned = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (turned !== this) recycle()
        return turned
    }

    private const val QUARTER = 90f
    private const val HALF = 180f
    private const val THREE_QUARTERS = 270f
}

/**
 * What an image says about itself, as [PhotoEncoder.metadata] reads it.
 *
 * @property takenAt when it was taken, epoch milliseconds, or null when it does not say.
 * @property orientation its EXIF orientation tag, [ExifInterface.ORIENTATION_NORMAL] when absent.
 */
internal data class PhotoMetadata(
    val takenAt: Long?,
    val orientation: Int,
)
