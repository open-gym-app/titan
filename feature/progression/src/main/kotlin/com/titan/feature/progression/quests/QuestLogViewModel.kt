package com.titan.feature.progression.quests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.gamification.Achievements
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.LevelTitles
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.model.UserProgress
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.QuestProgressUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.YearMonth
import javax.inject.Inject

/**
 * The quest log's state: level and rank, the streak, the quest board, and the milestone count.
 *
 * ### Five reads, combined, and nothing polled
 *
 * The progress row, the unlocked set, the quest board, the streak state with the weekly goal it is
 * judged against, and the recent drops are five flows off the stores, combined into one state.
 * Every one of them is a *reactive* read, which is what #104's "update reactively" asks for
 * concretely: a workout finished while this screen sits in the back stack moves the level, the
 * streak, the board and the inventory, and all of them fill in without the screen being left and
 * returned to.
 *
 * The streak arrives twice over, and that is not a duplication to collapse. `observeProgress`
 * carries the two *counts*, which live on the progress row beside the XP total; `observeStreak`
 * carries the stored [com.titan.core.domain.model.StreakState], whose protection allowance and
 * count for the week in progress are the only things read from it here. They are separate reads in the store for that reason and are
 * kept separate here rather than one being derived from the other.
 *
 * ### Whether the streak is still running is this class's judgement to make
 *
 * The stored count is only recomputed when a session finishes, so the row still reads twelve for
 * a user who last trained in March — see [com.titan.core.domain.model.UserProgress.currentStreak],
 * which says in as many words that a surface drawing the figure unasked is a surface that lies.
 * [StreakCalculator.isLive] against the clock is that ask, and it travels as
 * [QuestLogUiState.isStreakLive] rather than by zeroing the count, because the card still has the
 * best-ever run to draw. `HomeViewModel.liveStreak` asks the same question of the same figure.
 *
 * ### The allowance is replenished for the month being drawn in
 *
 * `observeStreak` hands back the allowance **as stored**, which in a month the user has not yet
 * trained in is still the previous month's remainder — the rollover is applied when a session is
 * written, and the store deliberately reads no clock. So the consumer applies it, which its
 * contract says in as many words, and this is that consumer:
 * [com.titan.core.domain.model.StreakProtection.replenishedFor] against the month *now*, in the
 * streak's own zone so the month boundary here is the one the mechanic is judged in.
 *
 * The clock is read per emission rather than held, so a screen left open across midnight on the
 * first of a month shows the refilled allowance as soon as anything else moves. It does not
 * *cause* an emission — nothing here polls — which is the honest limit of a rollover that has no
 * scheduled job behind it, and is invisible in practice: the first session of the new month both
 * refills the allowance in the store and pushes an emission through this flow.
 *
 * ### The catalogues are walked, not queried
 *
 * The milestone count is taken by walking [Achievements.ALL] and asking which ids the user holds,
 * rather than by counting the rows on file. That is what keeps `3 / 6` honest when an id has been
 * renamed without a migration: an unmatched row contributes nothing instead of pushing the
 * numerator past the denominator. The grid those ids draw lives on its own screen — see
 * `AchievementsViewModel`, which walks the same catalogue for the same reason.
 *
 * The rank is looked up the same way, from [LevelTitles], which is why a level past the end of
 * the table keeps the last rank instead of going blank.
 *
 * ### Why `WhileSubscribed` and not `Lazily`
 *
 * The board's six aggregate reads are real queries. Held open for a screen nobody is looking at,
 * they would re-run on every finished workout for the life of the process. The five-second stop
 * timeout is the codebase's standard: long enough to survive a rotation without re-querying,
 * short enough that leaving the screen actually stops the work.
 */
@HiltViewModel
class QuestLogViewModel @Inject constructor(
    gamificationRepository: GamificationRepository,
    collectedItems: CollectedItemRepository,
    questProgress: QuestProgressUseCase,
    userPreferences: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<QuestLogUiState> =
        combine(
            // The store answers null while nothing has ever been banked (#110's rule, which
            // Home draws no header at all on). The board has something to draw in that state —
            // level 1 and an invitation to start a streak — so the zero row is substituted
            // here rather than each card below asking.
            gamificationRepository.observeProgress().map { it ?: UserProgress() },
            gamificationRepository.observeUnlockedAchievements(),
            questProgress(),
            // The goal rides with the streak rather than as a sixth source: it is only ever read
            // beside it, and `combine` stops being typed past five.
            gamificationRepository.observeStreak().combine(
                userPreferences.observe().map { it.weeklyWorkoutGoal }.distinctUntilChanged(),
            ) { streak, goal -> streak to goal },
            collectedItems.observeRecent(RECENT_LOOT_LIMIT),
        ) { progress, unlocked, quests, (streak, weeklyGoal), loot ->
            val heldIds = unlocked.mapTo(mutableSetOf()) { it.achievementId }
            val now = Instant.ofEpochMilli(clock.nowMillis())
            QuestLogUiState(
                progress = progress,
                levelTitle = LevelTitles.forLevel(progress.level),
                quests = quests,
                // The one judgement over the stored row this screen has to make for itself: the
                // count is only recomputed when a session finishes, so it stands at twelve for as
                // long as nobody trains. See `StreakCalculator.isLive`, and `HomeViewModel`, which
                // asks the same question of the same figure.
                isStreakLive = StreakCalculator.isLive(progress.lastCountedAt, progress.weekGoalMet, asOf = now),
                sessionsThisWeek = StreakCalculator.sessionsThisWeek(
                    streak.lastCountedAt,
                    streak.weekSessions,
                    asOf = now,
                ),
                isWeekGoalMet = StreakCalculator.isWeekGoalMet(streak.lastCountedAt, streak.weekGoalMet, asOf = now),
                weeklyGoal = weeklyGoal,
                // Counted against the catalogue rather than taken as the row count: an id on
                // file matching no entry — a milestone renamed without a migration — would
                // otherwise push the numerator past the denominator.
                achievementsUnlocked = Achievements.ALL.count { it.id in heldIds },
                achievementsTotal = Achievements.ALL.size,
                protection = streak.protection.replenishedFor(streakMonthOf(now)),
                // Resolved against the catalogue here rather than in the row, so a drop this
                // build cannot name is dealt with once. It is kept rather than filtered out:
                // it is loot the user holds, and a list that silently shortened would be a
                // worse answer than a row that says less than the others.
                recentLoot = loot.map { LootDrop(item = it, collectible = Collectibles.byId(it.collectibleId)) },
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            // Loading, not empty. The defaults are a brand-new user's real state, so the only
            // thing this initial value must get right is that the store has not answered yet.
            initialValue = QuestLogUiState(),
        )

    /** [at] as a month in the zone the streak and its allowance are both judged in. */
    private fun streakMonthOf(at: Instant): YearMonth = YearMonth.from(at.atZone(StreakCalculator.STREAK_ZONE))

    private companion object {

        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * How many drops the recent-loot section shows.
         *
         * Three: enough that a run of drops reads as a run rather than as one thing, few enough
         * that the section stays the footer of this screen rather than becoming a collection
         * browser. The full inventory is a screen of its own when something asks for one — the
         * store's read is bounded precisely so this figure is the screen's to choose (see
         * `CollectedItemRepository.observeRecent`).
         */
        const val RECENT_LOOT_LIMIT = 3
    }
}
