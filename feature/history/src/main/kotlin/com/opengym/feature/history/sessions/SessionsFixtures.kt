package com.opengym.feature.history.sessions

import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.ui.component.TrainingDay
import com.opengym.core.ui.component.TrainingLoad
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

/*
 * A month of training, as the previews render it.
 *
 * In `main` rather than in `test`, because the previews it backs are in `main` — Roborazzi
 * scans a module's previews and generates a screenshot test per preview, so a fixture in the
 * test source set would not be on the classpath when the preview is composed. The same place
 * and the same reason as `SessionSummaryFixtures`.
 *
 * **Every date here is fixed and lands near midday UTC.** A preview whose contents came off a
 * clock would produce a different reference image every day, and one that sat near midnight
 * would render as two different days on two machines in different zones — so the reference
 * would fail on whichever one did not record it.
 */

/** The month all of this happened in, which is also the calendar page the previews open on. */
private val PREVIEW_MONTH: YearMonth = YearMonth.of(2026, 4)

/** 2026-04-28, 12:00 UTC, and one session every two or three days before it. */
private const val PREVIEW_LATEST = 1_777_377_600_000L
private const val DAY_MILLIS = 86_400_000L

/**
 * Six sessions across two months, newest first, with a record on the most recent.
 *
 * Two months rather than one because the month heading is half of every row's date — a
 * single-month fixture would not show that the list is grouped at all.
 *
 * `isToday` is false on all of them and `hasRecord` is set by hand: both are functions of
 * something outside the fixture — the clock, and a records table — and a reference image that
 * drew the accent rail only on the day it was recorded is one that fails tomorrow.
 */
private val PREVIEW_SESSIONS: List<HistorySession> = listOf(
    HistorySession(
        id = 1,
        name = "Push Day · Upper",
        startedAt = startedAt(daysAgo = 0),
        duration = 52.minutes,
        totalSets = 12,
        totalVolume = 4_820_000_000L,
        hasRecord = true,
    ),
    HistorySession(
        id = 2,
        name = "Pull Day",
        startedAt = startedAt(daysAgo = 2),
        duration = 48.minutes,
        totalSets = 14,
        totalVolume = 3_210_000_000L,
    ),
    HistorySession(
        id = 3,
        name = "Legs · Heavy",
        startedAt = startedAt(daysAgo = 4),
        duration = 64.minutes,
        totalSets = 16,
        totalVolume = 6_820_000_000L,
        hasRecord = true,
    ),
    // A session nobody named, and one of nothing but running: the row falls back to the time
    // it started and its volume is genuinely zero.
    HistorySession(
        id = 4,
        name = null,
        startedAt = startedAt(daysAgo = 7),
        duration = 32.minutes,
        totalSets = 5,
        totalVolume = 0L,
    ),
    HistorySession(
        id = 5,
        name = "Push Day · Upper",
        startedAt = startedAt(daysAgo = 9),
        duration = 50.minutes,
        totalSets = 12,
        totalVolume = 4_650_000_000L,
    ),
    HistorySession(
        id = 6,
        name = "Full body",
        startedAt = startedAt(daysAgo = 34),
        duration = 58.minutes,
        totalSets = 15,
        totalVolume = 5_100_000_000L,
    ),
)

private fun startedAt(daysAgo: Int): Long = PREVIEW_LATEST - daysAgo * DAY_MILLIS

private val PREVIEW_TEMPLATES: List<TemplateSummary> = listOf(
    previewTemplate(1, "Push Day · Upper", 8, 55, TemplateCategory.STRENGTH),
    previewTemplate(2, "Pull Day", 7, 50, TemplateCategory.STRENGTH),
    previewTemplate(3, "Leg Day · Heavy", 6, 60, TemplateCategory.HYPERTROPHY),
    previewTemplate(4, "Full Body Beginner", 5, 40, TemplateCategory.GENERAL),
    previewTemplate(5, "HIIT Cardio", 3, 30, TemplateCategory.ENDURANCE),
)

private fun previewTemplate(
    id: Long,
    name: String,
    exercises: Int,
    minutes: Int,
    category: TemplateCategory,
): TemplateSummary = TemplateSummary(
    template = WorkoutTemplate(id = id, name = name, category = category),
    exerciseCount = exercises,
    estimatedDuration = minutes.minutes,
)

/** The heatmap the four sessions in April make, with one heavy day and three light ones. */
private val PREVIEW_CALENDAR = CalendarPage(
    month = PREVIEW_MONTH,
    days = mapOf(
        19 to TrainingDay(sessionCount = 1, load = TrainingLoad.LIGHT),
        21 to TrainingDay(sessionCount = 1, load = TrainingLoad.LIGHT),
        24 to TrainingDay(sessionCount = 1, load = TrainingLoad.HEAVY),
        26 to TrainingDay(sessionCount = 1, load = TrainingLoad.LIGHT),
        28 to TrainingDay(sessionCount = 1, load = TrainingLoad.HEAVY),
    ),
    sessionCount = 5,
    totalVolume = 19_500_000_000L,
    totalDuration = 246.minutes,
    // Null rather than a day of the month, for the reason `isToday` is always false above.
    today = null,
)

/**
 * A populated screen, on whichever tab is asked for.
 *
 * One fixture for every preview in the feature rather than one per file, so a change to what
 * a populated history looks like moves in one place.
 */
internal fun previewState(tab: SessionsTab = SessionsTab.HISTORY): SessionsUiState {
    val months = PREVIEW_SESSIONS
        // Grouped in UTC rather than in the device's zone, unlike the ViewModel: every
        // fixture instant is midday UTC, so the two agree everywhere the app runs, and this
        // one cannot make a reference image depend on where it was recorded.
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(ZoneOffset.UTC)) }
        .map { (month, sessions) -> SessionMonth(month = month, sessions = sessions) }

    return SessionsUiState(
        tab = tab,
        months = months,
        sessionCount = PREVIEW_SESSIONS.size,
        matchCount = PREVIEW_SESSIONS.size,
        templates = PREVIEW_TEMPLATES,
        calendar = PREVIEW_CALENDAR,
        isLoading = false,
        isLoadingTemplates = false,
    )
}
