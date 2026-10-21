package com.titan.core.data.db

import com.titan.core.data.db.entity.WorkoutSetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WorkoutSetDao.getRecentSetsByExercise`: the last few times one exercise was performed, for
 * the next-load engine (#M7-15).
 *
 * The per-exercise twin of [PreviousPerformanceDaoTest], and held to the same rules — which
 * slot is "last", which sets are work, what a never-performed exercise comes back as — plus
 * the two of its own: it reaches a bounded number of slots back, and it orders them newest
 * first so the caller can group without sorting.
 */
class RecentPerformanceDaoTest : RoomTestBase() {

    @Test
    fun `the newest slots come first, each in set order, up to the limit`() =
        runBlocking {
            val bench = db.exerciseDao().insert(exercise(name = "Bench press"))
            val today = db.workoutSessionDao().insert(session(createdAt = 400))

            logged(session = 100, exerciseId = bench, weights = listOf(60_000_000L))
            logged(session = 200, exerciseId = bench, weights = listOf(70_000_000L, 72_500_000L))
            logged(session = 300, exerciseId = bench, weights = listOf(80_000_000L, 82_500_000L))

            val rows = read(bench, today, limit = 2)

            // Two slots, not three — and the 60 kg session is the one left out.
            assertEquals(
                listOf(80_000_000L, 82_500_000L, 70_000_000L, 72_500_000L),
                rows.map(WorkoutSetEntity::weight),
            )
        }

    @Test
    fun `the session being logged is never its own history`() =
        runBlocking {
            val curl = db.exerciseDao().insert(exercise(name = "Barbell curl"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))
            val slot = db.sessionExerciseDao().insert(sessionExercise(today, curl, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 30_000_000, reps = 10))

            assertTrue(read(curl, today, limit = 2).isEmpty())
        }

    @Test
    fun `warm-ups are left out, and a slot of nothing but warm-ups takes no place`() =
        runBlocking {
            val squat = db.exerciseDao().insert(exercise(name = "Back squat"))
            val today = db.workoutSessionDao().insert(session(createdAt = 500))

            logged(session = 100, exerciseId = squat, weights = listOf(90_000_000L))
            val last = db.workoutSessionDao().insert(session(createdAt = 200))
            val slot = db.sessionExerciseDao().insert(sessionExercise(last, squat, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 40_000_000, setType = "WARMUP"))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 2, weight = 100_000_000, reps = 5))
            val warmedUpOnly = db.workoutSessionDao().insert(session(createdAt = 300))
            val skipped = db.sessionExerciseDao().insert(sessionExercise(warmedUpOnly, squat, position = 0))
            db.workoutSetDao().insert(workoutSet(skipped, setNumber = 1, weight = 40_000_000, setType = "WARMUP"))

            val rows = read(squat, today, limit = 2)

            // The warm-up-only session does not occupy one of the two places and then
            // contribute nothing: the two performances are the 100 kg one and the 90 kg one.
            assertEquals(listOf(100_000_000L, 90_000_000L), rows.map(WorkoutSetEntity::weight))
        }

    @Test
    fun `sets that were never completed do not count`() =
        runBlocking {
            val press = db.exerciseDao().insert(exercise(name = "Overhead press"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            val last = db.workoutSessionDao().insert(session(createdAt = 200))
            val slot = db.sessionExerciseDao().insert(sessionExercise(last, press, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 50_000_000, reps = 5))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 2, weight = 55_000_000, isCompleted = false))

            assertEquals(listOf(50_000_000L), read(press, today, limit = 2).map(WorkoutSetEntity::weight))
        }

    @Test
    fun `an exercise held twice by one session reads both slots, later one first`() =
        runBlocking {
            val deadlift = db.exerciseDao().insert(exercise(name = "Deadlift"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            val last = db.workoutSessionDao().insert(session(createdAt = 200))
            val first = db.sessionExerciseDao().insert(sessionExercise(last, deadlift, position = 0))
            val second = db.sessionExerciseDao().insert(sessionExercise(last, deadlift, position = 3))
            db.workoutSetDao().insert(workoutSet(first, setNumber = 1, weight = 140_000_000, reps = 3))
            db.workoutSetDao().insert(workoutSet(second, setNumber = 1, weight = 100_000_000, reps = 8))

            val rows = read(deadlift, today, limit = 2)

            // Slots, not sessions — the same grain the `PREV` column reads at, so the engine's
            // "last time" is the 100 kg slot that column shows.
            assertEquals(listOf(100_000_000L, 140_000_000L), rows.map(WorkoutSetEntity::weight))
            assertEquals(listOf(second, first), rows.map(WorkoutSetEntity::sessionExerciseId))
        }

    @Test
    fun `another exercise's history is not this one's`() =
        runBlocking {
            val bench = db.exerciseDao().insert(exercise(name = "Bench press"))
            val fly = db.exerciseDao().insert(exercise(name = "Cable fly"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            logged(session = 200, exerciseId = bench, weights = listOf(80_000_000L))

            assertTrue(read(fly, today, limit = 2).isEmpty())
        }

    @Test
    fun `a session still in progress is history once it is not the one being logged`() =
        runBlocking {
            // A workout paused and never closed out is still the last time the lift was done.
            val bench = db.exerciseDao().insert(exercise(name = "Bench press"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))
            val paused = db.workoutSessionDao().insert(session(isInProgress = true, createdAt = 200))
            val slot = db.sessionExerciseDao().insert(sessionExercise(paused, bench, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 80_000_000, reps = 5))

            assertEquals(listOf(80_000_000L), read(bench, today, limit = 2).map(WorkoutSetEntity::weight))
        }

    @Test
    fun `with no session to leave out, the one in progress is history too`() =
        runBlocking {
            // The exercise detail's read: nothing is being logged from there, so every slot
            // counts — including a workout still open, which from that page is simply the
            // most recent time the lift was done.
            val bench = db.exerciseDao().insert(exercise(name = "Bench press"))
            logged(session = 100, exerciseId = bench, weights = listOf(75_000_000))
            val open = db.workoutSessionDao().insert(session(isInProgress = true, createdAt = 200))
            val slot = db.sessionExerciseDao().insert(sessionExercise(open, bench, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 80_000_000, reps = 5))

            assertEquals(
                listOf(80_000_000L, 75_000_000L),
                read(bench, sessionId = null, limit = 2).map(WorkoutSetEntity::weight),
            )
        }

    private suspend fun read(
        exerciseId: Long,
        sessionId: Long?,
        limit: Int,
    ): List<WorkoutSetEntity> =
        db.workoutSetDao().getRecentSetsByExercise(
            exerciseId = exerciseId,
            sessionId = sessionId,
            limit = limit,
        ).first()

    /** A finished session that performed [exerciseId] for [weights], one completed set of five each. */
    private suspend fun logged(
        session: Long,
        exerciseId: Long,
        weights: List<Long>,
    ) {
        val sessionId = db.workoutSessionDao().insert(session(isInProgress = false, createdAt = session))
        val slot = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
        weights.forEachIndexed { index, weight ->
            db.workoutSetDao().insert(workoutSet(slot, setNumber = index + 1, weight = weight, reps = 5))
        }
    }
}
