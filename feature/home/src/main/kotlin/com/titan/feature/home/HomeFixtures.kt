package com.titan.feature.home

import com.titan.core.domain.gamification.Quests
import com.titan.core.domain.model.MILLIGRAMS_PER_KILOGRAM
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestPeriod
import com.titan.core.domain.model.QuestProgress
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateSummary
import com.titan.core.domain.model.WeeklyVolume
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.ui.component.WeekDay
import java.time.LocalDate
import java.time.Month
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

/** 2026-04-27, midday UTC — the Monday of the previewed week, when the record below was set. */
private const val PREVIEW_RECORD_AT = 1_777_291_200_000L

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

/** Part way through level 14 — the header's ordinary case, neither empty nor about to tip. */
internal val PREVIEW_LEVEL = LevelState(level = 14, xpIntoLevel = 40, xpForLevel = 100)

/**
 * A one-rep-max estimate on the deadlift, set in the last session and up 7 kg on the one it
 * beat — the card with everything on it: a kind, a name, a figure, a `NEW` badge and an
 * improvement.
 */
internal val PREVIEW_RECORD = PersonalRecordEntry(
    record = PersonalRecord(
        id = 1L,
        exerciseId = 1L,
        workoutSetId = 1L,
        type = PrType.ESTIMATED_1RM,
        estimated1Rm = 142_000_000L,
        achievedAt = PREVIEW_RECORD_AT,
    ),
    exerciseName = "Deadlift",
    previousValue = 135_000_000L,
    isRecent = true,
)

/**
 * Eight weeks of volume with one week clearly the heaviest and a week off in the middle of it.
 *
 * The rest week is the case worth having in a reference image: it is drawn as the zero it is
 * rather than as a gap, which is the whole reason the series carries untrained weeks at all.
 */
internal val PREVIEW_VOLUME: List<WeeklyVolume> = listOf(
    18_400,
    21_200,
    19_800,
    24_600,
    0,
    26_100,
    31_800,
    28_400,
).mapIndexed { index, kilograms ->
    WeeklyVolume(
        weekStart = PREVIEW_WEEK_START.minusWeeks((PREVIEW_VOLUME_WEEKS - 1 - index).toLong()),
        totalVolume = kilograms * MILLIGRAMS_PER_KILOGRAM.toLong(),
    )
}

/** How many weeks [PREVIEW_VOLUME] covers — the same eight the card asks for. */
private const val PREVIEW_VOLUME_WEEKS = 8

/** Four saved templates, in the order the strip draws them. */
private val PREVIEW_TEMPLATES: List<TemplateSummary> = listOf(
    previewTemplate(1L, "Push Day", 8, 55.minutes, TemplateCategory.STRENGTH),
    previewTemplate(2L, "Pull Day", 7, 50.minutes, TemplateCategory.STRENGTH),
    previewTemplate(3L, "Leg Day · Heavy", 6, 62.minutes, TemplateCategory.HYPERTROPHY),
    previewTemplate(4L, "HIIT Cardio", 3, 28.minutes, TemplateCategory.ENDURANCE),
)

/** A settled Home with every card on it — the state #110 asks the tests to cover first. */
internal fun previewHome(resume: ResumeState? = PREVIEW_RESUME) =
    HomeUiState(
        level = PREVIEW_LEVEL,
        resume = resume,
        week = WeekState(days = PREVIEW_DAYS, sessionCount = 4, streak = 12),
        templates = PREVIEW_TEMPLATES,
        recentRecord = PREVIEW_RECORD,
        volume = PREVIEW_VOLUME,
        quests = PREVIEW_QUESTS,
        isLoading = false,
    )

/**
 * A week in: a level, a week and a streak, and nothing the progression engines can answer yet.
 *
 * The state a user is in for their first fortnight — they have trained, so the week is real,
 * but they have broken no record and their eight weeks hold too little to chart. Each card is
 * absent rather than empty, which is the rule the screen is built on and the reason this state
 * has a reference image of its own.
 */
internal fun previewPartialHome() =
    HomeUiState(
        level = LevelState(level = 2, xpIntoLevel = 75, xpForLevel = 100),
        week = WeekState(days = PREVIEW_DAYS, sessionCount = 4, streak = 3),
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

/** Wednesday 3 September 2025, so a preview's periods are stable across runs. */
private val PREVIEW_DAY: LocalDate = LocalDate.of(2025, Month.SEPTEMBER, 3)

/**
 * A mid-week board: one weekly quest banked, three still open.
 *
 * Built from the real catalogue rather than from invented quests, so a preview shows what ships —
 * the call `populatedQuestLog()` makes in `:feature:progression`. The three open ones are ordered
 * nearest-first, which is the order the card is given by [HomeViewModel] rather than one it sorts
 * for itself.
 */
private val PREVIEW_QUESTS = QuestSummary(
    clearedThisPeriod = 1,
    totalThisPeriod = 4,
    period = QuestPeriod.WEEKLY,
    next = listOf(
        previewQuest(quest = Quests.WEEKLY_SESSIONS_PLUS, progress = 4),
        previewQuest(quest = Quests.WEEKLY_RECORD, progress = 0),
        previewQuest(quest = Quests.WEEKLY_VOLUME, progress = kilograms(count = 12_400)),
    ),
)

private fun previewQuest(
    quest: Quest,
    progress: Long,
): QuestProgress =
    QuestProgress(
        quest = quest,
        progress = progress,
        isComplete = false,
        periodStart = quest.period.startOf(PREVIEW_DAY),
    )

/** [count] kilograms as the milligrams a [QuestMetric.VOLUME_MILLIGRAMS] figure is stated in. */
private fun kilograms(count: Int): Long = count * MILLIGRAMS_PER_KILOGRAM.toLong()
