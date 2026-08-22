package com.opengym.feature.home

import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.ui.component.WeekDay
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Home as the previews render it.
 *
 * In `main` rather than in `test`, because the previews it backs are in `main` — Roborazzi
 * scans a module's previews and generates a screenshot test per preview, so a fixture in the
 * test source set would not be on the classpath when the preview is composed. The same place
 * and the same reason as `SessionsFixtures`.
 *
 * **Every value here is fixed.** A preview built off a clock produces a different reference
 * image every day, and one built off `LocalDate.now()` would draw its today ring on a
 * different cell every morning — so the week below is a Monday in April 2026 and stays there.
 */

/** 2026-04-27, a Monday, so the strip below reads the same in every locale that starts there. */
private val PREVIEW_WEEK_START: LocalDate = LocalDate.of(2026, 4, 27)

/** 2026-04-29, 10:14 UTC — the day the previewed workout was opened. */
private const val PREVIEW_STARTED_AT = 1_777_803_240_000L

/**
 * Trained Monday and Tuesday, twice on Wednesday, which is also today.
 *
 * Thursday onwards is therefore *ahead* of the week, and drawn as an outline rather than as
 * four rest days the user has already failed to train on — see [WeekDay.isFuture].
 */
private val PREVIEW_DAYS: List<WeekDay> = List(7) { offset ->
    WeekDay(
        date = PREVIEW_WEEK_START.plusDays(offset.toLong()),
        sessionCount = when (offset) {
            0, 1 -> 1
            2 -> 2
            else -> 0
        },
        isToday = offset == 2,
        isFuture = offset > 2,
    )
}

/** The everyday banner: a named workout, twenty-three minutes in, part way through an exercise. */
internal val PREVIEW_RESUME = ResumeState(
    sessionId = 1L,
    name = "Push Day · Upper",
    startedAt = PREVIEW_STARTED_AT,
    elapsed = 23.minutes + 14.seconds,
    loggedSets = 7,
    position = SessionPosition(exerciseName = "Bench press", setNumber = 3),
)

/**
 * The other banner worth looking at: suspended, unnamed, and with nothing logged in it.
 *
 * Unnamed exercises the date fallback, and an empty workout is what the discard question looks
 * like when it costs nothing — both of which are easier to get wrong than the case above.
 */
internal val PREVIEW_PAUSED = ResumeState(
    sessionId = 2L,
    startedAt = PREVIEW_STARTED_AT,
    elapsed = 8.minutes + 2.seconds,
    isPaused = true,
)

/** Four saved templates, in the order the strip draws them. */
private val PREVIEW_TEMPLATES: List<TemplateSummary> = listOf(
    previewTemplate(1L, "Push Day", 8, 55.minutes, TemplateCategory.STRENGTH),
    previewTemplate(2L, "Pull Day", 7, 50.minutes, TemplateCategory.STRENGTH),
    previewTemplate(3L, "Leg Day · Heavy", 6, 62.minutes, TemplateCategory.HYPERTROPHY),
    previewTemplate(4L, "HIIT Cardio", 3, 28.minutes, TemplateCategory.ENDURANCE),
)

/** A settled Home, with everything M4 can fill in filled in. */
internal fun previewHome(resume: ResumeState? = PREVIEW_RESUME) = HomeUiState(
    resume = resume,
    week = WeekState(days = PREVIEW_DAYS, sessionCount = 4),
    templates = PREVIEW_TEMPLATES,
    isLoading = false,
)

private fun previewTemplate(
    id: Long,
    name: String,
    exerciseCount: Int,
    duration: Duration,
    category: TemplateCategory,
) = TemplateSummary(
    template = WorkoutTemplate(id = id, name = name, category = category),
    exerciseCount = exerciseCount,
    estimatedDuration = duration,
)
