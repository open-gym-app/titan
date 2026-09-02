package com.opengym.feature.progression.achievements

import com.opengym.core.domain.gamification.Achievements

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

/** A brand-new install: every cell locked, and a `0 / 6` over them. */
internal fun emptyAchievements(): AchievementsUiState = AchievementsUiState(entries = unlocked(), isLoading = false)

/** Every catalogue achievement, with [held] marked unlocked. */
internal fun unlocked(vararg held: String): List<AchievementEntry> =
    Achievements.ALL.map { achievement ->
        AchievementEntry(achievement = achievement, isUnlocked = achievement.id in held)
    }
