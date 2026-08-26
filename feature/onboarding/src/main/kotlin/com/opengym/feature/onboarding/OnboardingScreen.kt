package com.opengym.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.onboarding.component.GoalStep
import com.opengym.feature.onboarding.component.OnboardingFooter
import com.opengym.feature.onboarding.component.OnboardingHeader
import com.opengym.feature.onboarding.component.ReadyStep
import com.opengym.feature.onboarding.component.UnitsStep
import com.opengym.feature.onboarding.component.WelcomeStep
import kotlinx.coroutines.flow.filterNotNull

/**
 * The first-run flow, wired to its ViewModel.
 *
 * @param onFinished onboarding is over and recorded. `:app` turns this into the move to Home
 *   that also drops onboarding off the back stack — see `OnboardingNavigation`. Raised once,
 *   from the state rather than from the button, so it cannot fire before the completion flag
 *   is on disk.
 */
@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onFinished()
    }

    // Back is a step *within* the flow rather than a way out of it — the four steps are one
    // destination, so without this the gesture would drop the user out of onboarding entirely
    // from step three, having answered two questions and been shown none of them again.
    //
    // Disabled on the first step, where there is no previous question and back therefore means
    // what it means anywhere else at the root: leave. Nothing is lost by it — onboarding is not
    // marked complete until the last step, so the next launch opens it again.
    //
    // Here rather than in [OnboardingScreen] because `BackHandler` needs an
    // `OnBackPressedDispatcherOwner`, which a `@Preview` does not provide.
    BackHandler(enabled = state.step != OnboardingStep.FIRST) {
        viewModel.onEvent(OnboardingEvent.BackPressed)
    }

    OnboardingScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * Four questions, one screen at a time.
 *
 * The whole flow is a single destination rather than four, and the step is state. The back
 * stack is the wrong place for it: `:app` has to be able to drop onboarding *entirely* when it
 * finishes — the acceptance criterion is that system back does not return to it — and popping
 * one destination is a thing that can be got right once, where popping four is a thing that
 * has to keep being got right as steps are added.
 *
 * The screen is stateless: it renders [state] and reports [OnboardingEvent]s. It writes
 * nothing and reads no preference of its own.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it.textRes()) }

    // The message is marked shown *before* the snackbar is, and read through
    // `rememberUpdatedState` from an effect keyed on `Unit` — the same arrangement settings
    // uses, and for the same two reasons: `showSnackbar` suspends for the whole time it is up,
    // so clearing afterwards leaves the message to be shown a second time by a recomposed
    // screen, and clearing it while keyed on the message itself would cancel the very
    // `showSnackbar` this is in the middle of.
    val currentMessage by rememberUpdatedState(message)
    LaunchedEffect(Unit) {
        snapshotFlow { currentMessage }
            .filterNotNull()
            .collect { text ->
                onEvent(OnboardingEvent.MessageShown)
                snackbarHostState.showSnackbar(text)
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            OnboardingFooter(
                step = state.step,
                // Disabled only while the closing write is in flight. Nothing else here can
                // be in a state that makes moving on wrong: every question has an answer from
                // the moment it is drawn, because the store has a default for all of them.
                enabled = !state.isFinishing,
                onBack = { onEvent(OnboardingEvent.BackPressed) },
                onContinue = { onEvent(OnboardingEvent.Continued) },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            OnboardingHeader(
                step = state.step,
                onSkip = { onEvent(OnboardingEvent.Skipped) },
                modifier = Modifier.padding(top = OpenGymTheme.spacing.md),
            )

            AnimatedContent(
                targetState = state.step,
                // A push, sideways, in the direction the user is travelling — the same
                // vocabulary the app's own navigation uses for "one screen over another" (see
                // `NavMotion`). The steps are not separate destinations, so the motion is what
                // tells the user this is the same kind of move.
                //
                // `SizeTransform(clip = false)`: the four steps are different heights, and
                // animating the container between them makes the header appear to breathe.
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val direction = if (forward) 1 else -1
                    (
                        slideInHorizontally { width -> direction * width / STEP_SLIDE_FRACTION } +
                            fadeIn()
                        ) togetherWith (
                        slideOutHorizontally { width -> -direction * width / STEP_SLIDE_FRACTION } +
                            fadeOut()
                        ) using SizeTransform(clip = false)
                },
                label = "onboarding step",
                modifier = Modifier.weight(1f),
            ) { step ->
                // `BoxWithConstraints` so the scrolling column can be told to be *at least* as
                // tall as the space it was given. Without a floor it wraps its content and a
                // `verticalArrangement` has nothing spare to distribute — which is how the two
                // steps that announce rather than ask came to sit jammed under the header with
                // a third of a screen of nothing under them. With it, a step that fits is laid
                // out in the whole space, and one that does not — four options at twice the
                // font scale — still scrolls, because `Center` only distributes what is spare.
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val available = maxHeight

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = available)
                            .padding(
                                start = OpenGymTheme.spacing.screen,
                                end = OpenGymTheme.spacing.screen,
                                top = OpenGymTheme.spacing.section,
                                bottom = OpenGymTheme.spacing.section,
                            ),
                        verticalArrangement = if (step.isCentred) {
                            Arrangement.Center
                        } else {
                            Arrangement.Top
                        },
                    ) {
                        StepBody(step = step, state = state, onEvent = onEvent)
                    }
                }
            }
        }
    }
}

