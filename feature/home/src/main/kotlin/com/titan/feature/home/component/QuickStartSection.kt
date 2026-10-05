package com.titan.feature.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.titan.core.domain.model.TemplateSummary
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TemplateRow
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.home.R
import com.titan.core.ui.R as CoreR

/**
 * The ways into a workout.
 *
 * ```text
 * QUICK START ──────────────────────────────
 * ┌─────────────────────────────────────────┐
 * │ START EMPTY SESSION                     │
 * └─────────────────────────────────────────┘
 *
 * YOUR TEMPLATES ───────────────────────────
 * ┌─────────────────────────────────────────┐
 * │ ┌──┐ Push Day                           │
 * │ └──┘ 8 ex · ~1h 15m  STRENGTH           │
 * │─────────────────────────────────────────│
 * │ ┌──┐ Pull Day                           │
 * │ └──┘ 7 ex · ~1h 10m  STRENGTH           │
 * │─────────────────────────────────────────│
 * │ See all templates                     › │
 * └─────────────────────────────────────────┘
 * ```
 *
 * ### The one block that is always drawn
 *
 * Everything else on Home hides when it has nothing to say. The empty-session action never
 * does: it needs no data, and a user with an empty database is precisely the one who needs it.
 * That is what makes the first-launch screen a call to action rather than an empty page with a
 * glyph on it.
 *
 * ### Why the accent is not here
 *
 * The empty-session action is a filled button, but it is not *the* accent when a workout is
 * already running — the resume card above it has it, and two accented controls on one screen
 * make both of them ordinary. Material's own filled button uses `primary`, which is the one
 * place this design tolerates a second use of it: the card above is bordered in the accent
 * rather than filled with it precisely so it still wins.
 *
 * ### The templates are a list, under a heading that says whose they are
 *
 * They used to be a strip of cards that scrolled sideways under a "Start from template"
 * button. Neither half said what the cards were — saved templates, or kinds of workout the app
 * was offering to build — and a sideways strip hides everything past the second card behind a
 * gesture nobody is told about. So they are now drawn the way every other list of templates in
 * the app draws them, as [TemplateRow]s with the mark, the figures and the category, under a
 * heading that says *Your templates*, in a card whose last row opens the picker with all of
 * them. A user who has used the picker or the templates tab recognises the rows on sight.
 *
 * It holds the handful the ViewModel cut it to, and tapping one goes straight to that
 * template's ready-to-start panel — the template step of #25's flow, skipped past. The way to
 * *all* of them is the card's last row.
 *
 * @param templates what the list offers. Empty draws no list and no heading for one, and puts
 *   the outlined "Start from template" action back under the empty-session one, which is what
 *   a user who has deleted every template, the defaults a fresh install starts with (#213)
 *   included, sees — the picker it opens is where a template gets made.
 * @param enabled false while a workout is already open. Every control here leads into the flow
 *   that sets a new session up, and that flow *refuses* while one is running —
 *   `ResumeSessionDialog` is where the user would find that out, a screen and a transition
 *   later. Home knows before the tap, and the banner that knows it is directly above, offering
 *   the two ways out. The line of prose below the heading is what says so, because a control
 *   that is dead and silent about it is the complaint disabled controls exist to earn.
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
    enabled: Boolean = true,
    isFirstLaunch: Boolean = false,
) {
    // Material fades a disabled button's container for us but not its label, because both
    // labels here are drawn in a colour of their own — see the note on the accent above — and
    // would otherwise sit at full strength on a greyed-out control.
    val disabledLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

    Column(
        modifier = modifier.fillMaxWidth(),
        // The section rhythm between the actions and the list, because they are two things to
        // read: what to do, and what to do it from.
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            SectionHeading(text = stringResource(R.string.home_quick_start_heading))

            // One line of prose above the actions, for the two states that need a word of
            // explanation: nothing has ever been logged, or a workout is open and they are
            // therefore out of service. A disabled control with nothing saying why is the reason
            // disabled controls have the reputation they have. The blocked line wins where both
            // are true — a user whose first workout is the one still running is being told the
            // more useful of the two things.
            val note = when {
                !enabled -> R.string.home_quick_start_blocked
                isFirstLaunch -> R.string.home_first_launch
                else -> null
            }
            if (note != null) {
                Text(
                    text = stringResource(note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onStartEmptySession,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Eyebrow(
                    text = stringResource(R.string.home_start_empty),
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary else disabledLabel,
                    // Three tracked words do not fit across a phone at twice the type, and an
                    // action clipped to "START EMPTY" is an action that means something else.
                    maxLines = 2,
                )
            }

            if (templates.isEmpty()) {
                OutlinedButton(
                    onClick = onStartFromTemplates,
                    enabled = enabled,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.home_start_from_template),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledLabel,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (templates.isNotEmpty()) {
            TemplateList(
                templates = templates,
                enabled = enabled,
                onStartFromTemplate = onStartFromTemplate,
                onSeeAll = onStartFromTemplates,
            )
        }
    }
}

/**
 * The user's templates as rows in one card, closed by the way to all of them.
 *
 * Every row — the templates and the last one alike — leads into the flow that is refused while
 * a workout is open, so [enabled] dims and disarms all of them together; a live row here would
 * be the one remaining way to walk into that refusal.
 */
@Composable
private fun TemplateList(
    templates: List<TemplateSummary>,
    enabled: Boolean,
    onStartFromTemplate: (templateId: Long) -> Unit,
    onSeeAll: () -> Unit,
) {
    val startLabel = stringResource(CoreR.string.template_start)

    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        SectionHeading(text = stringResource(R.string.home_templates_heading))

        TitanCard(modifier = Modifier.fillMaxWidth()) {
            templates.forEachIndexed { index, summary ->
                if (index > 0) HairlineDivider()
                TemplateRow(
                    summary = summary,
                    onClick = { onStartFromTemplate(summary.template.id) },
                    onClickLabel = startLabel,
                    // No chevron, as the picker's rows have none: on a 320dp screen it is the
                    // estimate that would give way to it, and the estimate is what a lifter
                    // choosing between templates is reading.
                    enabled = enabled,
                )
            }
            HairlineDivider()
            SeeAllRow(enabled = enabled, onClick = onSeeAll)
        }
    }
}

/**
 * The card's last row, in the accent as the quest card's way to its board is: a link, set
 * apart from the templates above it by colour rather than by being a second button.
 */
@Composable
private fun SeeAllRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = TitanTheme.spacing.card, vertical = TitanTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_all_templates),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Material's own, for the labels it does not fade — see [QuickStartSection]. */
private const val DISABLED_ALPHA = 0.38f
