package com.opengym.core.domain.usecase

import com.opengym.core.domain.backup.BackupConstraintException
import com.opengym.core.domain.backup.asSource
import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.backup.model.ImportProgress
import com.opengym.core.domain.backup.model.WorkoutSetRow
import com.opengym.core.domain.backup.sampleDocument
import com.opengym.core.domain.backup.samplePreferences
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.repository.FakeInProgressSessionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What an import does, and — for every way it can be refused — what it leaves behind.
 *
 * The contract this leans on hardest is that a refusal means *nothing happened*. Three of the
 * four [ImportError]s are decided before the database is touched at all, so the assertions
 * below are that the repository was never called rather than that it rolled back: a rollback
 * that has to be trusted is a rollback nobody has tested.
 */
class ImportUseCaseTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
    private val timeline = mutableListOf<String>()
    private val backup = FakeBackupRepository(emptyDocument()).also { it.timeline = timeline }
    private val preferences = RecordingUserPreferencesRepository(timeline)
    private val draft = FakeInProgressSessionRepository(ActiveSession(sessionId = 7, startedAt = 1_000))
    private val sessionState = FakeSessionStateRepository(SessionState.Active(sessionId = 7, startedAt = 1_000))

    @Test
    fun `a valid document is written to the database whole`() =
        runTest {
            val document = sampleDocument()

            val emissions = importer().invoke(encode(document)).toList()

            assertEquals(document, backup.replaced)
            assertEquals(ImportProgress.Complete(rowsWritten = document.rowCount), emissions.last())
        }

    @Test
    fun `validating is the first thing reported`() =
        runTest {
            val emissions = importer().invoke(encode(sampleDocument())).toList()

            assertEquals(ImportProgress.Validating, emissions.first())
        }

    @Test
    fun `every table is named on the way through`() =
        runTest {
            val emissions = importer().invoke(encode(sampleDocument())).toList()

            val named = emissions.filterIsInstance<ImportProgress.Writing>().map { it.table }.toSet()
            assertEquals(BackupTable.entries.toSet(), named)
        }

    @Test
    fun `progress counts up to the document's own row count`() =
        runTest {
            val document = sampleDocument()

            val emissions = importer().invoke(encode(document)).toList()

            val writing = emissions.filterIsInstance<ImportProgress.Writing>()
            assertTrue(writing.all { it.rowsTotal == document.rowCount })
            assertEquals(document.rowCount, writing.last().rowsWritten)
        }

    /** The live session and its draft both name ids from before the wipe, so both must go. */
    @Test
    fun `the live session and its draft are forgotten`() =
        runTest {
            importer().invoke(encode(sampleDocument())).toList()

            assertEquals(SessionState.Idle, sessionState.current)
            assertEquals(1, draft.clearCount)
        }

    /**
     * Settings last, deliberately: the unit system changes how every stored weight is *read*,
     * so applying it after the rows means the app re-formats once, at the end.
     */
    @Test
    fun `the settings are applied after the rows`() =
        runTest {
            importer().invoke(encode(sampleDocument())).toList()

            assertEquals(FakeBackupRepository.REPLACE_ALL, timeline.first())
            assertTrue(
                "the settings should follow the rows: $timeline",
                timeline.drop(1).all { it != FakeBackupRepository.REPLACE_ALL },
            )
        }

    /**
     * Reset before the eight setters. Replace means replace: a setting added to the store later
     * and forgotten in the importer must show up as a default, never as the old device's value.
     */
    @Test
    fun `the settings are reset before they are written`() =
        runTest {
            importer().invoke(encode(sampleDocument())).toList()

            val writes = timeline.drop(1)
            assertEquals("clear", writes.first())
        }

    @Test
    fun `the settings restored are the document's`() =
        runTest {
            importer().invoke(encode(sampleDocument())).toList()

            val restored = preferences.observe().first()
            assertEquals(UnitSystem.IMPERIAL, restored.unitSystem)
            assertEquals(ThemePreference.DARK, restored.theme)
            assertEquals(120.seconds, restored.defaultRestDuration)
            assertEquals(45_000_000L, restored.barWeight)
            assertEquals(TrainingGoal.HYPERTROPHY, restored.trainingGoal)
            assertEquals(OneRepMaxFormula.BRZYCKI, restored.oneRepMaxFormula)
            assertTrue(restored.onboardingCompleted)
            assertEquals(listOf(20_000_000L, 10_000_000L), restored.plateInventory?.stock?.map { it.weight })
        }

    /** Null means "never chosen a rack", and writing it is what forgets the target device's. */
    @Test
    fun `an unchosen rack in the document unchooses the device's`() =
        runTest {
            preferences.setPlateInventory(PlateInventory.METRIC)
            val document = sampleDocument(preferences = samplePreferences().copy(plateInventory = null))

            importer().invoke(encode(document)).toList()

            assertNull(preferences.observe().first().plateInventory)
        }

    @Test
    fun `a document from a newer version is refused, and nothing is touched`() =
        runTest {
            val newer = text(sampleDocument()).replace("\"schema_version\":1", "\"schema_version\":2")

            val emissions = importer().invoke(newer.asSource()).toList()

            assertEquals(
                ImportProgress.Failed(ImportError.UnsupportedSchemaVersion(found = 2, supported = 1)),
                emissions.last(),
            )
            assertUntouched()
        }

    @Test
    fun `a document that is not JSON is refused, and nothing is touched`() =
        runTest {
            val emissions = importer().invoke("this is not a backup".asSource()).toList()

            assertTrue(failure(emissions) is ImportError.MalformedJson)
            assertUntouched()
        }

    /**
     * The acceptance criterion's own case: a `workout_set` naming a `session_exercise` that is
     * not in the file. Caught before the wipe, so "rolled back completely" is "never started".
     */
    @Test
    fun `a dangling foreign key is refused, and nothing is touched`() =
        runTest {
            val document = sampleDocument().let {
                it.copy(
                    workoutSet = it.workoutSet + WorkoutSetRow(
                        id = 99,
                        sessionExerciseId = 999,
                        setNumber = 1,
                        completedAt = 0,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                )
            }

            val emissions = importer().invoke(encode(document)).toList()

            val error = failure(emissions)
            assertTrue(error is ImportError.ConstraintViolation)
            assertTrue(
                (error as ImportError.ConstraintViolation).reason.contains("workout_set.session_exercise_id 999"),
            )
            assertUntouched()
        }

    /** The backstop: a constraint the validator does not model still arrives typed. */
    @Test
    fun `a constraint the database refuses is reported as a constraint violation`() =
        runTest {
            backup.replaceFailure = BackupConstraintException("UNIQUE constraint failed: tag.name")

            val emissions = importer().invoke(encode(sampleDocument())).toList()

            assertEquals(
                ImportProgress.Failed(ImportError.ConstraintViolation("UNIQUE constraint failed: tag.name")),
                emissions.last(),
            )
        }

    /**
     * The one failure that leaves work done — the rows are committed and the settings are not —
     * which is why it has to be reported rather than swallowed.
     */
    @Test
    fun `a settings write that cannot land is reported as an IO failure`() =
        runTest {
            preferences.failure = IOException("read-only filesystem")

            val emissions = importer().invoke(encode(sampleDocument())).toList()

            assertEquals(
                ImportProgress.Failed(ImportError.IoFailure("read-only filesystem")),
                emissions.last(),
            )
            assertEquals(sampleDocument(), backup.replaced)
        }

    /**
     * The other half of [ImportError.IoFailure]: a file that cannot be read at all.
     *
     * Reported like the settings failure and unlike it in what it leaves behind — the document
     * is read before anything is cleared, so this one really is "nothing happened".
     */
    @Test
    fun `a document that cannot be read is reported as an IO failure`() =
        runTest {
            val emissions = importer().invoke { throw IOException("permission denied") }.toList()

            assertEquals(ImportProgress.Failed(ImportError.IoFailure("permission denied")), emissions.last())
            assertUntouched()
        }

    /** A bug is not a corrupt file: only the two documented failures are caught. */
    @Test
    fun `an unexpected failure is not reported as a bad backup`() =
        runTest {
            backup.replaceFailure = IllegalStateException("a bug")

            val failure = runCatching { importer().invoke(encode(sampleDocument())).toList() }.exceptionOrNull()

            assertTrue("expected the bug to propagate, got $failure", failure is IllegalStateException)
        }

    /** A fresh install exports an empty document, and it has to restore onto anything. */
    @Test
    fun `an empty document empties the database`() =
        runTest {
            val emissions = importer().invoke(encode(emptyDocument())).toList()

            assertEquals(ImportProgress.Complete(rowsWritten = 0), emissions.last())
            assertEquals(emptyDocument(), backup.replaced)
        }

    private fun importer() =
        ImportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            inProgressSessionRepository = draft,
            sessionStateRepository = sessionState,
        )

    /** [document] as the use case takes it: bytes to be opened, never a string it holds. */
    private fun encode(document: ExportDocument) = text(document).asSource()

    private fun text(document: ExportDocument) = json.encodeToString(ExportDocument.serializer(), document)

    private fun failure(emissions: List<ImportProgress>): ImportError =
        (emissions.last() as ImportProgress.Failed).error

    /** Nothing written, nothing cleared: what every refusal before the wipe has to leave behind. */
    private fun assertUntouched() {
        assertNull("the database should not have been touched", backup.replaced)
        assertEquals("the draft should not have been cleared", 0, draft.clearCount)
        assertEquals(
            "the session should not have been ended",
            SessionState.Active(sessionId = 7, startedAt = 1_000),
            sessionState.current,
        )
        assertTrue("the settings should not have been touched: $timeline", timeline.isEmpty())
    }

    private fun emptyDocument() =
        ExportDocument(exportedAt = 1, appVersion = "1.0-debug", preferences = samplePreferences())
}

