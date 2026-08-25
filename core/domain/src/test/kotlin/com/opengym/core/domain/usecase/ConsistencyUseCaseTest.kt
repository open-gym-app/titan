package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.stats.TrainingWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Sessions per week, and the "vs prev" figure beside it. The averages are written out with
 * their division shown, so a change to how the period's width is counted fails here rather
 * than quietly moving the number the screen draws.
 */
class ConsistencyUseCaseTest {

    private companion object {
        /** 2024-10-07, a Monday. */
        val WEEK_1: LocalDate = LocalDate.of(2024, 10, 7)
        val WEEK_2: LocalDate = WEEK_1.plusDays(7)
        val WEEK_3: LocalDate = WEEK_1.plusDays(14)
        val WEEK_4: LocalDate = WEEK_1.plusDays(21)
    }

    private val repository = FakeProgressionRepository()
    private val useCase = ConsistencyUseCase(repository)

    /** `(week, trainingDays, sessionCount)`, the shape the aggregate returns. */
    private fun week(start: LocalDate, days: Int, sessions: Int = days) = Triple(start, days, sessions)

    @Test
    fun `a multi-week series reports each week's training days`() = runTest {
        repository.setTraining(week(WEEK_1, 4), week(WEEK_2, 3), week(WEEK_3, 5))

        val trend = useCase(WEEK_1..WEEK_3.plusDays(6)).first()

        assertEquals(listOf(WEEK_1, WEEK_2, WEEK_3), trend.weeks.map(WeeklyTraining::weekStart))
        assertEquals(listOf(4, 3, 5), trend.weeks.map(WeeklyTraining::trainingDays))
        assertEquals(12, trend.totalTrainingDays)
        // 12 days over 3 weeks.
        assertEquals(4.0, trend.averagePerWeek, 1e-9)
    }

    @Test
    fun `a single-week period reports one bucket`() = runTest {
        repository.setTraining(week(WEEK_1, 3))

        val trend = useCase(WEEK_1..WEEK_1.plusDays(6)).first()

        assertEquals(listOf(WeeklyTraining(WEEK_1, trainingDays = 3, sessionCount = 3)), trend.weeks)
        assertEquals(3.0, trend.averagePerWeek, 1e-9)
    }

    @Test
    fun `two sessions in one day are one training day, and both counts are carried`() = runTest {
        // A week of four training days holding six sessions: two of the days were doubles.
        repository.setTraining(week(WEEK_1, days = 4, sessions = 6))

        val trend = useCase(WEEK_1..WEEK_1.plusDays(6)).first()

        assertEquals(4, trend.totalTrainingDays)
        assertEquals(6, trend.totalSessions)
        // The average is built on days, so the doubles do not inflate it past four.
        assertEquals(4.0, trend.averagePerWeek, 1e-9)
    }

    @Test
    fun `a week off is a zero the average is divided by`() = runTest {
        repository.setTraining(week(WEEK_1, 4), week(WEEK_3, 4))

        val trend = useCase(WEEK_1..WEEK_3.plusDays(6)).first()

        assertEquals(listOf(4, 0, 4), trend.weeks.map(WeeklyTraining::trainingDays))
        assertEquals(0, trend.weeks[1].sessionCount)
        // 8 days over 3 weeks, not over the 2 that had anything in them.
        assertEquals(8.0 / 3.0, trend.averagePerWeek, 1e-9)
    }

    @Test
    fun `a mid-week period is widened to the whole weeks it touches`() = runTest {
        repository.setTraining(week(WEEK_1, 4), week(WEEK_2, 2))

        val trend = useCase(WEEK_1.plusDays(4)..WEEK_2.plusDays(1)).first()

        assertEquals(listOf(WEEK_1, WEEK_2), trend.weeks.map(WeeklyTraining::weekStart))
        assertEquals(3.0, trend.averagePerWeek, 1e-9)
    }

    @Test
    fun `the change against the previous period is a difference in days per week`() = runTest {
        repository.setTraining(
            week(WEEK_1, 2),
            week(WEEK_2, 3),
            week(WEEK_3, 4),
            week(WEEK_4, 3),
        )

        // Weeks 3 and 4 against weeks 1 and 2: 3.5 a week against 2.5.
        val trend = useCase(WEEK_3..WEEK_4.plusDays(6)).first()

        assertEquals(3.5, trend.averagePerWeek, 1e-9)
        assertEquals(2.5, trend.previousAveragePerWeek!!, 1e-9)
        assertEquals(1.0, trend.changePerWeek!!, 1e-9)
    }

    @Test
    fun `the previous period's average is divided by its whole width too`() = runTest {
        // One week of training in a two-week comparison period: 3 days over 2 weeks, not 3.
        repository.setTraining(week(WEEK_1, 3), week(WEEK_3, 4), week(WEEK_4, 4))

        val trend = useCase(WEEK_3..WEEK_4.plusDays(6)).first()

        assertEquals(1.5, trend.previousAveragePerWeek!!, 1e-9)
        assertEquals(2.5, trend.changePerWeek!!, 1e-9)
    }

    @Test
    fun `both periods are read in one query`() = runTest {
        repository.setTraining(week(WEEK_3, 4))

        useCase(WEEK_3..WEEK_4.plusDays(6)).first()

        assertEquals(1, repository.requestedRanges.size)
        assertEquals(WEEK_1..WEEK_4.plusDays(6), repository.requestedRanges.single())
    }

    @Test
    fun `a first period has nothing to be compared against`() = runTest {
        repository.setTraining(week(WEEK_1, 4))

        val trend = useCase(WEEK_1..WEEK_1.plusDays(6)).first()

        assertNull(trend.previousAveragePerWeek)
        assertNull(trend.changePerWeek)
    }

    @Test
    fun `a user with no sessions gets an empty series and a zero average`() = runTest {
        val trend = useCase(TrainingWeek.trailing(weeks = 12, endingOn = WEEK_4)).first()

        assertTrue(trend.weeks.isEmpty())
        assertEquals(0, trend.totalTrainingDays)
        assertEquals(0, trend.totalSessions)
        assertEquals(0.0, trend.averagePerWeek, 1e-9)
        assertNull(trend.previousAveragePerWeek)
        assertNull(trend.changePerWeek)
    }

    @Test
    fun `a period with nothing in it is empty even when the one before it was not`() = runTest {
        repository.setTraining(week(WEEK_1, 4), week(WEEK_2, 4))

        val trend = useCase(WEEK_3..WEEK_4.plusDays(6)).first()

        assertTrue(trend.weeks.isEmpty())
        assertEquals(4.0, trend.previousAveragePerWeek!!, 1e-9)
        // Stopped altogether: four days a week fewer than before.
        assertEquals(-4.0, trend.changePerWeek!!, 1e-9)
    }

    @Test
    fun `the trailing window is the last twelve weeks, this one included`() = runTest {
        repository.setTraining(week(WEEK_4, 3))

        // Asked on the Thursday of week 4.
        val trend = useCase(TrainingWeek.trailing(weeks = 12, endingOn = WEEK_4.plusDays(3))).first()

        assertEquals(12, trend.weeks.size)
        assertEquals(WEEK_4, trend.weeks.last().weekStart)
        assertEquals(3, trend.weeks.last().trainingDays)
        // 3 days over the 12 weeks the window covers.
        assertEquals(0.25, trend.averagePerWeek, 1e-9)
    }

    @Test
    fun `an inverted period is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            useCase(WEEK_3..WEEK_1)
        }
    }
}
