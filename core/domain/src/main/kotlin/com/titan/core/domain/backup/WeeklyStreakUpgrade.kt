package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.UserProgressRow
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.StreakProtection

/**
 * Reads a backup written before the streak counted weeks as one this build can restore.
 *
 * The streak used to count sessions kept up within a grace window, and its achievements and its
 * protection allowance were sized for that; the XP curve was retuned in the same change. Such a
 * file is still `schema_version` 1 — the change added columns rather than a version — and four
 * things in it would mislead if restored as they are:
 *
 * - **`current_streak` and `longest_streak` are in other units.** Thirty sessions is not thirty
 *   weeks, and no conversion is honest: the old count says nothing about which weeks met the
 *   goal. Both start again at zero, and the first week that meets the goal begins a new run.
 *   `last_counted_at` is kept, because it is what stops a re-run of the cascade from counting
 *   the restored sessions a second time.
 * - **The streak achievements were renamed with their thresholds** ([RETIRED_ACHIEVEMENT_IDS]).
 *   The old milestones are not the new ones, so an old unlock is dropped rather than carried
 *   over as one; the XP it paid stays in `total_xp`. Kept, it would be drawn as a milestone no
 *   catalogue names.
 * - **The protection allowance was larger.** `protection_remaining` is cut to
 *   [StreakProtection.MONTHLY_ALLOWANCE].
 * - **`current_level` was bought on the old curve**, at a sixth of today's price per level, so it
 *   is several times what `total_xp` buys now. It is recomputed from the total with
 *   [XpCalculator]: kept, the header would draw a level the progress bar under it contradicts,
 *   and the next award would silently drop the column to the level the total really buys.
 *   `total_xp` itself is kept — the points were earned, only their price moved.
 *
 * A file from before the weekly streak is told apart by the column it could not have: a
 * `user_progress` row with no `week_sessions`. Every file this build writes carries it.
 */
internal object WeeklyStreakUpgrade {

    /** The achievement ids of the session-based streak, which no build after it recognises. */
    val RETIRED_ACHIEVEMENT_IDS: Set<String> = setOf("seven_day_streak", "thirty_day_streak")

    /** [document] as this build reads it: unchanged unless it predates the weekly streak. */
    fun apply(document: ExportDocument): ExportDocument {
        if (document.userProgress.none { it.weekSessions == null }) return document
        return document.copy(
            userProgress = document.userProgress.map { it.upgraded() },
            achievement = document.achievement.filterNot { it.achievementId in RETIRED_ACHIEVEMENT_IDS },
        )
    }

    private fun UserProgressRow.upgraded(): UserProgressRow =
        if (weekSessions != null) {
            this
        } else {
            copy(
                currentStreak = 0,
                longestStreak = 0,
                weekSessions = 0,
                weekGoalMet = false,
                // Clamped for the curve's sake only: the stored total is repaired on its way into
                // the table, and the level is not asked of a total the curve cannot price.
                currentLevel = XpCalculator.levelForTotalXp(totalXp.coerceIn(0, XpCalculator.MAX_TOTAL_XP)),
                protectionRemaining = protectionRemaining.coerceAtMost(StreakProtection.MONTHLY_ALLOWANCE),
            )
        }
}
