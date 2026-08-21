package com.opengym.core.data.db

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the workout_session -> session_exercise -> workout_set -> personal_record
 * chain: ordering, filtered joins, pagination, and the foreign-key delete rules
 * declared on the entities (CASCADE / RESTRICT / SET_NULL).
 */
class WorkoutFlowDaoTest : RoomTestBase() {

    // --- workout_session --------------------------------------------------

    @Test
    fun `sessions are listed newest-created first`() = runBlocking {
        val dao = db.workoutSessionDao()
        dao.insert(session(createdAt = 100))
        dao.insert(session(createdAt = 300))
        dao.insert(session(createdAt = 200))
        val order = dao.getAll().first().map { it.createdAt }
        assertEquals(listOf(300L, 200L, 100L), order)
    }

    @Test
    fun `a session keeps its name, and an unnamed one stays null`() = runBlocking {
        val dao = db.workoutSessionDao()
        val named = dao.insert(session(name = "Push Day - Upper", createdAt = 200))
        val unnamed = dao.insert(session(createdAt = 100))

        assertEquals("Push Day - Upper", dao.getById(named).first()?.name)
        // Null rather than "": the column is what the date-derived fallback keys off,
        // so a store that normalised it would make an unnamed session unrecoverable.
        assertNull(requireNotNull(dao.getById(unnamed).first()).name)
    }

    @Test
    fun `getActive returns the most recently active in-progress session`() = runBlocking {
        val dao = db.workoutSessionDao()
        dao.insert(session(isInProgress = false, lastActiveAt = 500))
        dao.insert(session(isInProgress = true, lastActiveAt = 100))
        dao.insert(session(isInProgress = true, lastActiveAt = 400))
        assertEquals(400L, dao.getActive().first()?.lastActiveAt)
    }

    @Test
    fun `getActive is null when nothing is in progress`() = runBlocking {
        val dao = db.workoutSessionDao()
        dao.insert(session(isInProgress = false, lastActiveAt = 10))
        assertNull(dao.getActive().first())
    }

    @Test
    fun `session pagination pages through newest-first`() = runBlocking {
        val dao = db.workoutSessionDao()
        (1..5).forEach { dao.insert(session(createdAt = it.toLong())) }
        // initialLoadSize defaults to pageSize * 3; pin it so the first page is
        // exactly one page and we can assert page boundaries.
        val pager = TestPager(
            PagingConfig(pageSize = 2, initialLoadSize = 2),
            dao.getAllPaginated(),
        )

        val first = pager.refresh() as PagingSource.LoadResult.Page
        assertEquals(listOf(5L, 4L), first.data.map { it.createdAt })
        val second = pager.append() as PagingSource.LoadResult.Page
        assertEquals(listOf(3L, 2L), second.data.map { it.createdAt })
    }

    // --- session_exercise -------------------------------------------------

