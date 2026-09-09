package com.opengym.feature.progression.exercise.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.ExerciseProgression
import com.opengym.core.domain.model.PrType
import com.opengym.core.ui.component.StatTile
import com.opengym.core.ui.component.formattedFigure
import com.opengym.core.ui.component.recordUnit
import com.opengym.core.ui.format.formatCount
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R

/**
 * The four figures a lift is judged by: `1RM (EST)`, `MAX SET`, `BEST VOL`, `TOTAL SETS`.
 *
 * ### All-time, whatever the selector says
 *
 * Every one of these is read over the whole history, unlike the charts below them. A card
 * headed `1RM (EST)` that fell to a smaller number because the user narrowed the timeframe
 * would be reporting a record they have not lost — see [ExerciseProgression], which is where
 * the two scopes are separated.
 *
 * ### Three of them are the PR engine's, and they render its own figures
 *
 * The first three come straight from `PrDetectionUseCase`'s rows, through the same
 * `formattedFigure` a record renders as anywhere else in the app, so a lift's best here and its
 * `PrValue` on a library row cannot disagree. The figure and its unit are taken *apart* here
 * where a row joins them: a tile sets them in two type roles, and `2,240 kg` at the large
 * numeric size ellipsises to `2,240 …` in a 160dp tile — the unit clipped off the figure it
 * belongs to.
 *
 * A kind the exercise has no record of draws a dash rather than a zero: `0 kg` is a lift
 * somebody made, and nothing here has been.
 *
 * ### The badge is the accent, and it is the only one on the grid
 *
 * `NEW` marks a record broken in the most recent attempt on screen — see
 * [ExerciseProgression.newRecords]. `accented` is deliberately left alone: the wireframes put
 * the tag in the corner rather than painting the figure, and a grid where the record *and* its
 * figure both shouted would spend the accent twice on one fact.
 */
@Composable
fun ExerciseRecordCards(
    progression: ExerciseProgression,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
            RecordTile(
                progression = progression,
                type = PrType.ESTIMATED_1RM,
                label = stringResource(R.string.exercise_card_estimate),
                modifier = Modifier.weight(1f),
            )
            RecordTile(
                progression = progression,
                type = PrType.MAX_WEIGHT,
                label = stringResource(R.string.exercise_card_top_set),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
            RecordTile(
                progression = progression,
                type = PrType.MAX_VOLUME,
                label = stringResource(R.string.exercise_card_volume),
                // The one card whose unit is not the record's own: a session's volume is in
                // the weight unit *per session*, and the caption is where that is said.
                unit = stringResource(
                    R.string.exercise_card_volume_unit,
                    LocalUnitSystem.current.weightSuffix,
                ),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.exercise_card_sets),
                value = formatCount(progression.totalSets),
                unit = stringResource(R.string.exercise_card_sets_unit),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One record as a tile, or a dash where there is no record of that kind.
 *
 * The dash is the tile's *value*, so the grid keeps its shape: a card that collapsed for a
 * lift with no volume record would leave a hole in a two-by-two. Its caption goes with it —
 * a unit under an absent figure is a unit for nothing.
 *
 * @param unit what the figure is measured in, or **null** to take the record's own.
 */
@Composable
private fun RecordTile(
    progression: ExerciseProgression,
    type: PrType,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    val record = progression.records[type]
    val figure = if (record == null) null else record.formattedFigure()
    val suffix = if (record == null) null else unit ?: record.recordUnit()

    StatTile(
        label = label,
        value = figure ?: stringResource(R.string.exercise_card_no_record),
        unit = suffix.takeIf { figure != null },
        badge = stringResource(R.string.exercise_card_badge_new).takeIf { type in progression.newRecords },
        modifier = modifier,
    )
}
