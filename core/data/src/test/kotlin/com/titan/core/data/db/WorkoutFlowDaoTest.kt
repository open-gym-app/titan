package com.titan.core.data.db

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `sessions are listed newest-created first`() =
        runBlocking {
            val dao = db.workoutSessionDao()
            dao.insert(session(createdAt = 100))
            dao.insert(session(createdAt = 300))
            dao.insert(session(createdAt = 200))
            val order = dao.getAll().first().map { it.createdAt }
            assertEquals(listOf(300L, 200L, 100L), order)
        }

    @Test
    fun `a session keeps its name, and an unnamed one stays null`() =
        runBlocking {
            val dao = db.workoutSessionDao()
            val named = dao.insert(session(name = "Push Day - Upper", createdAt = 200))
            val unnamed = dao.insert(session(createdAt = 100))

            assertEquals("Push Day - Upper", dao.getById(named).first()?.name)
            // Null rather than "": the column is what the date-derived fallback keys off,
            // so a store that normalised it would make an unnamed session unrecoverable.
            assertNull(requireNotNull(dao.getById(unnamed).first()).name)
        }

    @Test
    fun `getActive returns the most recently active in-progress session`() =
        runBlocking {
            val dao = db.workoutSessionDao()
            dao.insert(session(isInProgress = false, lastActiveAt = 500))
            dao.insert(session(isInProgress = true, lastActiveAt = 100))
            dao.insert(session(isInProgress = true, lastActiveAt = 400))
            assertEquals(400L, dao.getActive().first()?.lastActiveAt)
        }

    @Test
    fun `getActive is null when nothing is in progress`() =
        runBlocking {
            val dao = db.workoutSessionDao()
            dao.insert(session(isInProgress = false, lastActiveAt = 10))
            assertNull(dao.getActive().first())
        }

    @Test
    fun `countFinishedBy counts only sessions that were closed out`() =
        runBlocking {
            val dao = db.workoutSessionDao()
            dao.insert(session(isInProgress = false, completedAt = 100))
            dao.insert(session(isInProgress = false, completedAt = 200))
            // Still running: not a session shown up for yet.
            dao.insert(session(isInProgress = true))
            // Out of progress with nothing stamped on it — the half-written state the
            // analytics queries also refuse, so the two cannot disagree about what counts.
            dao.insert(session(isInProgress = false, completedAt = null))

            assertEquals(2, dao.countFinishedBy(Long.MAX_VALUE))
        }

    @Test
    fun `countFinishedBy leaves out sessions imported from Health Connect`() =
        runBlocking {
            // The figure the session-count achievements are judged against, and an import earns
            // nothing — counted here, it would be paid for at the next workout's cascade.
            val dao = db.workoutSessionDao()
            dao.insert(session(isInProgress = false, completedAt = 100))
            dao.insert(session(isInProgress = false, completedAt = 200, healthConnectId = "run-1"))

            assertEquals(1, dao.countFinishedBy(Long.MAX_VALUE))
        }

    @Test
    fun `countFinishedBy leaves out what finished after the moment, and keeps what finished at it`() =
        runBlocking {
            val dao = db.workoutSessionDao()
            dao.insert(session(isInProgress = false, completedAt = 100))
            dao.insert(session(isInProgress = false, completedAt = 200))
            dao.insert(session(isInProgress = false, completedAt = 300))

            assertEquals(0, dao.countFinishedBy(99))
            assertEquals(2, dao.countFinishedBy(200))
        }

    @Test
    fun `countFinishedBy is zero on a fresh install`() =
        runBlocking {
            assertEquals(0, db.workoutSessionDao().countFinishedBy(Long.MAX_VALUE))
        }

    @Test
    fun `session pagination pages through newest-first`() =
        runBlocking {
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
    fun `session exercises are ordered by position`() =
        runBlocking {
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
    fun `duplicate position within a session is rejected`() =
        runBlocking {
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
    fun `a reorder that swaps two positions goes through in one write`() =
        runBlocking {
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
    fun `a whole layout can be reversed, groups and all`() =
        runBlocking {
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
    fun `a row the reorder did not touch keeps the timestamp it had`() =
        runBlocking {
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
    fun `a superset grouping survives the round trip, and a standalone exercise stays null`() =
        runBlocking {
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
    fun `deleting a session cascades to its session_exercise rows`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionId = db.workoutSessionDao().insert(session())
            db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
            db.workoutSessionDao().delete(sessionId)
            assertTrue(db.sessionExerciseDao().getBySession(sessionId).first().isEmpty())
        }

    @Test
    fun `deleting an exercise referenced by a session is restricted`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionId = db.workoutSessionDao().insert(session())
            db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
            val threw = runCatching { db.exerciseDao().delete(exerciseId) }.isFailure
            assertTrue("FK RESTRICT should block deleting an in-use exercise", threw)
        }

    @Test
    fun `deleting a template nulls the referencing session's template_id`() =
        runBlocking {
            val templateId = db.workoutTemplateDao().insert(template())
            val sessionId = db.workoutSessionDao().insert(session(templateId = templateId))
            db.workoutTemplateDao().delete(templateId)
            // Non-null-asserted: SET_NULL has to leave the session standing, so a safe call
            // here would let a rule that deleted the row pass as "template_id is null".
            assertNull(requireNotNull(db.workoutSessionDao().getById(sessionId).first()).templateId)
        }

    // --- workout_set ------------------------------------------------------

    @Test
    fun `sets are ordered by set_number`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            val dao = db.workoutSetDao()
            dao.insert(workoutSet(sessionExerciseId, setNumber = 3))
            dao.insert(workoutSet(sessionExerciseId, setNumber = 1))
            dao.insert(workoutSet(sessionExerciseId, setNumber = 2))
            val numbers = dao.getBySessionExercise(sessionExerciseId).first().map { it.setNumber }
            assertEquals(listOf(1, 2, 3), numbers)
        }

    @Test
    fun `history join returns only completed sets for the exercise newest-first`() =
        runBlocking {
            val (exerciseId, sessionExerciseId) = seedSessionExercise()
            val dao = db.workoutSetDao()
            dao.insert(workoutSet(sessionExerciseId, setNumber = 1, isCompleted = true, completedAt = 100))
            dao.insert(workoutSet(sessionExerciseId, setNumber = 2, isCompleted = false, completedAt = 200))
            dao.insert(workoutSet(sessionExerciseId, setNumber = 3, isCompleted = true, completedAt = 300))

            val history = dao.getHistoryByExercise(exerciseId).first()
            assertEquals(listOf(300L, 100L), history.map { it.completedAt })
        }

    @Test
    fun `history pagination filters and orders like the flow variant`() =
        runBlocking {
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
    fun `delete removes a set by id`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            val dao = db.workoutSetDao()
            val setId = dao.insert(workoutSet(sessionExerciseId, setNumber = 1))
            dao.delete(setId)
            assertTrue(dao.getBySessionExercise(sessionExerciseId).first().isEmpty())
        }

    @Test
    fun `updateAll renumbers a table in one write`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            val dao = db.workoutSetDao()
            val first = dao.insert(workoutSet(sessionExerciseId, setNumber = 1))
            val third = dao.insert(workoutSet(sessionExerciseId, setNumber = 3))
            val fourth = dao.insert(workoutSet(sessionExerciseId, setNumber = 4))

            // The gap a deletion leaves, closed up: the survivors move down onto numbers two of
            // them are still holding at the moment the write starts. `workout_set` carries no
            // unique index on `(session_exercise_id, set_number)`, which is exactly why this does
            // not need `session_exercise`'s parking dance — see `WorkoutSetDao.updateAll`.
            val stored = dao.getBySessionExercise(sessionExerciseId).first().associateBy { it.id }
            dao.updateAll(
                listOf(
                    requireNotNull(stored[third]).copy(setNumber = 2),
                    requireNotNull(stored[fourth]).copy(setNumber = 3),
                ),
            )

            val table = dao.getBySessionExercise(sessionExerciseId).first()
            assertEquals(listOf(1, 2, 3), table.map { it.setNumber })
            assertEquals(listOf(first, third, fourth), table.map { it.id })
        }

    @Test
    fun `deleting a session_exercise cascades to its sets`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
            db.sessionExerciseDao().delete(sessionExerciseId)
            assertTrue(db.workoutSetDao().getBySessionExercise(sessionExerciseId).first().isEmpty())
        }

    // --- personal_record --------------------------------------------------

    @Test
    fun `records for an exercise are returned newest-achieved first`() =
        runBlocking {
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
    fun `record pagination filters and orders like the flow variant`() =
        runBlocking {
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
    fun `getLatestByTypeOnce returns the most recent record of that type`() =
        runBlocking {
            val (exerciseId, sessionExerciseId) = seedSessionExercise()
            val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
            val dao = db.personalRecordDao()
            dao.insert(personalRecord(exerciseId, setId, prType = "MAX_WEIGHT", achievedAt = 100))
            dao.insert(personalRecord(exerciseId, setId, prType = "MAX_WEIGHT", achievedAt = 400))
            dao.insert(personalRecord(exerciseId, setId, prType = "MAX_REPS", achievedAt = 900))
            assertEquals(400L, dao.getLatestByTypeOnce(exerciseId, "MAX_WEIGHT")?.achievedAt)
        }

    @Test
    fun `insertIfBetter writes only when isBetter accepts the current row`() =
        runBlocking {
            val (exerciseId, sessionExerciseId) = seedSessionExercise()
            val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
            val dao = db.personalRecordDao()
            dao.insert(weightRecord(exerciseId, setId, achievedAt = 100, weight = 92_000_000))

            val rejected = dao.insertIfBetter(
                exerciseId = exerciseId,
                prType = "MAX_WEIGHT",
                record = weightRecord(exerciseId, setId, achievedAt = 200, weight = 90_000_000),
            ) { current -> current == null || 90_000_000L > (current.weight ?: 0L) }
            assertEquals(null, rejected)
            assertEquals(1, dao.getByExercise(exerciseId).first().size)

            val accepted = dao.insertIfBetter(
                exerciseId = exerciseId,
                prType = "MAX_WEIGHT",
                record = weightRecord(exerciseId, setId, achievedAt = 300, weight = 95_000_000),
            ) { current -> current == null || 95_000_000L > (current.weight ?: 0L) }
            assertNotNull(accepted)
            assertEquals(2, dao.getByExercise(exerciseId).first().size)
        }

    @Test
    fun `insertIfBetter serializes concurrent calls so only one write survives`() =
        runBlocking {
            val (exerciseId, sessionExerciseId) = seedSessionExercise()
            val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
            val dao = db.personalRecordDao()

            // Every attempt only ever sees "nothing on file yet" as an improvement, so if the
            // read of the current row and the insert were not one transaction, two callers could
            // each read the empty table before either writes and both would insert.
            coroutineScope {
                repeat(20) { attempt ->
                    launch(Dispatchers.IO) {
                        dao.insertIfBetter(
                            exerciseId = exerciseId,
                            prType = "MAX_WEIGHT",
                            record = weightRecord(exerciseId, setId, achievedAt = attempt.toLong(), weight = 1L),
                        ) { current -> current == null }
                    }
                }
            }

            assertEquals(1, dao.getByExercise(exerciseId).first().size)
        }

    private fun weightRecord(
        exerciseId: Long,
        setId: Long,
        achievedAt: Long,
        weight: Long,
    ) = personalRecord(exerciseId, setId, prType = "MAX_WEIGHT", achievedAt = achievedAt).copy(weight = weight)

    @Test
    fun `deleting the source set cascades to its personal records`() =
        runBlocking {
            val (exerciseId, sessionExerciseId) = seedSessionExercise()
            val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
            db.personalRecordDao().insert(personalRecord(exerciseId, setId))
            db.workoutSetDao().delete(setId)
            assertTrue(db.personalRecordDao().getByExercise(exerciseId).first().isEmpty())
        }

    /** Inserts an exercise + session + session_exercise, returning (exerciseId, sessionExerciseId). */
    private fun seedSessionExercise(): Pair<Long, Long> =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionId = db.workoutSessionDao().insert(session())
            val sessionExerciseId = db.sessionExerciseDao().insert(
                sessionExercise(sessionId, exerciseId, position = 0),
            )
            exerciseId to sessionExerciseId
        }
}
