package com.opengym.feature.session.summary

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.SessionProgression
import com.opengym.core.domain.model.StreakProtection

/**
 * The one thing the recap says about the streak: that this session's missed day was paid for
 * out of the monthly allowance, and how much of it is left.
 *
 * ### Why the recap says anything about it at all
 *
 * #106 spends a protection **automatically** — the break is discovered when the next session
 * is finished, hours or days after the day that was missed, so there is nothing to ask the user
 * about. That makes telling them afterwards the whole of the mechanic's honesty: a resource
 * silently spent on the user's behalf, never reported, is one they find out about by running
 * out of it. #39 will announce it as a notification when notifications exist; this is the
 * in-app surface, and it is the *right* one whether or not that lands, because the recap is
 * already open in front of the person whose streak was just saved.
 *
 * The quest log (#104) draws the standing allowance — three shields, however many are left,
 * whether or not anything happened today — from `GamificationRepository.observeStreak`. This is
 * the other half: the *event*, which only the session that caused it can report.
 *
 * ### Absent, not empty
 *
 * Null for every ordinary session, which is almost all of them: a session that extended a
 * streak, one that broke it with no allowance to save it, one on a day already counted. The
 * screen draws nothing at all in those cases, the same rule [SummaryCelebration] follows —
 * a card reading "0 protections used" would be the app reporting a non-event.
 *
 * @property streakDays the streak as it stands *after* this session — the protected day is
 *   covered and the session in hand counted, so this is one more than the streak that was
 *   nearly lost. It is the figure to draw: "your streak is 13 days" is what the user wants
 *   confirmed, not the number it would have fallen to.
 * @property protectionsRemaining what is left of the month's allowance after this spend.
 *   May be zero, which is worth saying plainly rather than hiding — a user with none left is
 *   one bad day from losing the streak this card just celebrated.
 */
@Immutable
data class SummaryStreakSave(
    val streakDays: Int,
    val protectionsRemaining: Int,
) {

    /** How many the month holds, which is what the row of marks is drawn against. */
    val allowance: Int get() = StreakProtection.MONTHLY_ALLOWANCE

    companion object {

        /**
         * [progression] as something to draw, or **null** when this session spent nothing.
         *
         * Null on three inputs a reader would not distinguish and the engine does: a streak
         * step that could not run at all (see `SessionProgression.streak`), a session that did
         * not break anything, and a break the allowance could not cover. In the last of those
         * the streak is genuinely gone, and a card about the protection mechanic is not how a
         * user should find that out — the recap says nothing, exactly as it says nothing about
         * a session that broke no records.
         */
        fun from(progression: SessionProgression): SummaryStreakSave? {
            val streak = progression.streak ?: return null
            if (!streak.protectionConsumed) return null
            return SummaryStreakSave(
                streakDays = streak.days,
                protectionsRemaining = streak.protectionsRemaining,
            )
        }
    }
}
