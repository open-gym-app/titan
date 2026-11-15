package com.titan.feature.progression.body.component

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
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.BodyDiagramDefaults
import com.titan.core.ui.component.BodyFigure
import com.titan.core.ui.component.BodyRegion
import com.titan.core.ui.component.BodyView
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.MuscleEmphasis
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatLength
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.body.BodyReadings
import com.titan.feature.progression.body.BodySite
import com.titan.feature.progression.body.latestReadings

/**
 * The parts: a figure with what the user tracks picked out on it, and the latest tape reading
 * beside each.
 *
 * ### The diagram is a readout, not decoration
 *
 * The wireframes draw a plain body here. What is drawn instead is the same figure with the
 * measured regions marked — each [BodySite], lit only once there is a reading for it — so the
 * illustration says *what you track* rather than being a picture of a person beside a list. A
 * user who has only ever measured their waist sees one region lit and one row filled, which is
 * the list and the drawing agreeing.
 *
 * They are marked in [MuscleEmphasis.PRIMARY] — the accent, at full strength — rather than in
 * the container tone. The one-accent rule asks that the accent mean "this is the thing", and
 * on this card it is: the regions *are* the card's subject, the way the worked muscles are on
 * the hub's map. The container tone was the obvious alternative and is worse in the light
 * scheme, where `primaryContainer` sits a shade off the card and leaves the four parts as
 * hairline outlines — a mark so quiet it stops saying anything.
 *
 * The mapping is the honest one available in the drawing vocabulary: a waist is the abdominal
 * region, a thigh the quadriceps and a hip the abductors, because [BodyRegion] names muscles —
 * it was built for #28's muscles-worked diagram — and a tape does not go round a muscle. That is
 * a small lie in the drawing and a smaller one than adding regions no exercise will ever
 * reference. The hip is the abductors rather than the glutes because the glutes have no shape on
 * the front figure, and the abductors are the outer hip the tape actually passes over.
 *
 * ### The core four, and the rest once measured
 *
 * Chest, waist, arm and thigh always have a row — they are what the screen first offered to
 * track, and a card that grew a row with each one would reflow on the first measurement of each.
 * The other five ([BodySite.isCore] false) are sites most users never tape, mostly reaching the
 * app from another tracker's import, so each gets its row once it has a reading. Nine rows of
 * dashes for somebody who measures their waist would bury the one figure they track.
 *
 * ### Front only
 *
 * Every site is measured from the front, so the back figure would be an unlit body
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
    TitanCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(TitanTheme.spacing.card),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BodyFigure(
                view = BodyView.FRONT,
                primary = readings.measuredRegions(),
                modifier = Modifier.height(BodyDiagramDefaults.HEIGHT),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
            ) {
                BodySite.entries
                    .filter { it.isCore || readings[it] != null }
                    .forEachIndexed { index, site ->
                        if (index > 0) HairlineDivider()
                        MeasurementRow(label = stringResource(site.label), millimetres = readings[site])
                    }
            }
        }
    }
}

/**
 * One part and what it last measured: the name on the left, the figure on the right.
 *
 * A core part's row is drawn whether or not it has a reading — see [MeasurementsCard]. An
 * unmeasured part carries the dash the rest of the app uses for "no reading
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
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
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
            style = TitanTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** The regions to light: the ones a reading exists for. See [MeasurementsCard]. */
private fun BodyReadings.measuredRegions(): Set<BodyRegion> = circumferences.keys.mapTo(HashSet()) { it.region }

/** Where the figure marks this site. See [MeasurementsCard] for why a muscle stands in for it. */
private val BodySite.region: BodyRegion
    get() = when (this) {
        BodySite.NECK -> BodyRegion.NECK
        BodySite.SHOULDERS -> BodyRegion.SHOULDERS
        BodySite.CHEST -> BodyRegion.CHEST
        BodySite.WAIST -> BodyRegion.ABDOMINALS
        BodySite.HIPS -> BodyRegion.ABDUCTORS
        BodySite.ARM -> BodyRegion.BICEPS
        BodySite.FOREARM -> BodyRegion.FOREARMS
        BodySite.THIGH -> BodyRegion.QUADRICEPS
        BodySite.CALF -> BodyRegion.CALVES
    }

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Measurements card", showBackground = true, widthDp = 360)
@Composable
fun MeasurementsCardPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        MeasurementsCard(readings = previewReadings())
    }
}

@Preview(name = "Measurements card - dark", showBackground = true, widthDp = 360)
@Composable
fun MeasurementsCardDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        MeasurementsCard(readings = previewReadings())
    }
}

@Preview(name = "Measurements card - one part", showBackground = true, widthDp = 360)
@Composable
fun MeasurementsCardPartialPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        MeasurementsCard(readings = previewReadings(previewEntry().copy(chest = null, arm = null, thigh = null)))
    }
}

@Preview(name = "Measurements card - every site", showBackground = true, widthDp = 360)
@Composable
fun MeasurementsCardEverySitePreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        MeasurementsCard(
            readings = previewReadings(
                previewEntry().copy(neck = 390, shoulders = 1_210, hips = 980, forearm = 300, calf = 390),
            ),
        )
    }
}

/** The readings the card is drawn from, taken off one entry the way the screen takes them off the history. */
private fun previewReadings(entry: BodyMeasurement = previewEntry()): BodyReadings = listOf(entry).latestReadings()

/** The core four, measured. */
private fun previewEntry() =
    BodyMeasurement(chest = 1_020, waist = 820, arm = 360, thigh = 580, createdAt = 0, updatedAt = 0)
