package com.opengym.feature.progression.exercise

import com.opengym.core.domain.model.AttemptSet
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.ExerciseEstimate
import com.opengym.core.domain.model.ExerciseProgression
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.TrainedExercise
import com.opengym.core.domain.pr.OneRepMaxCalculator
import com.opengym.feature.progression.stats.StatsTimeframe
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.roundToLong

/*
 * The state the drill-down's previews are drawn from.
 *
 * In `main` beside the previews that use it, where `StatsFixtures` and `HomeFixtures` keep the
 * same thing, and internal so nothing outside this module can build a screen on it. Twelve
 * weeks of a bench press that is going somewhere: a top set climbing 72 → 92 kg with the odd
 * flat week, and a personal record on the last of them.
 *
 * The dates are fixed rather than derived from a clock, for the reason `StatsFixtures` gives: a
 * preview whose figures move with the calendar is a screenshot reference that fails on a
 * Monday.
 */

/** The first session of the preview quarter. */
private val PREVIEW_START: LocalDate = LocalDate.of(2026, 2, 3)

/**
 * The top set of each session, `weight kg × reps`.
 *
 * Not a straight climb: two weeks repeat the weight for more reps and one is a deload, which
 * is what a real block looks like and what makes the line worth drawing.
 */
private val PREVIEW_TOP_SETS = listOf(
    72 to 5, 72 to 6, 75 to 5, 75 to 6, 77 to 5, 70 to 8,
    80 to 5, 80 to 6, 82 to 5, 85 to 4, 85 to 5, 92 to 3,
)

internal fun previewExercise(): Exercise =
    Exercise(
        id = PREVIEW_EXERCISE_ID,
        name = "Bench Press",
        type = ExerciseType.STRENGTH,
        equipmentType = EquipmentType.BARBELL,
    )

/** The full screen: twelve sessions, four records, one of them broken on the last of them. */
internal fun previewState(timeframe: StatsTimeframe = StatsTimeframe.DEFAULT): ExerciseProgressionUiState =
    ExerciseProgressionUiState(
        timeframe = timeframe,
        isLoading = false,
        exercise = previewExercise(),
        choices = previewChoices(),
        progression = previewProgression(),
    )

/** The chooser: no lift picked, a handful logged. */
internal fun previewChooserState(): ExerciseProgressionUiState =
    ExerciseProgressionUiState(isLoading = false, choices = previewChoices())

/** One session in the window, which is a table and no charts. */
internal fun previewSingleSessionState(): ExerciseProgressionUiState =
    previewState().copy(progression = previewProgression(sessions = 1))

/** A window the lift was not trained in, with its all-time records still above it. */
internal fun previewQuietState(): ExerciseProgressionUiState =
    previewState(timeframe = StatsTimeframe.WEEK).copy(progression = previewProgression(sessions = 0))

internal fun previewChoices(): List<TrainedExercise> =
    listOf(
        choice(id = PREVIEW_EXERCISE_ID, name = "Bench Press", sets = 142, week = 11),
        choice(id = 2, name = "Back Squat", sets = 128, week = 11),
        choice(id = 3, name = "Deadlift", sets = 96, week = 10),
        choice(id = 4, name = "Overhead Press", sets = 74, week = 10),
        choice(id = 5, name = "Barbell Row", sets = 68, week = 9),
    )

/**
 * The readings, cut to [sessions] attempts.
 *
 * The records are all-time whatever the window holds — see [ExerciseProgression] — so they are
 * built from the whole fixture and only the series are trimmed. That is also what makes the
 * quiet-window preview worth having: it is the one state where the two scopes visibly differ.
 */
