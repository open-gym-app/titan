package com.titan.core.ui.component

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
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.format.distanceSuffix
import com.titan.core.ui.format.formatAsElapsed
import com.titan.core.ui.format.formatDistanceValue
import com.titan.core.ui.format.formatEstimateValue
import com.titan.core.ui.format.formatNumber
import com.titan.core.ui.format.formatVolumeValue
import com.titan.core.ui.format.formatWeightValue
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.TitanTheme
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
fun PrValue(
    record: PersonalRecord,
    modifier: Modifier = Modifier,
) {
    val value = record.formattedValue() ?: return
    val label = stringResource(R.string.pr_badge)
    val spoken = stringResource(R.string.pr_value_description, record.type.label(), value)

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Eyebrow(text = label)
        Text(
            text = value,
            style = TitanTheme.typography.numeric,
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
 * The join of [formattedFigure] and [recordUnit], rather than a third rendering: a record
 * quoted in a sentence and a record set as a figure over its unit have to agree to the digit.
 *
 * Joined through a resource rather than with a space, because the two halves are separately
 * translated and the space between them is a third piece of the same sentence — see
 * `figure_with_unit`. The weight cases could go through `formatWeight` instead, but the rep
 * and pace ones could not, and one join for all six is what keeps them looking alike.
 */
@Composable
@ReadOnlyComposable
fun PersonalRecord.formattedValue(): String? {
    val figure = formattedFigure() ?: return null
    val unit = recordUnit() ?: return figure
    return stringResource(R.string.figure_with_unit, figure, unit)
}

/**
 * The record's figure **without** its unit — `92`, `45`, `5.2`.
 *
 * For the one layout that sets the two in different type roles: a [StatTile], whose figure is
 * `numericLarge` and whose unit is a quiet line under it. Joining them there would put
 * `2,240 kg` in a 160dp tile at the large numeric size, which ellipsises to `2,240 …` — the
 * unit clipped off the figure it belongs to.
 *
 * [PrType.BEST_PACE] renders as the time the record was set in rather than as a pace. A pace
 * is time *per distance* and needs a formatter and a unit of its own; that belongs with the
 * PR engine (#30), which is also what first writes one of these.
 *
 * [PrType.ESTIMATED_1RM] is the one figure here that is *projected* rather than lifted, and it
 * is rounded to the whole unit for that reason — see [formatEstimateValue]. Every other weight
 * keeps the two decimals that let a load read back exactly as it was entered.
 */
@Composable
@ReadOnlyComposable
fun PersonalRecord.formattedFigure(): String? =
    when (type) {
        PrType.MAX_WEIGHT -> weight?.let { formatWeightValue(it) }
        PrType.ESTIMATED_1RM -> estimated1Rm?.let { formatEstimateValue(it) }
        PrType.MAX_REPS -> reps?.let { formatNumber(it.toDouble(), REP_FRACTION_DIGITS) }
        PrType.MAX_VOLUME -> weight?.let { formatVolumeValue(it) }
        PrType.MAX_DISTANCE -> distance?.let { formatDistanceValue(it) }
        PrType.BEST_PACE -> duration?.let { it.seconds.formatAsElapsed() }
    }

/**
 * What [formattedFigure] is measured in — `kg`, `reps`, `km` — or **null** for a figure that
 * carries its own unit inside it.
 *
 * The distance unit is chosen per *value* rather than per system, so a 400 m interval and a
 * 5.2 km run read in metres and kilometres on the same screen — see [distanceSuffix]. A pace
 * record is drawn as a duration, which is already `mm:ss` and has nothing to append.
 */
@Composable
@ReadOnlyComposable
fun PersonalRecord.recordUnit(): String? =
    when (type) {
        PrType.MAX_WEIGHT, PrType.ESTIMATED_1RM, PrType.MAX_VOLUME ->
            LocalUnitSystem.current.weightSuffix

        PrType.MAX_REPS -> reps?.let { pluralStringResource(R.plurals.pr_unit_reps, it) }

        PrType.MAX_DISTANCE -> distance?.let { distanceSuffix(it, LocalUnitSystem.current) }

        PrType.BEST_PACE -> null
    }

/**
 * A rep count is a whole number.
 *
 * Through [formatNumber] rather than `toString`, which is ASCII whatever the locale — a row
 * whose rep record was in Latin digits beside a set count in Bengali ones is two numbering
 * systems on one line.
 */
private const val REP_FRACTION_DIGITS = 0

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private fun previewRecord(
    type: PrType,
    weight: Long? = null,
    reps: Int? = null,
) = PersonalRecord(
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
    TitanTheme(theme = ThemePreference.LIGHT) {
        PrValueCatalogue()
    }
}

@Preview(name = "PR value, dark", widthDp = 360)
@Composable
fun PrValueDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        PrValueCatalogue()
    }
}

@Composable
private fun PrValueCatalogue() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xxl),
    ) {
        PrValue(previewRecord(PrType.MAX_WEIGHT, weight = 92_000_000))
        PrValue(previewRecord(PrType.MAX_REPS, reps = 45))
        PrValue(previewRecord(PrType.ESTIMATED_1RM, weight = 104_500_000))
    }
}
