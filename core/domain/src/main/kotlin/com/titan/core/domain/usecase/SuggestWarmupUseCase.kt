package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionMovement
import com.titan.core.domain.model.WarmupSuggestion
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.warmup.WarmupPlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What to warm up with and what to stretch afterwards, kept current while a session is logged.
 *
 * The reading half of the warm-up suggestion (#214): two repositories in, one
 * [WarmupSuggestion] out, and every decision left to [WarmupPlanner]. The same split
 * [NextLoadUseCase] makes, for the same reason — the planner is a pure function over values, and
 * this owns nothing but the choice of what to read.
 *
 * ### The session is passed in, not read
 *
 * Unlike [NextLoadUseCase], which is asked about one exercise and reads that exercise's history
 * itself, this is asked about a *whole session the caller is already holding*. The active
 * session screen has the exercises, their catalogue rows, their muscle links and the weights
 * their sets are planned at, all assembled; reading them again here would be four more observers
 * on tables the screen is already watching, and they would sometimes disagree with what is drawn.
 * So the caller builds [SessionMovement]s and this supplies the two things it cannot: the
 * mobility half of the catalogue, and the user's own equipment.
 *
 * ### The setting is honoured here
 *
 * "Warm-up suggestions" off means an empty suggestion, decided once in this class rather than by
 * every screen that might draw one. There is nothing for the cards to render and so nothing to
 * render, which is the same code path as a session with no match in the catalogue — a screen does
 * not have to tell the two apart, because in both cases there is no suggestion.
 */
class SuggestWarmupUseCase(
    private val exerciseRepository: ExerciseRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    /**
     * The suggestion for [sessionId], which is empty where there is nothing to say.
     *
     * @param movements the session's exercises **in the order they will be performed**.
     */
    operator fun invoke(
        sessionId: Long,
        movements: List<SessionMovement>,
    ): Flow<WarmupSuggestion> =
        combine(
            exerciseRepository.getMobilityExercises(),
            userPreferencesRepository.observe(),
        ) { catalogue, preferences ->
            if (!preferences.warmupSuggestions) {
                WarmupSuggestion()
            } else {
                WarmupPlanner.plan(
                    sessionId = sessionId,
                    movements = movements,
                    catalogue = catalogue,
                    preferences = preferences,
                )
            }
        }
            // On the result, for the reason [NextLoadUseCase] gives: the preferences flow
            // re-emits on every setting, and a theme change must not redraw two cards.
            .distinctUntilChanged()
}
