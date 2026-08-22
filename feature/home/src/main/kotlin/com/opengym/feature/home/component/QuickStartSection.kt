package com.opengym.feature.home.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.TemplateCard
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.home.R

/**
 * The ways into a workout.
 *
 * ```text
 * QUICK START ──────────────────────────────
 * ┌─────────────────────────────────────────┐
 * │ START EMPTY SESSION                     │
 * └─────────────────────────────────────────┘
 * ┌─────────────────────────────────────────┐
 * │ START FROM TEMPLATE                     │
 * └─────────────────────────────────────────┘
 * ┌───────────┐ ┌───────────┐ ┌───────────┐
 * │ Push Day  │ │ Pull Day  │ │ Legs      │→
 * └───────────┘ └───────────┘ └───────────┘
 * ```
 *
 * ### The one block that is always drawn
 *
 * Everything else on Home hides when it has nothing to say. These two actions never do: they
 * need no data, and a user with an empty database is precisely the one who needs them. That is
 * what makes the first-launch screen a call to action rather than an empty page with a glyph
 * on it.
 *
 * ### Why the accent is not here
 *
 * The empty-session action is a filled button and the template action is outlined, but neither
 * is *the* accent when a workout is already running — the resume card above them has it, and
 * two accented controls on one screen make both of them ordinary. Material's own filled button
 * uses `primary`, which is the one place this design tolerates a second use of it: the pair
 * reads as one control with two halves, and the card above is bordered in the accent rather
 * than filled with it precisely so it still wins.
 *
 * ### The strip is a shortcut, not a list
 *
 * It holds the handful the ViewModel cut it to, and tapping one goes straight to that
 * template's ready-to-start panel — the template step of #25's flow, skipped past. The way to
 * *all* the templates is the action above it, which opens the picker built for choosing.
 *
 * @param templates what the strip offers. Empty draws no strip and no heading for one, which
 *   is what a user who has never saved a template sees.
 * @param isFirstLaunch nothing has ever been logged, so this section is the whole screen. It
 *   gets one line of prose above it — not a card and not a glyph, both of which would be the
 *   empty shell this screen is built to avoid, but enough that a first launch reads as a place
 *   to begin rather than as a screen that failed to load.
 */
@Composable
fun QuickStartSection(
    templates: List<TemplateSummary>,
    onStartEmptySession: () -> Unit,
    onStartFromTemplates: () -> Unit,
    onStartFromTemplate: (templateId: Long) -> Unit,
    modifier: Modifier = Modifier,
    isFirstLaunch: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        SectionHeading(text = stringResource(R.string.home_quick_start_heading))

        if (isFirstLaunch) {
            Text(
                text = stringResource(R.string.home_first_launch),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onStartEmptySession,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Eyebrow(
                text = stringResource(R.string.home_start_empty),
                color = MaterialTheme.colorScheme.onPrimary,
                // Three tracked words do not fit across a phone at twice the type, and an
                // action clipped to "START EMPTY" is an action that means something else.
                maxLines = 2,
            )
        }

        OutlinedButton(
            onClick = onStartFromTemplates,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.home_start_from_template),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }

        if (templates.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                // A hair of vertical padding so the cards' borders are not clipped by the
                // row's own bounds; the horizontal margin is the screen's, applied by the
                // column above.
                contentPadding = PaddingValues(vertical = OpenGymTheme.spacing.xs),
            ) {
                items(items = templates, key = { it.template.id }) { summary ->
                    TemplateCard(
                        summary = summary,
                        onClick = { onStartFromTemplate(summary.template.id) },
                    )
                }
            }
        }
    }
}
