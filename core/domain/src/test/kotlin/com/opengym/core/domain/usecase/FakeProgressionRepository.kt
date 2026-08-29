package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.MuscleTraining
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
 *  - **Rows outside the requested range are not returned.** The fake holds weekly totals, so
 *    it can only filter on the bucket's Monday; the real queries filter row by row and can
 *    hand back a *partial* boundary bucket. Rather than let that difference pass unnoticed,
 *    a range that is not whole weeks is **rejected** — see [requireWholeWeeks]. A use case
 *    that stopped snapping its period would fail here instead of quietly agreeing with a
 *    fake that cannot produce the bucket it would then be wrong about.
 *  - **Rows come back ascending by week**, since both queries are `ORDER BY week_start ASC`.
 *
 * [requestedRanges] records what was asked for, which is how a test asserts that the
 * comparison period is fetched by widening one query rather than by making a second.
 */
class FakeProgressionRepository : ProgressionRepository {

    private val volume = MutableStateFlow<List<WeeklyVolume>>(emptyList())
    private val training = MutableStateFlow<List<WeeklyTraining>>(emptyList())
    private val everTrained = MutableStateFlow(false)
    private val muscleTraining = MutableStateFlow<List<MuscleTraining>>(emptyList())
    private val trainingMillis = MutableStateFlow(0L)
    private val firstTrainingDay = MutableStateFlow<LocalDate?>(null)

    /** Every range this repository has been read over, in call order. */
    val requestedRanges = mutableListOf<ClosedRange<LocalDate>>()

    /**
     * Seeds one week's volume. [weekStart] is snapped to its Monday, so a test can write a
     * date that reads like a training day and still land in the bucket the query would put
     * it in. Seeding anything also makes [hasTrainingHistory] true, since a seeded week is
     * history; [setHasTrainingHistory] is for the case that has none to seed.
     */
    fun setVolume(vararg weeks: Pair<LocalDate, Long>) {
        volume.value = weeks
            .map { (day, total) -> WeeklyVolume(TrainingWeek.startOf(day), total) }
            .sortedBy(WeeklyVolume::weekStart)
        if (weeks.isNotEmpty()) everTrained.value = true
    }

    /** Seeds one week's training days and sessions, snapped as [setVolume] snaps. */
    fun setTraining(vararg weeks: Triple<LocalDate, Int, Int>) {
        training.value = weeks
            .map { (day, days, sessions) -> WeeklyTraining(TrainingWeek.startOf(day), days, sessions) }
            .sortedBy(WeeklyTraining::weekStart)
        if (weeks.isNotEmpty()) everTrained.value = true
    }

    /** Seeds the muscle map, in the order the query would return it. */
    fun setMuscleTraining(vararg muscles: MuscleTraining) {
        muscleTraining.value = muscles.toList()
    }

    /** Seeds how long the period's sessions ran for, in milliseconds. */
    fun setTrainingMillis(millis: Long) {
        trainingMillis.value = millis
    }

    /** Seeds the day the user first finished a session, or null for one who never has. */
    fun setFirstTrainingDay(day: LocalDate?) {
        firstTrainingDay.value = day
    }

    /**
     * Sets the all-history predicate on its own — the lapsed user, whose training is real but
     * falls outside every range the use case asks for and so cannot be seeded as a week.
     */
    fun setHasTrainingHistory(value: Boolean) {
        everTrained.value = value
    }

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> {
        requestedRanges += requireWholeWeeks(days)
        return volume.map { rows -> rows.filter { it.weekStart in days } }
    }

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> {
        requestedRanges += requireWholeWeeks(days)
        return training.map { rows -> rows.filter { it.weekStart in days } }
    }

    override fun hasTrainingHistory(): Flow<Boolean> = everTrained

    /*
     * The three period readings, which nothing in this module derives anything from — they are
     * read straight by a screen, so there is no gap-filling or bucketing for a fake to model.
     * Plain seedable values, and still routed through [requireWholeWeeks] so a caller that
     * stopped snapping its period fails here as it does above.
     */

    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> {
        requestedRanges += requireWholeWeeks(days)
        return muscleTraining
    }

    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> {
        requestedRanges += requireWholeWeeks(days)
        return trainingMillis
    }

    override fun getFirstTrainingDay(): Flow<LocalDate?> = firstTrainingDay

    /**
     * Rejects a range that starts anywhere but a Monday or ends anywhere but a Sunday.
     *
     * The real queries answer such a range with a partial boundary bucket; this holds only
     * whole weeks and would answer with the whole one or with nothing, either of which is a
     * wrong number a test would read as right. Refusing is the honest option, and it turns
     * "the caller forgot to snap" into a failure rather than a coincidence.
     */
    private fun requireWholeWeeks(days: ClosedRange<LocalDate>): ClosedRange<LocalDate> {
        require(TrainingWeek.startOf(days.start) == days.start) {
            "range must start on a Monday, was ${days.start}"
        }
        val dayAfter = days.endInclusive.plusDays(1)
        require(TrainingWeek.startOf(dayAfter) == dayAfter) {
            "range must end on a Sunday, was ${days.endInclusive}"
        }
        return days
    }
}
