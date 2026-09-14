package com.opengym.feature.onboarding.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.StepProgress
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.onboarding.OnboardingStep
import com.opengym.feature.onboarding.R

/**
 * The flow's header: where you are, and the way past this question.
 *
 * `STEP 2 OF 5` on the left, `SKIP` on the right, the segmented rail under both — the
 * wireframes' treatment exactly. The counter is what a screen reader announces; the rail is
 * decorative and says so (see `StepProgress`), so the same fact is not read out twice.
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
            // A *fixed* height, not a minimum, and the minimum touch target rather than
            // `ButtonDefaults.MinHeight`: a `TextButton` is laid out at 48dp however small its
            // own box is, so a row merely floored at 40dp is 48dp tall on the steps that have
            // a `SKIP` and 40dp tall on the last one — which walked the counter and the rail
            // eight pixels up the screen exactly when the user was looking at them.
            modifier = Modifier.fillMaxWidth().height(HEADER_ROW_HEIGHT),
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
 * The way on, and the way back, pinned to the bottom.
 *
 * Pinned rather than at the end of the content for the reason every other committing bar in
 * this app is: the welcome step's list and the goal step's four options are both taller than a
 * small phone at a large font scale, and a first-run flow whose way forward is below the fold
 * is a first-run flow that looks broken.
 *
 * ## Back is a button, not only a gesture
 *
 * The system back gesture moves a step at a time (see `OnboardingRoute`), but a gesture is not
 * an affordance: nothing on the screen said the flow could be reversed, so a user who mistapped
 * the unit system had no visible way to fix it and every reason to think there was none. The
 * control sits beside `Continue` rather than as an arrow in the header because this is a
 * wizard — back and forward are the same kind of move, they belong in the same place, and that
 * place is the bottom of the screen where the thumb already is.
 *
 * It is absent rather than disabled on the first step. There is nothing behind it there, and a
 * greyed control on the very first screen of an app reads as something being broken.
 *
 * The navigation-bar inset is asked for rather than assumed. Under the app shell it resolves
 * to nothing, because `OpenGymApp` pads the graph for the system bars and consumes them; it is
 * the whole inset anywhere this is hosted without that — a preview, a screenshot test.
 */
@Composable
internal fun OnboardingFooter(
    step: OnboardingStep,
    enabled: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.navigationBarsPadding()) {
        // The wireframes rule off every bar rather than floating it on elevation. A hairline
        // is legible in a gym; a 1dp shadow is not.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(OpenGymTheme.spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step.previous() != null) {
                OutlinedButton(
                    onClick = onBack,
                    enabled = enabled,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        // The word beside it is the label; a description here would have a
                        // screen reader announce the button twice.
                        contentDescription = null,
                        modifier = Modifier.size(BACK_ICON_SIZE),
                    )
                    Spacer(modifier = Modifier.width(OpenGymTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.onboarding_back),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Button(
                onClick = onContinue,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                // The weight, so the primary action takes whatever `Back` leaves and fills the
                // bar on its own on the first step. It is the wider of the two on every step,
                // which is the hierarchy said in width as well as in colour.
                modifier = Modifier.weight(1f),
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
}

/**
 * The header row's height: the minimum interactive size, which is what the `SKIP` inside it
 * is laid out at whatever else the row asks for.
 */
private val HEADER_ROW_HEIGHT: Dp = 48.dp

/** The arrow in `Back`, sized to sit on the cap height of the label beside it. */
private val BACK_ICON_SIZE: Dp = 18.dp
