package com.titan.feature.progression.quests

import androidx.compose.runtime.Immutable
import com.titan.core.domain.gamification.LevelTitle
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.QuestPeriod
import com.titan.core.domain.model.QuestProgress
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.UserProgress

/**
 * Everything the quest log draws.
 *
 * One state object rather than a stream, unlike the records list: every section here is a bounded
 * value — a level, seven quests, a milestone count, two streak figures — so there is nothing that
 * has to be paged and nothing that is a stream rather than a list.
 *
 * ### The zero state is the default, and it is a real state
 *
 * Every field defaults to what a brand-new user holds: level 1, no streak, no milestones, and
 * quests at zero progress. That is not a placeholder standing in for "not loaded yet" — it is
 * what the screen should draw for that user, and #104 asks for it explicitly. [isLoading] is the
 * separate question, and only two things on this screen actually depend on it; see below.
 *
 * @property progress the level, XP and streak. Defaulted to a fresh install's zeroes, which is
 *   what the ViewModel substitutes for the **null** `GamificationRepository.observeProgress`
 *   emits while nothing has ever been banked — see `QuestLogViewModel`, so no card below has to
 *   know the row is written on the first award rather than seeded.
 * @property levelTitle the rank [progress]'s level carries, from the catalogue.
 * @property quests the board, in catalogue order, each with its progress and whether it is
 *   banked. Empty only before the first emission: the catalogue always has entries, so a user
 *   who has never trained sees every quest at zero rather than an empty section.
 * @property achievementsUnlocked how many milestones the user holds, and
 * @property achievementsTotal how many there are — the `3 / 6` on the row that leads to them.
 *   The counts and nothing more: the grid itself has its own screen and its own state, because an
 *   achievement is a permanent unlock rather than a thing to do this week. See
 *   `AchievementsUiState`, which carries that argument.
 * @property protection what is left of this month's streak allowance, **already replenished for
 *   the month the screen is drawing in** — the ViewModel applies
 *   [StreakProtection.replenishedFor], as `GamificationRepository.observeStreak` requires of
 *   every consumer that renders the count. Defaulted to a full allowance, which is a fresh
 *   install's real state.
 * @property recentLoot the latest drops, newest first, each paired with the catalogue entry that
 *   describes it. Empty for a user who has never been dropped anything, which is a real state
 *   rather than a loading one — see [showsLoot], which is what decides whether the section is
 *   drawn at all.
 * @property isStreakLive whether [UserProgress.currentStreak] is **still running**, judged by the
 *   ViewModel against the clock — see [com.titan.core.domain.gamification.StreakCalculator.isLive].
 *   The stored count is only ever recomputed when a session finishes, so the row keeps its figure
 *   while the days pass; drawing it unasked tells a lifter who last trained in March that they are
 *   twelve days deep, which is the one thing a streak must never do. Defaulted to true because a
 *   fixture or a preview states the streak it wants drawn rather than a moment to judge it at.
 * @property isLoading the first emission has not landed. What it gates is narrow — see
 *   [showsEmptyQuests] — because most of this screen is correct at its defaults.
 */