private fun previewProgression(sessions: Int = PREVIEW_TOP_SETS.size): ExerciseProgression {
    val attempts = previewAttempts().takeLast(sessions)
    val best = PREVIEW_TOP_SETS.maxBy { (weight, _) -> weight }
    val lastAttempt = previewAttempts().last()

    val records = mapOf(
        PrType.ESTIMATED_1RM to record(
            type = PrType.ESTIMATED_1RM,
            achievedAt = lastAttempt.firstSetAt,
            estimated1Rm = OneRepMaxCalculator
                .calculate(kilograms(best.first), best.second, OneRepMaxFormula.EPLEY)
                .roundToLong(),
        ),
        PrType.MAX_WEIGHT to record(
            type = PrType.MAX_WEIGHT,
            achievedAt = lastAttempt.firstSetAt,
            weight = kilograms(best.first),
        ),
        // Older than the last session, which is what keeps the preview from badging every
        // card and turning the accent into decoration.
        PrType.MAX_VOLUME to record(
            type = PrType.MAX_VOLUME,
            achievedAt = previewAttempts().first().firstSetAt,
            weight = previewAttempts().maxOf(ExerciseAttempt::volume),
        ),
    )

    return ExerciseProgression(
        attempts = attempts,
        estimates = attempts.mapNotNull { attempt ->
            val weight = attempt.topSet?.weight ?: return@mapNotNull null
            val reps = attempt.topSet?.reps ?: return@mapNotNull null
            ExerciseEstimate(
                finishedAt = attempt.finishedAt,
                estimate = OneRepMaxCalculator
                    .calculate(weight, reps, OneRepMaxFormula.EPLEY)
                    .roundToLong(),
            )
        },
        records = records,
        // Derived the way the use case derives it — a record is new when it was achieved in
        // the most recent attempt *on screen* — rather than hardcoded, so the quiet-window
        // preview badges nothing, which is what a window holding no session should show.
        newRecords = attempts.lastOrNull()?.let { latest ->
            records.filterValues { it.achievedAt in latest.firstSetAt..latest.finishedAt }.keys
        }.orEmpty(),
        totalSets = PREVIEW_TOTAL_SETS,
    )
}

private fun previewAttempts(): List<ExerciseAttempt> =
    PREVIEW_TOP_SETS.mapIndexed { index, (weight, reps) ->
        val finishedAt = millisOn(PREVIEW_START.plusWeeks(index.toLong()))
        ExerciseAttempt(
            sessionId = index + 1L,
            finishedAt = finishedAt,
            sets = PREVIEW_SETS_PER_SESSION,
            // The top set plus four back-off sets at roughly the same work, which is what makes
            // the bars move with the block rather than tracking the top set exactly.
            volume = kilograms(weight) * reps * PREVIEW_SETS_PER_SESSION,
            topSet = AttemptSet(weight = kilograms(weight), reps = reps),
            firstSetAt = finishedAt - PREVIEW_SESSION_LENGTH_MILLIS,
        )
    }

private fun choice(
    id: Long,
    name: String,
    sets: Int,
    week: Int,
) = TrainedExercise(
    exerciseId = id,
    name = name,
    sets = sets,
    lastPerformedAt = millisOn(PREVIEW_START.plusWeeks(week.toLong())),
)

private fun record(
    type: PrType,
    achievedAt: Long,
    weight: Long? = null,
    estimated1Rm: Long? = null,
) = PersonalRecord(
    id = type.ordinal + 1L,
    exerciseId = PREVIEW_EXERCISE_ID,
    workoutSetId = type.ordinal + 1L,
    type = type,
    weight = weight,
    estimated1Rm = estimated1Rm,
    achievedAt = achievedAt,
)

/** Midday UTC on [date] — a fixed instant, so the previews do not move with a time zone. */
private fun millisOn(date: LocalDate): Long = date.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC).toEpochMilli()

private fun kilograms(value: Int): Long = value * MILLIGRAMS_PER_KILOGRAM

private const val PREVIEW_EXERCISE_ID = 1L
private const val MILLIGRAMS_PER_KILOGRAM = 1_000_000L
private const val PREVIEW_SETS_PER_SESSION = 4
private const val PREVIEW_TOTAL_SETS = 142
private const val PREVIEW_SESSION_LENGTH_MILLIS = 3_600_000L
