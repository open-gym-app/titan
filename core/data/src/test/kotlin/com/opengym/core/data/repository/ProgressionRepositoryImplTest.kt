package com.opengym.core.data.repository

import com.opengym.core.data.db.RoomTestBase
import com.opengym.core.data.db.exercise
import com.opengym.core.data.db.session
import com.opengym.core.data.db.sessionExercise
import com.opengym.core.data.db.workoutSet
import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.model.WeeklyVolume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The progression aggregates read through the repository, which is where the epoch days the
 * queries speak in become the [LocalDate]s the domain does.
 *
 * The translation the tests are really about is the range: callers hand over a period with
 * **both ends inclusive** and the queries take a half-open one, so a bucket landing on the
 * last day of a period is the case a dropped `+ 1` would lose.
 */
class ProgressionRepositoryImplTest : RoomTestBase() {

    private companion object {
        /** 2024-10-07, a Monday, matching `AnalyticsDaoTest`'s anchor. */
        val WEEK_1: LocalDate = LocalDate.of(2024, 10, 7)
        val WEEK_2: LocalDate = WEEK_1.plusDays(7)
        val WEEK_3: LocalDate = WEEK_1.plusDays(14)

        const val MILLIS_PER_DAY = 86_400_000L

        fun kg(value: Long): Long = value * 1_000_000L

        /** Midday on [date], so a bucket that ignored the time of day would show. */
        fun at(date: LocalDate): Long = date.toEpochDay() * MILLIS_PER_DAY + 12 * 3_600_000L
    }

    private val repository by lazy {
        ProgressionRepositoryImpl(setDao = db.workoutSetDao(), sessionDao = db.workoutSessionDao())
    }

    @Test
    fun `weekly volume comes back as domain buckets keyed by their Monday`() =
        runBlocking {
            // week 1: 100kg x 10. week 3: 60kg x 5.
            logSet(workoutOn(WEEK_1.plusDays(2)), 1, at(WEEK_1.plusDays(2)), reps = 10, weight = kg(100))
            logSet(workoutOn(WEEK_3), 1, at(WEEK_3), reps = 5, weight = kg(60))

            val weeks = repository.getWeeklyVolume(WEEK_1..WEEK_3.plusDays(6)).first()

            assertEquals(
                listOf(WeeklyVolume(WEEK_1, kg(1_000)), WeeklyVolume(WEEK_3, kg(300))),
                weeks,
            )
        }

    @Test
    fun `a bucket on the last day of the period is inside it`() =
        runBlocking {
            // The Sunday that closes week 2, which is also the last day asked for.
            val day = WEEK_2.plusDays(6)
            logSet(workoutOn(day), 1, at(day), reps = 10, weight = kg(100))

            val weeks = repository.getWeeklyVolume(WEEK_1..WEEK_2.plusDays(6)).first()

            assertEquals(listOf(WeeklyVolume(WEEK_2, kg(1_000))), weeks)
        }

    @Test
    fun `a week outside the period is not read`() =
        runBlocking {
            logSet(workoutOn(WEEK_3), 1, at(WEEK_3), reps = 10, weight = kg(100))

            assertTrue(repository.getWeeklyVolume(WEEK_1..WEEK_2.plusDays(6)).first().isEmpty())
        }

    @Test
    fun `training history is true once a session has been finished, whatever the period`() =
        runBlocking {
            assertFalse(repository.hasTrainingHistory().first())

            finishSession(at(WEEK_1))

            assertTrue(repository.hasTrainingHistory().first())
        }

    @Test
    fun `weekly training comes back as domain buckets carrying both counts`() =
        runBlocking {
            // Week 1: two sessions on the Monday, one on the Thursday - two days, three sessions.
            finishSession(at(WEEK_1))
            finishSession(at(WEEK_1) + 8 * 3_600_000L)
            finishSession(at(WEEK_1.plusDays(3)))
            finishSession(at(WEEK_2.plusDays(1)))

            val weeks = repository.getWeeklyTraining(WEEK_1..WEEK_2.plusDays(6)).first()

            assertEquals(
                listOf(
                    WeeklyTraining(WEEK_1, trainingDays = 2, sessionCount = 3),
                    WeeklyTraining(WEEK_2, trainingDays = 1, sessionCount = 1),
                ),
                weeks,
            )
        }

