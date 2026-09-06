package com.opengym.core.domain.usecase

import app.cash.turbine.test
import com.opengym.core.domain.backup.ExportFormat
import com.opengym.core.domain.backup.ExportSink
import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.ExportProgress
import com.opengym.core.domain.backup.sampleDocument
import com.opengym.core.domain.backup.samplePreferences
import com.opengym.core.domain.backup.toPayload
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an export produces, how it reports itself, and — the part that matters on a phone — that
 * it never holds the history it is writing.
 */
class ExportUseCaseTest {

    private val stored = sampleDocument()
    private val backup = FakeBackupRepository(stored)
    private val preferences = FakeUserPreferencesRepository()
    private val clock = MutableClock(now = EXPORTED_AT)

    @Test
    fun `the document written is the database, stamped with the clock and the build`() =
        runTest {
            val text = StringBuilder()

            exporter().invoke(ExportSink { text.append(it) }, APP_VERSION).toList()

            val document = (ExportFormat.decode(text.toString()) as ExportFormat.Decoded.Success).document
            assertEquals(EXPORTED_AT, document.exportedAt)
            assertEquals(APP_VERSION, document.appVersion)
            assertEquals(ExportDocument.SCHEMA_VERSION, document.schemaVersion)
        }

    @Test
    fun `every table's rows come back in the order they were stored`() =
        runTest {
            val text = StringBuilder()

            exporter().invoke(ExportSink { text.append(it) }, APP_VERSION).toList()

            val document = (ExportFormat.decode(text.toString()) as ExportFormat.Decoded.Success).document
            BackupTable.entries.forEach { table ->
                assertEquals("${table.tableName} did not round-trip", stored.rows(table), document.rows(table))
            }
        }

    @Test
    fun `the settings in the file are the settings the store holds`() =
        runTest {
            preferences.setUnitSystem(UnitSystem.IMPERIAL)
            preferences.setTheme(ThemePreference.DARK)
            val text = StringBuilder()

            exporter().invoke(ExportSink { text.append(it) }, APP_VERSION).toList()

            val document = (ExportFormat.decode(text.toString()) as ExportFormat.Decoded.Success).document
            assertEquals(preferences.observe().first().toPayload(), document.preferences)
            assertEquals("IMPERIAL", document.preferences.unitSystem)
            assertEquals("DARK", document.preferences.theme)
        }

    @Test
    fun `progress ends with exactly one completion carrying every row written`() =
        runTest {
            val emissions = exporter().invoke(ExportSink { }, APP_VERSION).toList()

            val completions = emissions.filterIsInstance<ExportProgress.Complete>()
            assertEquals(1, completions.size)
            assertEquals(emissions.last(), completions.single())
            assertEquals(stored.rowCount, completions.single().rowsWritten)
        }

    @Test
    fun `progress is determinate from the first emission`() =
        runTest {
            exporter().invoke(ExportSink { }, APP_VERSION).test {
                val first = awaitItem()
                assertEquals(stored.rowCount, first.rowsTotal)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `progress never goes backwards and never overshoots the total`() =
        runTest {
            val emissions = exporter().invoke(ExportSink { }, APP_VERSION).toList()

            emissions.zipWithNext { earlier, later ->
                assertTrue("progress went backwards", later.rowsWritten >= earlier.rowsWritten)
            }
            assertTrue(emissions.all { it.rowsWritten <= stored.rowCount })
        }

    /** So a screen naming the table it is on never silently skips one. */
    @Test
    fun `every table is named by at least one emission, empty ones included`() =
        runTest {
            val emissions = exporter(FakeBackupRepository(emptyDocument())).invoke(ExportSink { }, APP_VERSION).toList()

            val named = emissions.filterIsInstance<ExportProgress.Writing>().map { it.table }.toSet()
            assertEquals(BackupTable.entries.toSet(), named)
        }

    @Test
    fun `a database with nothing in it exports a document with nothing in it`() =
        runTest {
            val text = StringBuilder()
            val empty = FakeBackupRepository(emptyDocument())

            val emissions = exporter(empty).invoke(ExportSink { text.append(it) }, APP_VERSION).toList()

            assertEquals(0L, (emissions.last() as ExportProgress.Complete).rowsWritten)
            val document = (ExportFormat.decode(text.toString()) as ExportFormat.Decoded.Success).document
            assertEquals(0L, document.rowCount)
        }

    /**
     * The memory guarantee, asserted the only way it can be from outside: the repository is
     * never asked for more than a page, so nothing bigger than a page can be held.
     */
    @Test
    fun `no read asks for more rows than the page size`() =
        runTest {
            exporter(pageSize = 1).invoke(ExportSink { }, APP_VERSION).toList()

            assertTrue(backup.pageRequests.isNotEmpty())
            assertTrue(backup.pageRequests.all { it.limit == 1 })
        }

    @Test
    fun `a table longer than one page is read from successive offsets`() =
        runTest {
            exporter(pageSize = 1).invoke(ExportSink { }, APP_VERSION).toList()

            val offsets = backup.pageRequests.filter { it.table == BackupTable.EXERCISE }.map { it.offset }
            // Two exercises in the fixture: offsets 0 and 1 return a row each, and 2 the empty
            // page that ends the table.
            assertEquals(listOf(0, 1, 2), offsets)
        }

    /** A short page is the last page, so a table shorter than one is read exactly once. */
    @Test
    fun `a table shorter than a page is read once`() =
        runTest {
            exporter(pageSize = 500).invoke(ExportSink { }, APP_VERSION).toList()

            assertEquals(1, backup.pageRequests.count { it.table == BackupTable.EXERCISE })
        }

    /** The page size is a memory trade, not a format decision. */
    @Test
    fun `the page size does not change the file`() =
        runTest {
            val small = StringBuilder()
            val large = StringBuilder()

            exporter(pageSize = 1).invoke(ExportSink { small.append(it) }, APP_VERSION).toList()
            exporter(pageSize = 5_000).invoke(ExportSink { large.append(it) }, APP_VERSION).toList()

            assertEquals(small.toString(), large.toString())
        }

    @Test
    fun `a page size of zero is refused at construction`() {
        val failure = runCatching { exporter(pageSize = 0) }.exceptionOrNull()

        assertTrue("expected the page size to be validated, got $failure", failure is IllegalArgumentException)
    }

    private fun exporter(
        repository: FakeBackupRepository = backup,
        pageSize: Int = ExportUseCase.DEFAULT_PAGE_SIZE,
    ) = ExportUseCase(
        backupRepository = repository,
        userPreferencesRepository = preferences,
        clock = clock,
        pageSize = pageSize,
    )

    private fun emptyDocument() =
        ExportDocument(exportedAt = EXPORTED_AT, appVersion = APP_VERSION, preferences = samplePreferences())

    private companion object {
        const val EXPORTED_AT = 1_788_686_970_491L
        const val APP_VERSION = "1.0-debug"
    }
}
