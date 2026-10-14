package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.dumpEverything
import com.titan.core.data.db.dumpTable
import com.titan.core.data.db.seedEveryTable
import com.titan.core.domain.backup.BackupConstraintException
import com.titan.core.domain.backup.model.AchievementRow
import com.titan.core.domain.backup.model.BackupRow
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.BodyMeasurementRow
import com.titan.core.domain.backup.model.CollectedItemRow
import com.titan.core.domain.backup.model.ExerciseMuscleGroupRow
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExerciseTagRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.GymRow
import com.titan.core.domain.backup.model.MarketplaceTemplateRow
import com.titan.core.domain.backup.model.MuscleGroupRow
import com.titan.core.domain.backup.model.PersonalRecordRow
import com.titan.core.domain.backup.model.PreferencesPayload
import com.titan.core.domain.backup.model.QuestCompletionRow
import com.titan.core.domain.backup.model.SessionExerciseRow
import com.titan.core.domain.backup.model.SessionLootClaimRow
import com.titan.core.domain.backup.model.SessionXpClaimRow
import com.titan.core.domain.backup.model.TagRow
import com.titan.core.domain.backup.model.TemplateExerciseRow
import com.titan.core.domain.backup.model.TemplateSetRow
import com.titan.core.domain.backup.model.UserProgressRow
import com.titan.core.domain.backup.model.WorkoutSessionRow
import com.titan.core.domain.backup.model.WorkoutSetRow
import com.titan.core.domain.backup.model.WorkoutTemplateRow
import com.titan.core.domain.backup.toPayload
import com.titan.core.domain.model.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository on its own: paging, counting, and the transaction.
 *
 * `BackupRoundTripTest` drives this through the two use cases, which is where the acceptance
 * criteria live. What is left here is the half a use case cannot reach — the *backstop*. The
 * importer validates a document before the wipe, so in the app nothing invalid ever gets this
 * far; these tests hand it one directly, which is the only way to exercise the path that has to
 * hold when the validator has missed something.
 */
class BackupRepositoryImplTest : RoomTestBase() {

