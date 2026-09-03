package com.opengym.core.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * A quest that was just completed, and the XP completing it paid.
 *
 * The quest twin of [AchievementUnlock], returned by
 * [com.opengym.core.domain.usecase.QuestCompletionUseCase] so the cascade can hand the summary
 * screen everything a celebration needs without a follow-up query — including
 * [XpAward.leveledUp], which is only trustworthy as the value produced inside the write that
 * banked the points.
 *
 * One of these means the completion *happened here*, on this call. A quest the user had already
 * finished this period is not in the list at all, which is what makes "did anything complete" a
 * check on emptiness rather than a diff against what was stored.
 *
 * @property quest which quest, catalogue entry and all — carrying the target it met and the
 *   reward [xp] is the banking of.
 * @property periodStart the first day of the period it was completed *into*, which is the other
 *   half of the row's identity. Taken from the finishing session's own moment rather than a
 *   clock: see [com.opengym.core.domain.usecase.QuestCompletionUseCase].
 * @property completedAt when it was earned — the session's finish, not the moment the row was
 *   written, for the same reason [AchievementUnlock.unlockedAt] is.
 * @property xp what the completion was worth and where it left the user.
 */
data class QuestCompletion(
    val quest: Quest,
    val periodStart: LocalDate,
    val completedAt: Instant,
    val xp: XpAward,
)
