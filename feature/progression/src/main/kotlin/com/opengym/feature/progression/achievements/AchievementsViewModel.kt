package com.opengym.feature.progression.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.repository.GamificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The achievements grid's state: the catalogue, marked against what the user holds.
 *
 * ### The catalogue is walked, not queried
 *
 * The grid is built by walking [Achievements.ALL] and marking which ids are held, rather than by
 * listing what is held. Two things fall out of that and both are wanted: a locked achievement has
 * a cell, so a new user sees what there is to earn; and an id on file matching no catalogue entry
 * — a milestone renamed without a migration — cannot put a cell with no definition behind it into
 * the grid.
 *
 * ### One read, and it is reactive
 *
 * A milestone unlocked by a workout finished while this screen sits in the back stack fills in
 * without the screen being left and returned to. `WhileSubscribed` with the codebase's standard
 * five-second stop timeout: long enough to survive a rotation without re-querying, short enough
 * that leaving the screen stops the work.
 */
@HiltViewModel
class AchievementsViewModel @Inject constructor(gamificationRepository: GamificationRepository) : ViewModel() {

    val uiState: StateFlow<AchievementsUiState> =
        gamificationRepository.observeUnlockedAchievements()
            .map { unlocked ->
                val heldIds = unlocked.mapTo(mutableSetOf()) { it.achievementId }
                AchievementsUiState(
                    entries = Achievements.ALL.map { achievement ->
                        AchievementEntry(
                            achievement = achievement,
                            isUnlocked = achievement.id in heldIds,
                        )
                    },
                    isLoading = false,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                // Loading, not empty. A user who holds nothing still gets six locked cells, so
                // the only thing this initial value must get right is that nothing has answered.
                initialValue = AchievementsUiState(),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
