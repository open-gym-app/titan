package com.titan.core.domain.usecase

import com.titan.core.domain.backup.BackupConstraintException
import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.backup.asSource
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.ImportProgress
import com.titan.core.domain.backup.model.WorkoutSetRow
import com.titan.core.domain.backup.sampleDocument
import com.titan.core.domain.backup.samplePreferences
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.TemplateDraft
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.repository.FakeInProgressSessionRepository
import com.titan.core.domain.repository.FakeTemplateDraftRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What an import does, and — for every way it can be refused — what it leaves behind.
 *
 * The contract this leans on hardest is that a refusal means *nothing happened*. Five of the
 * six [ImportError]s are decided before the database is touched at all, so the assertions
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
    private val templateDraft = FakeTemplateDraftRepository(TemplateDraft(templateId = 3, name = "Push"))
    private val sessionState = FakeSessionStateRepository(SessionState.Active(sessionId = 7, startedAt = 1_000))
    private val reminders = TimelineReminderScheduler(timeline)

    /** This class's own locks, so a test holding one blocks only the importer it built. */
    private val backupLock = BackupLock()
    private val sessionLock = SessionLifecycleLock()

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
     * Reset before the setters. Replace means replace: a setting added to the store later
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
            assertEquals(FitnessLevel.ADVANCED, restored.fitnessLevel)
            assertEquals(RepRange(6, 10), restored.customRepRange)
            assertEquals(1_250_000L, restored.customIncrement)
            assertEquals(OneRepMaxFormula.BRZYCKI, restored.oneRepMaxFormula)
            assertFalse(restored.warmupSuggestions)
            assertEquals(3, restored.weeklyWorkoutGoal)
            assertFalse(restored.autoOpenRestTimer)
            assertTrue(restored.onboardingCompleted)
            assertEquals(listOf(20_000_000L, 10_000_000L), restored.plateInventory?.stock?.map { it.weight })
        }

    /** A lifter who turned the cards off keeps them off on the device the file is restored to. */
    @Test
    fun `warm-up suggestions switched off in the document stay off`() =
        runTest {
            val document = sampleDocument(preferences = samplePreferences().copy(warmupSuggestions = false))

            importer().invoke(encode(document)).toList()

            assertFalse(preferences.observe().first().warmupSuggestions)
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

    /**
     * A v1 file from before #M7-11 has no `fitness_level` at all — not a null, an absent key.
     * It is still a v1 file, and the rule in docs/export-schema.md is that a nullable addition
     * does not bump the version: it restores as the device it was, one never asked the question.
     */
    @Test
    fun `a document written before the fitness level existed imports as a beginner`() =
        runTest {
            preferences.setFitnessLevel(FitnessLevel.ADVANCED)
            val older = text(sampleDocument()).replace(",\"fitness_level\":\"ADVANCED\"", "")
            check("fitness_level" !in older) { "the key must be absent, not null, for this to be a v1 file" }

            val outcome = importer().invoke(older.asSource()).toList()

            assertTrue(outcome.last() is ImportProgress.Complete)
            assertEquals(FitnessLevel.BEGINNER, preferences.observe().first().fitnessLevel)
        }

    /**
     * The CUSTOM goal's range and step (#M7-15) took the same shape as the fitness level: a
     * file from before them has none of the three keys, and restores as a device that never
     * typed a range — the default one — with the step left to follow the rack.
     */
    @Test
    fun `a document written before the custom range existed imports with the default range`() =
        runTest {
            preferences.setCustomRepRange(RepRange(5, 5))
            preferences.setCustomIncrement(2_000_000)
            val older = text(sampleDocument())
                .replace(",\"custom_rep_range_min\":6", "")
                .replace(",\"custom_rep_range_max\":10", "")
                .replace(",\"custom_increment_milligrams\":1250000", "")
            check("custom_" !in older) { "the keys must be absent, not null, for this to be a v1 file" }

            val outcome = importer().invoke(older.asSource()).toList()

            assertTrue(outcome.last() is ImportProgress.Complete)
            val restored = preferences.observe().first()
            assertEquals(RepRange.DEFAULT_CUSTOM, restored.customRepRange)
            assertNull(restored.customIncrement)
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
     * which is why it is its own error rather than the "could not be read" a refused file gets
     * (#257). A screen told `IoFailure` here would say nothing was restored, over a database
     * that now holds the whole file.
     */
    @Test
    fun `a settings write that cannot land is reported as settings not restored`() =
        runTest {
            preferences.failure = IOException("read-only filesystem")
            val document = sampleDocument()

            val emissions = importer().invoke(encode(document)).toList()

            assertEquals(
                ImportProgress.Failed(
                    ImportError.SettingsNotRestored(rowsRestored = document.rowCount, reason = "read-only filesystem"),
                ),
                emissions.last(),
            )
            assertEquals(document, backup.replaced)
        }

    /**
     * After the commit the sentence is the same whatever was thrown, so an unexpected failure
     * is not left to escape and be reported by the screen as a file it could not read.
     */
    @Test
    fun `any failure writing the settings after the commit is settings not restored`() =
        runTest {
            preferences.failure = IllegalStateException("the store's own bug")

            val emissions = importer().invoke(encode(sampleDocument())).toList()

            assertTrue(
                "expected SettingsNotRestored, was ${emissions.last()}",
                (emissions.last() as ImportProgress.Failed).error is ImportError.SettingsNotRestored,
            )
        }

    /**
     * Restoring the settings starts from `clear()`, which turns the workout reminder off — it is
     * device state and not in the file — so the alarms behind it have to come down after (#257).
     */
    @Test
    fun `the reminder alarms are re-synced after the settings`() =
        runTest {
            importer().invoke(encode(sampleDocument())).toList()

            assertEquals(1, reminders.syncs)
            assertEquals("the sync should follow every settings write: $timeline", SYNC, timeline.last())
        }

    /** The reset may have landed before the setter that failed, so the alarms follow it anyway. */
    @Test
    fun `the reminder alarms are re-synced even when a setting cannot be written`() =
        runTest {
            preferences.failure = IOException("read-only filesystem")

            importer().invoke(encode(sampleDocument())).toList()

            assertEquals(1, reminders.syncs)
        }

    @Test
    fun `a refused document re-syncs nothing`() =
        runTest {
            importer().invoke(encode(sampleDocument().copy(schemaVersion = 99))).toList()

            assertEquals(0, reminders.syncs)
        }

    /**
     * The order the class doc argues for, pinned: by the time the transaction opens, the live
     * session is idle and both drafts are gone — and when the transaction then rolls back, they
     * stay gone (#257). The drafts are files and cannot join the transaction; this is the
     * window that loses a place in a workout rather than the one that points a draft into a
     * replaced database.
     */
    @Test
    fun `the live state is cleared before the transaction and stays cleared if it rolls back`() =
        runTest {
            var seenAtReplace: Triple<SessionState, Int, Int>? = null
            backup.onReplaceAll = {
                val state = sessionState.current
                seenAtReplace = Triple(state, draft.clearCount, templateDraft.clearCount)
            }
            backup.replaceFailure = BackupConstraintException("UNIQUE constraint failed: tag.name")

            importer().invoke(encode(sampleDocument())).toList()

            assertEquals(Triple(SessionState.Idle, 1, 1), seenAtReplace)
            assertNull("the rows should have rolled back", backup.replaced)
            assertEquals(1, draft.clearCount)
            assertEquals(SessionState.Idle, sessionState.current)
        }

    /**
     * A session transition holds [SessionLifecycleLock] across its read and its write. The
     * restore takes it too, so a start or a finish cannot interleave with the reset of the
     * session state and the replace of the rows it named (#257).
     */
    @Test
    fun `the session is reset under the lifecycle lock`() =
        runTest {
            val transitionRunning = CompletableDeferred<Unit>()
            val transitionMayEnd = CompletableDeferred<Unit>()
            val transition = launch {
                sessionLock.withTransition {
                    transitionRunning.complete(Unit)
                    transitionMayEnd.await()
                }
            }
            transitionRunning.await()

            val restore = async { importer().invoke(encode(sampleDocument())).toList() }
            yieldUntilIdle()

            assertEquals(
                "the restore must wait for the transition before touching the session",
                SessionState.Active(sessionId = 7, startedAt = 1_000),
                sessionState.current,
            )
            assertNull(backup.replaced)

            transitionMayEnd.complete(Unit)
            transition.join()

            assertEquals(ImportProgress.Complete(sampleDocument().rowCount), restore.await().last())
            assertEquals(SessionState.Idle, sessionState.current)
        }

    /**
     * An export or a delete-all holding [BackupLock] keeps the restore out until it is done —
     * rows *and* settings, since an export between the two would carry the new history in the
     * old units (#257).
     */
    @Test
    fun `a restore waits for whatever holds the backup lock`() =
        runTest {
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch {
                backupLock.exclusively {
                    holding.complete(Unit)
                    release.await()
                }
            }
            holding.await()

            val restore = async { importer().invoke(encode(sampleDocument())).toList() }
            yieldUntilIdle()

            assertNull("nothing may be written while the lock is held", backup.replaced)
            assertTrue("no setting may be written either: $timeline", timeline.isEmpty())

            release.complete(Unit)
            holder.join()

            assertEquals(ImportProgress.Complete(sampleDocument().rowCount), restore.await().last())
        }

    /** And the other way round: the lock is held until the settings are in. */
    @Test
    fun `the backup lock is held until the settings are written`() =
        runTest {
            var lockedDuringSettings: Boolean? = null
            preferences.onClear = { lockedDuringSettings = backupLock.isHeld() }

            importer().invoke(encode(sampleDocument())).toList()

            assertEquals(true, lockedDuringSettings)
            assertFalse("the lock should be released at the end", backupLock.isHeld())
        }

    /**
     * Once the rows are committed the settings follow them, even if whoever collected the
     * restore has gone — a screen closed mid-restore must not leave the history restored and
     * the settings half-written with nobody told (#257).
     */
    @Test
    fun `the settings are applied even if the restore is cancelled once the rows are in`() =
        runTest {
            val settingsStarted = CompletableDeferred<Unit>()
            val settingsMayContinue = CompletableDeferred<Unit>()
            preferences.onClear = {
                settingsStarted.complete(Unit)
                settingsMayContinue.await()
            }

            val restore = launch { importer().invoke(encode(sampleDocument())).toList() }
            settingsStarted.await()
            restore.cancel()
            settingsMayContinue.complete(Unit)
            restore.join()

            assertEquals(sampleDocument(), backup.replaced)
            assertTrue("every setter should have run: $timeline", "setOnboardingCompleted" in timeline)
            assertEquals(1, reminders.syncs)
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
            templateDraftRepository = templateDraft,
            sessionStateRepository = sessionState,
            reminderScheduler = reminders,
            backupLock = backupLock,
            sessionLock = sessionLock,
        )

    /** Lets every coroutine the test has started run as far as it can before the next assertion. */
    private suspend fun yieldUntilIdle() = repeat(YIELDS) { yield() }

    /** Whether something holds this lock now: true if a zero-length attempt to take it fails. */
    private suspend fun BackupLock.isHeld(): Boolean = withTimeoutOrNull(1) { exclusively { } } == null

    /** [document] as the use case takes it: bytes to be opened, never a string it holds. */
    private fun encode(document: ExportDocument) = text(document).asSource()

    private fun text(document: ExportDocument) = json.encodeToString(ExportDocument.serializer(), document)

    private fun failure(emissions: List<ImportProgress>): ImportError =
        (emissions.last() as ImportProgress.Failed).error

    /** Nothing written, nothing cleared: what every refusal before the wipe has to leave behind. */
    private fun assertUntouched() {
        assertNull("the database should not have been touched", backup.replaced)
        assertEquals("the draft should not have been cleared", 0, draft.clearCount)
        assertEquals("the template draft should not have been cleared", 0, templateDraft.clearCount)
        assertEquals(
            "the session should not have been ended",
            SessionState.Active(sessionId = 7, startedAt = 1_000),
            sessionState.current,
        )
        assertTrue("the settings should not have been touched: $timeline", timeline.isEmpty())
    }

    private fun emptyDocument() =
        ExportDocument(exportedAt = 1, appVersion = "1.0-debug", preferences = samplePreferences())

    private companion object {

        /** More than the handful of suspension points between an import starting and its first write. */
        const val YIELDS = 50
    }
}

/** The [timeline] entry [TimelineReminderScheduler] writes. */
private const val SYNC = "sync"

/** A [ReminderScheduler] that counts syncs and writes them into the shared call log. */
private class TimelineReminderScheduler(private val timeline: MutableList<String>) : ReminderScheduler {
    var syncs = 0
        private set

    override suspend fun sync() {
        syncs++
        timeline += SYNC
    }

    override fun isExact(): Boolean = true
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
    var failure: Exception? = null

    /** Run as [clear] begins — the first settings write of a restore. */
    var onClear: (suspend () -> Unit)? = null

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

    override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) =
        record("setFitnessLevel") { delegate.setFitnessLevel(fitnessLevel) }

    override suspend fun setCustomRepRange(range: RepRange) =
        record("setCustomRepRange") { delegate.setCustomRepRange(range) }

    override suspend fun setCustomIncrement(milligrams: Long?) =
        record("setCustomIncrement") { delegate.setCustomIncrement(milligrams) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) =
        record("setOneRepMaxFormula") { delegate.setOneRepMaxFormula(formula) }

    override suspend fun setWarmupSuggestions(enabled: Boolean) =
        record("setWarmupSuggestions") { delegate.setWarmupSuggestions(enabled) }

    override suspend fun setAutoOpenRestTimer(enabled: Boolean) =
        record("setAutoOpenRestTimer") { delegate.setAutoOpenRestTimer(enabled) }

    override suspend fun setWeeklyWorkoutGoal(goal: Int) =
        record("setWeeklyWorkoutGoal") { delegate.setWeeklyWorkoutGoal(goal) }

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        record("setOnboardingCompleted") { delegate.setOnboardingCompleted(completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = record("setNotificationEnabled") { delegate.setNotificationEnabled(kind, enabled) }

    override suspend fun setNotificationPermissionRequested() =
        record("setNotificationPermissionRequested") { delegate.setNotificationPermissionRequested() }

    override suspend fun setReminderDay(
        day: DayOfWeek,
        enabled: Boolean,
    ) = record("setReminderDay") { delegate.setReminderDay(day, enabled) }

    override suspend fun setReminderTime(time: LocalTime) = record("setReminderTime") { delegate.setReminderTime(time) }

    override suspend fun clear() {
        onClear?.invoke()
        record("clear") { delegate.clear() }
    }

    private suspend fun record(
        name: String,
        write: suspend () -> Unit,
    ) {
        timeline += name
        failure?.let { throw it }
        write()
    }
}
