package com.titan.feature.progression.achievements

import com.titan.core.domain.gamification.Achievements

/*
 * States the previews render and the screen tests drive.
 *
 * In `main` rather than `test` so the `@Preview`s can reach them, which is what `StatsFixtures`
 * and `populatedQuestLog()` already do in this module. Built from the real catalogue rather than
 * from invented milestones, so a preview shows what ships.
 */

/** A lifter a few months in: the first two rungs of the session ladder, and a first record. */
internal fun populatedAchievements(): AchievementsUiState =
    AchievementsUiState(
        entries = unlocked("first_session", "ten_sessions", "first_record"),
        isLoading = false,
    )

/** A brand-new install: every medal locked, and a `0 / 6` over them. */
internal fun emptyAchievements(): AchievementsUiState = AchievementsUiState(entries = unlocked(), isLoading = false)

/**
 * Every catalogue achievement, with [held] marked unlocked.
 *
 * Each held one is dated a day apart in the order it is named, so the last argument is the newest
 * and a preview's hero is whichever milestone the fixture ends on. Fixed dates rather than
 * `now()`: a screenshot reference that re-rendered a different date every day would fail on the
 * second run for a reason nothing had caused.
 */
internal fun unlocked(vararg held: String): List<AchievementEntry> =
    Achievements.ALL.map { achievement ->
        val rank = held.indexOf(achievement.id)
        AchievementEntry(
            achievement = achievement,
            isUnlocked = rank >= 0,
            unlockedAt = if (rank >= 0) FIRST_UNLOCK_MILLIS + rank * DAY_MILLIS else null,
        )
    }

/** 14 February 2026, noon UTC — a date with no significance beyond being a fixed one. */
private const val FIRST_UNLOCK_MILLIS = 1_771_070_400_000L

private const val DAY_MILLIS = 86_400_000L
