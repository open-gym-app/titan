package com.titan.core.data.db

import com.titan.core.data.db.projection.ExerciseAttemptRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SQL behind the per-exercise drill-down: one row per session a lift was performed in, the
 * all-time working-set count, and the list of lifts the chooser is built from.
 *
 * The fixture spans four weeks anchored on a real Monday, matching [AnalyticsDaoTest], so the
 * expected sums can be worked out by hand — they are written out as literals below, with the
 * arithmetic in a comment, rather than recomputed with the same expression the query uses.
 */
class ExerciseProgressionDaoTest : RoomTestBase() {

    private companion object {
        /** 2024-10-07, a Monday. */
        const val DAY_1 = 20_003L

        const val MILLIS_PER_DAY = 86_400_000L

        /** Kilograms in the canonical storage unit, milligrams. */
        fun kg(value: Long): Long = value * 1_000_000L

        /**
         * Epoch millis for midday on the day [dayOffset] days after [DAY_1].
         *
         * Relative rather than absolute so the fixture reads as "day 0, day 7, day 21"; the
         * time of day is deliberately non-zero, so a query that placed a session by anything
         * but its own stamp would still be caught.
         */
        fun at(dayOffset: Long): Long = (DAY_1 + dayOffset) * MILLIS_PER_DAY + 12 * 3_600_000L

        /** A range wide enough to hold the whole fixture. */
        const val LAST_DAY_EXCLUSIVE = DAY_1 + 28
    }

    // --- getAttemptsByExercise --------------------------------------------

    @Test
    fun `one row per session, oldest first, with the session's own sums`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            // Day 0: 80x5 + 80x5 + 70x8 = 400 + 400 + 560 = 1,360 kg-reps over three sets.
            attempt(bench, day = 0) {
                set(it, 1, day = 0, weight = kg(80), reps = 5)
                set(it, 2, day = 0, weight = kg(80), reps = 5)
                set(it, 3, day = 0, weight = kg(70), reps = 8)
            }
            // Day 7: 85x5 + 85x4 = 425 + 340 = 765 kg-reps over two sets.
            attempt(bench, day = 7) {
                set(it, 1, day = 7, weight = kg(85), reps = 5)
                set(it, 2, day = 7, weight = kg(85), reps = 4)
            }

            val rows = attempts(bench)

