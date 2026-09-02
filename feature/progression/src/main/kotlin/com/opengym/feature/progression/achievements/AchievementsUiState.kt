package com.opengym.feature.progression.achievements

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.Achievement

/**
 * Everything the achievements screen draws.
 *
 * ### Why this is not part of the quest log's state
 *
 * They shared one screen and one state object, and the two ideas blurred into each other as a
 * result. An achievement is a **permanent, one-shot unlock** with no deadline and no partial
 * progress; a quest **resets**, carries a target and pays again next period. They answer different
 * questions — "what have I done, ever" against "what is there to do this week" — and a single
 * state carrying both invited every future field to have to say which of the two it belonged to.
 *
 * What the quest log keeps is the *count*, because "3 / 6" is the one thing about the milestones
 * worth knowing from the board. See `QuestLogUiState.achievementsUnlocked`.
 *
 * @property entries every achievement in the catalogue, marked held or not, in catalogue order.
 *   Never filtered: a grid that hid the locked ones would tell a new user there is nothing to
 *   earn. Empty only before the first emission.
 * @property isLoading the first read has not answered. Gates the empty-catalogue claim and
 *   nothing else — a user who holds none is a real, correct state and draws six locked cells.
 */
@Immutable
data class AchievementsUiState(
    val entries: List<AchievementEntry> = emptyList(),
    val isLoading: Boolean = true,
) {

    /** How many the user holds — the numerator of the header's count. */
    val unlockedCount: Int get() = entries.count(AchievementEntry::isUnlocked)
}

/**
 * One cell of the grid: a catalogue entry and whether it is held.
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
