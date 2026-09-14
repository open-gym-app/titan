package com.opengym.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.ui.state.savedScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The first-run flow: which question is showing, and the writes each answer makes.
 *
 * ### The answers are the store's, the step is the screen's
 *
 * Same split as settings, for the same reason: [stepState] holds only what the *flow* owns —
 * where the user is, whether the closing write is in flight, an outcome waiting to be shown —
 * and the answers are read from `UserPreferencesRepository` and never copied here. A radio
 * therefore shows what is stored rather than what was tapped, so there is no path where the
 * screen and the disk disagree, and a failed write needs no rollback because nothing was
 * optimistically changed.
 *
 * It is also what makes the flow *resumable*. Onboarding is not marked complete until the
 * last step, so an app killed on step three relaunches into onboarding — and lands on a flow
 * that already holds the answers to steps one and two, because they were written when they
 * were given rather than banked in a draft that died with the process.
 *
 * ### Skipping writes, it does not pass
 *
 * [skip] stores the step's documented default and moves on. Writing a value the store would
 * have returned anyway looks redundant and is the point: "skip" has to mean *the default*
 * rather than *whatever is in there*, which is the difference that shows up when a user picks
 * a goal, goes back with the system gesture and skips it instead.
 *
 * ### Only one failure is fatal
 *
 * Every setter can throw [java.io.IOException]. A failed *answer* is worth saying out loud and
 * nothing more — the step still advances, and the preference is still the default it would
 * have been. A failed *completion* stops the flow where it stands: navigating to Home without
 * `onboardingCompleted` on disk means the next launch asks all five questions again, which is
 * worse than a snackbar and a second press of the same button.
 *
 * Both catch [Throwable] rather than a type, because the store is not the only thing that can
 * refuse a write, and re-throw [CancellationException], because a cancelled `viewModelScope`
 * is the screen going away rather than a write that failed.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /**
     * What the flow owns, as opposed to what the store does.
     *
     * Saved, so a process killed on the third question comes back to the third question (#183).
     * The *answers* need no saving: each one is written to the settings store as it is given —
     * which is what makes this flow resumable at all — so only the place in the sequence is
     * this class's to keep.
     */
    private val stepState = savedStateHandle.savedScreenState(viewModelScope) { StepState() }

    val uiState: StateFlow<OnboardingUiState> = combine(
        userPreferencesRepository.observe(),
        stepState,
    ) { preferences, screen ->
        OnboardingUiState(
            step = screen.step,
            preferences = preferences,
            isFinishing = screen.isFinishing,
            isFinished = screen.isFinished,
            message = screen.message,
        )
    }.stateIn(
        scope = viewModelScope,
        // Long enough to survive a rotation without re-reading the store, short enough that a
        // flow left in the background is not holding a collector open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        // Carrying the restored step, so a flow rebuilt on question three draws question three
        // rather than the welcome card and then a jump — the store read behind this is a
        // DataStore one, which is several frames rather than none.
        initialValue = OnboardingUiState(step = stepState.value.step),
    )

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            OnboardingEvent.Continued -> advance()

            OnboardingEvent.Skipped -> skip()

            OnboardingEvent.BackPressed -> goBack()

            is OnboardingEvent.TrainingGoalSelected -> answer {
                userPreferencesRepository.setTrainingGoal(event.goal)
            }

            is OnboardingEvent.FitnessLevelSelected -> answer {
                userPreferencesRepository.setFitnessLevel(event.level)
            }

            is OnboardingEvent.UnitSystemSelected -> answer {
                userPreferencesRepository.setUnitSystem(event.unitSystem)
            }

            OnboardingEvent.MessageShown -> stepState.update { it.copy(message = null) }
        }
    }

    /**
     * Move on with the answer as it stands — or finish, on the last step.
     *
     * The primary action does both because it is one action to the user: the button says
     * `Continue` four times and then says `Start training`, and there is nothing behind that
     * difference except which step is showing.
     */
    private fun advance() {
        val step = stepState.value.step
        when (val next = step.next()) {
            null -> finish()
            else -> stepState.update { it.copy(step = next) }
        }
    }

    /** Apply the step's documented default, then move on. See [OnboardingEvent.Skipped]. */
    private fun skip() {
        when (stepState.value.step) {
            // Nothing is asked on the welcome step, so there is nothing to default.
            OnboardingStep.WELCOME -> Unit

            OnboardingStep.GOAL -> answer {
                userPreferencesRepository.setTrainingGoal(DEFAULT_TRAINING_GOAL)
            }

            OnboardingStep.LEVEL -> answer {
                userPreferencesRepository.setFitnessLevel(DEFAULT_FITNESS_LEVEL)
            }

            OnboardingStep.UNITS -> answer {
                userPreferencesRepository.setUnitSystem(DEFAULT_UNIT_SYSTEM)
            }

            // The last step's action is the way out; there is no `SKIP` on it to reach this.
            OnboardingStep.READY -> Unit
        }
        advance()
    }

    /**
     * Back up one question.
     *
     * Nothing is undone by it: the previous step's answer is already on disk, and this
     * returns the user to the screen showing it so they can change it. On the first step
     * there is no previous, and the screen does not register a handler at all — back there
     * means leaving the app, which is the correct answer to "I do not want to do this now"
     * given the flow resumes on the next launch.
     */
    private fun goBack() {
        stepState.value.step.previous()?.let { previous ->
            stepState.update { it.copy(step = previous) }
        }
    }

    /**
     * Store one answer, in the background, and move on regardless.
     *
     * The step is never gated on the write. A first-run flow that stalls on a disk that will
     * not take a two-byte preference is a first-run flow the user cannot get out of, and the
     * value that would have been written is the one already being read back anyway.
     */
    private fun answer(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                stepState.update { it.copy(message = OnboardingMessage.SAVE_FAILED) }
            }
        }
    }

    /**
     * Record that onboarding is over, and only then say so.
     *
     * Guarded against a second run while one is in flight, so a double tap on the last button
     * cannot fire two writes and two navigations.
     */
    private fun finish() {
        if (stepState.value.isFinishing) return
        stepState.update { it.copy(isFinishing = true) }

        viewModelScope.launch {
            try {
                userPreferencesRepository.setOnboardingCompleted(true)
                stepState.update { it.copy(isFinishing = false, isFinished = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                stepState.update {
                    it.copy(isFinishing = false, message = OnboardingMessage.FINISH_FAILED)
                }
            }
        }
    }

    /**
     * Where the flow is, and what it is doing about it.
     *
     * Only the step comes back. The write that finishes onboarding went with the process that
     * started it, and a flow restored claiming to be finishing — or finished, which is the state
     * that navigates the user out — would be reporting something that did not happen.
     */
    @Serializable
    private data class StepState(
        val step: OnboardingStep = OnboardingStep.FIRST,
        @Transient
        val isFinishing: Boolean = false,
        @Transient
        val isFinished: Boolean = false,
        @Transient
        val message: OnboardingMessage? = null,
    )

    companion object {

        /**
         * What skipping the goal question stores.
         *
         * Read off [UserPreferences] rather than named again here, so "the documented default"
         * is the *same* default a fresh install already reads and the two cannot drift apart.
         */
        val DEFAULT_TRAINING_GOAL: TrainingGoal = UserPreferences().trainingGoal

        /**
         * What skipping the level question stores. Same argument as above — and here the
         * default is also the safe answer: a beginner is progressed most cautiously, which is
         * what somebody who did not want to say should get.
         */
        val DEFAULT_FITNESS_LEVEL: FitnessLevel = UserPreferences().fitnessLevel

        /** What skipping the units question stores. Same argument as above. */
        val DEFAULT_UNIT_SYSTEM: UnitSystem = UserPreferences().unitSystem

        private val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