            assertEquals(listOf(at(0), at(7)), rows.map(ExerciseAttemptRow::finishedAt))
            assertEquals(listOf(3, 2), rows.map(ExerciseAttemptRow::setCount))
            assertEquals(listOf(kg(1_360), kg(765)), rows.map(ExerciseAttemptRow::totalVolume))
        }

    @Test
    fun `the top set is the heaviest, and ties go to the one that got more reps out of it`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            attempt(bench, day = 0) {
                set(it, 1, day = 0, weight = kg(70), reps = 12)
                set(it, 2, day = 0, weight = kg(90), reps = 3)
                // The same weight as the top set, for one more rep.
                set(it, 3, day = 0, weight = kg(90), reps = 4)
            }

            val row = attempts(bench).single()

            assertEquals(kg(90), row.topSetWeight)
            assertEquals(4, row.topSetReps)
        }

    @Test
    fun `an attempt with no weight anywhere in it falls through to reps`() =
        runBlocking {
            val pullUp = exerciseNamed("Pull Up")
            attempt(pullUp, day = 0) {
                set(it, 1, day = 0, weight = null, reps = 8)
                set(it, 2, day = 0, weight = null, reps = 12)
            }

            val row = attempts(pullUp).single()

            assertNull(row.topSetWeight)
            assertEquals(12, row.topSetReps)
            // Bodyweight sets move no measurable load, which is zero rather than a missing row.
            assertEquals(0L, row.totalVolume)
        }

    @Test
    fun `an exercise performed twice in one session is one attempt`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            val sessionId = finishedSession(day = 0)
            // Two slots of the same session — a superset, or a second run at it after
            // accessories. The PR engine judges the two together, and so does this.
            val first = slot(sessionId, bench, position = 0)
            val second = slot(sessionId, bench, position = 3)
            set(first, 1, day = 0, weight = kg(80), reps = 5)
            set(second, 1, day = 0, weight = kg(90), reps = 3)

            val row = attempts(bench).single()

            assertEquals(2, row.setCount)
            // 80x5 + 90x3 = 400 + 270.
            assertEquals(kg(670), row.totalVolume)
            assertEquals(kg(90), row.topSetWeight)
        }

    @Test
    fun `warm-ups are out of the count, out of the volume and never the top set`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            attempt(bench, day = 0) {
                // Heavier than the working set, and a warm-up all the same: an impossible
                // fixture on purpose, so a query that ignored the type would be caught.
                set(it, 1, day = 0, weight = kg(200), reps = 1, setType = "WARMUP")
                set(it, 2, day = 0, weight = kg(80), reps = 5)
            }

            val row = attempts(bench).single()

            assertEquals(1, row.setCount)
            assertEquals(kg(400), row.totalVolume)
            assertEquals(kg(80), row.topSetWeight)
        }

    @Test
    fun `sets that were never completed and sessions still open do not make an attempt`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            attempt(bench, day = 0) {
                set(it, 1, day = 0, weight = kg(80), reps = 5)
                set(it, 2, day = 0, weight = kg(500), reps = 5, isCompleted = false)
            }
            // Mid-workout: the sets are completed, the session is not.
            val openSession = db.workoutSessionDao().insert(
                session(isInProgress = true, lastActiveAt = at(7), createdAt = at(7)),
            )
            set(slot(openSession, bench, position = 0), 1, day = 7, weight = kg(90), reps = 5)

            val row = attempts(bench).single()

            assertEquals(1, row.setCount)
            assertEquals(kg(400), row.totalVolume)
        }

    @Test
    fun `the first set stamp is the earliest of the attempt, not the session's close-out`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            val sessionId = finishedSession(day = 0)
            val slotId = slot(sessionId, bench, position = 0)
            db.workoutSetDao().insert(
                workoutSet(slotId, 1, completedAt = at(0) - 3_600_000L, reps = 5, weight = kg(80)),
            )
            db.workoutSetDao().insert(
                workoutSet(slotId, 2, completedAt = at(0) - 1_800_000L, reps = 5, weight = kg(80)),
            )

            val row = attempts(bench).single()

            assertEquals(at(0) - 3_600_000L, row.firstSetAt)
            assertEquals(at(0), row.finishedAt)
        }

    @Test
    fun `the range is inclusive of its first day and exclusive of its last, and other lifts are out`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            val squat = exerciseNamed("Back Squat")
            attempt(bench, day = -1) { set(it, 1, day = -1, weight = kg(70), reps = 5) }
            attempt(bench, day = 0) { set(it, 1, day = 0, weight = kg(80), reps = 5) }
            attempt(bench, day = 6) { set(it, 1, day = 6, weight = kg(85), reps = 5) }
            attempt(bench, day = 7) { set(it, 1, day = 7, weight = kg(90), reps = 5) }
            attempt(squat, day = 0) { set(it, 1, day = 0, weight = kg(140), reps = 5) }

            val rows = db.workoutSetDao()
                .getAttemptsByExercise(bench, DAY_1, DAY_1 + 7)
                .first()

            assertEquals(listOf(at(0), at(6)), rows.map(ExerciseAttemptRow::finishedAt))
        }

    @Test
    fun `an exercise nobody has performed has no attempts`() =
        runBlocking {
            assertTrue(attempts(exerciseNamed("Bench Press")).isEmpty())
        }

    // --- countSetsByExercise ----------------------------------------------

    @Test
    fun `the set count is all-time, working sets only`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            val squat = exerciseNamed("Back Squat")
            attempt(bench, day = 0) {
                set(it, 1, day = 0, weight = kg(80), reps = 5)
                set(it, 2, day = 0, weight = kg(80), reps = 5)
                set(it, 3, day = 0, weight = kg(40), reps = 10, setType = "WARMUP")
                set(it, 4, day = 0, weight = kg(80), reps = 5, isCompleted = false)
            }
            // Years later, and still counted: this figure has no window.
            attempt(bench, day = 21) { set(it, 1, day = 21, weight = kg(85), reps = 5) }
            attempt(squat, day = 0) { set(it, 1, day = 0, weight = kg(140), reps = 5) }

            assertEquals(3, db.workoutSetDao().countSetsByExercise(bench).first())
        }

    @Test
    fun `an exercise nobody has performed counts zero rather than returning no row`() =
        runBlocking {
            assertEquals(0, db.workoutSetDao().countSetsByExercise(exerciseNamed("Bench Press")).first())
        }

    // --- getTrainedExercises ----------------------------------------------

    @Test
    fun `the chooser lists what was logged, most recently trained first`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            val squat = exerciseNamed("Back Squat")
            // Never performed, so it is not something to drill into.
            exerciseNamed("Zercher Squat")

            attempt(bench, day = 0) { set(it, 1, day = 0, weight = kg(80), reps = 5) }
            attempt(bench, day = 14) { set(it, 1, day = 14, weight = kg(85), reps = 5) }
            attempt(squat, day = 21) { set(it, 1, day = 21, weight = kg(140), reps = 5) }

            val rows = db.workoutSetDao().getTrainedExercises().first()

            assertEquals(listOf("Back Squat", "Bench Press"), rows.map { it.name })
            assertEquals(listOf(squat, bench), rows.map { it.exerciseId })
            assertEquals(listOf(1, 2), rows.map { it.setCount })
            assertEquals(listOf(at(21), at(14)), rows.map { it.lastPerformedAt })
        }

    @Test
    fun `an exercise only ever warmed up with is not offered as one to drill into`() =
        runBlocking {
            val bench = exerciseNamed("Bench Press")
            attempt(bench, day = 0) {
                set(it, 1, day = 0, weight = kg(40), reps = 10, setType = "WARMUP")
            }

            assertTrue(db.workoutSetDao().getTrainedExercises().first().isEmpty())
        }

    // --- Fixture ----------------------------------------------------------

    private suspend fun attempts(exerciseId: Long): List<ExerciseAttemptRow> =
        db.workoutSetDao().getAttemptsByExercise(exerciseId, DAY_1, LAST_DAY_EXCLUSIVE).first()

    private suspend fun exerciseNamed(name: String): Long = db.exerciseDao().insert(exercise(name = name))

    /** A session closed out on [day], the state `FinishSessionUseCase` leaves. */
    private suspend fun finishedSession(day: Long): Long =
        db.workoutSessionDao().insert(
            session(
                isInProgress = false,
                lastActiveAt = at(day),
                createdAt = at(day),
                completedAt = at(day),
            ),
        )

    private suspend fun slot(
        sessionId: Long,
        exerciseId: Long,
        position: Int,
    ): Long = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position))

    /** One finished session holding [exerciseId], with [sets] logged into its slot. */
    private suspend fun attempt(
        exerciseId: Long,
        day: Long,
        sets: suspend (sessionExerciseId: Long) -> Unit,
    ) {
        sets(slot(finishedSession(day), exerciseId, position = 0))
    }

    private suspend fun set(
        sessionExerciseId: Long,
        setNumber: Int,
        day: Long,
        weight: Long?,
        reps: Int?,
        setType: String = "NORMAL",
        isCompleted: Boolean = true,
    ) = db.workoutSetDao().insert(
        workoutSet(
            sessionExerciseId = sessionExerciseId,
            setNumber = setNumber,
            isCompleted = isCompleted,
            completedAt = at(day),
            reps = reps,
            weight = weight,
            setType = setType,
        ),
    )
}
