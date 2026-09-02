package com.opengym.feature.progression.body.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.BodyDiagramDefaults
import com.opengym.core.ui.component.BodyFigure
import com.opengym.core.ui.component.BodyRegion
import com.opengym.core.ui.component.BodyView
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.MuscleEmphasis
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.format.formatLength
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.body.BodyReadings

/**
 * The parts: a figure with what the user tracks picked out on it, and the latest tape reading
 * beside each.
 *
 * ### The diagram is a readout, not decoration
 *
 * The wireframes draw a plain body here. What is drawn instead is the same figure with the
 * measured regions marked — chest, waist, arm and thigh, each lit only once there is a reading
 * for it — so the illustration says *what you track* rather than being a picture of a person
 * beside a list. A user who has only ever measured their waist sees one region lit and one row
 * filled, which is the list and the drawing agreeing.
 *
 * They are marked in [MuscleEmphasis.PRIMARY] — the accent, at full strength — rather than in
 * the container tone. The one-accent rule asks that the accent mean "this is the thing", and
 * on this card it is: the regions *are* the card's subject, the way the worked muscles are on
 * the hub's map. The container tone was the obvious alternative and is worse in the light
 * scheme, where `primaryContainer` sits a shade off the card and leaves the four parts as
 * hairline outlines — a mark so quiet it stops saying anything.
 *
 * The mapping is the honest one available in the drawing vocabulary: a waist is the abdominal
 * region and a thigh is the quadriceps, because [BodyRegion] names muscles — it was built for
 * #28's muscles-worked diagram — and a tape does not go round a muscle. That is a small lie in
 * the drawing and a smaller one than adding four regions no exercise will ever reference.
 *
 * ### Front only
 *
 * Every one of the four is measured from the front, so the back figure would be an unlit body
 * taking half the card's width. `BodyDiagram` draws both by default for a muscle set, which
 * usually spans them; a tape measure does not.
 *
 * ### The figure is silent
 *
 * It carries no content description, so a screen reader walks straight to the rows — which are
 * the same information in words, each announced as one phrase rather than as a label and a
 * figure two swipes apart.
 */
@Composable
fun MeasurementsCard(
    readings: BodyReadings,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BodyFigure(
                view = BodyView.FRONT,
                primary = readings.measuredRegions(),
                modifier = Modifier.height(BodyDiagramDefaults.HEIGHT),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            ) {
                MeasurementRow(label = stringResource(R.string.body_part_chest), millimetres = readings.chest)
                HairlineDivider()
                MeasurementRow(label = stringResource(R.string.body_part_waist), millimetres = readings.waist)
                HairlineDivider()
                MeasurementRow(label = stringResource(R.string.body_part_arm), millimetres = readings.arm)
                HairlineDivider()
                MeasurementRow(label = stringResource(R.string.body_part_thigh), millimetres = readings.thigh)
            }
        }
    }
}

/**
 * One part and what it last measured: the name on the left, the figure on the right.
 *
 * A row is drawn for every part whether or not it has a reading, because the four are the set
 * the screen offers to track and a card that grew a row per measurement would reflow every time
 * one was added. An unmeasured part carries the dash the rest of the app uses for "no reading
 * yet" rather than a zero, which is a measurement somebody took.
 */
@Composable
private fun MeasurementRow(
    label: String,
    millimetres: Int?,
) {
    val value = millimetres?.let { formatLength(it) } ?: stringResource(R.string.body_no_reading)
    val spoken = stringResource(R.string.body_measurement_description, label, value)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One node, so TalkBack says "Chest, 102 cm" rather than stopping twice on two
            // fragments — the rule `StatTile` states for a label over a figure.
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = value,
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** The regions to light: the ones a reading exists for. See [MeasurementsCard]. */
private fun BodyReadings.measuredRegions(): Set<BodyRegion> =
    buildSet {
        if (chest != null) add(BodyRegion.CHEST)
        if (waist != null) add(BodyRegion.ABDOMINALS)
        if (arm != null) add(BodyRegion.BICEPS)
        if (thigh != null) add(BodyRegion.QUADRICEPS)
    }

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Measurements card", showBackground = true, widthDp = 360)
@Composable
fun MeasurementsCardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MeasurementsCard(readings = BodyReadings(chest = 1_020, waist = 820, arm = 360, thigh = 580))
    }
}

@Preview(name = "Measurements card - dark", showBackground = true, widthDp = 360)
@Composable
fun MeasurementsCardDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        MeasurementsCard(readings = BodyReadings(chest = 1_020, waist = 820, arm = 360, thigh = 580))
    }
}

@Preview(name = "Measurements card - one part", showBackground = true, widthDp = 360)
@Composable
fun MeasurementsCardPartialPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MeasurementsCard(readings = BodyReadings(waist = 820))
    }
}
