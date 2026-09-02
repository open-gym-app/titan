package com.opengym.feature.progression.quests

import com.opengym.core.domain.gamification.LevelTitles
import com.opengym.core.domain.gamification.Quests
import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.domain.model.UserProgress
import java.time.LocalDate
import java.time.Month

/*
 * States the previews render and the screen tests drive.
 *
 * In `main` rather than `test` so the `@Preview`s can reach them, which is what
 * `StatsFixtures` and `previewRecordEntries()` already do in this module. Built from the real
 * catalogues rather than from invented quests, so a preview shows what ships.
 */

/** A lifter several months in: mid-level, a live streak, most of the board part-way done. */
internal fun populatedQuestLog(): QuestLogUiState {
    val progress = UserProgress(
        totalXp = 1_240,
        currentLevel = 13,
        currentStreak = 6,
        longestStreak = 21,
    )
    return QuestLogUiState(
        progress = progress,
        levelTitle = LevelTitles.forLevel(progress.currentLevel),
        quests = board(
            at(quest = Quests.WEEKLY_SESSIONS, progress = 3),
            at(quest = Quests.WEEKLY_SESSIONS_PLUS, progress = 3),
            at(quest = Quests.WEEKLY_RECORD, progress = 1),
            at(quest = Quests.WEEKLY_VOLUME, progress = 14_200),
            at(quest = Quests.MONTHLY_SESSIONS, progress = 9),
            at(quest = Quests.MONTHLY_RECORDS, progress = 2),
            at(quest = Quests.MONTHLY_VOLUME, progress = 51_000),
        ),
        // The two the ladder's lower rungs would have unlocked, and the first record.
        achievementsUnlocked = 3,
        achievementsTotal = ACHIEVEMENT_TOTAL,
        isLoading = false,
    )
}

/**
 * A brand-new install: level 1, no streak, nothing unlocked, every quest at zero.
 *
 * The state #104 asks to render "with no crash or blank card", and the one this screen's defaults
 * already produce — see [QuestLogUiState].
 */
internal fun emptyQuestLog(): QuestLogUiState {
    val progress = UserProgress()
    return QuestLogUiState(
        progress = progress,
        levelTitle = LevelTitles.forLevel(progress.currentLevel),
        quests = board(*Quests.ALL.map { at(quest = it, progress = 0) }.toTypedArray()),
        achievementsUnlocked = 0,
        achievementsTotal = ACHIEVEMENT_TOTAL,
        isLoading = false,
    )
}

/**
 * The moment after a level-up: a level just reached, so the ring is nearly empty.
 *
 * Level 5 is the first level of a *new rank* — `iron_disciple` — which is the case worth having a
 * fixture for. There is no transient "just levelled up" flag on this screen and deliberately so:
 * the crossing is observed by the award that caused it and celebrated on the session summary
 * (#111), while this screen draws the standing state. What is asserted here is that the state
 * left behind by a level-up renders correctly — the new level, the new rank, and an almost-empty
 * ring rather than a full one carried over from the level before.
 */
internal fun justLevelledUpQuestLog(): QuestLogUiState {
    // Level 5 opens at 400 XP on the shipped curve; 405 is five points into it.
    val progress = UserProgress(
        totalXp = 405,
        currentLevel = 5,
        currentStreak = 3,
        longestStreak = 3,
    )
    return QuestLogUiState(
        progress = progress,
        levelTitle = LevelTitles.forLevel(progress.currentLevel),
        quests = board(
            at(quest = Quests.WEEKLY_SESSIONS, progress = 3),
            at(quest = Quests.WEEKLY_SESSIONS_PLUS, progress = 3),
            at(quest = Quests.WEEKLY_RECORD, progress = 0),
            at(quest = Quests.WEEKLY_VOLUME, progress = 8_000),
            at(quest = Quests.MONTHLY_SESSIONS, progress = 5),
            at(quest = Quests.MONTHLY_RECORDS, progress = 1),
            at(quest = Quests.MONTHLY_VOLUME, progress = 24_000),
        ),
        achievementsUnlocked = 1,
        achievementsTotal = ACHIEVEMENT_TOTAL,
        isLoading = false,
    )
}

/**
 * The board, with each quest at the progress given.
 *
 * A quest is marked complete exactly when its target is met, which is *not* the rule the real
 * board follows — completion is a written row there, and can outlive the figures behind it. For a
 * fixture the simplification is the honest one: these are states a real board reaches, and the
 * case where the two come apart has its own test in `QuestProgressUseCaseTest`.
 */
private fun at(
    quest: Quest,
    progress: Int,
): Pair<Quest, Int> = quest to progress

private fun board(vararg entries: Pair<Quest, Int>): List<QuestProgress> =
    entries.map { (quest, progress) ->
        QuestProgress(
            quest = quest,
            progress = progress,
            isComplete = quest.isMetBy(progress),
            periodStart = quest.period.startOf(FIXTURE_DAY),
        )
    }

/** What the shipped catalogue holds. A fixture states it rather than reading it, so a
 * milestone added to the domain shows up here as a failing count rather than silently. */
private const val ACHIEVEMENT_TOTAL = 6

/** Wednesday 3 September 2025, so a fixture's periods are stable across runs. */
private val FIXTURE_DAY: LocalDate = LocalDate.of(2025, Month.SEPTEMBER, 3)