    private val repository by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }

    @Test
    fun `every table is counted, empty ones included`() =
        runBlocking {
            db.seedEveryTable()

            val counts = repository.rowCounts()

            assertEquals(BackupTable.entries.toSet(), counts.keys)
            assertEquals(2L, counts.getValue(BackupTable.MUSCLE_GROUP))
            assertEquals(1L, counts.getValue(BackupTable.SESSION_XP_CLAIM))
        }

    @Test
    fun `an empty database counts zero for every table`() =
        runBlocking {
            val counts = repository.rowCounts()

            assertEquals(BackupTable.entries.toSet(), counts.keys)
            assertTrue(counts.values.all { it == 0L })
        }

    /** Ordered by primary key, so an export and the import that reads it back agree row for row. */
    @Test
    fun `pages come back in primary-key order and do not overlap`() =
        runBlocking {
            db.seedEveryTable()

            val first = repository.readPage(BackupTable.MUSCLE_GROUP, offset = 0, limit = 1)
            val second = repository.readPage(BackupTable.MUSCLE_GROUP, offset = 1, limit = 1)
            val past = repository.readPage(BackupTable.MUSCLE_GROUP, offset = 2, limit = 1)

            assertEquals(listOf(1L), first.map { (it as MuscleGroupRow).id })
            assertEquals(listOf(2L), second.map { (it as MuscleGroupRow).id })
            assertTrue("a page past the end should be empty", past.isEmpty())
        }

    /** A composite-key table has no id to order by, so it orders by the key — and must be stable. */
    @Test
    fun `a table with no surrogate id still pages stably`() =
        runBlocking {
            db.seedEveryTable()

            val whole = repository.readPage(BackupTable.EXERCISE_MUSCLE_GROUP, offset = 0, limit = 10)
            val paged = (0 until whole.size).flatMap { repository.readPage(BackupTable.EXERCISE_MUSCLE_GROUP, it, 1) }

            assertEquals(whole, paged)
        }

    @Test
    fun `every table can be read through the same call`() =
        runBlocking {
            db.seedEveryTable()

            BackupTable.entries.forEach { table ->
                val page = repository.readPage(table, offset = 0, limit = 100)
                assertEquals("${table.tableName} did not read back", db.dumpTable(table).size, page.size)
            }
        }

    /**
     * The backstop. A document the validator would have refused, handed straight to the
     * repository: SQLite refuses the row, the transaction rolls back, and what comes out is a
     * typed exception rather than an `SQLiteConstraintException` the domain cannot name.
     */
    @Test
    fun `a row the database refuses rolls the whole transaction back`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val failure = runCatching {
                repository.replaceAll(
                    documentWithADanglingSet(),
                ) { _, _ -> }
            }.exceptionOrNull()

            assertTrue("expected a typed constraint failure, got $failure", failure is BackupConstraintException)
            assertEquals("the transaction did not roll back", before, db.dumpEverything())
        }

    /** Rolled back means rolled back: the wipe that opened the transaction is undone too. */
    @Test
    fun `a failed import leaves the rows that were there before it`() =
        runBlocking {
            db.seedEveryTable()

            runCatching { repository.replaceAll(documentWithADanglingSet()) { _, _ -> } }

            assertEquals(2, db.dumpTable(BackupTable.WORKOUT_SESSION).size)
            assertEquals(2, db.dumpTable(BackupTable.WORKOUT_SET).size)
        }

    /**
     * The delete order is the insert order reversed, so emptying `exercise` happens after the
     * `session_exercise` rows that `RESTRICT` it. Getting it wrong is a constraint failure, not
     * a silent one — which is what makes this worth asserting over a real database.
     */
    @Test
    fun `the wipe empties a fully populated database without tripping a restrict`() =
        runBlocking {
            db.seedEveryTable()

            repository.replaceAll(emptyDocument()) { _, _ -> }

            BackupTable.entries.forEach { assertEquals("${it.tableName} is not empty", 0, db.dumpTable(it).size) }
        }

    /** Progress is reported per table, as a running total, with every table named. */
    @Test
    fun `progress names every table and counts up`() =
        runBlocking {
            db.seedEveryTable()
            val document = documentOfEverythingInTheDatabase()
            val reported = mutableListOf<Pair<BackupTable, Long>>()

            repository.replaceAll(document) { table, written -> reported += table to written }

            val totals = reported.map { it.second }
            assertEquals(BackupTable.entries, reported.map { it.first }.distinct())
            assertEquals(document.rowCount, totals.last())
            assertEquals("the running total went backwards", totals.sorted(), totals)
        }

    /**
     * Replace, at the repository level: rows the document does not carry are gone.
     *
     * The tag dropped is the *unlinked* one — `exercise_tag` points at the other — because
     * dropping a referenced row is not a smaller document, it is an invalid one, and SQLite
     * refuses it. That refusal is the subject of the two rollback tests above rather than of
     * this one.
     */
    @Test
    fun `replacing removes rows the document does not carry`() =
        runBlocking {
            db.seedEveryTable()
            val document = documentOfEverythingInTheDatabase()
                .let { it.copy(tag = it.tag.filter { row -> row.name == "PUSH" }) }

            repository.replaceAll(document) { _, _ -> }

            assertEquals(listOf("PUSH"), db.dumpTable(BackupTable.TAG).map { it["name"] })
        }

    /** Reads every table out of the database as a document, which is what an export would write. */
    private suspend fun documentOfEverythingInTheDatabase(): ExportDocument {
        var document = emptyDocument()
        BackupTable.entries.forEach { table ->
            val rows = repository.readPage(table, offset = 0, limit = PAGE)
            document = document.withRows(table, rows)
        }
        return document
    }

    /**
     * The whole document, with a `workout_set` naming a `session_exercise` that is not in it.
     *
     * Built by hand rather than through `BackupValidator`, because the validator is precisely
     * what would refuse it: the point is to reach the insert.
     */
    private suspend fun documentWithADanglingSet(): ExportDocument {
        val document = documentOfEverythingInTheDatabase()
        return document.copy(
            workoutSet = document.workoutSet + WorkoutSetRow(
                id = 999,
                sessionExerciseId = 9_999,
                setNumber = 1,
                completedAt = 0,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
    }

    private fun emptyDocument() =
        ExportDocument(exportedAt = 0, appVersion = "1.0-debug", preferences = defaultPreferences)

    private companion object {

        const val PAGE = 1_000

        val defaultPreferences: PreferencesPayload = UserPreferences().toPayload()
    }
}

/**
 * [ExportDocument] with [table]'s rows replaced by [rows].
 *
 * A test-only inverse of `ExportDocument.rows`, so a document can be assembled from paged reads
 * without nineteen `copy` calls at the call site. Its `when` is exhaustive over [BackupTable]
 * for the same reason that one is: a table added and not handled here does not compile.
 */
private fun ExportDocument.withRows(
    table: BackupTable,
    rows: List<BackupRow>,
): ExportDocument =
    when (table) {
        BackupTable.MUSCLE_GROUP -> copy(muscleGroup = rows.filterIsInstance<MuscleGroupRow>())
        BackupTable.TAG -> copy(tag = rows.filterIsInstance<TagRow>())
        BackupTable.EXERCISE -> copy(exercise = rows.filterIsInstance<ExerciseRow>())
        BackupTable.EXERCISE_MUSCLE_GROUP -> copy(exerciseMuscleGroup = rows.filterIsInstance<ExerciseMuscleGroupRow>())
        BackupTable.EXERCISE_TAG -> copy(exerciseTag = rows.filterIsInstance<ExerciseTagRow>())
        BackupTable.WORKOUT_TEMPLATE -> copy(workoutTemplate = rows.filterIsInstance<WorkoutTemplateRow>())
        BackupTable.TEMPLATE_EXERCISE -> copy(templateExercise = rows.filterIsInstance<TemplateExerciseRow>())
        BackupTable.TEMPLATE_SET -> copy(templateSet = rows.filterIsInstance<TemplateSetRow>())
        BackupTable.WORKOUT_SESSION -> copy(workoutSession = rows.filterIsInstance<WorkoutSessionRow>())
        BackupTable.SESSION_EXERCISE -> copy(sessionExercise = rows.filterIsInstance<SessionExerciseRow>())
        BackupTable.WORKOUT_SET -> copy(workoutSet = rows.filterIsInstance<WorkoutSetRow>())
        BackupTable.PERSONAL_RECORD -> copy(personalRecord = rows.filterIsInstance<PersonalRecordRow>())
        BackupTable.BODY_MEASUREMENT -> copy(bodyMeasurement = rows.filterIsInstance<BodyMeasurementRow>())
        BackupTable.SESSION_XP_CLAIM -> copy(sessionXpClaim = rows.filterIsInstance<SessionXpClaimRow>())
        BackupTable.SESSION_LOOT_CLAIM -> copy(sessionLootClaim = rows.filterIsInstance<SessionLootClaimRow>())
        BackupTable.USER_PROGRESS -> copy(userProgress = rows.filterIsInstance<UserProgressRow>())
        BackupTable.ACHIEVEMENT -> copy(achievement = rows.filterIsInstance<AchievementRow>())
        BackupTable.QUEST_COMPLETION -> copy(questCompletion = rows.filterIsInstance<QuestCompletionRow>())
        BackupTable.COLLECTED_ITEM -> copy(collectedItem = rows.filterIsInstance<CollectedItemRow>())
        BackupTable.GYM -> copy(gym = rows.filterIsInstance<GymRow>())
        BackupTable.MARKETPLACE_TEMPLATE -> copy(marketplaceTemplate = rows.filterIsInstance<MarketplaceTemplateRow>())
    }