/**
 * [FakeUserPreferencesRepository] with a call log and a failure switch.
 *
 * The log is what lets a test say *when* the settings were written relative to the rows, which
 * is the only externally visible form of "restored last". The failure switch stands in for the
 * store that cannot be written — the one import failure that leaves the database changed.
 */
private class RecordingUserPreferencesRepository(private val timeline: MutableList<String>) :
    UserPreferencesRepository {

    private val delegate = FakeUserPreferencesRepository()

    /** Thrown by every setter instead of writing, when set. */
    var failure: IOException? = null

    override fun observe(): Flow<UserPreferences> = delegate.observe()

    override suspend fun setUnitSystem(unitSystem: UnitSystem) =
        record("setUnitSystem") { delegate.setUnitSystem(unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = record("setTheme") { delegate.setTheme(theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) =
        record("setDefaultRestDuration") { delegate.setDefaultRestDuration(duration) }

    override suspend fun setBarWeight(barWeight: Long) = record("setBarWeight") { delegate.setBarWeight(barWeight) }

    override suspend fun setPlateInventory(inventory: PlateInventory?) =
        record("setPlateInventory") { delegate.setPlateInventory(inventory) }

    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) = record("setPlatePairs") { delegate.setPlatePairs(weight, pairs) }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) =
        record("setTrainingGoal") { delegate.setTrainingGoal(trainingGoal) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) =
        record("setOneRepMaxFormula") { delegate.setOneRepMaxFormula(formula) }

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        record("setOnboardingCompleted") { delegate.setOnboardingCompleted(completed) }

    override suspend fun clear() = record("clear") { delegate.clear() }

    private suspend fun record(
        name: String,
        write: suspend () -> Unit,
    ) {
        timeline += name
        failure?.let { throw it }
        write()
    }
}
