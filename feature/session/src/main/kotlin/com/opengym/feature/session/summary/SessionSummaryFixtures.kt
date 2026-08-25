package com.opengym.feature.session.summary

import com.opengym.core.domain.model.ExerciseStats
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.ui.component.muscleContributions

/*
 * A worked session, for the previews to render.
 *
 * In `main` rather than in `test` because the `@Preview`s that consume it are — they are what
 * the screenshot tests capture, and a preview cannot reach into a test source set. The screen
 * tests read the same fixture, which is the point: a state that is awkward to build by hand is
 * a signal the UI state model is wrong, and one built twice is two states that drift.
 *
 * The figures are a real push day: milligrams for weights and volume, milligram-reps for
 * attributed volume, so the formatters have something to actually format.
 */

/** A finished push day, in the shape the ViewModel would have assembled it. */
internal fun previewState(
    mode: SessionSummaryMode = SessionSummaryMode.JUST_FINISHED,
    records: List<SummaryRecord> = previewRecords(),
    notes: String? = "Felt strong on bench. Bumped to 80 kg × 5 for the top set.",
    notesDraft: String? = null,
    templateName: String? = null,
): SessionSummaryUiState {
    val muscles = previewMuscleVolume()

    return SessionSummaryUiState(
        mode = mode,
        header = SummaryHeader(
            name = "Push Day · Upper",
            startedAt = PREVIEW_STARTED_AT,
            finishedAt = PREVIEW_FINISHED_AT,
        ),
        stats = previewStats(),
        records = records,
        muscles = muscleContributions(
            volumeByMuscle = muscles,
            primaryMuscleIds = setOf(CHEST_ID, SHOULDERS_ID),
        ),
        primaryMuscles = muscles.keys.filter { it.id in setOf(CHEST_ID, SHOULDERS_ID) },
        secondaryMuscles = muscles.keys.filterNot { it.id in setOf(CHEST_ID, SHOULDERS_ID) },
        exercises = previewExercises(),
        notes = notes,
        notesDraft = notesDraft,
        templateName = templateName,
        isLoading = false,
    )
}

internal fun previewStats(): SessionStats =
    SessionStats(
        totalSets = 17,
        totalVolume = 4_820_000_000,
        durationSeconds = 3_144,
        exerciseBreakdown = List(PREVIEW_EXERCISE_COUNT) { index ->
            ExerciseStats(sessionExerciseId = index + 1L, totalSets = 3, totalVolume = 964_000_000)
        },
    )

internal fun previewRecords(): List<SummaryRecord> =
    listOf(
        SummaryRecord(
            record = PersonalRecord(
                id = 1,
                exerciseId = 1,
                workoutSetId = 4,
                type = PrType.ESTIMATED_1RM,
                estimated1Rm = 92_000_000,
                achievedAt = PREVIEW_FINISHED_AT,
            ),
            exerciseName = "Barbell bench press",
        ),
    )

internal fun previewExercises(): List<SummaryExerciseRow> =
    listOf(
        SummaryExerciseRow(
            sessionExerciseId = 1,
            name = "Barbell bench press",
            setCount = 4,
            lowestReps = 5,
            highestReps = 10,
            topWeight = 80_000_000,
            record = previewRecords().first().record,
        ),
        SummaryExerciseRow(
            sessionExerciseId = 2,
            name = "Incline dumbbell press",
            setCount = 4,
            lowestReps = 10,
            highestReps = 10,
            topWeight = 22_000_000,
        ),
        SummaryExerciseRow(
            sessionExerciseId = 3,
            name = "Cable fly",
            setCount = 3,
            lowestReps = 12,
            highestReps = 12,
            topWeight = 18_000_000,
        ),
        SummaryExerciseRow(
            sessionExerciseId = 4,
            name = "Triceps pushdown",
            setCount = 3,
            lowestReps = 12,
            highestReps = 15,
            topWeight = 30_000_000,
        ),
        // No weight and no reps: a plank belongs in a push day and logs neither, so the meta line
        // is absent rather than reading `3 × 0 · 0 kg max`.
        SummaryExerciseRow(
            sessionExerciseId = 5,
            name = "Plank",
            setCount = 3,
            lowestReps = null,
            highestReps = null,
            topWeight = null,
        ),
    )

/** Ordered highest first, as `MuscleVolumeCalculator` returns it. */
private fun previewMuscleVolume(): Map<MuscleGroup, Long> =
    linkedMapOf(
        MuscleGroup(id = CHEST_ID, name = "CHEST") to CHEST_VOLUME,
        MuscleGroup(id = SHOULDERS_ID, name = "SHOULDERS") to SHOULDERS_VOLUME,
        MuscleGroup(id = TRICEPS_ID, name = "TRICEPS") to TRICEPS_VOLUME,
        MuscleGroup(id = ABS_ID, name = "ABDOMINALS") to ABS_VOLUME,
    )

/** How many exercises the fixture's session performed. */
private const val PREVIEW_EXERCISE_COUNT = 5

private const val CHEST_ID = 1L
private const val SHOULDERS_ID = 2L
private const val TRICEPS_ID = 3L
private const val ABS_ID = 4L

/** Attributed volume in milligram-reps: a chest that took about twice what the triceps did. */
private const val CHEST_VOLUME = 2_480_000_000L
private const val SHOULDERS_VOLUME = 1_960_000_000L
private const val TRICEPS_VOLUME = 1_240_000_000L
private const val ABS_VOLUME = 420_000_000L

/** 2026-04-28, 10:14 and 11:06 UTC — a Tuesday morning, and a 52-minute session. */
private const val PREVIEW_STARTED_AT = 1_777_284_840_000L
private const val PREVIEW_FINISHED_AT = 1_777_287_984_000L
