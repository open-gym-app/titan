package com.opengym.feature.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.format.formatAsClock
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.home.R
import com.opengym.feature.home.ResumeState
import com.opengym.feature.home.SessionPosition

/**
 * The workout the app is still holding open, and the two things that can be done about it.
 *
 * ```text
 * ┌─────────────────────────────────────────┐
 * │ RUNNING · 23:14                         │
 * │ Push Day · Upper                        │
 * │ Bench press · set 3                     │
 * │ ┌───────────────────┐ ┌───────────────┐ │
 * │ │ ▶ RESUME          │ │ Discard       │ │
 * │ └───────────────────┘ └───────────────┘ │
 * └─────────────────────────────────────────┘
 * ```
 *
 * The one accented card on the screen — [OpenGymCard]'s `accented`, which exists for exactly
 * this — and the only place the flat accent is spent, on the button that picks the workout back
 * up. The wireframe fills the whole card with the accent tint; the card is bordered instead,
 * because a tinted card would take the accent away from the button inside it, which is the
 * thing the user is meant to press.
 *
 * ### Two buttons of different weights, side by side — until they do not fit
 *
 * Resuming is what almost everybody wants, and it takes the width left over. Discarding is
 * outlined and sized to its own label: it is the exit for a workout somebody opened by
 * mistake, and it has to be *reachable* without being the neighbour that gets hit by a thumb
 * aiming at the other one. What it opens is a question, not a delete — see
 * [com.opengym.core.ui.component.DiscardSessionDialog].
 *
 * Past [STACKED_FONT_SCALE] they stack instead, full width, the way `ResumeSessionDialog` lays
 * its three out and for the same reason: at twice the type there is not room for both across a
 * phone, and two buttons squeezing each other are two buttons with clipped labels. A card that
 * grows taller is the cheap half of that trade — an action nobody can read is the expensive
 * one.
 *
 * ### It says how long, not how much
 *
 * The clock is the figure a lifter reads here: twenty-three minutes ago means "I am still in
 * this workout", and two hours ago means "the phone was in my pocket on the way home". The set
 * count is the discard question's, and is asked there rather than drawn here.
 *
 * Read as a countdown is — `23:14`, growing an hours field only when there is one — and not as
 * the session header's `0:23:14`. The header keeps its zero hours because it is a live column
 * that must not change width mid-workout; this is a line of prose in an eyebrow, and a leading
 * `0:` on it is a digit the reader has to discard before the number means anything.
 *
 * ### The card is the target, not just the button
 *
 * The title and the position line are the largest thing on the screen, and a banner offering
 * one obvious action should take a tap anywhere on it. The button stays as the explicit
 * affordance, and the nested `Discard` keeps its own: a tap that lands on either of them is
 * that button's, and only the space around them falls through to the card.
 */
@Composable
fun ResumeCard(
    resume: ResumeState,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(accented = true, modifier = modifier.fillMaxWidth()) {
        Column(
            // Inside the card and before the padding, the way a template card's click is
            // built: on the modifier handed to [OpenGymCard] it would sit outside the
            // `Surface`'s clip and ripple square over a rounded card, and after the padding it
            // would leave a dead border the width of the card's inset.
            modifier = Modifier
                .clickable(
                    onClickLabel = stringResource(R.string.home_resume_action),
                    onClick = onResume,
                )
                .padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            // "RUNNING · 23:14", or "PAUSED · 23:14". Which of the two it says changes
            // nothing about what the card offers: a paused workout is picked back up exactly
            // as a running one is, and the word is there so the clock that has stopped moving
            // does not read as a screen that has stopped updating. It is a *state* and not an
            // action — the action is the button below, and "RESUME · 23:14" over a button
            // marked RESUME said the same word twice and left the state unsaid.
            val status = stringResource(
                if (resume.isPaused) R.string.home_resume_paused else R.string.home_resume_running,
            )
            Eyebrow(
                text = stringResource(
                    R.string.home_resume_eyebrow,
                    status,
                    resume.elapsed.formatAsClock(),
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                // The same fallback the workout's own header uses, so a session started
                // unnamed is called the same thing here as on the screen this opens.
                text = resume.name ?: formatSessionDate(resume.startedAt),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            resume.position?.let { position ->
                Text(
                    text = position.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Actions(
                onResume = onResume,
                onDiscard = onDiscard,
                modifier = Modifier.padding(top = OpenGymTheme.spacing.md),
            )
        }
    }
}

/**
 * The two things that can be done about the workout: side by side, or stacked once the type is
 * too large for that — see [ResumeCard].
 */
@Composable
private fun Actions(
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val resume: @Composable (Modifier) -> Unit = { buttonModifier ->
        Button(
            onClick = onResume,
            shape = MaterialTheme.shapes.small,
            modifier = buttonModifier,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                // The label beside it says what this does; a second announcement of the same
                // word is one TalkBack has to read past.
                contentDescription = null,
                modifier = Modifier.padding(end = OpenGymTheme.spacing.xs),
            )
            Eyebrow(
                text = stringResource(R.string.home_resume_action),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
    val discard: @Composable (Modifier) -> Unit = { buttonModifier ->
        OutlinedButton(
            onClick = onDiscard,
            shape = MaterialTheme.shapes.small,
            modifier = buttonModifier,
        ) {
            Text(
                text = stringResource(R.string.home_resume_discard),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (stacked) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            resume(Modifier.fillMaxWidth())
            discard(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            resume(Modifier.weight(1f))
            discard(Modifier)
        }
    }
}

/**
 * The font scale at which the two actions stop fitting across a phone and stack.
 *
 * 1.3 rather than the 2.0 the accessibility pass tests at: the pair is already tight at 1.0 —
 * "RESUME" is set in the tracked eyebrow role — and the first size it fails at is well before
 * the largest one a user can choose.
 */
private const val STACKED_FONT_SCALE = 1.3f

/**
 * `Bench press · set 3` — the movement they were on, and the set they came back to do.
 *
 * No total after it: nothing in the app knows how many sets an exercise was meant to get, and
 * [SessionPosition] says why inventing one here would be worse than saying less.
 */
@Composable
private fun SessionPosition.label(): String =
    stringResource(R.string.home_resume_position, exerciseName, setNumber)
