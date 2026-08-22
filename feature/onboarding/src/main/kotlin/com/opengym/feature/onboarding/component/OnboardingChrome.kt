package com.opengym.feature.onboarding.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.StepProgress
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.onboarding.OnboardingStep
import com.opengym.feature.onboarding.R

/**
 * The flow's header: where you are, and the way past this question.
 *
 * `STEP 2 OF 4` on the left, `SKIP` on the right, the filled rail under both — the wireframes'
 * treatment exactly. The counter is what a screen reader announces; the rail is decorative and
 * says so (see `StepProgress`), so the same fact is not read out twice.
 *
 * `SKIP` is a [TextButton] rather than the wireframe's plain text because it is a control and
 * needs the touch target, and it is drawn in the quiet role rather than the accent: the accent
 * belongs to the primary action at the bottom of the screen, and spending it up here would put
 * two equally loud ways forward on a screen with one question on it.
 */
@Composable
internal fun OnboardingHeader(
    step: OnboardingStep,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Row(
            // Held to the height a `TextButton` gives it, so the counter and the rail do not
            // jump up the screen on the last step — where there is nothing left to skip and
            // the button that was setting this row's height is gone.
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MinHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(
                text = stringResource(
                    R.string.onboarding_step_counter,
                    step.number,
                    OnboardingStep.COUNT,
                ),
            )
            if (step.isSkippable) {
                TextButton(onClick = onSkip) {
                    Eyebrow(
                        text = stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        StepProgress(step = step.number, steps = OnboardingStep.COUNT)
    }
}

/**
 * The one action on the screen, pinned to the bottom.
 *
 * Pinned rather than at the end of the content for the reason every other committing bar in
 * this app is: the welcome step's list and the goal step's four options are both taller than a
 * small phone at a large font scale, and a first-run flow whose way forward is below the fold
 * is a first-run flow that looks broken.
 *
 * The navigation-bar inset is asked for rather than assumed. Under the app shell it resolves
 * to nothing, because `OpenGymApp` pads the graph for the system bars and consumes them; it is
 * the whole inset anywhere this is hosted without that — a preview, a screenshot test.
 */
@Composable
internal fun OnboardingFooter(
    step: OnboardingStep,
    enabled: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.navigationBarsPadding()) {
        // The wireframes rule off every bar rather than floating it on elevation. A hairline
        // is legible in a gym; a 1dp shadow is not.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = onContinue,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen),
        ) {
            Text(
                // The label is the only thing that marks the last step as the last one. It
                // says what pressing it does — `Start training`, not `Done` — because what
                // happens next is opening the app, not closing a form.
                text = stringResource(
                    if (step.isLast) R.string.onboarding_start else R.string.onboarding_continue,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
