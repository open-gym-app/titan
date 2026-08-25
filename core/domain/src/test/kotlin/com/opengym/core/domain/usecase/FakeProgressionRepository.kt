package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.model.WeeklyVolume
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.stats.TrainingWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * The weekly aggregates, in memory, built to behave the way the SQL ones do rather than the
 * way a test would find convenient.
 *
 * Three properties of the real queries are reproduced here on purpose, because a use case
 * that only worked against a friendlier fake would be a use case that does not work:
 *
 *  - **A week with nothing in it has no row.** `GROUP BY` produces rows for what exists, so
 *    filling the gaps is the use case's job and a fake that pre-filled them would prove
 *    nothing about it.
 *  - **Rows outside the requested range are not returned**, and the range is filtered by
 *    *day* — the same half-open comparison against `completed_at` the queries make — so a
 *    seeded week that only partly overlaps a period appears exactly when its Monday does.
 *  - **Rows come back ascending by week**, since both queries are `ORDER BY week_start ASC`.
 *
 * [requestedRanges] records what was asked for, which is how a test asserts that the
 * comparison period is fetched by widening one query rather than by making a second.
 */
class FakeProgressionRepository : ProgressionRepository {

    private val volume = MutableStateFlow<List<WeeklyVolume>>(emptyList())
    private val training = MutableStateFlow<List<WeeklyTraining>>(emptyList())

    /** Every range this repository has been read over, in call order. */
    val requestedRanges = mutableListOf<ClosedRange<LocalDate>>()

    /**
     * Seeds one week's volume. [weekStart] is snapped to its Monday, so a test can write a
     * date that reads like a training day and still land in the bucket the query would put
     * it in.
     */
    fun setVolume(vararg weeks: Pair<LocalDate, Long>) {
        volume.value = weeks
            .map { (day, total) -> WeeklyVolume(TrainingWeek.startOf(day), total) }
            .sortedBy(WeeklyVolume::weekStart)
    }

    /** Seeds one week's training days and sessions, snapped as [setVolume] snaps. */
    fun setTraining(vararg weeks: Triple<LocalDate, Int, Int>) {
        training.value = weeks
            .map { (day, days, sessions) -> WeeklyTraining(TrainingWeek.startOf(day), days, sessions) }
            .sortedBy(WeeklyTraining::weekStart)
    }

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> {
        requestedRanges += days
        return volume.map { rows -> rows.filter { it.weekStart in days } }
    }

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> {
        requestedRanges += days
        return training.map { rows -> rows.filter { it.weekStart in days } }
    }
}
