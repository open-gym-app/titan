package com.opengym.feature.onboarding.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.ICON_TILE_HERO_SIZE
import com.opengym.core.ui.component.IconTile
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.SelectableOption
import com.opengym.core.ui.component.detail
import com.opengym.core.ui.component.icon
import com.opengym.core.ui.component.label
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.onboarding.R

/*
 * The four step bodies, and the heading every one of them opens with.
 *
 * Each is stateless and hoisted like any other screen half: it takes what is stored and emits
 * what was tapped. The step *dispatch* lives in `OnboardingScreen`, so this file is only ever
 * read as "what does step three look like".
 *
 * ## This screen is the one that is allowed to breathe
 *
 * Everything else in this app is dense on purpose — a set row carries four figures and a tick
 * on a 360dp screen, and the spacing scale tops out at 16dp because of it. A first-run screen
 * is the opposite problem: it holds one question, it is read once, and it is the only place
 * the user is deciding whether this app is worth keeping. So the blocks here are separated by
 * [STEP_GAP] rather than by the screen scale's largest step, and the two steps that ask nothing
 * open with a mark rather than with a paragraph. It is a deliberate exception, and it stops at
 * this feature.
 */

/**
 * A step's opening: an optional mark, the question, and one line of context under it.
 *
 * `headlineLarge` is the wireframes' 28sp `h1`. It is the largest type in the app outside the
 * numeric roles, and it is spent here because a first-run screen has exactly one thing on it
 * and the question should be readable at arm's length before the options are.
 *
 * @param mark the picture over the title, on the two steps that are announcing something
 *   rather than asking. The steps that ask a question do not get one: an icon over "What are
 *   you training for?" is decoration competing with the four marks in the answer under it.
 * @param centred whether the block is centred. It follows the step: an announcement is centred
 *   under its mark, a question is set at the start of the line the options begin on, so the
 *   heading and the first option share an edge.
 */
@Composable
private fun StepHeading(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    mark: ImageVector? = null,
    markShape: Shape? = null,
    centred: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        if (mark != null) {
            IconTile(
                icon = mark,
                size = ICON_TILE_HERO_SIZE,
                shape = markShape ?: MaterialTheme.shapes.medium,
                modifier = Modifier.padding(bottom = OpenGymTheme.spacing.md),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        )
    }
}

/**
 * Step one: what this app is.
 *
 * It asks for nothing, which is deliberate — the first screen of a first-run flow is where the
 * user decides whether to keep going, and a question asked before they know what they have
 * opened is one they answer by uninstalling. The three claims under the heading are the app's
 * actual ones, in the order they matter to somebody standing in a gym, and each is drawn with
 * the mark that stands for it: three lines of prose in a box is a paragraph, and a paragraph
 * is what a first screen gets skipped for.
 */
@Composable
internal fun WelcomeStep(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(STEP_GAP),
    ) {
        StepHeading(
            title = stringResource(R.string.onboarding_welcome_title),
            body = stringResource(R.string.onboarding_welcome_body),
            mark = Icons.Outlined.FitnessCenter,
            centred = true,
        )

        OpenGymCard(modifier = Modifier.fillMaxWidth()) {
            HighlightRow(
                // Not the barbell: that one is the hero above, standing for the app itself,
                // and the same picture twice on one screen makes the second one furniture.
                icon = Icons.Outlined.Checklist,
                label = stringResource(R.string.onboarding_welcome_logging),
                detail = stringResource(R.string.onboarding_welcome_logging_detail),
            )
            HairlineDivider()
            HighlightRow(
                icon = Icons.AutoMirrored.Outlined.TrendingUp,
                label = stringResource(R.string.onboarding_welcome_progress),
                detail = stringResource(R.string.onboarding_welcome_progress_detail),
            )
            HairlineDivider()
            HighlightRow(
                icon = Icons.Outlined.Lock,
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

/** One claim: its mark, what it does, and the line saying how. */
@Composable
private fun HighlightRow(
    icon: ImageVector,
    label: String,
    detail: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(OpenGymTheme.spacing.card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        IconTile(icon = icon, container = MaterialTheme.colorScheme.surfaceContainer)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // Prose, in a prose face. This line used to be set in the mono numeric role
                // like the meta line under an exercise name, but those lines are *figures* —
                // `80 kg × 5`, `4 sets` — and a sentence typed out on a typewriter beside a
                // proportional heading is the thing that made this card read as a debug dump.
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        icon = { it.icon() },
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
        icon = { it.icon() },
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
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(STEP_GAP),
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
                    icon = icon(option),
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
 * here, one press of `Back` from the screen that fixes it, rather than in the middle of their
 * first set. Each is shown with the same mark it was chosen under, so the recap is recognisable
 * as the answer given rather than as a word that has to be re-read.
 */
@Composable
internal fun ReadyStep(
    goal: TrainingGoal,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(STEP_GAP),
    ) {
        StepHeading(
            title = stringResource(R.string.onboarding_ready_title),
            body = stringResource(R.string.onboarding_ready_body),
            // A circle rather than the rounded square the welcome mark gets: this one is an
            // outcome, not a subject, and the shape is what says so before the tick is read.
            mark = Icons.Outlined.Check,
            markShape = CircleShape,
            centred = true,
        )

        OpenGymCard(modifier = Modifier.fillMaxWidth()) {
            RecapRow(
                icon = goal.icon(),
                label = stringResource(R.string.onboarding_ready_goal),
                value = goal.label(),
            )
            HairlineDivider()
            RecapRow(
                icon = unitSystem.icon(),
                label = stringResource(R.string.onboarding_ready_units),
                value = unitSystem.label(),
            )
        }

        Text(
            text = stringResource(R.string.onboarding_ready_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One answer, read back: its mark, what was asked, and what was stored.
 *
 * The row announces as a single node. A label and a value read as two fragments are
 * "Training goal", then "Hypertrophy" a swipe apart, which is not the same information — the
 * same rule `MetaGrid` follows for the metadata blocks elsewhere in the app.
 */
@Composable
private fun RecapRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    val spoken = stringResource(R.string.onboarding_ready_recap_description, label, value)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(OpenGymTheme.spacing.card)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        IconTile(icon = icon)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Eyebrow(text = label)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The gap between two blocks of a step.
 *
 * Wider than `spacing.section`, which is this app's largest and is sized for screens that are
 * holding as much as they can. See the note at the top of this file: a first-run screen has
 * one thing on it, and 16dp between a heading and the box under it makes them read as one
 * crowded block rather than as a question and its answer.
 */
private val STEP_GAP: Dp = 24.dp