/**
 * Which step is drawn.
 *
 * An exhaustive `when` over the enum, so a step added to [OnboardingStep] fails to compile
 * here rather than rendering an empty screen with a Continue button under it.
 */
@Composable
private fun StepBody(
    step: OnboardingStep,
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    when (step) {
        OnboardingStep.WELCOME -> WelcomeStep(modifier = Modifier.fillMaxWidth())

        OnboardingStep.GOAL -> GoalStep(
            selected = state.preferences.trainingGoal,
            onSelect = { onEvent(OnboardingEvent.TrainingGoalSelected(it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OnboardingStep.UNITS -> UnitsStep(
            selected = state.preferences.unitSystem,
            onSelect = { onEvent(OnboardingEvent.UnitSystemSelected(it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OnboardingStep.READY -> ReadyStep(
            goal = state.preferences.trainingGoal,
            unitSystem = state.preferences.unitSystem,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Whether a step's content is centred in the space it was given, or set from the top.
 *
 * It follows what the step is *doing*, and the two answers look wrong on each other's steps.
 *
 * The two that ask a question are lists. They start at the top, under the rail that says how
 * far through the flow this is, because that is where the eye goes looking for the question —
 * centred, the units step's two options push the heading into the middle of the screen with a
 * third of it blank above, which reads as a page that failed to draw its top half. They are
 * also the two that overflow at a large font scale, where there is no spare space to centre in
 * anyway.
 *
 * The two that announce something — the greeting and the recap — are a mark, a sentence and
 * one small block, and there is no list under them for the eye to run down. Left at the top
 * they read as a screen that failed to load the *rest* of itself.
 *
 * An exhaustive `when`, so a step added to [OnboardingStep] is a compile error here rather
 * than a screen that quietly picks an alignment nobody chose.
 */
private val OnboardingStep.isCentred: Boolean
    get() = when (this) {
        OnboardingStep.WELCOME, OnboardingStep.READY -> true
        OnboardingStep.GOAL, OnboardingStep.UNITS -> false
    }

/** The sentence a message is shown as. */
private fun OnboardingMessage.textRes(): Int =
    when (this) {
        OnboardingMessage.SAVE_FAILED -> R.string.onboarding_save_failed
        OnboardingMessage.FINISH_FAILED -> R.string.onboarding_finish_failed
    }

/**
 * How far a step slides in from: a fifth of the width, not the whole of it.
 *
 * A full-width slide reads as a *destination* change, which this is not — the header and the
 * button stay put across it. A short travel with a cross-fade reads as the content being
 * replaced, which is what is happening.
 */
private const val STEP_SLIDE_FRACTION = 5

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Onboarding - welcome", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun OnboardingWelcomePreview() {
    OpenGymTheme {
        OnboardingScreen(state = OnboardingUiState(), onEvent = {})
    }
}

@Preview(name = "Onboarding - training goal", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun OnboardingGoalPreview() {
    OpenGymTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                step = OnboardingStep.GOAL,
                preferences = UserPreferences(trainingGoal = TrainingGoal.HYPERTROPHY),
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Onboarding - units, dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun OnboardingUnitsDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        OnboardingScreen(
            state = OnboardingUiState(
                step = OnboardingStep.UNITS,
                preferences = UserPreferences(
                    theme = ThemePreference.DARK,
                    unitSystem = UnitSystem.IMPERIAL,
                ),
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Onboarding - ready", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun OnboardingReadyPreview() {
    OpenGymTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                step = OnboardingStep.READY,
                preferences = UserPreferences(
                    trainingGoal = TrainingGoal.HYPERTROPHY,
                    unitSystem = UnitSystem.IMPERIAL,
                ),
            ),
            onEvent = {},
        )
    }
}

@Preview(
    name = "Onboarding - goal, twice the font",
    showBackground = true,
    heightDp = FULL_SCREEN_HEIGHT_DP,
    fontScale = 2f,
)
@Composable
fun OnboardingGoalLargeFontPreview() {
    // The step that overflows first: four options, each two lines, under a heading set in the
    // largest role in the app. It is here to keep the flow honest about scrolling — the
    // primary action stays pinned and reachable however far the content runs past the fold.
    OpenGymTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                step = OnboardingStep.GOAL,
                preferences = UserPreferences(trainingGoal = TrainingGoal.STRENGTH),
            ),
            onEvent = {},
        )
    }
}

/**
 * The height the previews render at.
 *
 * A phone, not the 480dp the preview default gives: this flow's whole job is filling a first
 * screen, and a reference rendered short enough to clip the welcome card is a reference that
 * cannot tell anybody whether it does.
 */
private const val FULL_SCREEN_HEIGHT_DP = 780
