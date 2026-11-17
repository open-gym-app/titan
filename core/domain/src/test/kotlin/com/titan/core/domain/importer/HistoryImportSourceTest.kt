package com.titan.core.domain.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sources the import screen offers, and the parser each one hands the file to.
 *
 * The mapping is an exhaustive `when`, so a missing source cannot compile; what this holds is the
 * other half — that no two sources are crossed, which would compile cleanly and answer "this file
 * is not a Strong export" to every Strong user. Each source is shown one of its own app's real
 * exports and must read it, and every other source's parser must refuse it.
 */
class HistoryImportSourceTest {

    /** One real export per source, from the parsers' own fixtures. */
    private val ownExport = mapOf(
        HistoryImportSource.STRONG to "strong/strong-ios.csv",
        HistoryImportSource.HEVY to "hevy/hevy-workouts.csv",
        HistoryImportSource.FITNOTES to "fitnotes/fitnotes-export.csv",
        HistoryImportSource.JEFIT to "jefit/jefit-export.csv",
        HistoryImportSource.FITBOD to "fitbod/fitbod-export.csv",
        HistoryImportSource.STRONGLIFTS to "stronglifts/stronglifts-export.csv",
        HistoryImportSource.PLAIN_CSV to "plain/plain-workouts.csv",
    )

    /**
     * The names are stored with an import's background work, so an interrupted one can be
     * offered again after the process that ran it is gone. A rename strands that one request;
     * see the enum's own doc.
     */
    @Test
    fun `the source names are the ones a stored import request holds`() {
        assertEquals(
            setOf("STRONG", "HEVY", "FITNOTES", "JEFIT", "FITBOD", "STRONGLIFTS", "PLAIN_CSV"),
            HistoryImportSource.entries.map(HistoryImportSource::name).toSet(),
        )
    }

    @Test
    fun `every source has a sample export here`() {
        assertEquals(HistoryImportSource.entries.toSet(), ownExport.keys)
    }

    @Test
    fun `each source's parser reads its own app's export`() {
        ownExport.forEach { (source, path) ->
            val result = ImporterFixtures.read(
                HistoryParsers.SUPPORTED.parserFor(source),
                ImporterFixtures.resource(path),
            )
            assertTrue("$source did not read $path: $result", result is ParseResult.Parsed)
        }
    }

    @Test
    fun `no source's parser reads another app's export`() {
        HistoryImportSource.entries.forEach { source ->
            val parser = HistoryParsers.SUPPORTED.parserFor(source)
            (ownExport - source).forEach { (other, path) ->
                assertEquals(
                    "$source read $other's $path",
                    ParseResult.NotRecognised,
                    ImporterFixtures.read(parser, ImporterFixtures.resource(path)),
                )
            }
        }
    }

    @Test
    fun `a request and every run carry what they were built with`() {
        val request = HistoryImportRequest(HistoryImportSource.HEVY, "content://documents/1")
        val report = HistoryImportReport(
            workoutsImported = 1,
            setsImported = 2,
            exercisesMatched = 1,
            exercisesCreated = 0,
            templatesImported = 0,
            measurementsImported = 0,
            skipped = ImportSkips(),
            recordsSet = 1,
            xpEarned = 10,
            achievementsUnlocked = 0,
            questsCompleted = 0,
            collectiblesFound = 0,
        )

        assertEquals(HistoryImportSource.HEVY, request.source)
        assertEquals("content://documents/1", request.document)
        assertEquals(request, HistoryImportRun.Running(request, HistoryImportProgress.Reading).request)
        assertEquals(
            HistoryImportProgress.Reading,
            HistoryImportRun.Running(request, HistoryImportProgress.Reading).progress,
        )
        assertEquals(report, HistoryImportRun.Completed(request, report).report)
        assertEquals(request, HistoryImportRun.Completed(request, report).request)
        assertEquals(
            HistoryImportError.NotRecognised,
            HistoryImportRun.Failed(request, HistoryImportError.NotRecognised).error,
        )
        assertEquals(request, HistoryImportRun.Failed(request, HistoryImportError.NotRecognised).request)
        assertEquals(request, HistoryImportRun.Interrupted(request).request)
        assertEquals(request, HistoryImportRun.Cancelled(request).request)
    }
}
