package com.opengym.core.data.db

import com.opengym.core.data.db.projection.PreviousSetRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WorkoutSetDao.getPreviousSetsForSession`: what an exercise did the *last* time it was
 * performed, for a whole workout in one read.
 *
 * A file of its own rather than another block of `WorkoutFlowDaoTest`, because the query has
 * rules rather than a shape — which session counts as "last", which sets count as work, and
 * what an exercise nobody has ever performed comes back as. Every one of those is a decision
 * stated on `SessionRepository.getPreviousSets`, and this is where they are held to.
 */
class PreviousPerformanceDaoTest : RoomTestBase() {

    @Test
    fun `an exercise reads back the sets of the last session that performed it`() =
        runBlocking {
            val bench = db.exerciseDao().insert(exercise(name = "Bench press"))
            val today = session(createdAt = 300).let { db.workoutSessionDao().insert(it) }

            logged(session = 100, exerciseId = bench, weights = listOf(60_000_000L, 65_000_000L))
            logged(session = 200, exerciseId = bench, weights = listOf(80_000_000L, 85_000_000L))
            db.sessionExerciseDao().insert(sessionExercise(today, bench, position = 0))

            val previous = read(today)

            assertEquals(listOf(80_000_000L, 85_000_000L), previous.getValue(bench).map { it.set.weight })
        }

    @Test
    fun `warm-ups are left out of the reference`() =
        runBlocking {
            val squat = db.exerciseDao().insert(exercise(name = "Back squat"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            val last = db.workoutSessionDao().insert(session(createdAt = 200))
            val slot = db.sessionExerciseDao().insert(sessionExercise(last, squat, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 40_000_000, setType = "WARMUP"))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 2, weight = 100_000_000))
            db.sessionExerciseDao().insert(sessionExercise(today, squat, position = 0))

            val previous = read(today)

            // The working set alone, and it is the *first* thing the caller lines up against
            // today's first working set — the 40 kg warm-up would have answered that question low.
            assertEquals(listOf(100_000_000L), previous.getValue(squat).map { it.set.weight })
        }

    @Test
    fun `a session whose only sets were warm-ups is not the last time`() =
        runBlocking {
            val row = db.exerciseDao().insert(exercise(name = "Barbell row"))
            val today = db.workoutSessionDao().insert(session(createdAt = 400))

            logged(session = 100, exerciseId = row, weights = listOf(70_000_000L))
            val skipped = db.workoutSessionDao().insert(session(createdAt = 300))
            val slot = db.sessionExerciseDao().insert(sessionExercise(skipped, row, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 20_000_000, setType = "WARMUP"))
            db.sessionExerciseDao().insert(sessionExercise(today, row, position = 0))

            val previous = read(today)

            // Not "the last session, which turned out to be empty" — a session that never got past
            // the warm-up has nothing to compare against, so the reference falls through to the one
            // before it. Anything else shows a blank column for an exercise with history behind it.
            assertEquals(listOf(70_000_000L), previous.getValue(row).map { it.set.weight })
        }

    @Test
    fun `sets that were never completed do not count`() =
        runBlocking {
            val press = db.exerciseDao().insert(exercise(name = "Overhead press"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            val last = db.workoutSessionDao().insert(session(createdAt = 200))
            val slot = db.sessionExerciseDao().insert(sessionExercise(last, press, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 50_000_000))
            db.workoutSetDao().insert(
                workoutSet(slot, setNumber = 2, weight = 55_000_000, isCompleted = false),
            )
            db.sessionExerciseDao().insert(sessionExercise(today, press, position = 0))

            assertEquals(listOf(50_000_000L), read(today).getValue(press).map { it.set.weight })
        }

    @Test
    fun `the session being logged is never its own reference`() =
        runBlocking {
            val curl = db.exerciseDao().insert(exercise(name = "Barbell curl"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))
            val slot = db.sessionExerciseDao().insert(sessionExercise(today, curl, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 30_000_000))

            // Without the exclusion every row would report the set logged a minute ago on the row
            // above it, which is a column that tells the user what they can already see.
            assertTrue(read(today).isEmpty())
        }

    @Test
    fun `an exercise never performed has no entry at all`() =
        runBlocking {
            val fly = db.exerciseDao().insert(exercise(name = "Cable fly"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench press"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            logged(session = 200, exerciseId = bench, weights = listOf(80_000_000L))
            db.sessionExerciseDao().insert(sessionExercise(today, bench, position = 0))
            db.sessionExerciseDao().insert(sessionExercise(today, fly, position = 1))

            val previous = read(today)

            // Absent rather than empty: the screen draws a "first time" state off this, and an
            // empty list is what an exercise with a shorter history than today's table looks like.
            assertEquals(setOf(bench), previous.keys)
        }

    @Test
    fun `an exercise held twice by one session reads its later slot, not both merged`() =
        runBlocking {
            val deadlift = db.exerciseDao().insert(exercise(name = "Deadlift"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            val last = db.workoutSessionDao().insert(session(createdAt = 200))
            val first = db.sessionExerciseDao().insert(sessionExercise(last, deadlift, position = 0))
            val second = db.sessionExerciseDao().insert(sessionExercise(last, deadlift, position = 3))
            db.workoutSetDao().insert(workoutSet(first, setNumber = 1, weight = 140_000_000))
            db.workoutSetDao().insert(workoutSet(second, setNumber = 1, weight = 100_000_000))
            db.sessionExerciseDao().insert(sessionExercise(today, deadlift, position = 0))

            // One slot's table, not two tables numbered 1, 1. The later one, because that is what
            // "the last time you did this" means inside a session that did it twice.
            assertEquals(listOf(100_000_000L), read(today).getValue(deadlift).map { it.set.weight })
        }

    @Test
    fun `the whole workout comes back from one read`() =
        runBlocking {
            val today = db.workoutSessionDao().insert(session(createdAt = 1_000))
            val exercises = (1..10).map { db.exerciseDao().insert(exercise(name = "Exercise $it")) }

            exercises.forEachIndexed { index, exerciseId ->
                logged(
                    session = 100L + index,
                    exerciseId = exerciseId,
                    weights = listOf(50_000_000L + index),
                )
                db.sessionExerciseDao().insert(sessionExercise(today, exerciseId, position = index))
            }

            // The criterion is the shape of the read, not the values: this is one query whatever
            // the workout holds, where a lookup per exercise would be ten of them — and ten Flows
            // held open for the length of a session.
            val previous = read(today)

            assertEquals(exercises.toSet(), previous.keys)
            assertTrue(previous.values.all { it.size == 1 })
        }

    @Test
    fun `each exercise's sets come back in the order they were performed`() =
        runBlocking {
            val bench = db.exerciseDao().insert(exercise(name = "Bench press"))
            val today = db.workoutSessionDao().insert(session(createdAt = 300))

            val last = db.workoutSessionDao().insert(session(createdAt = 200))
            val slot = db.sessionExerciseDao().insert(sessionExercise(last, bench, position = 0))
            // Written out of order, because a set can be restored after a deletion and land last.
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 3, weight = 90_000_000))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 70_000_000))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 2, weight = 80_000_000))
            db.sessionExerciseDao().insert(sessionExercise(today, bench, position = 0))

            assertEquals(
                listOf(70_000_000L, 80_000_000L, 90_000_000L),
                read(today).getValue(bench).map { it.set.weight },
            )
        }

    /** The read under test, grouped the way the repository groups it. */
    private suspend fun read(sessionId: Long): Map<Long, List<PreviousSetRow>> =
        db.workoutSetDao().getPreviousSetsForSession(sessionId).first()
            .groupBy(PreviousSetRow::exerciseId)

    /** A finished session that performed [exerciseId] for [weights], one completed set each. */
    private suspend fun logged(
        session: Long,
        exerciseId: Long,
        weights: List<Long>,
    ) {
        val sessionId = db.workoutSessionDao().insert(
            session(isInProgress = false, createdAt = session),
        )
        val slot = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
        weights.forEachIndexed { index, weight ->
            db.workoutSetDao().insert(
                workoutSet(slot, setNumber = index + 1, weight = weight, reps = 5),
            )
        }
    }
}
