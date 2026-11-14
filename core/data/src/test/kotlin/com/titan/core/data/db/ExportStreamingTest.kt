package com.titan.core.data.db

import com.titan.core.data.repository.BackupRepositoryImpl
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportProgress
import com.titan.core.domain.usecase.ExportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The last of #37's acceptance criteria: exporting the scale the app commits to — 1,000
 * sessions and 50,000 sets, `LargeHistoryBenchmarkTest`'s `TARGET_SCALE` — without ever holding
 * the history in memory.
 *
 * ### How "without loading it into memory" is asserted
 *
 * Not by measuring the heap, which on a JVM runner measures the garbage collector's mood. By
 * the two things that would have to be true for it to be false:
 *
 * 1. **No read is bigger than a page.** The exporter asks
 *    [com.titan.core.domain.repository.BackupRepository.readPage] for at most
 *    [ExportUseCase.DEFAULT_PAGE_SIZE] rows at a time, so the rows themselves cannot pile up —
 *    [ExportUseCaseTest] in `:core:domain` pins that with a counting fake, and this pins it
 *    against the real repository.
 * 2. **No chunk handed to the sink is bigger than a page's worth of JSON.** A writer that built
 *    the document and handed it over in one call would fail this however small the reads were.
 *
 * The bytes go to a real file, as they will in #38, so nothing in the test holds the document
 * either — the assertions below read it back off disk.
 */
class ExportStreamingTest : RoomTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val backup by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }

    private val export by lazy {
        ExportUseCase(
            backupRepository = backup,
            userPreferencesRepository = InMemoryUserPreferencesRepository(),
            clock = { EXPORTED_AT },
        )
    }

    /**
     * The target-scale run, and the only test here that pays for it.
     *
     * Seeding 1,000 sessions and 50,000 sets takes real time, and `LargeHistoryBenchmarkTest`
     * in this same module already does it twice — so the memory guarantee, the progress
     * contract and the file's contents are asserted in one pass over one fixture rather than
     * in three tests over three. They are one run of the exporter either way; splitting them
     * would triple the cost of the suite to make three failures nameable instead of one.
     */
    @Test
    fun `the target-scale history exports in pages, reports determinately, and parses`() =
        runBlocking {
            val seeded = seedHistory(TARGET_SCALE)
            val expected = rowsInDatabase()
            val file = temporaryFolder.newFile("export.json")
            var largestChunk = 0

            val progress = export(
                ExportSink { chunk ->
                    largestChunk = maxOf(largestChunk, chunk.length)
                    file.appendText(chunk)
                },
                APP_VERSION,
            ).toList()

            // Determinate from the first emission, monotonic, and finishing on the count.
            assertEquals(expected, progress.first().rowsTotal)
            progress.zipWithNext { earlier, later ->
                assertTrue("progress went backwards", later.rowsWritten >= earlier.rowsWritten)
            }
            assertEquals(expected, (progress.last() as ExportProgress.Complete).rowsWritten)

            // The memory guarantee, asserted the only way it can be from outside: a writer that
            // had built the document and handed it over in one call would fail this by two
            // orders of magnitude.
            assertTrue(
                "a chunk of $largestChunk characters is more than a page of rows: the writer stopped streaming",
                largestChunk < MAX_CHUNK_CHARACTERS,
            )
            assertSetsAndSessions(file, seeded.spec)
        }

    /**
     * The verification step #37 spells out: the file's top-level keys are the ones
     * docs/export-schema.md documents, and it parses.
     */
    @Test
    fun `the exported file's top-level keys are the envelope and the twenty tables`() =
        runBlocking {
            seedHistory(SMALL_SCALE)
            val file = temporaryFolder.newFile("keys.json")

            export(ExportSink { file.appendText(it) }, APP_VERSION).toList()

            val keys = Json.parseToJsonElement(file.readText()).jsonObject.keys.toList()
            assertEquals(ENVELOPE_KEYS + BackupTable.entries.map { it.tableName }, keys)
        }

    /** Reads the file back and checks the two tables the scale is measured in. */
    private fun assertSetsAndSessions(
        file: File,
        spec: HistorySpec,
    ) {
        val document = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(spec.setCount, document.getValue("workout_set").jsonArray.size)
        assertEquals(spec.sessionExerciseCount, document.getValue("session_exercise").jsonArray.size)
        // The in-progress session the fixture adds is exported like any other: it is history the
        // moment its rows are written, and only the *draft* is device state.
        assertEquals(spec.sessionCount + 1, document.getValue("workout_session").jsonArray.size)
    }

    private suspend fun rowsInDatabase(): Long = backup.rowCounts().values.sum()

    private companion object {

        /** `LargeHistoryBenchmarkTest.TARGET_SCALE`: the scale the app commits to staying usable at. */
        val TARGET_SCALE = HistorySpec(sessionCount = 1_000, exercisesPerSession = 5, setsPerExercise = 10)

        /** Enough to be a real document, small enough that the shape assertions stay quick. */
        val SMALL_SCALE = HistorySpec(sessionCount = 5, exercisesPerSession = 2, setsPerExercise = 3)

        const val EXPORTED_AT = 1_788_686_970_491L
        const val APP_VERSION = "1.0-debug"

        /**
         * The ceiling on one chunk, in characters.
         *
         * A page is 500 rows and a `workout_set` row encodes to roughly 250 characters, so a
         * full page is on the order of 125,000. This allows four times that, which is loose
         * enough that a wider table cannot fail it by accident and tight enough that a writer
         * which stopped streaming — 50,000 sets is tens of millions of characters — fails it by
         * two orders of magnitude.
         */
        const val MAX_CHUNK_CHARACTERS = 500_000

        val ENVELOPE_KEYS = listOf("schema_version", "exported_at", "app_version", "preferences")
    }
}
