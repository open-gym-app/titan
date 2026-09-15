package com.opengym.core.domain.usecase

import com.opengym.core.domain.load.NextLoadEngine
import com.opengym.core.domain.model.LoadSuggestion
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the next set of one exercise should be, kept current while a session is logged.
 *
 * The reading half of the next-load engine (#M7-15): three repositories in, one
 * [LoadSuggestion] out, and every decision left to [NextLoadEngine]. This class owns nothing
 * but the choice of *what* to read — which is the part a pure function cannot be tested for
 * and a repository cannot be asked about.
 *
 * ### Two performances, not one
 *
 * [ProgressionRepository.getRecentPerformances] is asked for [HISTORY_DEPTH] entries because
 * the level gate looks one performance further back than the rule itself — see the engine's
 * note on [com.opengym.core.domain.model.FitnessLevel]. Asking for more would be rows nothing
 * reads; asking for one would make every intermediate a beginner.
 *
 * ### Live, and quiet
 *
 * A `Flow` rather than a one-shot, because the inputs move while the screen is open: the
 * user changes their goal in settings mid-workout, or logs the set this was suggesting and
 * the history behind it does not change — the current session is excluded — but the
 * preferences flow re-emits on every setting. [distinctUntilChanged] on the *result* is what
 * keeps a theme change from redrawing the suggestion row: the engine is cheap enough to re-run
 * and the screen is not cheap enough to re-render.
 *
 * An exercise that no longer exists — deleted under an open session — yields null, which is
 * what the screen draws for "nothing to suggest" anyway.
 */
class NextLoadUseCase(
    private val progressionRepository: ProgressionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    /**
     * The suggestion for [exerciseId]'s next set in [sessionId], or null when the engine has
     * nothing to say — see [NextLoadEngine.suggest] for when that is.
     *
     * @param sessionId the session being logged, whose own sets are never its own history.
     */
    operator fun invoke(
        exerciseId: Long,
        sessionId: Long,
    ): Flow<LoadSuggestion?> =
        combine(
            progressionRepository.getRecentPerformances(
                exerciseId = exerciseId,
                excludingSessionId = sessionId,
                limit = HISTORY_DEPTH,
            ),
            exerciseRepository.getById(exerciseId),
            userPreferencesRepository.observe(),
        ) { history, exercise, preferences ->
            exercise?.let { NextLoadEngine.suggest(history = history, preferences = preferences, exercise = it) }
        }.distinctUntilChanged()

    companion object {

        /** The latest performance, and the one before it for the level gate. */
        const val HISTORY_DEPTH: Int = 2
    }
}
