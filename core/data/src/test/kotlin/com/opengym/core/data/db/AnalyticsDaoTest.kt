package com.opengym.core.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SQL-level aggregates behind the progression screens: weekly volume,
 * weekly training days, volume per muscle group, and the recent-PR summary.
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

        /** Epoch millis at 23:30 on [epochDay] — late enough for a session to run past midnight. */
        fun lateOn(epochDay: Long): Long = epochDay * MILLIS_PER_DAY + 23 * 3_600_000L + 1_800_000L
    }

    // --- getVolumeByWeek --------------------------------------------------

    @Test
    fun `weekly volume is summed per Monday-aligned week`() =
        runBlocking {
            val dao = db.workoutSetDao()
            // week 1: 100kg x 10 + 80kg x 5   = 1_000_000_000 + 400_000_000
            logSet(workoutOn(WEEK_1), 1, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(workoutOn(WEEK_1 + 2), 1, at(WEEK_1 + 2), reps = 5, weight = kg(80))
            // week 2: 60kg x 12               = 720_000_000
            logSet(workoutOn(WEEK_2 + 1), 1, at(WEEK_2 + 1), reps = 12, weight = kg(60))
            // week 3: 100kg x 3 + 50kg x 8    = 300_000_000 + 400_000_000
            //         the second one falls on the Sunday that closes week 3
            logSet(workoutOn(WEEK_3), 1, at(WEEK_3), reps = 3, weight = kg(100))
            logSet(workoutOn(WEEK_3 + 6), 1, at(WEEK_3 + 6), reps = 8, weight = kg(50))

            val series = dao.getVolumeByWeek(WEEK_1, WEEK_4 + 7).first()

            assertEquals(listOf(WEEK_1, WEEK_2, WEEK_3), series.map { it.weekStartEpochDay })
            assertEquals(
                listOf(1_400_000_000L, 720_000_000L, 700_000_000L),
                series.map { it.totalVolume },
            )
        }

    @Test
    fun `weekly volume ignores sets before the cutoff and sets that were never completed`() =
        runBlocking {
            val dao = db.workoutSetDao()
            // Before the cutoff: the Sunday that closes the week preceding WEEK_1.
            logSet(workoutOn(WEEK_1 - 1), 1, at(WEEK_1 - 1), reps = 10, weight = kg(200))
            val inRange = workoutOn(WEEK_1)
            logSet(inRange, 1, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(inRange, 2, at(WEEK_1), reps = 10, weight = kg(500), isCompleted = false)

            val series = dao.getVolumeByWeek(WEEK_1, WEEK_4 + 7).first()

            assertEquals(1, series.size)
            assertEquals(WEEK_1, series.single().weekStartEpochDay)
            assertEquals(1_000_000_000L, series.single().totalVolume) // 100kg x 10 only
        }

    @Test
    fun `a week of sets without weight or reps reports zero volume rather than dropping out`() =
        runBlocking {
            val dao = db.workoutSetDao()
            logSet(workoutOn(WEEK_1), 1, at(WEEK_1), reps = 10, weight = kg(100))
            // A plank and a run: duration/distance only, no weight or reps.
            val cardio = workoutOn(WEEK_2)
            logSet(cardio, 1, at(WEEK_2), reps = null, weight = null)
            logSet(cardio, 2, at(WEEK_2), reps = 20, weight = null)

            val series = dao.getVolumeByWeek(WEEK_1, WEEK_4 + 7).first()

            assertEquals(listOf(WEEK_1, WEEK_2), series.map { it.weekStartEpochDay })
            assertEquals(listOf(1_000_000_000L, 0L), series.map { it.totalVolume })
        }

    @Test
    fun `weekly volume is an empty series when there is no completed history`() =
        runBlocking {
            logSet(workoutOn(WEEK_1), 1, at(WEEK_1), reps = 10, weight = kg(100), isCompleted = false)

            assertTrue(db.workoutSetDao().getVolumeByWeek(WEEK_1, WEEK_4 + 7).first().isEmpty())
        }

    @Test
    fun `volume from a session still in progress is not charted until it is finished`() =
        runBlocking {
            val dao = db.workoutSetDao()
            logSet(workoutOn(WEEK_1), 1, at(WEEK_1), reps = 10, weight = kg(100))
            // Mid-workout: the sets are completed, the session is not. Charting these would
            // put volume in a week the consistency series has no training day for, and would
            // keep it there for good if the user never closed the session out.
            val openSessionId = db.workoutSessionDao().insert(
                session(isInProgress = true, lastActiveAt = at(WEEK_1 + 1), createdAt = at(WEEK_1 + 1)),
            )
            val openSlot = db.sessionExerciseDao().insert(
                sessionExercise(openSessionId, sharedExerciseId(), position = 0),
            )
            logSet(openSlot, 1, at(WEEK_1 + 1), reps = 10, weight = kg(500))

            val series = dao.getVolumeByWeek(WEEK_1, WEEK_4 + 7).first()

            assertEquals(1_000_000_000L, series.single().totalVolume)
        }

    @Test
    fun `a session's sets are bucketed by its close-out, not by their own stamps`() =
        runBlocking {
            // A session started 23:30 on the Sunday closing week 1 and finished after midnight.
            // Both halves of its volume belong to the week it was finished in, which is the week
            // getTrainingByWeek credits with the training day.
            val sessionId = db.workoutSessionDao().insert(
                session(
                    isInProgress = false,
                    lastActiveAt = at(WEEK_2),
                    createdAt = lateOn(WEEK_2 - 1),
                    completedAt = at(WEEK_2),
                ),
            )
            val slot = db.sessionExerciseDao().insert(
                sessionExercise(sessionId, sharedExerciseId(), position = 0),
            )
            logSet(slot, 1, lateOn(WEEK_2 - 1), reps = 10, weight = kg(100))
            logSet(slot, 2, at(WEEK_2), reps = 10, weight = kg(100))

            val series = db.workoutSetDao().getVolumeByWeek(WEEK_1, WEEK_4 + 7).first()

            assertEquals(listOf(WEEK_2), series.map { it.weekStartEpochDay })
            assertEquals(2_000_000_000L, series.single().totalVolume)
        }

    @Test
    fun `the weekly volume range is inclusive of its first day and exclusive of its last`() =
        runBlocking {
            logSet(workoutOn(WEEK_1 - 1), 1, at(WEEK_1 - 1), reps = 10, weight = kg(100))
            logSet(workoutOn(WEEK_1), 1, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(workoutOn(WEEK_2 - 1), 1, at(WEEK_2 - 1), reps = 10, weight = kg(100))
            logSet(workoutOn(WEEK_2), 1, at(WEEK_2), reps = 10, weight = kg(100))

            val series = db.workoutSetDao().getVolumeByWeek(WEEK_1, WEEK_2).first()

            // Only week 1's two sessions: the one before the range and the one on its
            // exclusive end are both outside it.
            assertEquals(listOf(WEEK_1), series.map { it.weekStartEpochDay })
            assertEquals(2_000_000_000L, series.single().totalVolume)
        }

    @Test
    fun `a range ending mid-week sums that week over the days asked for only`() =
        runBlocking {
            // Monday and Wednesday inside the range, Friday past its end - all one bucket.
            logSet(workoutOn(WEEK_1), 1, at(WEEK_1), reps = 10, weight = kg(100))
            logSet(workoutOn(WEEK_1 + 2), 1, at(WEEK_1 + 2), reps = 10, weight = kg(50))
            logSet(workoutOn(WEEK_1 + 4), 1, at(WEEK_1 + 4), reps = 10, weight = kg(200))

            val series = db.workoutSetDao().getVolumeByWeek(WEEK_1, WEEK_1 + 3).first()

            // 100kg x 10 + 50kg x 10, with the Friday's 200kg x 10 left out.
            assertEquals(WEEK_1, series.single().weekStartEpochDay)
            assertEquals(1_500_000_000L, series.single().totalVolume)
        }

    // --- getTrainingByWeek ------------------------------------------------

    @Test
    fun `training days are counted per Monday-aligned week`() =
        runBlocking {
            // week 1: Monday and Wednesday. week 2: Tuesday. week 3: the closing Sunday.
            finishSession(at(WEEK_1))
            finishSession(at(WEEK_1 + 2))
            finishSession(at(WEEK_2 + 1))
            finishSession(at(WEEK_3 + 6))

            val series = db.workoutSessionDao().getTrainingByWeek(WEEK_1, WEEK_4 + 7).first()

            assertEquals(listOf(WEEK_1, WEEK_2, WEEK_3), series.map { it.weekStartEpochDay })
            assertEquals(listOf(2, 1, 1), series.map { it.trainingDays })
            assertEquals(listOf(2, 1, 1), series.map { it.sessionCount })
        }

    @Test
    fun `two sessions on the same day are one training day and two sessions`() =
        runBlocking {
            // A morning lift and an evening run, plus a second day to prove the week is not
            // collapsing everything into one.
            finishSession(at(WEEK_1))
            finishSession(at(WEEK_1) + 8 * 3_600_000L)
            finishSession(at(WEEK_1 + 3))

            val week = db.workoutSessionDao().getTrainingByWeek(WEEK_1, WEEK_2).first().single()

            assertEquals(2, week.trainingDays)
            assertEquals(3, week.sessionCount)
        }

    @Test
    fun `a session still in progress has not been trained yet`() =
        runBlocking {
            finishSession(at(WEEK_1))
            // In progress, and carrying a completed_at anyway - a resumed session, whose flag
            // went back to 1 while the stamp from its first close-out stayed. The flag is what
            // has to exclude it; a query filtering only on the stamp would count it.
            db.workoutSessionDao().insert(
                session(
                    isInProgress = true,
                    lastActiveAt = at(WEEK_1 + 1),
                    createdAt = at(WEEK_1 + 1),
                    completedAt = at(WEEK_1 + 1),
                ),
            )

            val week = db.workoutSessionDao().getTrainingByWeek(WEEK_1, WEEK_2).first().single()

            assertEquals(1, week.trainingDays)
            assertEquals(1, week.sessionCount)
        }

    @Test
    fun `the training range is inclusive of its first day and exclusive of its last`() =
        runBlocking {
            finishSession(at(WEEK_1 - 1))
            finishSession(at(WEEK_1))
            finishSession(at(WEEK_2 - 1))
            finishSession(at(WEEK_2))

            val series = db.workoutSessionDao().getTrainingByWeek(WEEK_1, WEEK_2).first()

            assertEquals(listOf(WEEK_1), series.map { it.weekStartEpochDay })
            assertEquals(2, series.single().trainingDays)
        }

    @Test
    fun `training by week is an empty series for a user with no finished sessions`() =
        runBlocking {
            db.workoutSessionDao().insert(session(isInProgress = true, lastActiveAt = at(WEEK_1)))

            assertTrue(db.workoutSessionDao().getTrainingByWeek(WEEK_1, WEEK_4 + 7).first().isEmpty())
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
            // One workout per day, since the range is filtered on the session's close-out.
            listOf(WEEK_1 - 1, WEEK_1, WEEK_2 - 1, WEEK_2).forEach { day ->
                val slot = seedSessionExerciseFor(exerciseId, finishedOn = day)
                logSet(slot, 1, at(day), reps = 10, weight = kg(100))
            }

            val rows = db.workoutSetDao().getVolumeByMuscleGroup(WEEK_1, WEEK_2).first()

            // The two workouts inside [WEEK_1, WEEK_2): 2 x 1_000_000_000.
            assertEquals(2_000_000_000L, rows.single().totalVolume)
        }

    @Test
    fun `muscle volume and weekly volume agree on which sessions a period holds`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST")
            val exerciseId = db.exerciseDao().insert(exercise())
            link(exerciseId, muscles.getValue("CHEST"), isPrimary = true)
            val finished = seedSessionExerciseFor(exerciseId, finishedOn = WEEK_1)
            logSet(finished, 1, at(WEEK_1), reps = 10, weight = kg(100))
            // An open session in the same week: excluded from both aggregates, so the breakdown
            // still adds up to the total the chart draws.
            val openSessionId = db.workoutSessionDao().insert(
                session(isInProgress = true, lastActiveAt = at(WEEK_1 + 1), createdAt = at(WEEK_1 + 1)),
            )
            val openSlot = db.sessionExerciseDao()
                .insert(sessionExercise(openSessionId, exerciseId, position = 0))
            logSet(openSlot, 1, at(WEEK_1 + 1), reps = 10, weight = kg(900))

            val byMuscle = db.workoutSetDao().getVolumeByMuscleGroup(WEEK_1, WEEK_2).first()
            val byWeek = db.workoutSetDao().getVolumeByWeek(WEEK_1, WEEK_2).first()

            assertEquals(byWeek.single().totalVolume, byMuscle.single().totalVolume)
            assertEquals(1_000_000_000L, byMuscle.single().totalVolume)
        }

    // --- hasFinishedSession -----------------------------------------------

    @Test
    fun `a user who has never finished a session has no training history`() =
        runBlocking {
            db.workoutSessionDao().insert(session(isInProgress = true, lastActiveAt = at(WEEK_1)))

            assertFalse(db.workoutSessionDao().hasFinishedSession().first())
        }

    @Test
    fun `training history stays true however far outside every window it falls`() =
        runBlocking {
            // Finished years before any range a twelve-week chart would ask for. This is the
            // signal that separates a returning lifter from a first-time one, and no window can
            // carry it.
            finishSession(at(WEEK_1))

            assertTrue(db.workoutSessionDao().hasFinishedSession().first())
            assertTrue(db.workoutSessionDao().getTrainingByWeek(WEEK_3, WEEK_4 + 7).first().isEmpty())
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

    private var sharedExercise: Long? = null

    /** One exercise for the fixtures that do not care which; created on first use. */
    private suspend fun sharedExerciseId(): Long =
        sharedExercise ?: db.exerciseDao().insert(exercise()).also { sharedExercise = it }

    /**
     * A workout finished on [epochDay], returning the session_exercise id its sets go under.
     *
     * Volume buckets on the *session's* close-out, so a fixture wanting sets in two different
     * weeks needs two workouts — two stamps under one session would land in one bucket, which
     * is the behaviour `a session's sets are bucketed by its close-out` pins.
     */
    private suspend fun workoutOn(epochDay: Long): Long {
        val sessionId = finishSession(at(epochDay))
        return db.sessionExerciseDao()
            .insert(sessionExercise(sessionId, sharedExerciseId(), position = 0))
    }

    /** Inserts an exercise + session + session_exercise, returning (exerciseId, sessionExerciseId). */
    private suspend fun seedSessionExercise(): Pair<Long, Long> {
        val exerciseId = db.exerciseDao().insert(exercise())
        return exerciseId to seedSessionExerciseFor(exerciseId)
    }

    /**
     * Puts [exerciseId] into a session finished on [finishedOn], returning the
     * session_exercise id. Finished rather than in progress because the volume aggregates
     * read only closed-out sessions.
     */
    private suspend fun seedSessionExerciseFor(
        exerciseId: Long,
        finishedOn: Long = WEEK_1,
    ): Long {
        val sessionId = finishSession(at(finishedOn))
        return db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
    }

    /** A session closed out at [completedAt], the state `FinishSessionUseCase` leaves. */
    private suspend fun finishSession(completedAt: Long): Long =
        db.workoutSessionDao().insert(
            session(
                isInProgress = false,
                lastActiveAt = completedAt,
                createdAt = completedAt,
                completedAt = completedAt,
            ),
        )

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
