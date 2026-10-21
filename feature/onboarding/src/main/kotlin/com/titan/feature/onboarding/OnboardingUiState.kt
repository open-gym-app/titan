package com.titan.feature.onboarding

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.UserPreferences

/**
 * Everything the onboarding flow draws, as one value.
 *
 * The answers are held as the domain's own [UserPreferences], read straight from the store,
 * rather than as a draft the flow accumulates and writes at the end. That is the shape the
 * acceptance criteria ask for — *each step persists its selection* — and it is what makes
 * killing the app on step four and relaunching land on an app that already knows the answers
 * to the steps before it. It also means a radio here shows what is *stored*, never an
 * optimistic copy of it, so a write that fails cannot leave the screen disagreeing with the
 * disk.
 *
 * @param step which question is on screen.
 * @param preferences the stored settings. Defaults are the real defaults, so a state built by
 *   hand in a test or a preview is a fresh install rather than a placeholder.
 * @param isFinishing the completion flag is being written. Held so the last step's button can
 *   be disabled: it is one small write, but a second press during it would fire a second
 *   navigation at a screen that is already leaving.
 * @param isFinished onboarding is over and recorded — the flow's way of saying it is done.
 *   Set only once [UserPreferences.onboardingCompleted] has actually been written, because
 *   navigating on anything less means the next launch asks the same five questions again.
 * @param message a one-shot outcome to show and forget. Present only between the event that
 *   caused it and the screen acknowledging it with [OnboardingEvent.MessageShown].
 */
@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.FIRST,
    val preferences: UserPreferences = UserPreferences(),
    val isFinishing: Boolean = false,
    val isFinished: Boolean = false,
    val message: OnboardingMessage? = null,
)

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, so the ViewModel does not reach for a `Context` and every
 * user-visible word stays in the screen's own resources — the same arrangement settings uses.
 */
enum class OnboardingMessage {

    /** An answer could not be stored. The step still advances; the default stands. */
    SAVE_FAILED,

    /**
     * The completion flag could not be written, so the flow did **not** finish.
     *
     * The one failure here that has to stop the user rather than merely inform them:
     * onboarding whose completion is not recorded and that navigates away anyway is
     * onboarding the next launch shows all over again.
     */
    FINISH_FAILED,
}
