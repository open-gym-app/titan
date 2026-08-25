package com.opengym.core.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SQL-level aggregates behind the progression screens: weekly volume,
 * volume per muscle group, and the recent-PR summary.
 *
 * The fixture spans four calendar weeks anchored on a real Monday so the
 * expected sums can be worked out by hand (they are written out as literals
 * below, with the arithmetic in a comment) rather than recomputed with the same
 * formula the query uses.
 */
class AnalyticsDaoTest : RoomTestBase() {

    private companion object {
        /** 2024-10-07, a Monday. Weeks in the fixture start here, +7, +14, +21. */
        const val WEEK_1 = 20_003L
        const val WEEK_2 = WEEK_1 + 7
        const val WEEK_3 = WEEK_1 + 14
        const val WEEK_4 = WEEK_1 + 21

        const val MILLIS_PER_DAY = 86_400_000L

        /** Kilograms in the canonical storage unit, milligrams. */
        fun kg(value: Long): Long = value * 1_000_000L

        /**
         * Epoch millis for midday on [epochDay] — the time of day is deliberately
         * non-zero so a bucket that ignored it would still be caught.
         */
        fun at(epochDay: Long): Long = epochDay * MILLIS_PER_DAY + 12 * 3_600_000L
    }

    // --- getVolumeByWeek --------------------------------------------------

    @Test
    fun `weekly volume is summed per Monday-aligned week`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            val dao = db.workoutSetDao()
            // week 1: 100kg x 10 + 80kg x 5   = 1_000_000_000 + 400_000_000
            logSet(sessionExerciseId, 1, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(sessionExerciseId, 2, at(WEEK_1 + 2), reps = 5, weight = kg(80))
            // week 2: 60kg x 12               = 720_000_000
            logSet(sessionExerciseId, 3, at(WEEK_2 + 1), reps = 12, weight = kg(60))
            // week 3: 100kg x 3 + 50kg x 8    = 300_000_000 + 400_000_000
            //         the second one falls on the Sunday that closes week 3
            logSet(sessionExerciseId, 4, at(WEEK_3), reps = 3, weight = kg(100))
            logSet(sessionExerciseId, 5, at(WEEK_3 + 6), reps = 8, weight = kg(50))

            val series = dao.getVolumeByWeek(WEEK_1).first()

            assertEquals(listOf(WEEK_1, WEEK_2, WEEK_3), series.map { it.weekStartEpochDay })
            assertEquals(
                listOf(1_400_000_000L, 720_000_000L, 700_000_000L),
                series.map { it.totalVolume },
            )
        }

    @Test
    fun `weekly volume ignores sets before the cutoff and sets that were never completed`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            val dao = db.workoutSetDao()
            // Before the cutoff: the Sunday that closes the week preceding WEEK_1.
            logSet(sessionExerciseId, 1, at(WEEK_1 - 1), reps = 10, weight = kg(200))
            logSet(sessionExerciseId, 2, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(
                sessionExerciseId,
                3,
                at(WEEK_1 + 1),
                reps = 10,
                weight = kg(500),
                isCompleted = false,
            )

            val series = dao.getVolumeByWeek(WEEK_1).first()

            assertEquals(1, series.size)
            assertEquals(WEEK_1, series.single().weekStartEpochDay)
            assertEquals(1_000_000_000L, series.single().totalVolume) // 100kg x 10 only
        }

    @Test
    fun `a week of sets without weight or reps reports zero volume rather than dropping out`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            val dao = db.workoutSetDao()
            logSet(sessionExerciseId, 1, at(WEEK_1), reps = 10, weight = kg(100))
            // A plank and a run: duration/distance only, no weight or reps.
            logSet(sessionExerciseId, 2, at(WEEK_2), reps = null, weight = null)
            logSet(sessionExerciseId, 3, at(WEEK_2 + 3), reps = 20, weight = null)

            val series = dao.getVolumeByWeek(WEEK_1).first()

