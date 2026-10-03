package com.titan.feature.progression.exercise.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.ExerciseProgression
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PrType
import com.titan.core.ui.component.StatTile
import com.titan.core.ui.component.formattedFigure
import com.titan.core.ui.component.label
import com.titan.core.ui.component.recordUnit
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R

/**
 * The four figures a lift is judged by: `1RM (EST)`, `MAX SET`, `BEST VOL`, `TOTAL SETS` — or the
 * ones a run or a hold is, for an exercise logged that way (#225).
 *
 * ### The exercise's type chooses the cards
 *
 * The same kinds the PR engine lets it earn — see `PrType.eligibleFor` — so a plank is never
 * shown an empty `1RM (EST)` it could not have, and a run's distance, pace and time are not
 * hidden behind a lift's cards.
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
    exerciseType: ExerciseType,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        when (exerciseType) {
            ExerciseType.STRENGTH -> StrengthRecords(progression)
            ExerciseType.ENDURANCE -> EnduranceRecords(progression)
            ExerciseType.ISOMETRIC -> HoldRecords(progression)
        }
    }
}

/** A lift: its projected max, its heaviest set, its biggest session and how many sets it has had. */
@Composable
private fun StrengthRecords(progression: ExerciseProgression) {
    Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
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
        TotalSetsTile(progression = progression, modifier = Modifier.weight(1f))
    }
}

/**
 * A run, a ride or a swim (#225): how far, how fast, how long, and how many sets.
 *
 * The pace card is named by its record where there is one — `Best pace` for a run, `Top speed`
 * for a ride, as the figure under it is drawn — and by the kind while there is not.
 */
@Composable
private fun EnduranceRecords(progression: ExerciseProgression) {
    Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        RecordTile(progression = progression, type = PrType.MAX_DISTANCE, modifier = Modifier.weight(1f))
        RecordTile(progression = progression, type = PrType.BEST_PACE, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        RecordTile(progression = progression, type = PrType.MAX_DURATION, modifier = Modifier.weight(1f))
        TotalSetsTile(progression = progression, modifier = Modifier.weight(1f))
    }
}

/**
 * A hold (#225): the longest, the most load held, and how many sets. Three figures, so the set
 * count takes the second row whole rather than leaving a hole beside it.
 */
@Composable
private fun HoldRecords(progression: ExerciseProgression) {
    Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        RecordTile(progression = progression, type = PrType.MAX_DURATION, modifier = Modifier.weight(1f))
        RecordTile(progression = progression, type = PrType.MAX_WEIGHTED_HOLD, modifier = Modifier.weight(1f))
    }
    TotalSetsTile(progression = progression, modifier = Modifier.fillMaxWidth())
}

/** Every completed working set of the exercise, all-time — the one card that is not a record. */
@Composable
private fun TotalSetsTile(
    progression: ExerciseProgression,
    modifier: Modifier = Modifier,
) {
    StatTile(
        label = stringResource(R.string.exercise_card_sets),
        value = formatCount(progression.totalSets),
        unit = stringResource(R.string.exercise_card_sets_unit),
        modifier = modifier,
    )
}

/**
 * One record as a tile, or a dash where there is no record of that kind.
 *
 * The dash is the tile's *value*, so the grid keeps its shape: a card that collapsed for a
 * lift with no volume record would leave a hole in a two-by-two. Its caption goes with it —
 * a unit under an absent figure is a unit for nothing.
 *
 * @param label what the tile is headed, or **null** to take the record's own name — see
 *   `PersonalRecord.label` — or the kind's, while there is no record of it.
 * @param unit what the figure is measured in, or **null** to take the record's own.
 */
@Composable
private fun RecordTile(
    progression: ExerciseProgression,
    type: PrType,
    modifier: Modifier = Modifier,
    label: String? = null,
    unit: String? = null,
) {
    val record = progression.records[type]
    val figure = if (record == null) null else record.formattedFigure()
    val suffix = if (record == null) null else unit ?: record.recordUnit()

    StatTile(
        label = label ?: record?.label() ?: type.label(),
        value = figure ?: stringResource(R.string.exercise_card_no_record),
        unit = suffix.takeIf { figure != null },
        badge = stringResource(R.string.exercise_card_badge_new).takeIf { type in progression.newRecords },
        modifier = modifier,
    )
}
