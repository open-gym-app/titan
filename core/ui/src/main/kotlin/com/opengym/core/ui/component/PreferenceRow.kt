package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/*
 * The settings row, and the three shapes it comes in.
 *
 * ### Why it is here rather than in `:feature:settings`, where it was written
 *
 * Because it is not only a settings row. #M5-15's body screen carries the Health Connect row,
 * disabled until #54 lands, and its acceptance criterion is that it *matches the settings
 * screen's pattern* — which a feature module cannot do by importing another feature module
 * (`checkArchitecture` fails the build) and should not do by copying, because two copies of
 * "present, in its final position, and visibly unavailable" is two chances to disagree about
 * what unavailable looks like.
 *
 * So the row moved to the design system on its second caller, which is the rule #86 states for
 * everything else in this package: reach for a component before a style, and a screen that
 * draws its own bordered `Surface` has restated four arguments that will drift.
 *
 * The `Preference` prefix is the one `:core:ui` already uses for this vocabulary — see
 * `PreferenceLabels` and `PreferenceIcons`, which name the same settings the rows carry.
 */

/**
 * A titled block of rows: the eyebrow, and the card under it.
 *
 * Dividers are the caller's, drawn with [HairlineDivider] between rows. A group that inserted
 * them itself would have to know how many children it was given, which Compose does not offer
 * without turning the content slot into a list — and that list would then have to be able to
 * express every row type below.
 */
@Composable
fun PreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Eyebrow(text = title, modifier = Modifier.padding(start = OpenGymTheme.spacing.xs))
        OpenGymCard(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * A row that opens something: a picker, or a link out of the app.
 *
 * @param value the current setting, drawn in the mono numeric role on the right. Everything
 *   the user reads as a *value* is mono here — `Auto`, `2:00`, `20 kg` — which is what makes
 *   the right-hand column scan as a column rather than as more prose.
 * @param external draws the outbound-link glyph instead of the chevron, and says so to a
 *   screen reader. Leaving the app is worth announcing before the tap, not after.
 * @param destructive paints the label in the error role. Reserved for the one row that
 *   destroys data — spending it on anything reversible makes it stop meaning anything.
 */
@Composable
fun PreferenceRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    external: Boolean = false,
    destructive: Boolean = false,
) {
    PreferenceRowLayout(
        label = label,
        value = value,
        supporting = supporting,
        labelColor = if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        trailingIcon = if (external) {
            Icons.AutoMirrored.Filled.OpenInNew
        } else {
            Icons.AutoMirrored.Filled.KeyboardArrowRight
        },
        trailingIconDescription = if (external) stringResource(R.string.preference_opens_externally) else null,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * A row that states something and cannot be tapped: the version, the font scale.
 *
 * Not a disabled row. Nothing is missing and nothing is coming — the value simply is what it
 * is — so it carries no chevron, no badge and no dimming, and a screen reader announces it as
 * the text it is rather than as a control that is unavailable.
 */
@Composable
fun PreferenceInfoRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
) {
    PreferenceRowLayout(
        label = label,
        value = value,
        supporting = supporting,
        labelColor = MaterialTheme.colorScheme.onSurface,
        trailingIcon = null,
        trailingIconDescription = null,
        modifier = modifier,
    )
}

/**
 * A row for a feature that has not shipped yet.
 *
 * Present, in its final position, and visibly unavailable — the decision recorded in #M4-23:
 * the information architecture is fixed now so that later milestones fill rows in rather than
 * rearranging the screen under people who have learned where things are. #M5-15's Health
 * Connect row is the same promise on a second screen, held to the same shape.
 *
 * Being *announced* as unavailable is the part that needs the code rather than the colour.
 * `Modifier.semantics { disabled() }` is what puts "disabled" in the semantics tree; dimming
 * alone tells a sighted user and nobody else, and a row that is merely un-clickable reads to
 * TalkBack as ordinary text with no hint that it is going to do something one day.
 *
 * @param leading a mark drawn before the label — the wireframes' Health Connect heart. Null on
 *   a settings row, where a column of icons down a list of a dozen settings would be a column
 *   of decoration.
 */
@Composable
fun PreferenceSoonRow(
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: ImageVector? = null,
) {
    PreferenceRowLayout(
        label = label,
        value = null,
        supporting = supporting,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingIcon = null,
        trailingIconDescription = null,
        badge = stringResource(R.string.preference_soon),
        leading = leading,
        // `mergeDescendants` so the row is one node carrying both the label and the disabled
        // state. Without it the state sits on an empty container and the text beside it reads
        // as ordinary, available text — to TalkBack and to a test alike.
        modifier = modifier.semantics(mergeDescendants = true) { disabled() },
    )
}

/**
 * The shared shape of every row above: an optional mark, the label and its supporting line on
 * the left, an optional value and an optional affordance on the right.
 *
 * One layout rather than three, so the rows line up down the card whatever they are made of —
 * the thing that goes wrong when three composables each pick their own padding is a column of
 * values that is a pixel out per row.
 */
@Composable
private fun PreferenceRowLayout(
    label: String,
    value: String?,
    supporting: String?,
    labelColor: Color,
    trailingIcon: ImageVector?,
    trailingIconDescription: String?,
    modifier: Modifier = Modifier,
    badge: String? = null,
    leading: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OpenGymTheme.spacing.xl, vertical = OpenGymTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        if (leading != null) {
            IconTile(
                icon = leading,
                // Neutral, like the explore rows on the stats hub: a row that is unavailable
                // or merely navigational is not where the accent is spent.
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceContainer,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (value != null) {
            Text(
                text = value,
                style = OpenGymTheme.typography.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (badge != null) {
            OpenGymBadge(text = badge)
        }

        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = trailingIconDescription,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(CHEVRON_SIZE),
            )
        }
    }
}

/**
 * The chevron, a size down from Material's 24dp default.
 *
 * At 24dp it is the heaviest thing in a row whose subject is a word and a value; at 18dp it
 * reads as punctuation, which is what it is.
 */
private val CHEVRON_SIZE = 18.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Preference rows", widthDp = 360)
@Composable
fun PreferenceRowPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PreferenceRowCatalogue()
    }
}

@Preview(name = "Preference rows, dark", widthDp = 360)
@Composable
fun PreferenceRowDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        PreferenceRowCatalogue()
    }
}

@Composable
private fun PreferenceRowCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        PreferenceGroup(title = "Appearance") {
            PreferenceRow(label = "Theme", value = "Auto", onClick = {})
            HairlineDivider()
            PreferenceRow(label = "Units", value = "Metric", onClick = {})
            HairlineDivider()
            PreferenceInfoRow(label = "Version", value = "1.0.0")
        }
        PreferenceGroup(title = "Data") {
            PreferenceSoonRow(label = "Backup", supporting = "Coming in a later release")
            HairlineDivider()
            PreferenceRow(label = "Source code", supporting = "github.com", external = true, onClick = {})
            HairlineDivider()
            PreferenceRow(label = "Delete all data", destructive = true, onClick = {})
        }
    }
}
