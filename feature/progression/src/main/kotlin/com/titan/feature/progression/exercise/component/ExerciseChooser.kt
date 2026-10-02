package com.titan.feature.progression.exercise.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.titan.core.domain.model.TrainedExercise
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R

/**
 * The lifts the user has actually trained, most recent first — what this screen opens on when
 * nobody named one, and what the top bar's switcher puts back up.
 *
 * ### From the history, not from the catalogue
 *
 * 873 seeded exercises would be a search problem with a filter sheet behind it, which is what
 * the Library tab already is. This is the handful somebody has logged, ordered so that the lift
 * they trained this week is the first row — the answer, most of the time, without reading.
 *
 * ### A state of the screen, not a destination
 *
 * Picking a row does not navigate; it changes what this screen is reading. A push per lift
 * would fill the back stack with screens the user was flicking through, and backing out of the
 * chooser would then land on whichever one they looked at first. See
 * `ExerciseProgressionEvent.ExerciseSelected`.
 *
 * ### Its own scroll, and lazy
 *
 * The list has no upper bound — `getTrainedExercises` returns every lift in the history, with
 * no `LIMIT`, and a lifter of a few years has a hundred of them — so it composes the rows on
 * screen rather than all of them. That is why it is the scrolling container itself instead of
 * a block inside the drill-down's column: a lazy list cannot measure inside a parent that
 * offers it infinite height. Capping the list was the other way out and it is the wrong one,
 * since the row a cap would drop is a lift the user trained and cannot otherwise reach.
 *
 * @param choices the lifts, in the order the store returned them.
 * @param onSelect a row was tapped.
 * @param contentPadding the screen margin, spent inside the scroll rather than on it, so the
 *   rows pass under the top and bottom edges instead of stopping short of them.
 */
@Composable
fun ExerciseChooser(
    choices: List<TrainedExercise>,
    onSelect: (exerciseId: Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        item(key = HEADING_KEY) {
            SectionHeading(
                text = stringResource(R.string.exercise_chooser_heading),
                modifier = Modifier.padding(bottom = TitanTheme.spacing.xs),
            )
        }
        items(items = choices, key = { it.exerciseId }) { choice ->
            ChooserRow(choice = choice, onClick = { onSelect(choice.exerciseId) })
        }
    }
}

/** The heading's key, so it is not confused with a row's when the list changes under it. */
private const val HEADING_KEY = "heading"

/**
 * One lift: its name, how much of it there is, and when it was last done.
 *
 * `mergeDescendants` rather than a hand-written description, for the reason the hub's explore
 * rows give: the name and the caption are already the sentence a reader wants, in the order
 * they are drawn, and `clearAndSetSemantics` here would wipe the click along with the text.
 */
@Composable
private fun ChooserRow(
    choice: TrainedExercise,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val caption = stringResource(
        R.string.exercise_chooser_caption,
        pluralStringResource(R.plurals.exercise_chooser_sets, choice.sets, choice.sets),
        formatDayAndMonth(choice.lastPerformedAt),
    )

    TitanCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            // Inside the card and before the padding, the way every other row in this feature
            // builds its click: outside the `Surface`'s clip it would ripple square over a
            // rounded card, and after the padding it would leave a dead border.
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { }
                .padding(
                    horizontal = TitanTheme.spacing.xl,
                    vertical = TitanTheme.spacing.lg,
                ),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = choice.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = caption,
                    style = TitanTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                // The row already announces itself as a control; a chevron that named itself
                // would be a second thing to swipe past on every lift in the list.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
