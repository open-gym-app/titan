package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.WeeklyVolume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The weekly volume series, against a fixture whose sums are written out as literals with
 * the arithmetic beside them — a test that recomputed them with the use case's own rule
 * would agree with whatever the rule became.
 */
class VolumeTrendUseCaseTest {

    private companion object {
        /** 2024-10-07, a Monday, matching `AnalyticsDaoTest`'s anchor. */
        val WEEK_1: LocalDate = LocalDate.of(2024, 10, 7)
        val WEEK_2: LocalDate = WEEK_1.plusDays(7)
        val WEEK_3: LocalDate = WEEK_1.plusDays(14)
        val WEEK_4: LocalDate = WEEK_1.plusDays(21)

        /** Kilogram-reps in the canonical storage unit, milligram-reps. */
        fun kg(value: Long): Long = value * 1_000_000L
    }

    private val repository = FakeProgressionRepository()
    private val useCase = VolumeTrendUseCase(repository)

    @Test
    fun `a multi-week series reports each week's own sum`() = runTest {
        repository.setVolume(
            WEEK_1 to kg(12_000),
            WEEK_2 to kg(9_500),
            WEEK_3 to kg(14_250),
        )

        val trend = useCase(WEEK_1..WEEK_3.plusDays(6)).first()

        assertEquals(listOf(WEEK_1, WEEK_2, WEEK_3), trend.weeks.map(WeeklyVolume::weekStart))
        assertEquals(
            listOf(kg(12_000), kg(9_500), kg(14_250)),
            trend.weeks.map(WeeklyVolume::totalVolume),
        )
        // 12,000 + 9,500 + 14,250 kg-reps.
        assertEquals(kg(35_750), trend.totalVolume)
    }

    @Test
    fun `a single-week period reports one bucket`() = runTest {
        repository.setVolume(WEEK_1 to kg(8_000))

        val trend = useCase(WEEK_1..WEEK_1.plusDays(6)).first()

        assertEquals(listOf(WeeklyVolume(WEEK_1, kg(8_000))), trend.weeks)
        assertEquals(kg(8_000), trend.totalVolume)
    }

    @Test
    fun `a week nobody trained in is a zero in the series, not a gap`() = runTest {
        // The middle week is missing from the aggregate, as it would be from the query.
        repository.setVolume(WEEK_1 to kg(12_000), WEEK_3 to kg(10_000))

        val trend = useCase(WEEK_1..WEEK_3.plusDays(6)).first()

        assertEquals(listOf(WEEK_1, WEEK_2, WEEK_3), trend.weeks.map(WeeklyVolume::weekStart))
        assertEquals(0L, trend.weeks[1].totalVolume)
        assertEquals(kg(22_000), trend.totalVolume)
    }

    @Test
    fun `a mid-week period is widened to the whole weeks it touches`() = runTest {
        repository.setVolume(WEEK_1 to kg(12_000), WEEK_2 to kg(9_500))

        // Asked for the Thursday of week 1 to the Wednesday of week 2 - eight days.
        val trend = useCase(WEEK_1.plusDays(3)..WEEK_2.plusDays(2)).first()

        assertEquals(listOf(WEEK_1, WEEK_2), trend.weeks.map(WeeklyVolume::weekStart))
        assertEquals(kg(21_500), trend.totalVolume)
    }

    @Test
    fun `the previous period is the same number of weeks immediately before`() = runTest {
        repository.setVolume(
            WEEK_1 to kg(6_000),
            WEEK_2 to kg(4_000),
            WEEK_3 to kg(12_000),
            WEEK_4 to kg(13_000),
        )

        // Weeks 3 and 4, compared against weeks 1 and 2.
        val trend = useCase(WEEK_3..WEEK_4.plusDays(6)).first()

        assertEquals(kg(25_000), trend.totalVolume)
        assertEquals(kg(10_000), trend.previousTotalVolume)
        // 25,000 against 10,000 is two and a half times as much.
        assertEquals(1.5, trend.volumeChangeRatio!!, 1e-9)
    }

    @Test
    fun `both periods are read in one query`() = runTest {
        repository.setVolume(WEEK_3 to kg(1_000))

        useCase(WEEK_3..WEEK_4.plusDays(6)).first()

        assertEquals(1, repository.requestedRanges.size)
        assertEquals(WEEK_1..WEEK_4.plusDays(6), repository.requestedRanges.single())
    }

    @Test
    fun `a fall against the previous period is a negative ratio`() = runTest {
        repository.setVolume(WEEK_1 to kg(10_000), WEEK_2 to kg(7_500))

        val trend = useCase(WEEK_2..WEEK_2.plusDays(6)).first()

        assertEquals(-0.25, trend.volumeChangeRatio!!, 1e-9)
    }

    @Test
    fun `a first period has nothing to be compared against`() = runTest {
        repository.setVolume(WEEK_1 to kg(12_000))

        val trend = useCase(WEEK_1..WEEK_1.plusDays(6)).first()

        assertNull(trend.previousTotalVolume)
        assertNull(trend.volumeChangeRatio)
    }

    @Test
    fun `a previous period that lifted nothing yields no ratio rather than a division by zero`() =
        runTest {
            // A week of cardio only: sessions were logged, the volume they added is zero.
            repository.setVolume(WEEK_1 to 0L, WEEK_2 to kg(9_000))

            val trend = useCase(WEEK_2..WEEK_2.plusDays(6)).first()

            assertEquals(0L, trend.previousTotalVolume)
            assertNull(trend.volumeChangeRatio)
        }

    @Test
    fun `an empty history is an empty series, not a run of zeroes`() = runTest {
        val trend = useCase(WEEK_1..WEEK_4.plusDays(6)).first()

        assertTrue(trend.weeks.isEmpty())
        assertEquals(0L, trend.totalVolume)
        assertNull(trend.previousTotalVolume)
        assertNull(trend.volumeChangeRatio)
    }

    @Test
    fun `a period with nothing in it is empty even when the one before it was not`() = runTest {
        repository.setVolume(WEEK_1 to kg(12_000), WEEK_2 to kg(9_000))

        // Two quiet weeks after a fortnight of training.
        val trend = useCase(WEEK_3..WEEK_4.plusDays(6)).first()

        assertTrue(trend.weeks.isEmpty())
        assertEquals(kg(21_000), trend.previousTotalVolume)
    }

    @Test
    fun `an inverted period is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            useCase(WEEK_3..WEEK_1)
        }
    }
}
