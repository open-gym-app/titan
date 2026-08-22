package com.opengym.feature.onboarding.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.MetaEntry
import com.opengym.core.ui.component.MetaGrid
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.SelectableOption
import com.opengym.core.ui.component.detail
import com.opengym.core.ui.component.label
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.onboarding.R

/**
 * The four step bodies, and the heading every one of them opens with.
 *
 * Each is stateless and hoisted like any other screen half: it takes what is stored and emits
 * what was tapped. The step *dispatch* lives in `OnboardingScreen`, so this file is only ever
 * read as "what does step three look like".
 */

/**
 * A step's opening: the question, then one line of context under it.
 *
 * `headlineLarge` is the wireframes' 28sp `h1`. It is the largest type in the app outside the
 * numeric roles, and it is spent here because a first-run screen has exactly one thing on it
 * and the question should be readable at arm's length before the options are.
 */
@Composable
private fun StepHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Step one: what this app is.
 *
 * It asks for nothing, which is deliberate — the first screen of a first-run flow is where the
 * user decides whether to keep going, and a question asked before they know what they have
 * opened is one they answer by uninstalling. The three lines under the heading are the app's
 * actual claims, in the order they matter to somebody standing in a gym.
 */
@Composable
internal fun WelcomeStep(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        StepHeading(
            title = stringResource(R.string.onboarding_welcome_title),
            body = stringResource(R.string.onboarding_welcome_body),
        )

        OpenGymCard(modifier = Modifier.fillMaxWidth()) {
            HighlightRow(
                label = stringResource(R.string.onboarding_welcome_logging),
                detail = stringResource(R.string.onboarding_welcome_logging_detail),
            )
            HairlineDivider()
            HighlightRow(
                label = stringResource(R.string.onboarding_welcome_progress),
                detail = stringResource(R.string.onboarding_welcome_progress_detail),
            )
            HairlineDivider()
            HighlightRow(
                label = stringResource(R.string.onboarding_welcome_privacy),
                detail = stringResource(R.string.onboarding_welcome_privacy_detail),
            )
        }

        // How long this is going to take, said before it is asked. The commonest reason a
        // first-run flow is abandoned is not knowing whether it ends.
        Eyebrow(
            text = stringResource(R.string.onboarding_welcome_length),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** One claim: what it does, and the mono line saying how. The same shape a settings row has. */
@Composable
private fun HighlightRow(label: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OpenGymTheme.spacing.card, vertical = OpenGymTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Step two: the training goal.
 *
 * Four options with a line each on what training that way looks like, because "strength or
 * hypertrophy?" is not a question somebody three weeks in can answer from the words alone —
 * and they are the reader this app is for.
 */
@Composable
internal fun GoalStep(
    selected: TrainingGoal,
    onSelect: (TrainingGoal) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionStep(
        title = stringResource(R.string.onboarding_goal_title),
        body = stringResource(R.string.onboarding_goal_body),
        options = TrainingGoal.entries,
        selected = selected,
        label = { it.label() },
        detail = { it.detail() },
        onSelect = onSelect,
        modifier = modifier,
    )
}

/**
 * Step three: kilograms or pounds.
 *
 * The one answer here that changes every later screen, and the one worth asking before the
 * user has logged anything — a lifter who sees their first set written in the wrong unit
 * concludes the app is wrong about weight, not that a setting is.
 */
@Composable
internal fun UnitsStep(
    selected: UnitSystem,
    onSelect: (UnitSystem) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionStep(
        title = stringResource(R.string.onboarding_units_title),
        body = stringResource(R.string.onboarding_units_body),
        options = UnitSystem.entries,
        selected = selected,
        label = { it.label() },
        detail = { it.detail() },
        onSelect = onSelect,
        modifier = modifier,
    )
}

/**
 * The shape both question steps share: a heading, then one [SelectableOption] per value.
 *
 * One composable rather than two nearly identical ones, so the two questions cannot drift into
 * different spacing or a different selection treatment. The whole list is a [selectableGroup]
 * so a screen reader announces it as one set of radio buttons rather than as four unrelated
 * controls.
 */
@Composable
private fun <T> OptionStep(
    title: String,
    body: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    detail: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        StepHeading(title = title, body = body)

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            options.forEach { option ->
                SelectableOption(
                    label = label(option),
                    detail = detail(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/**
 * Step four: the answers, read back.
 *
 * A closing step that only said "all done" would be a tap for nothing. Showing the two stored
 * values is what makes the flow verifiable — a user who mistapped the unit system finds out
 * here, one back gesture from the screen that fixes it, rather than in the middle of their
 * first set.
 */
@Composable
internal fun ReadyStep(
    goal: TrainingGoal,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        StepHeading(
            title = stringResource(R.string.onboarding_ready_title),
            body = stringResource(R.string.onboarding_ready_body),
        )

        MetaGrid(
            entries = listOf(
                MetaEntry(
                    label = stringResource(R.string.onboarding_ready_goal),
                    value = goal.label(),
                ),
                MetaEntry(
                    label = stringResource(R.string.onboarding_ready_units),
                    value = unitSystem.label(),
                ),
            ),
        )

        Text(
            text = stringResource(R.string.onboarding_ready_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
