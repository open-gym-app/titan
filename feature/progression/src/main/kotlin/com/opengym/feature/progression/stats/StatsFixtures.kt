package com.opengym.feature.progression.stats

import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.MuscleTraining
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.model.WeeklyVolume
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours

/*
 * The state the hub's previews are drawn from.
 *
 * In `main` beside the previews that use it, which is where `HomeFixtures` keeps the same
 * thing, and internal so nothing outside this module can build a screen on it. A quarter of
 * plausible training: thirteen weeks climbing with a deload in the middle, six muscle groups,
 * and a fortnight's worth of records.
 *
 * The dates are fixed rather than derived from a clock. A preview whose figures move with the
 * calendar is a screenshot reference that fails on a Monday.
 */

/** The Monday the preview quarter opens on. */
private val PREVIEW_START: LocalDate = LocalDate.of(2026, 2, 2)

/** Thirteen weeks of volume in milligram-reps, with a deload in the eighth. */
private val PREVIEW_WEEKLY_VOLUME = listOf(
    12_400L, 13_900L, 12_800L, 15_200L, 14_600L, 16_800L, 15_900L,
    9_400L, 17_600L, 18_200L, 17_100L, 19_800L, 21_400L,
)

internal fun previewVolume(): VolumeState =
    VolumeState(
        total = PREVIEW_WEEKLY_VOLUME.sum() * MILLIGRAMS_PER_KILOGRAM,
        weeks = PREVIEW_WEEKLY_VOLUME.mapIndexed { week, volume ->
            WeeklyVolume(
                weekStart = PREVIEW_START.plusWeeks(week.toLong()),
                totalVolume = volume * MILLIGRAMS_PER_KILOGRAM,
            )
        },
        changeRatio = PREVIEW_VOLUME_CHANGE,
    )

internal fun previewSummary(): SummaryState =
    SummaryState(
        sessions = PREVIEW_SESSIONS,
        averagePerWeek = PREVIEW_AVERAGE_PER_WEEK,
        averageChange = PREVIEW_AVERAGE_CHANGE,
        records = RecordTally(records = 7, exercises = 5),
        trainingMillis = PREVIEW_TRAINING_HOURS.hours.inWholeMilliseconds,
    )

internal fun previewMuscles(): List<MuscleTraining> =
    listOf(
        muscle(id = 1, name = "CHEST", sets = 18, isPrimary = true),
        muscle(id = 2, name = "QUADRICEPS", sets = 16, isPrimary = true),
        muscle(id = 3, name = "BICEPS", sets = 14, isPrimary = true),
        muscle(id = 4, name = "LATS", sets = 12, isPrimary = false),
        muscle(id = 5, name = "SHOULDERS", sets = 10, isPrimary = false),
        muscle(id = 6, name = "HAMSTRINGS", sets = 6, isPrimary = false),
        muscle(id = 7, name = "CALVES", sets = 4, isPrimary = false),
        muscle(id = 8, name = "ABS", sets = 3, isPrimary = false),
    )

private fun muscle(
    id: Long,
    name: String,
    sets: Int,
    isPrimary: Boolean,
) = MuscleTraining(
    muscle = MuscleGroup(id = id, name = name),
    sets = sets,
    // Roughly proportional to the sets, so the figures read as a plausible session rather than
    // as a rank the card does not draw.
    volume = sets * PREVIEW_VOLUME_PER_SET * MILLIGRAMS_PER_KILOGRAM,
    isPrimary = isPrimary,
)

private const val MILLIGRAMS_PER_KILOGRAM = 1_000_000L
private const val PREVIEW_VOLUME_PER_SET = 420L
private const val PREVIEW_VOLUME_CHANGE = 0.18
private const val PREVIEW_SESSIONS = 38
private const val PREVIEW_AVERAGE_PER_WEEK = 3.2
private const val PREVIEW_AVERAGE_CHANGE = 0.4
private const val PREVIEW_TRAINING_HOURS = 32