    @Test
    fun `session exercises are ordered by position`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        val dao = db.sessionExerciseDao()
        dao.insert(sessionExercise(sessionId, exerciseId, position = 2))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 0))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 1))
        val positions = dao.getBySession(sessionId).first().map { it.position }
        assertEquals(listOf(0, 1, 2), positions)
    }

    @Test
    fun `duplicate position within a session is rejected`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        val dao = db.sessionExerciseDao()
        dao.insert(sessionExercise(sessionId, exerciseId, position = 0))
        val threw = runCatching {
            dao.insert(sessionExercise(sessionId, exerciseId, position = 0))
        }.isFailure
        assertTrue("unique(session_id, position) should be enforced", threw)
    }

    @Test
    fun `a reorder that swaps two positions goes through in one write`() = runBlocking {
        // The write the unique index above exists to abort: whichever of the two is written
        // first lands on a position the other one still holds. `rewriteLayout` parks them out of
        // the way first, which is the whole reason it is not a loop over `update`.
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        val dao = db.sessionExerciseDao()
        dao.insert(sessionExercise(sessionId, exerciseId, position = 0))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 1))
        val stored = dao.getBySession(sessionId).first()

        dao.rewriteLayout(
            listOf(
                stored[0].copy(position = 1, updatedAt = 200),
                stored[1].copy(position = 0, updatedAt = 200),
            ),
        )

        assertEquals(
            listOf(stored[1].id, stored[0].id),
            dao.getBySession(sessionId).first().map { it.id },
        )
    }

    @Test
    fun `a whole layout can be reversed, groups and all`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        val dao = db.sessionExerciseDao()
        dao.insert(sessionExercise(sessionId, exerciseId, position = 0))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 1, supersetGroup = 0))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 2, supersetGroup = 0))
        val stored = dao.getBySession(sessionId).first()

        dao.rewriteLayout(
            stored.reversed().mapIndexed { index, row ->
                row.copy(position = index, supersetGroup = if (index < 2) 0 else null)
            },
        )

        val reordered = dao.getBySession(sessionId).first()
        assertEquals(listOf(stored[2].id, stored[1].id, stored[0].id), reordered.map { it.id })
        assertEquals(listOf(0, 0, null), reordered.map { it.supersetGroup })
    }

    @Test
    fun `a row the reorder did not touch keeps the timestamp it had`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        val dao = db.sessionExerciseDao()
        dao.insert(sessionExercise(sessionId, exerciseId, position = 0))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 1))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 2))
        val stored = dao.getBySession(sessionId).first()

        // Only the two that changed places are handed over, which is what the caller is
        // expected to work out — see `ReorderSessionExercisesUseCase`.
        dao.rewriteLayout(
            listOf(
                stored[0].copy(position = 1, updatedAt = 500),
                stored[1].copy(position = 0, updatedAt = 500),
            ),
        )

        assertEquals(
            listOf(500L, 500L, 0L),
            dao.getBySession(sessionId).first().map { it.updatedAt },
        )
    }

    @Test
    fun `a superset grouping survives the round trip, and a standalone exercise stays null`() = runBlocking {
        // NULL and a group id are different states — see Supersets — so this is a
        // column that has to come back exactly as it went in.
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        val dao = db.sessionExerciseDao()
        dao.insert(sessionExercise(sessionId, exerciseId, position = 0))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 1, supersetGroup = 0))
        dao.insert(sessionExercise(sessionId, exerciseId, position = 2, supersetGroup = 0))

        assertEquals(
            listOf(null, 0, 0),
            dao.getBySession(sessionId).first().map { it.supersetGroup },
        )
    }

    @Test
    fun `deleting a session cascades to its session_exercise rows`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
        db.workoutSessionDao().delete(sessionId)
        assertTrue(db.sessionExerciseDao().getBySession(sessionId).first().isEmpty())
    }

    @Test
    fun `deleting an exercise referenced by a session is restricted`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
        val threw = runCatching { db.exerciseDao().delete(exerciseId) }.isFailure
        assertTrue("FK RESTRICT should block deleting an in-use exercise", threw)
    }

    @Test
    fun `deleting a template nulls the referencing session's template_id`() = runBlocking {
        val templateId = db.workoutTemplateDao().insert(template())
        val sessionId = db.workoutSessionDao().insert(session(templateId = templateId))
        db.workoutTemplateDao().delete(templateId)
        // Non-null-asserted: SET_NULL has to leave the session standing, so a safe call
        // here would let a rule that deleted the row pass as "template_id is null".
        assertNull(requireNotNull(db.workoutSessionDao().getById(sessionId).first()).templateId)
    }

    // --- workout_set ------------------------------------------------------

    @Test
    fun `sets are ordered by set_number`() = runBlocking {
        val (_, sessionExerciseId) = seedSessionExercise()
        val dao = db.workoutSetDao()
        dao.insert(workoutSet(sessionExerciseId, setNumber = 3))
        dao.insert(workoutSet(sessionExerciseId, setNumber = 1))
        dao.insert(workoutSet(sessionExerciseId, setNumber = 2))
        val numbers = dao.getBySessionExercise(sessionExerciseId).first().map { it.setNumber }
        assertEquals(listOf(1, 2, 3), numbers)
    }

    @Test
    fun `history join returns only completed sets for the exercise newest-first`() = runBlocking {
        val (exerciseId, sessionExerciseId) = seedSessionExercise()
        val dao = db.workoutSetDao()
        dao.insert(workoutSet(sessionExerciseId, setNumber = 1, isCompleted = true, completedAt = 100))
        dao.insert(workoutSet(sessionExerciseId, setNumber = 2, isCompleted = false, completedAt = 200))
        dao.insert(workoutSet(sessionExerciseId, setNumber = 3, isCompleted = true, completedAt = 300))

        val history = dao.getHistoryByExercise(exerciseId).first()
        assertEquals(listOf(300L, 100L), history.map { it.completedAt })
    }

    @Test
    fun `history pagination filters and orders like the flow variant`() = runBlocking {
        val (exerciseId, sessionExerciseId) = seedSessionExercise()
        val dao = db.workoutSetDao()
        dao.insert(workoutSet(sessionExerciseId, setNumber = 1, isCompleted = true, completedAt = 10))
        dao.insert(workoutSet(sessionExerciseId, setNumber = 2, isCompleted = false, completedAt = 20))
        dao.insert(workoutSet(sessionExerciseId, setNumber = 3, isCompleted = true, completedAt = 30))

        val pager = TestPager(PagingConfig(pageSize = 10), dao.getHistoryByExercisePaginated(exerciseId))
        val page = pager.refresh() as PagingSource.LoadResult.Page
        assertEquals(listOf(30L, 10L), page.data.map { it.completedAt })
    }

    @Test
    fun `delete removes a set by id`() = runBlocking {
        val (_, sessionExerciseId) = seedSessionExercise()
        val dao = db.workoutSetDao()
        val setId = dao.insert(workoutSet(sessionExerciseId, setNumber = 1))
        dao.delete(setId)
        assertTrue(dao.getBySessionExercise(sessionExerciseId).first().isEmpty())
    }

    @Test
    fun `deleting a session_exercise cascades to its sets`() = runBlocking {
        val (_, sessionExerciseId) = seedSessionExercise()
        db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
        db.sessionExerciseDao().delete(sessionExerciseId)
        assertTrue(db.workoutSetDao().getBySessionExercise(sessionExerciseId).first().isEmpty())
    }

    // --- personal_record --------------------------------------------------

    @Test
    fun `records for an exercise are returned newest-achieved first`() = runBlocking {
        val (exerciseId, sessionExerciseId) = seedSessionExercise()
        val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
        val dao = db.personalRecordDao()
        dao.insert(personalRecord(exerciseId, setId, achievedAt = 100))
        dao.insert(personalRecord(exerciseId, setId, achievedAt = 300))
        dao.insert(personalRecord(exerciseId, setId, achievedAt = 200))
        val order = dao.getByExercise(exerciseId).first().map { it.achievedAt }
        assertEquals(listOf(300L, 200L, 100L), order)
    }

    @Test
    fun `record pagination filters and orders like the flow variant`() = runBlocking {
        val (exerciseId, sessionExerciseId) = seedSessionExercise()
        val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
        val dao = db.personalRecordDao()
        dao.insert(personalRecord(exerciseId, setId, achievedAt = 100))
        dao.insert(personalRecord(exerciseId, setId, achievedAt = 300))
        dao.insert(personalRecord(exerciseId, setId, achievedAt = 200))

        val pager = TestPager(PagingConfig(pageSize = 10), dao.getByExercisePaginated(exerciseId))
        val page = pager.refresh() as PagingSource.LoadResult.Page
        assertEquals(listOf(300L, 200L, 100L), page.data.map { it.achievedAt })
    }

    @Test
    fun `getLatestByType returns the most recent record of that type`() = runBlocking {
        val (exerciseId, sessionExerciseId) = seedSessionExercise()
        val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
        val dao = db.personalRecordDao()
        dao.insert(personalRecord(exerciseId, setId, prType = "MAX_WEIGHT", achievedAt = 100))
        dao.insert(personalRecord(exerciseId, setId, prType = "MAX_WEIGHT", achievedAt = 400))
        dao.insert(personalRecord(exerciseId, setId, prType = "MAX_REPS", achievedAt = 900))
        assertEquals(400L, dao.getLatestByType(exerciseId, "MAX_WEIGHT").first()?.achievedAt)
    }

    @Test
    fun `deleting the source set cascades to its personal records`() = runBlocking {
        val (exerciseId, sessionExerciseId) = seedSessionExercise()
        val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
        db.personalRecordDao().insert(personalRecord(exerciseId, setId))
        db.workoutSetDao().delete(setId)
        assertTrue(db.personalRecordDao().getByExercise(exerciseId).first().isEmpty())
    }

    /** Inserts an exercise + session + session_exercise, returning (exerciseId, sessionExerciseId). */
    private fun seedSessionExercise(): Pair<Long, Long> = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        val sessionExerciseId = db.sessionExerciseDao().insert(
            sessionExercise(sessionId, exerciseId, position = 0),
        )
        exerciseId to sessionExerciseId
    }
}
