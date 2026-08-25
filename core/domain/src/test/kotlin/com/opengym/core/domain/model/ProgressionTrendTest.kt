package com.opengym.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The figures the trend models derive from their own buckets — the ones a screen reads
 * straight off, and which nothing but these assertions holds to their documented shape.
 */
class ProgressionTrendTest {

    private companion object {
        val WEEK_1: LocalDate = LocalDate.of(2024, 10, 7)
        val WEEK_2: LocalDate = WEEK_1.plusDays(7)
    }

    @Test
    fun `an empty volume trend is a zero total with nothing to compare against`() {
        val trend = VolumeTrend.EMPTY

        assertTrue(trend.weeks.isEmpty())
        assertEquals(0L, trend.totalVolume)
        assertNull(trend.previousTotalVolume)
        assertNull(trend.volumeChangeRatio)
        assertTrue(trend.isFirstRun)
        assertFalse(trend.isQuietPeriod)
    }

    @Test
    fun `an empty period tells a break apart from a first run`() {
        val lapsed = VolumeTrend(
            weeks = emptyList(),
            previousTotalVolume = null,
            hasEverTrained = true,
        )

        // Identical to VolumeTrend.EMPTY in every figure a chart reads; the one bit that
        // differs is the one that decides whether a first-session prompt is shown.
        assertEquals(VolumeTrend.EMPTY.weeks, lapsed.weeks)
        assertEquals(VolumeTrend.EMPTY.totalVolume, lapsed.totalVolume)
        assertEquals(VolumeTrend.EMPTY.previousTotalVolume, lapsed.previousTotalVolume)
        assertTrue(lapsed.isQuietPeriod)
        assertFalse(lapsed.isFirstRun)
    }

    @Test
    fun `a volume trend's total is the sum of its weeks`() {
        val trend = VolumeTrend(
            weeks = listOf(WeeklyVolume(WEEK_1, 3_000L), WeeklyVolume(WEEK_2, 4_500L)),
            previousTotalVolume = 5_000L,
            hasEverTrained = true,
        )

        assertEquals(7_500L, trend.totalVolume)
        // 7,500 against 5,000 is half as much again.
        assertEquals(0.5, trend.volumeChangeRatio!!, 1e-9)
    }

    @Test
    fun `an empty consistency trend averages to zero rather than dividing by none`() {
        val trend = ConsistencyTrend.EMPTY

        assertTrue(trend.weeks.isEmpty())
        assertEquals(0, trend.totalTrainingDays)
        assertEquals(0, trend.totalSessions)
        assertEquals(0.0, trend.averagePerWeek, 1e-9)
        assertNull(trend.previousAveragePerWeek)
        assertNull(trend.changePerWeek)
        assertTrue(trend.isFirstRun)
        assertFalse(trend.isQuietPeriod)
    }

    @Test
    fun `a consistency trend counts days and sessions separately`() {
        val trend = ConsistencyTrend(
            weeks = listOf(
                WeeklyTraining(WEEK_1, trainingDays = 4, sessionCount = 6),
                WeeklyTraining(WEEK_2, trainingDays = 3, sessionCount = 3),
            ),
            previousAveragePerWeek = 2.0,
            hasEverTrained = true,
        )

        assertEquals(7, trend.totalTrainingDays)
        assertEquals(9, trend.totalSessions)
        // 7 days over 2 weeks - the doubles do not enter the average.
        assertEquals(3.5, trend.averagePerWeek, 1e-9)
        assertEquals(1.5, trend.changePerWeek!!, 1e-9)
    }
}
