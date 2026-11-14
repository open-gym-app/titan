package com.titan.core.data.db

import com.titan.core.data.db.entity.TagEntity
import com.titan.core.data.repository.BackupRepositoryImpl
import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.backup.ExportFormat
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.usecase.ExportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An export is one state of the database, however long it takes to write (#257).
 *
 * It pages through nineteen tables, parents before children, and a write landing between two
 * of those pages used to be in some of them and not in others — a session added after
 * `session_exercise` was read and before `workout_set` was is a file whose sets name a parent
 * it does not hold, which the next restore refuses. `BackupRepository.readConsistently` holds
 * one transaction across the whole document, so a write made meanwhile waits for it.
 *
 * Over a real (Robolectric) SQLite, because a fake could only assert that the block was called.
 */
class ExportSnapshotTest : RoomTestBase() {

    @Test
    fun `a write made while an export is reading waits for the export to finish`() =
        runBlocking {
            db.seedEveryTable()
            val export = ExportUseCase(
                backupRepository = BackupRepositoryImpl(db, Dispatchers.IO),
                userPreferencesRepository = InMemoryUserPreferencesRepository(),
                clock = { EXPORTED_AT },
                // A page a row, so the export is many queries long and a write has every chance
                // to land between two of them.
                pageSize = 1,
                lock = BackupLock(),
            )
            val text = StringBuilder()
            var chunks = 0
            var writer: Job? = null
            var landedDuringExport: Boolean? = null
            val sink = ExportSink { chunk ->
                text.append(chunk)
                // The second chunk is the first page: the export is mid-document. Write from
                // another thread and give the write every chance to land before going on.
                if (++chunks == 2) {
                    val landed = CountDownLatch(1)
                    writer = launch(Dispatchers.IO) {
                        db.tagDao().insert(TagEntity(name = ADDED_MID_EXPORT))
                        landed.countDown()
                    }
                    landedDuringExport = landed.await(WRITE_GRACE_MS, TimeUnit.MILLISECONDS)
                }
            }

            export(sink, APP_VERSION).collect()
            writer!!.join()

            assertEquals("the write should have waited for the export", false, landedDuringExport)
            val exported = (ExportFormat.decode { text.toString().byteInputStream() } as ExportFormat.Decoded.Success)
            assertFalse(exported.document.tag.any { it.name == ADDED_MID_EXPORT })
            assertTrue(
                "the write should have landed once the export ended",
                db.tagDao().getAll().first().any { it.name == ADDED_MID_EXPORT },
            )
        }

    private companion object {
        const val EXPORTED_AT = 1_788_686_970_491L
        const val APP_VERSION = "1.0-debug"
        const val ADDED_MID_EXPORT = "added mid-export"

        /** Many times what one insert into an in-memory database takes when nothing holds it back. */
        const val WRITE_GRACE_MS = 500L
    }
}