            assertEquals(listOf(WEEK_1, WEEK_2), series.map { it.weekStartEpochDay })
            assertEquals(listOf(1_000_000_000L, 0L), series.map { it.totalVolume })
        }

    @Test
    fun `weekly volume is an empty series when there is no completed history`() =
        runBlocking {
            val (_, sessionExerciseId) = seedSessionExercise()
            logSet(sessionExerciseId, 1, at(WEEK_1), reps = 10, weight = kg(100), isCompleted = false)

            assertTrue(db.workoutSetDao().getVolumeByWeek(WEEK_1).first().isEmpty())
        }

    // --- getVolumeByMuscleGroup -------------------------------------------

    @Test
    fun `each linked muscle group is credited with the full volume of the set`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST", "TRICEPS", "SHOULDERS")
            val exerciseId = db.exerciseDao().insert(exercise(name = "Bench Press"))
            link(exerciseId, muscles.getValue("CHEST"), isPrimary = true)
            link(exerciseId, muscles.getValue("TRICEPS"), isPrimary = false)
            link(exerciseId, muscles.getValue("SHOULDERS"), isPrimary = false)
            val sessionExerciseId = seedSessionExerciseFor(exerciseId)
            // one set, 100kg x 10 = 1_000_000_000
            logSet(sessionExerciseId, 1, at(WEEK_1), reps = 10, weight = kg(100))

            val rows = db.workoutSetDao().getVolumeByMuscleGroup(WEEK_1, WEEK_2).first()

            // Every linked group appears exactly once: three links, three rows.
            assertEquals(listOf("CHEST", "SHOULDERS", "TRICEPS"), rows.map { it.muscleGroupName }.sorted())
            assertEquals(setOf(1_000_000_000L), rows.map { it.totalVolume }.toSet())
        }

    @Test
    fun `a second secondary muscle does not double-count the set against the first`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST", "TRICEPS", "SHOULDERS")
            val oneSecondary = db.exerciseDao().insert(exercise(name = "Floor Press"))
            link(oneSecondary, muscles.getValue("CHEST"), isPrimary = true)
            link(oneSecondary, muscles.getValue("TRICEPS"), isPrimary = false)
            val twoSecondaries = db.exerciseDao().insert(exercise(name = "Incline Press"))
            link(twoSecondaries, muscles.getValue("CHEST"), isPrimary = true)
            link(twoSecondaries, muscles.getValue("TRICEPS"), isPrimary = false)
            link(twoSecondaries, muscles.getValue("SHOULDERS"), isPrimary = false)

            // One identical set on each exercise: 100kg x 10 = 1_000_000_000 apiece.
            logSet(seedSessionExerciseFor(oneSecondary), 1, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(seedSessionExerciseFor(twoSecondaries), 1, at(WEEK_1 + 1), reps = 10, weight = kg(100))

            val byName = db.workoutSetDao().getVolumeByMuscleGroup(WEEK_1, WEEK_2).first()
                .associateBy { it.muscleGroupName }

            // CHEST is primary on both sets: 2 x 1_000_000_000, not multiplied by the
            // number of secondary muscles the two exercises happen to have.
            assertEquals(2_000_000_000L, byName.getValue("CHEST").totalVolume)
            assertEquals(2_000_000_000L, byName.getValue("TRICEPS").totalVolume)
            assertEquals(1_000_000_000L, byName.getValue("SHOULDERS").totalVolume)
        }

    @Test
    fun `weighted volume gives a primary link all of the set and a secondary link half`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST", "TRICEPS")
            val exerciseId = db.exerciseDao().insert(exercise())
            link(exerciseId, muscles.getValue("CHEST"), isPrimary = true)
            link(exerciseId, muscles.getValue("TRICEPS"), isPrimary = false)
            // 100kg x 10 = 1_000_000_000
            logSet(seedSessionExerciseFor(exerciseId), 1, at(WEEK_1), reps = 10, weight = kg(100))

            val byName = db.workoutSetDao().getVolumeByMuscleGroup(WEEK_1, WEEK_2).first()
                .associateBy { it.muscleGroupName }

            assertEquals(1_000_000_000L, byName.getValue("CHEST").weightedVolume)
            assertEquals(500_000_000L, byName.getValue("TRICEPS").weightedVolume)
        }

    @Test
    fun `the muscle volume range is inclusive of its first day and exclusive of its last`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST")
            val exerciseId = db.exerciseDao().insert(exercise())
            link(exerciseId, muscles.getValue("CHEST"), isPrimary = true)
            val sessionExerciseId = seedSessionExerciseFor(exerciseId)
            logSet(sessionExerciseId, 1, at(WEEK_1 - 1), reps = 10, weight = kg(100))
            logSet(sessionExerciseId, 2, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(sessionExerciseId, 3, at(WEEK_2 - 1), reps = 10, weight = kg(100))
            logSet(sessionExerciseId, 4, at(WEEK_2), reps = 10, weight = kg(100))

            val rows = db.workoutSetDao().getVolumeByMuscleGroup(WEEK_1, WEEK_2).first()

            // The two sets inside [WEEK_1, WEEK_2): 2 x 1_000_000_000.
            assertEquals(2_000_000_000L, rows.single().totalVolume)
        }

    @Test
    fun `muscle volume is empty when nothing was trained in the range`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST")
            val exerciseId = db.exerciseDao().insert(exercise())
            link(exerciseId, muscles.getValue("CHEST"), isPrimary = true)
            logSet(seedSessionExerciseFor(exerciseId), 1, at(WEEK_1), reps = 10, weight = kg(100))

            assertTrue(db.workoutSetDao().getVolumeByMuscleGroup(WEEK_3, WEEK_4).first().isEmpty())
        }

    // --- getRecentRecords -------------------------------------------------

    @Test
    fun `recent records are newest-achieved first across every exercise`() =
        runBlocking {
            val dao = db.personalRecordDao()
            val benchId = db.exerciseDao().insert(exercise(name = "Bench Press"))
            val squatId = db.exerciseDao().insert(exercise(name = "Squat"))
            val benchSetId = db.workoutSetDao().insert(
                workoutSet(seedSessionExerciseFor(benchId), setNumber = 1),
            )
            val squatSetId = db.workoutSetDao().insert(
                workoutSet(seedSessionExerciseFor(squatId), setNumber = 1),
            )
            dao.insert(personalRecord(benchId, benchSetId, achievedAt = at(WEEK_1)))
            dao.insert(personalRecord(squatId, squatSetId, achievedAt = at(WEEK_3)))
            dao.insert(personalRecord(benchId, benchSetId, achievedAt = at(WEEK_2)))

            val achieved = dao.getRecentRecords(limit = 10).first().map { it.achievedAt }

            assertEquals(listOf(at(WEEK_3), at(WEEK_2), at(WEEK_1)), achieved)
        }

    @Test
    fun `recent records are capped at the requested limit`() =
        runBlocking {
            val dao = db.personalRecordDao()
            val (exerciseId, sessionExerciseId) = seedSessionExercise()
            val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))
            (1..5).forEach { day ->
                dao.insert(personalRecord(exerciseId, setId, achievedAt = at(WEEK_1 + day)))
            }

            val achieved = dao.getRecentRecords(limit = 2).first().map { it.achievedAt }

            assertEquals(listOf(at(WEEK_1 + 5), at(WEEK_1 + 4)), achieved)
        }

    @Test
    fun `recent records is empty when no personal record has been set`() =
        runBlocking {
            assertTrue(db.personalRecordDao().getRecentRecords(limit = 10).first().isEmpty())
        }

    // --- fixture helpers --------------------------------------------------

    /** Inserts an exercise + session + session_exercise, returning (exerciseId, sessionExerciseId). */
    private suspend fun seedSessionExercise(): Pair<Long, Long> {
        val exerciseId = db.exerciseDao().insert(exercise())
        return exerciseId to seedSessionExerciseFor(exerciseId)
    }

    /** Puts [exerciseId] into a fresh session, returning the session_exercise id. */
    private suspend fun seedSessionExerciseFor(exerciseId: Long): Long {
        val sessionId = db.workoutSessionDao().insert(session())
        return db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
    }

    private suspend fun seedMuscleGroups(vararg names: String): Map<String, Long> {
        db.muscleGroupDao().insertAll(names.map { muscleGroup(it) })
        return db.muscleGroupDao().getAll().first().associate { it.name to it.id }
    }

    private suspend fun link(
        exerciseId: Long,
        muscleGroupId: Long,
        isPrimary: Boolean,
    ) = db.exerciseDao().addMuscleGroup(exerciseMuscleGroup(exerciseId, muscleGroupId, isPrimary))

    private suspend fun logSet(
        sessionExerciseId: Long,
        setNumber: Int,
        completedAt: Long,
        reps: Int? = null,
        weight: Long? = null,
        isCompleted: Boolean = true,
    ) = db.workoutSetDao().insert(
        workoutSet(
            sessionExerciseId = sessionExerciseId,
            setNumber = setNumber,
            isCompleted = isCompleted,
            completedAt = completedAt,
            reps = reps,
            weight = weight,
        ),
    )
}
