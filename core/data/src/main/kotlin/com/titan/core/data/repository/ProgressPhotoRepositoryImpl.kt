package com.titan.core.data.repository

import android.content.Context
import com.titan.core.data.db.dao.ProgressPhotoDao
import com.titan.core.data.db.entity.ProgressPhotoEntity
import com.titan.core.data.di.IoDispatcher
import com.titan.core.data.photo.PhotoEncoder
import com.titan.core.data.photo.ProgressPhotoFiles
import com.titan.core.domain.model.ProgressPhoto
import com.titan.core.domain.repository.PhotoSource
import com.titan.core.domain.repository.ProgressPhotoRepository
import com.titan.core.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ProgressPhotoRepository] over the `progress_photo` table and the private photo directory.
 *
 * ### The file first, then the row
 *
 * An image is written under a scratch name and renamed into place before its row is inserted, and
 * a row that fails to insert takes its file with it. The order means a crash can leave an image no
 * row names — invisible, and gone with the next "Delete all data" — but never a row naming an
 * image that is not there, which every screen would draw as a broken photo.
 *
 * Deleting goes the other way, row first: the photo leaves the screen the moment it is deleted, and
 * a file that outlives its row is the same harmless leftover.
 */
@Singleton
class ProgressPhotoRepositoryImpl @Inject constructor(
    private val dao: ProgressPhotoDao,
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ProgressPhotoRepository {

    private val directory: File get() = ProgressPhotoFiles.directory(context)

    override fun getAll(): Flow<List<ProgressPhoto>> =
        dao.getAll().map { rows ->
            // Once per emission rather than per row: `filesDir` checks the directory on disk
            // every time it is asked, and this runs on the collector's thread.
            val directory = directory
            rows.map { row ->
                ProgressPhoto(
                    id = row.id,
                    takenAt = row.takenAt,
                    path = File(directory, row.fileName).absolutePath,
                    createdAt = row.createdAt,
                )
            }
        }

    override suspend fun add(
        source: PhotoSource,
        takenAt: Long,
    ): Long =
        withContext(ioDispatcher) {
            val metadata = PhotoEncoder.metadata(source, ZoneId.systemDefault())
            val stamped = metadata.takenAt ?: takenAt
            val name = "${UUID.randomUUID()}.jpg"
            val target = File(directory.apply { mkdirs() }, name)
            val scratch = File(directory, "$name.tmp")
            try {
                scratch.outputStream().buffered().use { PhotoEncoder.encode(source, it, metadata.orientation) }
                if (!scratch.renameTo(target)) throw IOException("could not move the photo into place")
            } finally {
                scratch.delete()
            }
            runCatching {
                dao.insert(
                    ProgressPhotoEntity(fileName = name, takenAt = stamped, createdAt = clock.nowMillis()),
                )
            }
                .onFailure { target.delete() }
                .getOrThrow()
        }

    override suspend fun delete(id: Long) {
        val row = dao.getById(id) ?: return
        dao.delete(id)
        withContext(ioDispatcher) { File(directory, row.fileName).delete() }
    }
}