    @Test
    fun `a user with no history reads as empty rather than as zeroed weeks`() =
        runBlocking {
            assertTrue(repository.getWeeklyVolume(WEEK_1..WEEK_3.plusDays(6)).first().isEmpty())
            assertTrue(repository.getWeeklyTraining(WEEK_1..WEEK_3.plusDays(6)).first().isEmpty())
        }

    @Test
    fun `an attempt comes back with its top set as one value, and its period is inclusive`() =
        runBlocking {
            val slot = workoutOn(WEEK_3.plusDays(6))
            logSet(slot, 1, at(WEEK_3.plusDays(6)), reps = 5, weight = kg(80))
            logSet(slot, 2, at(WEEK_3.plusDays(6)), reps = 8, weight = kg(70))

            // The Sunday that closes the period, which a dropped `+ 1` would lose.
            val attempts = repository
                .getExerciseAttempts(sharedExercise!!, WEEK_1..WEEK_3.plusDays(6))
                .first()

            val attempt = attempts.single()
            assertEquals(2, attempt.sets)
            // 80x5 + 70x8 = 400 + 560.
            assertEquals(kg(960), attempt.volume)
            assertEquals(kg(80), attempt.topSet?.weight)
            assertEquals(5, attempt.topSet?.reps)
        }

    @Test
    fun `an attempt with neither a weight nor a rep count carries no top set at all`() =
        runBlocking {
            val slot = workoutOn(WEEK_1)
            // A timed hold: the row exists, and there is nothing to quote it by.
            db.workoutSetDao().insert(
                workoutSet(slot, 1, completedAt = at(WEEK_1), reps = null, weight = null),
            )

            val attempt = repository
                .getExerciseAttempts(sharedExercise!!, WEEK_1..WEEK_1.plusDays(6))
                .first()
                .single()

            assertNull(attempt.topSet)
            assertEquals(0L, attempt.volume)
        }

    @Test
    fun `the set count and the chooser read the whole history, whatever period was last asked for`() =
        runBlocking {
            logSet(workoutOn(WEEK_1), 1, at(WEEK_1), reps = 5, weight = kg(80))
            logSet(workoutOn(WEEK_3), 1, at(WEEK_3), reps = 5, weight = kg(85))

            assertEquals(2, repository.countExerciseSets(sharedExercise!!).first())
            assertEquals(
                listOf(sharedExercise),
                repository.getTrainedExercises().first().map { it.exerciseId },
            )
            assertEquals(2, repository.getTrainedExercises().first().single().sets)
        }

    private var sharedExercise: Long? = null

    /**
     * A workout finished on [day], returning the session_exercise id its sets go under. The
     * volume aggregate reads closed-out sessions only and buckets them by their close-out,
     * so a week is a workout rather than a stamp on a set.
     */
    private suspend fun workoutOn(day: LocalDate): Long {
        val exerciseId = sharedExercise
            ?: db.exerciseDao().insert(exercise()).also { sharedExercise = it }
        val sessionId = finishSession(at(day))
        return db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
    }

    private suspend fun finishSession(completedAt: Long): Long =
        db.workoutSessionDao().insert(
            session(
                isInProgress = false,
                lastActiveAt = completedAt,
                createdAt = completedAt,
                completedAt = completedAt,
            ),
        )

    private suspend fun logSet(
        sessionExerciseId: Long,
        setNumber: Int,
        completedAt: Long,
        reps: Int,
        weight: Long,
    ) = db.workoutSetDao().insert(
        workoutSet(
            sessionExerciseId = sessionExerciseId,
            setNumber = setNumber,
            isCompleted = true,
            completedAt = completedAt,
            reps = reps,
            weight = weight,
        ),
    )
}
