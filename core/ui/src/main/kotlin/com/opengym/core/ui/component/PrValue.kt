package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatAsElapsed
import com.opengym.core.ui.format.formatDistance
import com.opengym.core.ui.format.formatVolume
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.theme.OpenGymTheme
import kotlin.time.Duration.Companion.seconds

/**
 * A personal record as a figure on the end of a row: `PR` over `92 kg`, in the accent.
 *
 * The other half of [PrBadge]'s pair. A badge says *that* there is a record and what kind;
 * this says what the record **is**, which is the thing a lifter is scanning a library for —
 * the wireframes put it at the end of every exercise row, and it is the one spot of colour
 * on a page of grey rules.
 *
 * Renders nothing for a record whose own figure is missing — see [formattedValue] — so a
 * caller can pass whatever the store gave it without first asking whether it is drawable.
 */
@Composable
fun PrValue(record: PersonalRecord, modifier: Modifier = Modifier) {
    val value = record.formattedValue() ?: return
    val label = stringResource(R.string.pr_badge)
    val spoken = stringResource(R.string.pr_value_description, record.type.label(), value)

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Eyebrow(text = label)
        Text(
            text = value,
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/**
 * The record's figure, in the user's units — `92 kg`, `45 reps`, `5.2 km` — or null when the
 * record does not carry the one its own type is about.
 *
 * Null is not a defensive flourish: [PersonalRecord] holds five nullable figures and the
 * type says which of them is the value, so a row whose `pr_type` and populated column
 * disagree is a row nothing can honestly draw. Dropping it is better than showing a `0 kg`
 * record that the user never set.
 *
 * [PrType.BEST_PACE] renders as the time the record was set in rather than as a pace. A pace
 * is time *per distance* and needs a formatter and a unit of its own; that belongs with the
 * PR engine (#30), which is also what will first write one of these.
 */
@Composable
@ReadOnlyComposable
fun PersonalRecord.formattedValue(): String? = when (type) {
    PrType.MAX_WEIGHT -> weight?.let { formatWeight(it) }
    PrType.ESTIMATED_1RM -> estimated1Rm?.let { formatWeight(it) }
    PrType.MAX_REPS -> reps?.let { pluralStringResource(R.plurals.pr_value_reps, it, it) }
    PrType.MAX_VOLUME -> weight?.let { formatVolume(it) }
    PrType.MAX_DISTANCE -> distance?.let { formatDistance(it) }
    PrType.BEST_PACE -> duration?.let { it.seconds.formatAsElapsed() }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private fun previewRecord(type: PrType, weight: Long? = null, reps: Int? = null) = PersonalRecord(
    id = type.ordinal.toLong(),
    exerciseId = 1,
    workoutSetId = 1,
    type = type,
    weight = weight,
    reps = reps,
    estimated1Rm = weight,
    achievedAt = 0,
)

@Preview(name = "PR value", widthDp = 360)
@Composable
fun PrValuePreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PrValueCatalogue()
    }
}

@Preview(name = "PR value, dark", widthDp = 360)
@Composable
fun PrValueDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        PrValueCatalogue()
    }
}

@Composable
private fun PrValueCatalogue() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xxl),
    ) {
        PrValue(previewRecord(PrType.MAX_WEIGHT, weight = 92_000_000))
        PrValue(previewRecord(PrType.MAX_REPS, reps = 45))
        PrValue(previewRecord(PrType.ESTIMATED_1RM, weight = 104_500_000))
    }
}
