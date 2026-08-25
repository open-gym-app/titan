package com.opengym.core.data.db

import com.opengym.core.domain.stats.TrainingWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the claim `TrainingWeek` documents: the Monday it rounds a date back to is the same
 * Monday `day - ((day + 3) % 7)` picks in SQL.
 *
 * The two exist because the buckets are cut where the rows are and the *axis* has to be
 * known before any row is read — a week nobody trained in produces no row, and only the
 * domain can say it should be drawn as a zero. If the two definitions drift, the use cases
 * fill a week the query never bucketed into and the series silently loses a week's training
 * to a gap that gets zeroed. Nothing but this test stops that.
 *
 * Both aggregates are checked, since they carry the arithmetic separately: volume buckets
 * `workout_set.completed_at`, consistency buckets `workout_session.completed_at`.
 */
class TrainingWeekParityTest : RoomTestBase() {

    private companion object {
        /** 2024-10-07, a Monday. */
        val ANCHOR: LocalDate = LocalDate.of(2024, 10, 7)

        const val MILLIS_PER_DAY = 86_400_000L

        /** Late enough in the day that a bucket ignoring the time would land a day out. */
        fun at(date: LocalDate): Long = date.toEpochDay() * MILLIS_PER_DAY + 23 * 3_600_000L
    }

    @Test
    fun `every day of a week buckets to the Monday the domain names`() = runBlocking {
        val sessionExerciseId = seedSessionExercise()

        // A fortnight, one set and one session a day, so every weekday - and the two
        // boundaries either side of a Monday - is exercised in both queries.
        val days = (0L until 14L).map { ANCHOR.minusDays(3).plusDays(it) }
        days.forEachIndexed { index, day ->
            db.workoutSetDao().insert(
                workoutSet(
                    sessionExerciseId = sessionExerciseId,
                    setNumber = index + 1,
                    isCompleted = true,
                    completedAt = at(day),
                    reps = 1,
                    weight = 1_000_000L,
                ),
            )
            db.workoutSessionDao().insert(
                session(
                    isInProgress = false,
                    lastActiveAt = at(day),
                    createdAt = at(day),
                    completedAt = at(day),
                ),
            )
        }

        val expected = days.map { TrainingWeek.startOf(it) }.distinct().sorted()
        val range = days.first().toEpochDay() to days.last().toEpochDay() + 1

        val volumeWeeks = db.workoutSetDao().getVolumeByWeek(range.first, range.second).first()
            .map { LocalDate.ofEpochDay(it.weekStartEpochDay) }
        val trainingWeeks = db.workoutSessionDao().getTrainingByWeek(range.first, range.second).first()
            .map { LocalDate.ofEpochDay(it.weekStartEpochDay) }

        assertEquals(expected, volumeWeeks)
        assertEquals(expected, trainingWeeks)
    }

    @Test
    fun `the week's own days all land in it and none of its neighbours do`() = runBlocking {
        val sessionExerciseId = seedSessionExercise()

        // The Sunday before, the whole of ANCHOR's week, and the Monday after.
        (-1L..7L).forEach { offset ->
            val day = ANCHOR.plusDays(offset)
            db.workoutSetDao().insert(
                workoutSet(
                    sessionExerciseId = sessionExerciseId,
                    setNumber = (offset + 2).toInt(),
                    isCompleted = true,
                    completedAt = at(day),
                    reps = 1,
                    weight = 1_000_000L,
                ),
            )
        }

        val byWeek = db.workoutSetDao()
            .getVolumeByWeek(ANCHOR.minusDays(7).toEpochDay(), ANCHOR.plusDays(14).toEpochDay())
            .first()
            .associate { LocalDate.ofEpochDay(it.weekStartEpochDay) to it.totalVolume }

        // Seven days in ANCHOR's week, one either side of it, at 1kg x 1 apiece.
        assertEquals(7_000_000L, byWeek.getValue(ANCHOR))
        assertEquals(1_000_000L, byWeek.getValue(TrainingWeek.startOf(ANCHOR.minusDays(1))))
        assertEquals(1_000_000L, byWeek.getValue(TrainingWeek.startOf(ANCHOR.plusDays(7))))
    }

    private suspend fun seedSessionExercise(): Long {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = db.workoutSessionDao().insert(session())
        return db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
    }
}
