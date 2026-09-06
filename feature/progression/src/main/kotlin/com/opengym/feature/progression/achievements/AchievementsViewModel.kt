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
 * The achievements shelf's state: the catalogue, marked against what the user holds.
 *
 * ### The catalogue is walked, not queried
 *
 * The shelf is built by walking [Achievements.ALL] and marking which ids are held, rather than by
 * listing what is held. Two things fall out of that and both are wanted: a locked achievement has
 * a medal, so a new user sees what there is to earn; and an id on file matching no catalogue entry
 * — a milestone renamed without a migration — cannot put a medal with no definition behind it on
 * the shelf.
 *
 * ### One read, and it is reactive
 *
 * A milestone unlocked by a workout finished while this screen sits in the back stack arrives
 * without the screen being left and returned to — and, because it becomes
 * [AchievementsUiState.latest], the hero re-strikes on the medal that has just been earned. `WhileSubscribed` with the codebase's standard
 * five-second stop timeout: long enough to survive a rotation without re-querying, short enough
 * that leaving the screen stops the work.
 */
@HiltViewModel
class AchievementsViewModel @Inject constructor(gamificationRepository: GamificationRepository) : ViewModel() {

    val uiState: StateFlow<AchievementsUiState> =
        gamificationRepository.observeUnlockedAchievements()
            .map { unlocked ->
                // By id rather than a set of them: the medal under the hero carries the date it
                // was struck, and a `contains` check would have thrown that away on the way in.
                val heldAt = unlocked.associate { it.achievementId to it.unlockedAt.toEpochMilli() }
                AchievementsUiState(
                    entries = Achievements.ALL.map { achievement ->
                        AchievementEntry(
                            achievement = achievement,
                            isUnlocked = achievement.id in heldAt,
                            unlockedAt = heldAt[achievement.id],
                        )
                    },
                    isLoading = false,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                // Loading, not empty. A user who holds nothing still gets six locked medals, so
                // the only thing this initial value must get right is that nothing has answered.
                initialValue = AchievementsUiState(),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
