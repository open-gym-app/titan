package com.opengym.feature.progression.quests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.gamification.LevelTitles
import com.opengym.core.domain.repository.GamificationRepository
import com.opengym.core.domain.usecase.QuestProgressUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The quest log's state: level and rank, the streak, the quest board, and the milestone count.
 *
 * ### Three reads, combined, and nothing polled
 *
 * The progress row, the unlocked set and the quest board are three flows off the store, combined
 * into one state. Every one of them is a *reactive* read, which is what #104's "update
 * reactively" asks for concretely: a workout finished while this screen sits in the back stack
 * moves the level, the streak and the board, and all three fill in without the screen being left
 * and returned to.
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
    questProgress: QuestProgressUseCase,
) : ViewModel() {

    val uiState: StateFlow<QuestLogUiState> =
        combine(
            gamificationRepository.observeProgress(),
            gamificationRepository.observeUnlockedAchievements(),
            questProgress(),
        ) { progress, unlocked, quests ->
            val heldIds = unlocked.mapTo(mutableSetOf()) { it.achievementId }
            QuestLogUiState(
                progress = progress,
                levelTitle = LevelTitles.forLevel(progress.currentLevel),
                quests = quests,
                // Counted against the catalogue rather than taken as the row count: an id on
                // file matching no entry — a milestone renamed without a migration — would
                // otherwise push the numerator past the denominator.
                achievementsUnlocked = Achievements.ALL.count { it.id in heldIds },
                achievementsTotal = Achievements.ALL.size,
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            // Loading, not empty. The defaults are a brand-new user's real state, so the only
            // thing this initial value must get right is that the store has not answered yet.
            initialValue = QuestLogUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