@Immutable
data class QuestLogUiState(
    val progress: UserProgress = UserProgress(),
    val levelTitle: LevelTitle? = null,
    val quests: List<QuestProgress> = emptyList(),
    val achievementsUnlocked: Int = 0,
    val achievementsTotal: Int = 0,
    val protection: StreakProtection = StreakProtection(),
    val recentLoot: List<LootDrop> = emptyList(),
    val isStreakLive: Boolean = true,
    val isLoading: Boolean = true,
) {

    /**
     * The board, one entry per cadence that has quests in it, in [QuestPeriod] declaration order.
     *
     * Derived here rather than in the ViewModel because it is a *presentation* split: the domain
     * hands over one list in catalogue order, and which of those rows are still live, how they
     * score and what is left on them are all questions this screen asks. A cadence with nothing
     * in it produces no entry at all rather than an empty group, which is what keeps the screen
     * correct for a build whose catalogue carries only one of the two.
     */
    val boards: List<QuestPeriodBoard>
        get() = quests
            .groupBy { it.quest.period }
            .let { byPeriod ->
                QuestPeriod.entries.mapNotNull { period ->
                    byPeriod[period]?.takeIf { it.isNotEmpty() }?.let { QuestPeriodBoard(period, it) }
                }
            }

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
     *
     * A question about the *stored* run, so it says nothing about whether that run is still
     * going — [isStreakLive] is what decides whether the count is drawn at all, and a lapsed
     * streak never reaches the badge this answers for.
     */
    val isOnBestStreak: Boolean
        get() = progress.currentStreak > 0 && progress.currentStreak >= progress.longestStreak

    /**
     * Whether the recent-loot section is drawn at all.
     *
     * Absent rather than empty for a user with no drops. Every other section here has something
     * true to say at zero — level 1 is a level, a streak of none is an invitation, a quest at zero
     * is a quest to go and clear — and an empty loot card has none of that: it would be a heading
     * over a blank promising a thing the user cannot go and do, since a drop is rolled by the app
     * rather than earned on purpose. Gated on [isLoading] as well, so the section does not flash
     * in and out on the frame before the store answers.
     */
    val showsLoot: Boolean get() = !isLoading && recentLoot.isNotEmpty()
}

/**
 * One drop, with what the catalogue says about it.
 *
 * The pairing is done once in the ViewModel rather than at each of the three places the row draws
 * from it, because the lookup can *fail*: a drop whose id this build does not recognise is a real
 * row (see [Collectibles.byId]), and a screen that resolved it separately for the name, the rarity
 * and the tile would have three chances to forget that.
 *
 * @property item the drop as stored — what was pulled, from what, and when.
 * @property collectible the catalogue entry behind [CollectedItem.collectibleId], or **null** for
 *   a drop this build cannot name. The row draws such a drop as a nameless one of unknown rarity
 *   rather than dropping it, because it is loot the user genuinely holds.
 */
@Immutable
data class LootDrop(
    val item: CollectedItem,
    val collectible: Collectible?,
)

/**
 * One cadence's board: what is still open, what has been banked, and the score across both.
 *
 * ### Live quests come first, and cleared ones are a separate group
 *
 * A completed quest used to stay in catalogue order, struck through. It read well on a board with
 * one cleared row and badly on a board with five, where the two quests still worth acting on were
 * buried among finished ones. Splitting them costs the stable ordering the old design protected —
 * a row does move the moment it is cleared — and buys a board whose top is always the thing to do
 * next. Within each group the catalogue's own order is kept, so nothing else shuffles.
 *
 * @property period the cadence these belong to.
 * @property rows every quest of that cadence, in catalogue order, cleared and not.
 */
@Immutable
data class QuestPeriodBoard(
    val period: QuestPeriod,
    val rows: List<QuestProgress>,
) {

    /** The quests still to clear, in catalogue order. The group the board leads with. */
    val live: List<QuestProgress> get() = rows.filterNot(QuestProgress::isComplete)

    /** The quests already banked this period, in catalogue order. */
    val cleared: List<QuestProgress> get() = rows.filter(QuestProgress::isComplete)

    /** How many are banked — the numerator of the header's score. */
    val clearedCount: Int get() = rows.count(QuestProgress::isComplete)

    /** How many there are in total. Never zero: a board with no rows is not built. */
    val total: Int get() = rows.size

    /** Whether every quest of this cadence is banked. */
    val isCleared: Boolean get() = clearedCount == total

    /**
     * What is still reachable this period, in XP.
     *
     * The sum of the *unbanked* rewards, which is the figure the header's subtitle is about: what
     * the user can still earn before the period resets, not what the cadence is worth in total.
     */
    val remainingXp: Int get() = live.sumOf { it.quest.xpReward }

    /**
     * The score as a proportion, for the header's bar.
     *
     * Counted in *quests*, not in XP. A bar weighted by reward would move further for clearing an
     * expensive quest than a cheap one, which is defensible arithmetic and a confusing bar: the
     * figure printed beside it says `2 / 4`, and a bar that does not agree with the number it sits
     * next to is worse than no bar.
     */
    val fraction: Float get() = if (total == 0) 0f else clearedCount.toFloat() / total
}
