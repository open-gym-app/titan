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
    fun `weekly volume comes back as domain buckets keyed by their Monday`() = runBlocking {
        val sessionExerciseId = seedSessionExercise()
        // week 1: 100kg x 10. week 3: 60kg x 5.
        logSet(sessionExerciseId, 1, at(WEEK_1.plusDays(2)), reps = 10, weight = kg(100))
        logSet(sessionExerciseId, 2, at(WEEK_3), reps = 5, weight = kg(60))

        val weeks = repository.getWeeklyVolume(WEEK_1..WEEK_3.plusDays(6)).first()

        assertEquals(
            listOf(WeeklyVolume(WEEK_1, kg(1_000)), WeeklyVolume(WEEK_3, kg(300))),
            weeks,
        )
    }

    @Test
    fun `a bucket on the last day of the period is inside it`() = runBlocking {
        val sessionExerciseId = seedSessionExercise()
        // The Sunday that closes week 2, which is also the last day asked for.
        logSet(sessionExerciseId, 1, at(WEEK_2.plusDays(6)), reps = 10, weight = kg(100))

        val weeks = repository.getWeeklyVolume(WEEK_1..WEEK_2.plusDays(6)).first()

        assertEquals(listOf(WeeklyVolume(WEEK_2, kg(1_000))), weeks)
    }

    @Test
    fun `a week outside the period is not read`() = runBlocking {
        val sessionExerciseId = seedSessionExercise()
        logSet(sessionExerciseId, 1, at(WEEK_3), reps = 10, weight = kg(100))

        assertTrue(repository.getWeeklyVolume(WEEK_1..WEEK_2.plusDays(6)).first().isEmpty())
    }

    @Test
    fun `weekly training comes back as domain buckets carrying both counts`() = runBlocking {
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
    fun `a user with no history reads as empty rather than as zeroed weeks`() = runBlocking {
        assertTrue(repository.getWeeklyVolume(WEEK_1..WEEK_3.plusDays(6)).first().isEmpty())
        assertTrue(repository.getWeeklyTraining(WEEK_1..WEEK_3.plusDays(6)).first().isEmpty())
    }

    private suspend fun seedSessionExercise(): Long {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        return db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
    }

    private suspend fun finishSession(completedAt: Long): Long = db.workoutSessionDao().insert(
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
