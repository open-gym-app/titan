package com.titan.feature.progression.records

import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.domain.model.PrType

/*
 * A handful of records, for the previews to render.
 *
 * In `main` rather than in `test` because the `@Preview`s that consume it are — they are what the
 * screenshot tests capture, and a preview cannot reach into a test source set. The screen tests
 * read the same fixture, which is the point: a state built twice is two states that drift.
 *
 * The set is chosen to exercise what the row can draw rather than to look plausible: one record
 * that improved on another, one that is the first of its kind, one badged as just set, and one
 * whose exercise has been removed from the catalogue.
 */

internal fun previewRecordEntries(): List<PersonalRecordEntry> =
    listOf(
        PersonalRecordEntry(
            record = previewRecord(
                id = 1,
                exerciseId = BENCH_ID,
                type = PrType.MAX_WEIGHT,
                weight = 92_000_000,
                achievedAt = APRIL_21,
            ),
            exerciseName = "Bench press",
            previousValue = 80_000_000,
            isRecent = true,
        ),
        PersonalRecordEntry(
            record = previewRecord(
                id = 2,
                exerciseId = BENCH_ID,
                type = PrType.MAX_REPS,
                reps = 12,
                achievedAt = APRIL_21,
            ),
            exerciseName = "Bench press",
            // A first of its kind: nothing to improve on, so the row draws no improvement at all.
            previousValue = null,
            isRecent = true,
        ),
        PersonalRecordEntry(
            record = previewRecord(
                id = 3,
                exerciseId = SQUAT_ID,
                type = PrType.ESTIMATED_1RM,
                estimated1Rm = 148_000_000,
                achievedAt = APRIL_14,
            ),
            exerciseName = "Back squat",
            previousValue = 141_000_000,
            isRecent = false,
        ),
        PersonalRecordEntry(
            record = previewRecord(
                id = 4,
                exerciseId = GONE_ID,
                type = PrType.MAX_VOLUME,
                weight = 2_240_000_000,
                achievedAt = APRIL_7,
            ),
            // Removed from the catalogue since. The record still stands, so the row draws a fallback
            // title rather than being dropped.
            exerciseName = null,
            previousValue = 1_960_000_000,
            isRecent = false,
        ),
    )

private fun previewRecord(
    id: Long,
    exerciseId: Long,
    type: PrType,
    achievedAt: Long,
    weight: Long? = null,
    reps: Int? = null,
    estimated1Rm: Long? = null,
) = PersonalRecord(
    id = id,
    exerciseId = exerciseId,
    workoutSetId = id,
    type = type,
    weight = weight,
    reps = reps,
    estimated1Rm = estimated1Rm,
    achievedAt = achievedAt,
)

private const val BENCH_ID = 1L
private const val SQUAT_ID = 2L
private const val GONE_ID = 3L

/** 2026-04-21, -14 and -07, so the dates read as a descending list. */
private const val APRIL_21 = 1_776_729_600_000L
private const val APRIL_14 = 1_776_124_800_000L
private const val APRIL_7 = 1_775_520_000_000L
