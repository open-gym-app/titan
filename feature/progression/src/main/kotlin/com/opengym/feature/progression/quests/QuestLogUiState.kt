package com.opengym.feature.progression.quests

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.gamification.LevelTitle
import com.opengym.core.domain.model.Achievement
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.domain.model.UserProgress

/**
 * Everything the quest log draws.
 *
 * One state object rather than a stream, unlike the records list: every section here is a bounded
 * value — a level, seven quests, six achievements, two streak figures — so there is nothing that
 * has to be paged and nothing that is a stream rather than a list.
 *
 * ### The zero state is the default, and it is a real state
 *
 * Every field defaults to what a brand-new user holds: level 1, no streak, no achievements, and
 * quests at zero progress. That is not a placeholder standing in for "not loaded yet" — it is
 * what the screen should draw for that user, and #104 asks for it explicitly. [isLoading] is the
 * separate question, and only two things on this screen actually depend on it; see below.
 *
 * @property progress the level, XP and streak. Defaulted to a fresh install's zeroes, which the
 *   store's own read guarantees rather than approximates — see
 *   `GamificationRepository.observeProgress`, which never emits null.
 * @property levelTitle the rank [progress]'s level carries, from the catalogue.
 * @property quests the board, in catalogue order, each with its progress and whether it is
 *   banked. Empty only before the first emission: the catalogue always has entries, so a user
 *   who has never trained sees every quest at zero rather than an empty section.
 * @property achievements every achievement in the catalogue, marked held or not. Never empty for
 *   the same reason, and never filtered: a grid that hid the locked ones would tell a new user
 *   there is nothing to earn.
 * @property isLoading the first emission has not landed. What it gates is narrow — see
 *   [showsEmptyQuests] — because most of this screen is correct at its defaults.
 */
@Immutable
data class QuestLogUiState(
    val progress: UserProgress = UserProgress(),
    val levelTitle: LevelTitle? = null,
    val quests: List<QuestProgress> = emptyList(),
    val achievements: List<AchievementEntry> = emptyList(),
    val isLoading: Boolean = true,
) {

    /** How many achievements the user holds — the numerator of the grid's count. */
    val unlockedCount: Int get() = achievements.count(AchievementEntry::isUnlocked)

    /**
     * Whether the quest section has nothing to draw.
     *
     * True only for a build whose catalogue is empty, which no shipped build has — so this is
     * a guard against a blank section rather than a state the user is expected to reach. Gated
     * on [isLoading] so the first frame does not claim there are no quests before the store has
     * answered.
     */
    val showsEmptyQuests: Boolean get() = !isLoading && quests.isEmpty()

    /**
     * Whether the user is on their best-ever run.
     *
     * Drawn rather than the two figures alone, because "5 days, best 5" reads as a coincidence
     * and "5 days — your best yet" reads as the thing it is. False at zero: a user who has never
     * trained is not tying a record.
     */
    val isOnBestStreak: Boolean
        get() = progress.currentStreak > 0 && progress.currentStreak >= progress.longestStreak
}

/**
 * One cell of the achievements grid: a catalogue entry and whether it is held.
 *
 * The catalogue entry travels rather than just its id, so the grid can draw a locked cell from
 * the same source as an unlocked one. What it deliberately does **not** carry is anything about
 * *how* to unlock a locked achievement beyond what the catalogue already defines — #104 is
 * explicit that a locked entry reveals nothing extra.
 *
 * @property achievement the catalogue entry. Always present: the grid is built by walking the
 *   catalogue, so a stored id matching no entry — a milestone renamed without a migration —
 *   simply does not appear, rather than producing a cell with no definition behind it.
 * @property isUnlocked whether the user holds it.
 */
@Immutable
data class AchievementEntry(
    val achievement: Achievement,
    val isUnlocked: Boolean,
)
