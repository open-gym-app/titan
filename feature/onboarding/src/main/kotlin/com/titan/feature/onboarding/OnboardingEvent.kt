package com.titan.feature.onboarding

import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem

/**
 * Everything the onboarding flow can report.
 *
 * [Continued] and [Skipped] are two events rather than one `Advanced` carrying a boolean,
 * because they are two different intentions and the ViewModel does two different things with
 * them: continuing takes whatever is stored, skipping first writes the step's documented
 * default. Naming them apart is what keeps that distinction out of an `if` at a call site.
 */
sealed interface OnboardingEvent {

    /** The primary action: take the answer as it stands and move on — or, on the last step, finish. */
    data object Continued : OnboardingEvent

    /**
     * The `SKIP` in the header: do not answer this one.
     *
     * It applies the step's documented default rather than leaving the preference untouched.
     * The two are the same value on a fresh install, which is exactly why the write happens:
     * skipping has to mean *the default*, including when the user has already chosen
     * something on this step and gone back to un-choose it.
     */
    data object Skipped : OnboardingEvent

    /** The system back gesture, on any step but the first. */
    data object BackPressed : OnboardingEvent

    data class TrainingGoalSelected(val goal: TrainingGoal) : OnboardingEvent

    data class FitnessLevelSelected(val level: FitnessLevel) : OnboardingEvent

    data class UnitSystemSelected(val unitSystem: UnitSystem) : OnboardingEvent

    /** The snackbar has been shown; drop [OnboardingUiState.message] so it cannot show twice. */
    data object MessageShown : OnboardingEvent
}
