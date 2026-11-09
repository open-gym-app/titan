package com.titan.feature.progression.quests

import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.LevelTitles
import com.titan.core.domain.gamification.Quests
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.MILLIGRAMS_PER_KILOGRAM
import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestProgress
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.UserProgress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset

/*
 * States the previews render and the screen tests drive.
 *
 * In `main` rather than `test` so the `@Preview`s can reach them, which is what
 * `StatsFixtures` and `previewRecordEntries()` already do in this module. Built from the real
 * catalogues rather than from invented quests, so a preview shows what ships.
 */

/** A lifter several months in: mid-level, a live streak, most of the board part-way done. */
internal fun populatedQuestLog(): QuestLogUiState {
    // Level 40 opens at 14,613 XP on the shipped curve; 14,853 is 240 points into it.
    val progress = UserProgress(
        totalXp = 14_853,
        level = 40,
        currentStreak = 6,
        longestStreak = 21,
    )
    return QuestLogUiState(
        progress = progress,
        levelTitle = LevelTitles.forLevel(progress.level),
        quests = board(
            at(quest = Quests.WEEKLY_SESSIONS, progress = 3),
            at(quest = Quests.WEEKLY_SESSIONS_PLUS, progress = 3),
            at(quest = Quests.WEEKLY_RECORD, progress = 1),
            at(quest = Quests.WEEKLY_VOLUME, progress = kilograms(count = 14_200)),
            at(quest = Quests.MONTHLY_SESSIONS, progress = 9),
            at(quest = Quests.MONTHLY_RECORDS, progress = 2),
            at(quest = Quests.MONTHLY_VOLUME, progress = kilograms(count = 51_000)),
        ),
        // The two the ladder's lower rungs would have unlocked, and the first record.
        achievementsUnlocked = 3,
        achievementsTotal = ACHIEVEMENT_TOTAL,
        // Spent this month, which is the state worth drawing: it is the one where the line under
        // the mark has something to warn about.
        protection = StreakProtection(remaining = 0, period = FIXTURE_MONTH),
        // Part-way through the week, which is where the line under the streak says the most.
        sessionsThisWeek = 2,
        weeklyGoal = 4,
        // One of each emphasis the row can take — a rare drop in the accent, two commoner ones
        // not — because that contrast is the whole of the section's design (see RecentLootCard).
        recentLoot = listOf(
            drop(Collectibles.PET_EGG, daysAgo = 1),
            drop(Collectibles.LIFTING_STRAP, daysAgo = 4),
            drop(Collectibles.CHALK_BLOCK, daysAgo = 6),
        ),
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
        levelTitle = LevelTitles.forLevel(progress.level),
        quests = board(*Quests.ALL.map { at(quest = it, progress = 0) }.toTypedArray()),
        achievementsUnlocked = 0,
        achievementsTotal = ACHIEVEMENT_TOTAL,
        // Untouched, and stamped with no month: what a fresh install actually holds.
        protection = StreakProtection(),
        // No drops, so the section is absent rather than an empty card — QuestLogUiState.showsLoot.
        recentLoot = emptyList(),
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
    // Level 20 opens at 4,969 XP on the shipped curve; 4,974 is five points into it.
    val progress = UserProgress(
        totalXp = 4_974,
        level = 20,
        currentStreak = 3,
        longestStreak = 3,
    )
    return QuestLogUiState(
        progress = progress,
        levelTitle = LevelTitles.forLevel(progress.level),
        quests = board(
            at(quest = Quests.WEEKLY_SESSIONS, progress = 3),
            at(quest = Quests.WEEKLY_SESSIONS_PLUS, progress = 3),
            at(quest = Quests.WEEKLY_RECORD, progress = 0),
            at(quest = Quests.WEEKLY_VOLUME, progress = kilograms(count = 8_000)),
            at(quest = Quests.MONTHLY_SESSIONS, progress = 5),
            at(quest = Quests.MONTHLY_RECORDS, progress = 1),
            at(quest = Quests.MONTHLY_VOLUME, progress = kilograms(count = 24_000)),
        ),
        achievementsUnlocked = 1,
        achievementsTotal = ACHIEVEMENT_TOTAL,
        protection = StreakProtection(remaining = 1, period = FIXTURE_MONTH),
        // The session that levelled them up also met the week's goal.
        sessionsThisWeek = 3,
        isWeekGoalMet = true,
        weeklyGoal = 3,
        // A single drop, which is the section at its shortest — a user a few weeks in.
        recentLoot = listOf(drop(Collectibles.WORN_WRISTBAND, daysAgo = 2)),
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
    progress: Long,
): Pair<Quest, Long> = quest to progress

private fun board(vararg entries: Pair<Quest, Long>): List<QuestProgress> =
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

/** [FIXTURE_DAY]'s month, which is the one a fixture's allowance is stamped with. */
private val FIXTURE_MONTH: YearMonth = YearMonth.from(FIXTURE_DAY)

/** [FIXTURE_DAY] at midnight UTC — the instant the drops below are dated back from. */
private val FIXTURE_INSTANT: Instant = FIXTURE_DAY.atStartOfDay(ZoneOffset.UTC).toInstant()

/**
 * A drop of [collectible], [daysAgo] days before [FIXTURE_DAY].
 *
 * Dated off a fixed day rather than off `now`, like every other figure here, so the date each row
 * prints is the same on every run — which is what lets these fixtures back a screenshot test.
 */
private fun drop(
    collectible: Collectible,
    daysAgo: Long,
): LootDrop =
    LootDrop(
        item = CollectedItem(
            id = daysAgo,
            collectibleId = collectible.id,
            source = DropSource.SESSION_FINISHED.key,
            acquiredAt = FIXTURE_INSTANT.minus(Duration.ofDays(daysAgo)),
        ),
        collectible = collectible,
    )

/** [count] kilograms as the milligrams a [QuestMetric.VOLUME_MILLIGRAMS] figure is stated in. */
private fun kilograms(count: Int): Long = count * MILLIGRAMS_PER_KILOGRAM.toLong()
