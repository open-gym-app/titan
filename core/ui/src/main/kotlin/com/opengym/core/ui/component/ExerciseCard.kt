package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/*
 * An exercise, as a row and as a card.
 *
 * The same four things either way — image, name, what it works, when it was last done — laid
 * out at two densities:
 *
 * - [ExerciseListItem] is a *row*, drawn with no surface of its own, for a list inside one
 *   `OpenGymCard`: the exercise library, a template's contents, the summary's breakdown.
 * - [ExerciseCard] is a row inside its own card, with a slot underneath. It is what the
 *   active session screen puts the set table in, and what a picker uses when the entries are
 *   far enough apart to each be a thing.
 *
 * Neither loads anything. `lastPerformed` arrives as a formatted string because *what* "last
 * performed" means differs per screen — a date on the library, a weight and rep count in a
 * session — and because a shared component that formatted a date would have to be told the
 * locale, the clock and the unit system to do it.
 */

/**
 * One exercise as a list row.
 *
 * @param muscleGroups what the exercise works. Rendered as a single line, primary movers
 *   first: `Barbell · Chest, Triceps`. Passing the domain's own link type rather than two
 *   lists keeps the primary flag with the group it belongs to, which is the whole reason
 *   `ExerciseMuscleGroupLink` exists.
 * @param lastPerformed a formatted snippet — `Last Tue · 80 kg × 5` — or null for an
 *   exercise that has never been logged, which draws the "not logged yet" line instead. Pass
 *   an empty string to draw no line at all, for a caller that has not loaded history.
 * @param onClick null makes the row inert. A row in a summary is a record, not a control,
 *   and giving it a ripple and a click role would tell a screen reader otherwise.
 * @param trailing drawn at the end of the row: a chevron, a badge, a drag handle.
 */
@Composable
fun ExerciseListItem(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    lastPerformed: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ExerciseRow(
        exercise = exercise,
        muscleGroups = muscleGroups,
        lastPerformed = lastPerformed,
        imageSize = ExerciseImageDefaults.LIST_SIZE,
        nameStyle = MaterialTheme.typography.titleSmall,
        trailing = trailing,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = OpenGymTheme.spacing.card, vertical = OpenGymTheme.spacing.lg),
    )
}

/**
 * One exercise as a card, with room underneath for whatever the screen logs against it.
 *
 * @param active draws the card's border in the accent. Reserved for the exercise being
 *   worked right now — there is exactly one on a screen, and it is the thing a glance
 *   between sets has to find.
 * @param content the slot under the header: the set table on the active session screen, the
 *   logged sets on the summary. Empty by default, which is a plain exercise card.
 */
@Composable
fun ExerciseCard(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    lastPerformed: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    active: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    OpenGymCard(
        modifier = modifier.fillMaxWidth(),
        accented = active,
    ) {
        ExerciseRow(
            exercise = exercise,
            muscleGroups = muscleGroups,
            lastPerformed = lastPerformed,
            imageSize = ExerciseImageDefaults.CARD_SIZE,
            nameStyle = MaterialTheme.typography.titleMedium,
            trailing = trailing,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(OpenGymTheme.spacing.card),
        )
        content()
    }
}

/** The shared body of both. Everything that differs between them is a parameter. */
@Composable
private fun ExerciseRow(
    exercise: Exercise,
    muscleGroups: List<ExerciseMuscleGroupLink>,
    lastPerformed: String?,
    imageSize: Dp,
    nameStyle: TextStyle,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseImage(exercise = exercise, size = imageSize)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Text(
                text = exercise.name,
                style = nameStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val meta = exerciseMeta(exercise.equipmentType, muscleGroups)
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LastPerformed(lastPerformed)
        }

        if (trailing != null) trailing()
    }
}

/**
 * `Last Tue · 80 kg × 5`, or `Not logged yet`, or nothing.
 *
 * Three outcomes from two inputs, and the distinction between the last two is why the
 * parameter is a nullable string rather than a boolean: null means "never done", an empty
 * string means "this screen does not know", and those must not look the same. A library
 * browsing screen that has not queried history should say nothing rather than tell the user
 * they have never benched.
 */
@Composable
private fun LastPerformed(lastPerformed: String?) {
    val text = when {
        lastPerformed == null -> stringResource(R.string.exercise_never_performed)
        lastPerformed.isEmpty() -> return
        else -> stringResource(R.string.exercise_last_performed, lastPerformed)
    }
    Text(
        text = text,
        style = OpenGymTheme.typography.numericSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * `Barbell · Chest, Triceps` — the equipment, then the muscles, primary movers first.
 *
 * Primaries first rather than in the order the join returned them: the point of the line is
 * "what does this train", and the answer to that is the prime mover. Within each half the
 * caller's order is kept, because the seed's order is the upstream dataset's and re-sorting
 * it alphabetically would put `Abs` in front of `Chest` on a bench press.
 */
@Composable
private fun exerciseMeta(
    equipment: EquipmentType?,
    muscleGroups: List<ExerciseMuscleGroupLink>,
): String {
    val separator = stringResource(R.string.exercise_meta_separator)
    val muscleSeparator = stringResource(R.string.body_muscle_separator)
    val ordered = muscleGroups.sortedByDescending { it.isPrimary }
    val muscles = ordered.map { it.muscleGroup.displayName() }.joinToString(muscleSeparator)

    return listOfNotNull(equipment?.labelOrNull(), muscles.ifEmpty { null }).joinToString(separator)
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private fun previewLink(name: String, primary: Boolean) =
    ExerciseMuscleGroupLink(MuscleGroup(id = name.hashCode().toLong(), name = name), isPrimary = primary)

private val PREVIEW_BENCH = Exercise(
    id = 1,
    name = "Barbell bench press",
    equipmentType = EquipmentType.BARBELL,
)

private val PREVIEW_LINKS = listOf(
    previewLink("TRICEPS", primary = false),
    previewLink("CHEST", primary = true),
    previewLink("SHOULDERS", primary = false),
)

@Preview(name = "Exercise - card and rows", widthDp = 360)
@Composable
fun ExerciseCardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseCatalogue()
    }
}

@Preview(name = "Exercise - card and rows, dark", widthDp = 360)
@Composable
fun ExerciseCardDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ExerciseCatalogue()
    }
}

@Composable
private fun ExerciseCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        ExerciseCard(
            exercise = PREVIEW_BENCH,
            muscleGroups = PREVIEW_LINKS,
            lastPerformed = "Tue · 80 kg × 5",
            active = true,
            trailing = { PrBadge() },
        )
        OpenGymCard(modifier = Modifier.fillMaxWidth()) {
            ExerciseListItem(
                exercise = PREVIEW_BENCH,
                muscleGroups = PREVIEW_LINKS,
                lastPerformed = "Tue · 80 kg × 5",
                onClick = {},
            )
            HairlineDivider()
            ExerciseListItem(
                exercise = Exercise(id = 2, name = "Cable fly", equipmentType = EquipmentType.CABLE),
                muscleGroups = listOf(previewLink("CHEST", primary = true)),
                onClick = {},
            )
            HairlineDivider()
            ExerciseListItem(
                exercise = Exercise(id = 3, name = "Farmer's carry", equipmentType = EquipmentType.OTHER),
                muscleGroups = listOf(previewLink("FOREARMS", primary = true), previewLink("TRAPS", primary = false)),
                lastPerformed = "",
                trailing = { StatChip(value = "3", label = "sets") },
            )
        }
    }
}
