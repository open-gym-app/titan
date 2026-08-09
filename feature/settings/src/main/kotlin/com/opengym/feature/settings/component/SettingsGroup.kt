package com.opengym.feature.settings.component

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
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R

/**
 * A titled block of rows: the eyebrow, and the card under it.
 *
 * Dividers are the caller's, drawn with `HairlineDivider()` between rows. A group that
 * inserted them itself would have to know how many children it was given, which Compose does
 * not offer without turning the content slot into a list — and that list would then have to
 * be able to express every row type below.
 */
@Composable
fun SettingsGroup(
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
fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    external: Boolean = false,
    destructive: Boolean = false,
) {
    SettingsRowLayout(
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
        trailingIconDescription = if (external) stringResource(R.string.settings_opens_externally) else null,
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
fun SettingsInfoRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
) {
    SettingsRowLayout(
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
 * rearranging the screen under people who have learned where things are.
 *
 * Being *announced* as unavailable is the part that needs the code rather than the colour.
 * `Modifier.semantics { disabled() }` is what puts "disabled" in the semantics tree; dimming
 * alone tells a sighted user and nobody else, and a row that is merely un-clickable reads to
 * TalkBack as ordinary text with no hint that it is going to do something one day.
 */
@Composable
fun SettingsSoonRow(
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    SettingsRowLayout(
        label = label,
        value = null,
        supporting = supporting,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingIcon = null,
        trailingIconDescription = null,
        badge = stringResource(R.string.settings_soon),
        // `mergeDescendants` so the row is one node carrying both the label and the disabled
        // state. Without it the state sits on an empty container and the text beside it reads
        // as ordinary, available text — to TalkBack and to a test alike.
        modifier = modifier.semantics(mergeDescendants = true) { disabled() },
    )
}

/**
 * The shared shape of every row above: label and optional supporting line on the left, an
 * optional value and an optional affordance on the right.
 *
 * One layout rather than three, so the rows line up down the card whatever they are made of —
 * the thing that goes wrong when three composables each pick their own padding is a column of
 * values that is a pixel out per row.
 */
@Composable
private fun SettingsRowLayout(
    label: String,
    value: String?,
    supporting: String?,
    labelColor: Color,
    trailingIcon: ImageVector?,
    trailingIconDescription: String?,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OpenGymTheme.spacing.xl, vertical = OpenGymTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
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
