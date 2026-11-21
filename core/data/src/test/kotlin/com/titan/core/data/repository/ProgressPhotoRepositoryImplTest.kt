package com.titan.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.photo.PhotoEncoder
import com.titan.core.data.photo.ProgressPhotoFiles
import com.titan.core.domain.repository.PhotoSource
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.ZoneOffset

/**
 * The photo store over a real database and real files, with real images.
 *
 * [GraphicsMode.Mode.NATIVE], because the whole point of the store is what it does to the pixels —
 * the scaling, the turning, the re-encode that drops the metadata — and the legacy shadows decode
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressPhotoRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: TitanDatabase
    private lateinit var repository: ProgressPhotoRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TitanDatabase::class.java).allowMainThreadQueries().build()
        repository = ProgressPhotoRepositoryImpl(db.progressPhotoDao(), context, Clock { NOW }, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
        ProgressPhotoFiles.directory(context).deleteRecursively()
    }

    private fun jpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun ByteArray.source() = PhotoSource { inputStream() }

    private fun stored(path: String) = BitmapFactory.decodeFile(path)

    @Test
    fun `a photo is stored as a file and a row, and read back with its path`() =
        runBlocking {
            val id = repository.add(jpeg(400, 600).source(), takenAt = TAKEN)

            val photo = repository.getAll().first().single()
            assertEquals(id, photo.id)
            assertEquals(TAKEN, photo.takenAt)
            assertEquals(NOW, photo.createdAt)
            assertTrue(File(photo.path).exists())
            assertTrue(photo.path.startsWith(ProgressPhotoFiles.directory(context).absolutePath))
            assertEquals(400 to 600, stored(photo.path).let { it.width to it.height })
        }

    @Test
    fun `a photo larger than a phone can use is scaled down to its longer edge`() =
        runBlocking {
            repository.add(jpeg(5000, 3000).source(), takenAt = TAKEN)

            val bitmap = stored(repository.getAll().first().single().path)
            assertEquals(PhotoEncoder.MAX_EDGE, bitmap.width)
            assertEquals(1229, bitmap.height)
        }

    @Test
    fun `bytes that are not an image are refused, and nothing is stored`() =
        runBlocking {
            assertThrows(IOException::class.java) {
                runBlocking { repository.add("not a photo".toByteArray().source(), takenAt = TAKEN) }
            }

            assertTrue(repository.getAll().first().isEmpty())
            assertTrue(ProgressPhotoFiles.directory(context).listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `deleting a photo takes its file with it`() =
        runBlocking {
            val id = repository.add(jpeg(100, 100).source(), takenAt = TAKEN)
            val path = repository.getAll().first().single().path

            repository.delete(id)

            assertTrue(repository.getAll().first().isEmpty())
            assertFalse(File(path).exists())
        }

    @Test
    fun `photos come newest first`() =
        runBlocking {
            repository.add(jpeg(10, 10).source(), takenAt = 1_000)
            repository.add(jpeg(10, 10).source(), takenAt = 3_000)
            repository.add(jpeg(10, 10).source(), takenAt = 2_000)

            assertEquals(listOf(3_000L, 2_000L, 1_000L), repository.getAll().first().map { it.takenAt })
        }

    @Test
    fun `nothing the camera recorded about the photo survives the re-encode`() =
        runBlocking {
            // A photo carrying a GPS position — the case the re-encode exists for.
            val original = File(context.cacheDir, "with-gps.jpg").apply { writeBytes(jpeg(200, 200)) }
            ExifInterface(original.absolutePath).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48/1,51/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }

            repository.add(PhotoSource { original.inputStream() }, takenAt = TAKEN)

            val kept = ExifInterface(repository.getAll().first().single().path)
            assertNull(kept.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            original.delete()
            Unit
        }

    @Test
    fun `when the camera says it was taken, that is when it was taken`() {
        val original = File(context.cacheDir, "dated.jpg").apply { writeBytes(jpeg(50, 50)) }
        ExifInterface(original.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2025:03:02 07:30:00")
            saveAttributes()
        }

        val takenAt = PhotoEncoder.metadata(PhotoSource { original.inputStream() }, ZoneOffset.UTC).takenAt

        assertEquals(1_740_900_600_000L, takenAt)
        original.delete()
    }

    @Test
    fun `a photo that does not say when it was taken is dated by the caller`() =
        runBlocking {
            repository.add(jpeg(50, 50).source(), takenAt = TAKEN)

            assertEquals(TAKEN, repository.getAll().first().single().takenAt)
        }

    @Test
    fun `decoding samples down by powers of two and never below the target edge`() {
        assertEquals(1, PhotoEncoder.sampleSize(2048, 1000))
        assertEquals(2, PhotoEncoder.sampleSize(4096, 3000))
        assertEquals(2, PhotoEncoder.sampleSize(8000, 6000))
        assertEquals(4, PhotoEncoder.sampleSize(8192, 6000))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val TAKEN = 1_790_000_000_000L
    }
}
