package com.titan.feature.progression.achievements

import androidx.compose.runtime.Immutable
import com.titan.core.domain.gamification.AchievementTier
import com.titan.core.domain.gamification.tier
import com.titan.core.domain.model.Achievement

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
 *   Never filtered: a shelf that hid the locked ones would tell a new user there is nothing to
 *   earn. Empty only before the first emission.
 * @property isLoading the first read has not answered, and the screen draws nothing while it is
 *   set. Distinct from holding none, which is a real, correct state that draws six locked cells:
 *   the two are indistinguishable in [entries] alone, and the screen must not make the second
 *   claim — a `0 / 6` and a "no medals yet" — of a user it has not yet heard about.
 */
@Immutable
data class AchievementsUiState(
    val entries: List<AchievementEntry> = emptyList(),
    val isLoading: Boolean = true,
) {

    /** How many the user holds — the numerator of the header's count. */
    val unlockedCount: Int get() = entries.count(AchievementEntry::isUnlocked)

    /**
     * The most recently earned medal, or null when the user holds none.
     *
     * The screen's hero, and the reason [AchievementEntry.unlockedAt] travels at all. *Most
     * recent* rather than *rarest*: the question a user opens this screen with is "what did I just
     * get", and a screen that led with their gold instead would show the same medal for months
     * while three new ones appeared quietly in the shelf below it.
     *
     * Ties break on catalogue order — the order unlocks are evaluated in, so two milestones
     * cleared by one workout put the cheaper one first and the *dearer* one here, which is the
     * one worth leading with. See `Achievements.ALL`.
     */
    val latest: AchievementEntry?
        get() = entries.withIndex()
            .filter { (_, entry) -> entry.isUnlocked }
            .maxWithOrNull(compareBy({ (_, entry) -> entry.unlockedAt ?: Long.MIN_VALUE }, IndexedValue<*>::index))
            ?.value
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
 * @property unlockedAt when it was earned, in epoch milliseconds, or null while it is locked. Two
 *   things read it: the date under a held medal, and [AchievementsUiState.latest]. It is *not*
 *   what decides whether the medal is drawn as held — [isUnlocked] is — so a row stored without a
 *   timestamp by some future import still shows as earned, with no date under it.
 * @property tier which metal it is struck in, read off the catalogue ladder rather than stored.
 *   Resolved here rather than in the grid so the screen, its previews and its tests all rank a
 *   milestone the same way, and so a composable never has to reach for the catalogue to draw one
 *   cell. See [com.titan.core.domain.gamification.tier].
 */
@Immutable
data class AchievementEntry(
    val achievement: Achievement,
    val isUnlocked: Boolean,
    val unlockedAt: Long? = null,
    val tier: AchievementTier = achievement.tier(),
)
