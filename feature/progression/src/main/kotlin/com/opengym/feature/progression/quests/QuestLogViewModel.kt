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
 * The quest log's state: level and rank, the quest board, the achievements grid, and the streak.
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
 * The achievements grid is built by walking [Achievements.ALL] and marking which ids the user
 * holds, rather than by listing what they hold. Two things fall out of that and both are wanted:
 * a locked achievement has a cell, so a new user sees what there is to earn; and an id on file
 * that matches no catalogue entry — a milestone renamed without a migration — cannot put a cell
 * with no definition behind it into the grid.
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
                achievements = Achievements.ALL.map { achievement ->
                    AchievementEntry(
                        achievement = achievement,
                        isUnlocked = achievement.id in heldIds,
                    )
                },
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
