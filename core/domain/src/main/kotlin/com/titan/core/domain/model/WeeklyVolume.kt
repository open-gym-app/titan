package com.titan.core.domain.model

import java.time.LocalDate

/**
 * One bucket of the volume trend: everything lifted in the week opening on [weekStart].
 *
 * The domain twin of `WeeklyVolumeRow`, which is what SQLite actually sums — see
 * [com.titan.core.domain.stats.TrainingWeek] for what a week is here and why the
 * aggregation stays in the database.
 *
 * @property weekStart the Monday (UTC) the bucket opens on.
 * @property totalVolume `weight x reps` over the week's completed sets, in milligram-reps,
 *   matching [com.titan.core.domain.stats.VolumeCalculator]. Zero is a real value: a week
 *   of nothing but cardio trained hard and lifted nothing, and so does a week off — the
 *   series carries the second as a bucket rather than a gap so a chart can draw it.
 */
data class WeeklyVolume(
    val weekStart: LocalDate,
    val totalVolume: Long,
)
