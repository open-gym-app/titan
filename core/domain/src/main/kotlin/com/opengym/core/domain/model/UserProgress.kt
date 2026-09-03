package com.opengym.core.domain.model

import java.time.Instant

/**
 * Where the user stands: the points they have banked, the level those buy, and the streak
 * they are on.
 *
 * The read side of everything #33 and #34 compute and #35 stores. Every write to that row
 * goes through [com.opengym.core.domain.repository.GamificationRepository]'s atomic
 * operations and answers with what *changed* — an [XpAward], a [StreakUpdate]. This is the
 * other question, and the one a screen asks: not "what did that session do" but "what is
 * true now".
 *
 * ### Narrow, like the interface that returns it
 *
 * `longestStreak` is stored and is not here, for the reason
 * [com.opengym.core.domain.repository.GamificationRepository] gives for its own shape: a
 * field appears when something draws it. The quest log (#104) is where a best-ever streak
 * belongs, and it can add one line here when it exists.
 *
 * @property totalXp lifetime points, only ever added to.
 * @property level the level [totalXp] buys, as
 *   [com.opengym.core.domain.gamification.XpCalculator.levelForTotalXp] answers it.
 * @property currentStreak consecutive days trained **as of the last counted session**. It is
 *   not re-derived by the passage of time, so a streak that has since lapsed still reads at
 *   its old length here — [lastCountedAt] is what settles that, through
 *   [com.opengym.core.domain.gamification.StreakCalculator.isLive]. A surface that draws this
 *   figure without asking is a surface that tells somebody who last trained in March that
 *   they are twelve days deep.
 * @property lastCountedAt when the session the streak was last *counted* from finished, or
 *   null for a user with no streak history. Deliberately not "when the last session
 *   finished": a second session on an already counted day leaves it where it is — see
 *   [com.opengym.core.domain.gamification.StreakCalculator].
 */
data class UserProgress(
    val totalXp: Long,
    val level: Int,
    val currentStreak: Int,
    val lastCountedAt: Instant?,
)
