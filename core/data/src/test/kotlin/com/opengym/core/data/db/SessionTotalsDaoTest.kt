package com.opengym.core.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `WorkoutSetDao.getTotalsBySession` — the one grouped query behind the history list.
 *
 * Worth its own class rather than a case in [AnalyticsDaoTest]: that one covers the
 * progression aggregates, which bucket by *time*, and this one buckets by session across the
 * join into `session_exercise`. The two get the same arithmetic wrong in different ways.
 *
 * The sums are written out as literals with the working in a comment, for the reason the
 * analytics test does it: recomputing them with the query's own formula would assert that the
 * formula equals itself.
 */
class SessionTotalsDaoTest : RoomTestBase() {

    private companion object {
        /** Kilograms in the canonical storage unit, milligrams. */
        fun kg(value: Long): Long = value * 1_000_000L
    }

    @Test
    fun `totals are summed per session across all of its exercises`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise(name = "Bench press"))
            val otherExerciseId = db.exerciseDao().insert(exercise(name = "Squat"))

            val sessionId = db.workoutSessionDao().insert(session(name = "Push", isInProgress = false))
            val benchId = db.sessionExerciseDao()
                .insert(sessionExercise(sessionId, exerciseId, position = 0))
            val squatId = db.sessionExerciseDao()
                .insert(sessionExercise(sessionId, otherExerciseId, position = 1))

            // 100kg x 10 = 1_000_000_000, 80kg x 5 = 400_000_000, 60kg x 8 = 480_000_000
            logSet(benchId, 1, reps = 10, weight = kg(100))
            logSet(benchId, 2, reps = 5, weight = kg(80))
            logSet(squatId, 1, reps = 8, weight = kg(60))

            val totals = db.workoutSetDao().getTotalsBySession().first()

            assertEquals(1, totals.size)
            assertEquals(sessionId, totals.single().sessionId)
            assertEquals(3, totals.single().totalSets)
            assertEquals(1_880_000_000L, totals.single().totalVolume)
        }

    @Test
    fun `two sessions get a row each and do not bleed into one another`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise(name = "Bench press"))

            val first = db.workoutSessionDao().insert(session(name = "Monday", isInProgress = false))
            val second = db.workoutSessionDao().insert(session(name = "Thursday", isInProgress = false))
            val firstExercise = db.sessionExerciseDao()
                .insert(sessionExercise(first, exerciseId, position = 0))
            val secondExercise = db.sessionExerciseDao()
                .insert(sessionExercise(second, exerciseId, position = 0))

            logSet(firstExercise, 1, reps = 10, weight = kg(100))
            logSet(secondExercise, 1, reps = 5, weight = kg(100))
            logSet(secondExercise, 2, reps = 5, weight = kg(100))

            val totals = db.workoutSetDao().getTotalsBySession().first().associateBy { it.sessionId }

            assertEquals(1, totals.getValue(first).totalSets)
            assertEquals(1_000_000_000L, totals.getValue(first).totalVolume)
            assertEquals(2, totals.getValue(second).totalSets)
            assertEquals(1_000_000_000L, totals.getValue(second).totalVolume)
        }

    @Test
    fun `sets that were never completed count for nothing`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise(name = "Bench press"))
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = false))
            val sessionExerciseId = db.sessionExerciseDao()
                .insert(sessionExercise(sessionId, exerciseId, position = 0))

            logSet(sessionExerciseId, 1, reps = 10, weight = kg(100))
            // A planned set the lifter never performed. Counting it would inflate both figures
            // against the recap, which filters the same way in memory.
            logSet(sessionExerciseId, 2, reps = 10, weight = kg(100), isCompleted = false)

            val totals = db.workoutSetDao().getTotalsBySession().first().single()

            assertEquals(1, totals.totalSets)
            assertEquals(1_000_000_000L, totals.totalVolume)
        }

    @Test
    fun `a set with no weight or reps counts as a set and no volume`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise(name = "Treadmill"))
            val sessionId = db.workoutSessionDao().insert(session(name = "Run", isInProgress = false))
            val sessionExerciseId = db.sessionExerciseDao()
                .insert(sessionExercise(sessionId, exerciseId, position = 0))

            logSet(sessionExerciseId, 1, reps = null, weight = null)

            val totals = db.workoutSetDao().getTotalsBySession().first().single()

            // A session of nothing but cardio is a real session with a real set count, and its
            // volume is genuinely zero — dropping it would lose the row from the history list.
            assertEquals(1, totals.totalSets)
            assertEquals(0L, totals.totalVolume)
        }

    @Test
    fun `a session with no completed set has no row at all`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise(name = "Bench press"))
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = false))
            val sessionExerciseId = db.sessionExerciseDao()
                .insert(sessionExercise(sessionId, exerciseId, position = 0))

            logSet(sessionExerciseId, 1, reps = 10, weight = kg(100), isCompleted = false)

            val totals = db.workoutSetDao().getTotalsBySession().first()

            // The absence is the contract: a caller drawing a list reads it as zeroes, and the
            // outer join that would manufacture them is a scan of every session to no end.
            assertNull(totals.firstOrNull { it.sessionId == sessionId })
        }

    @Test
    fun `the query re-emits when a set is logged`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise(name = "Bench press"))
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = false))
            val sessionExerciseId = db.sessionExerciseDao()
                .insert(sessionExercise(sessionId, exerciseId, position = 0))

            assertEquals(emptyList<Long>(), db.workoutSetDao().getTotalsBySession().first())

            logSet(sessionExerciseId, 1, reps = 10, weight = kg(100))

            assertEquals(1, db.workoutSetDao().getTotalsBySession().first().single().totalSets)
        }

    private suspend fun logSet(
        sessionExerciseId: Long,
        setNumber: Int,
        reps: Int? = null,
        weight: Long? = null,
        isCompleted: Boolean = true,
    ) = db.workoutSetDao().insert(
        workoutSet(
            sessionExerciseId = sessionExerciseId,
            setNumber = setNumber,
            isCompleted = isCompleted,
            reps = reps,
            weight = weight,
        ),
    )
}
