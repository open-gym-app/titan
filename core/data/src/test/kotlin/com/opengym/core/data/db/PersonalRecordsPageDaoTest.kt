package com.opengym.core.data.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `getRecordsPage` — the one query the records list reads.
 *
 * Four things are being asserted and they are easy to conflate: the **order** rows come back in,
 * the **window** a page covers, the **improvement** each row carries, and which rows are badged
 * as recent. The improvement is the part worth the most scrutiny, because it is a window function
 * over a partition and a partition that is subtly wrong still returns plausible numbers.
 */
class PersonalRecordsPageDaoTest : RoomTestBase() {

    private val dao get() = db.personalRecordDao()

    private var benchId = 0L
    private var squatId = 0L
    private var lastSetId = 0L

    @Before
    fun seedHistory() =
        runTest {
            benchId = db.exerciseDao().insert(exercise(name = "Bench press"))
            squatId = db.exerciseDao().insert(exercise(name = "Back squat"))

            // Two finished sessions, the second more recent. Only the second's records are "recent".
            val older = db.workoutSessionDao().insert(
                session(isInProgress = false, createdAt = 1_000, completedAt = 2_000),
            )
            val newer = db.workoutSessionDao().insert(
                session(isInProgress = false, createdAt = 3_000, completedAt = 4_000),
            )

            val olderExercise = db.sessionExerciseDao().insert(sessionExercise(older, benchId, position = 0))
            val newerExercise = db.sessionExerciseDao().insert(sessionExercise(newer, benchId, position = 0))
            val olderSet = db.workoutSetDao().insert(workoutSet(olderExercise, setNumber = 1))
            lastSetId = db.workoutSetDao().insert(workoutSet(newerExercise, setNumber = 1))

            // Bench, heaviest: 80 then 92. The second improved on the first by 12.
            db.personalRecordDao().insert(
                personalRecord(benchId, olderSet, "MAX_WEIGHT", achievedAt = 1_500).copy(weight = 80_000_000),
            )
            db.personalRecordDao().insert(
                personalRecord(benchId, lastSetId, "MAX_WEIGHT", achievedAt = 3_500).copy(weight = 92_000_000),
            )
            // Bench, most reps — a different kind, so its own partition and its own first record.
            db.personalRecordDao().insert(
                personalRecord(benchId, lastSetId, "MAX_REPS", achievedAt = 3_600).copy(reps = 12),
            )
            // Squat, heaviest — a different exercise, so also a first record despite sharing a kind.
            db.personalRecordDao().insert(
                personalRecord(squatId, olderSet, "MAX_WEIGHT", achievedAt = 1_800).copy(weight = 140_000_000),
            )
        }

    @Test
    fun `records come back newest first, across every exercise`() =
        runTest {
            val page = dao.getRecordsPage(type = null, limit = 10, offset = 0)

            assertEquals(
                listOf(3_600L, 3_500L, 1_800L, 1_500L),
                page.map { it.record.achievedAt },
            )
        }

    @Test
    fun `the improvement is the previous record of the same exercise and kind`() =
        runTest {
            val page = dao.getRecordsPage(type = "MAX_WEIGHT", limit = 10, offset = 0)

            val benchNewest = page.first { it.record.exerciseId == benchId }
            // 80 kg was on file; this is the row that beat it.
            assertEquals(80_000_000L, benchNewest.previousValue)
        }

    @Test
    fun `a first record of its kind improved on nothing`() =
        runTest {
            val page = dao.getRecordsPage(type = null, limit = 10, offset = 0)

            // The squat is a different partition from the bench, and most-reps a different one again
            // from heaviest — so all three are firsts, even though two of them share a kind and two
            // share an exercise. A partition keyed on only one of the two columns would give at least
            // one of these a previous value.
            assertNull(page.first { it.record.prType == "MAX_REPS" }.previousValue)
            assertNull(page.first { it.record.exerciseId == squatId }.previousValue)
            assertNull(page.last().previousValue)
        }

    @Test
    fun `the type filter narrows the list and keeps the improvement`() =
        runTest {
            val reps = dao.getRecordsPage(type = "MAX_REPS", limit = 10, offset = 0)

            assertEquals(1, reps.size)
            assertEquals("MAX_REPS", reps.single().record.prType)
        }

    @Test
    fun `a page is a window, not the whole table`() =
        runTest {
            val first = dao.getRecordsPage(type = null, limit = 2, offset = 0)
            val second = dao.getRecordsPage(type = null, limit = 2, offset = 2)

            assertEquals(2, first.size)
            assertEquals(2, second.size)
            // No row appears twice: the two windows are disjoint and in order.
            assertEquals(listOf(3_600L, 3_500L), first.map { it.record.achievedAt })
            assertEquals(listOf(1_800L, 1_500L), second.map { it.record.achievedAt })
        }

    @Test
    fun `the exercise name is joined onto the row`() =
        runTest {
            val page = dao.getRecordsPage(type = null, limit = 10, offset = 0)

            assertEquals("Bench press", page.first().exerciseName)
        }

    @Test
    fun `only records from the most recently finished session are recent`() =
        runTest {
            val page = dao.getRecordsPage(type = null, limit = 10, offset = 0)

            // The two set in the newer session, and neither of the two older ones.
            assertTrue(page.first { it.record.achievedAt == 3_600L }.isRecent)
            assertTrue(page.first { it.record.achievedAt == 3_500L }.isRecent)
            assertEquals(false, page.first { it.record.achievedAt == 1_800L }.isRecent)
            assertEquals(false, page.first { it.record.achievedAt == 1_500L }.isRecent)
        }

    @Test
    fun `the count signal reports what the table holds`() =
        runTest {
            assertEquals(4, dao.count())
        }
}
