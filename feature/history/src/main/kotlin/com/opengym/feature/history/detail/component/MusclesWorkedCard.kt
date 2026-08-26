package com.opengym.feature.history.detail.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.BodyDiagram
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.displayName
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R

/**
 * The figures with the worked muscles lit up, and the same answer in words beside them.
 *
 * Both, not either. The diagram is what makes "middle back" mean something to somebody who
 * has not learned the names, and the list is what makes it exact — the illustration has no
 * shape for a handful of the seeded groups (see `BodyRegion`), and for those the written line
 * is the only place they appear at all. Neither half is decoration for the other.
 *
 * Primary and secondary are distinguished twice for the same reason: in the drawing by the
 * accent against its container tone, in the list by a badge in the matching tones. A reader
 * who cannot tell two oranges apart on a 130dp figure can still read `PRIMARY`.
 *
 * The caller draws nothing at all when there are no links — see [ExerciseDetailScreen]. An
 * empty body with an empty legend is a section that looks like it failed to load.
 */
@Composable
internal fun MusclesWorkedCard(
    primary: List<MuscleGroup>,
    secondary: List<MuscleGroup>,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both views, because a movement's assistors routinely sit on the other side of
            // the body: a bench press is chest and triceps, and the triceps is a back-view
            // shape. Shorter than the component's own default so the two figures and the
            // legend fit across 360dp without the legend wrapping every muscle name.
            BodyDiagram(
                primary = primary,
                secondary = secondary,
                height = DIAGRAM_HEIGHT,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
            ) {
                MuscleLine(
                    badge = stringResource(R.string.detail_muscles_primary),
                    tone = BadgeTone.ACCENT,
                    muscles = primary,
                )
                MuscleLine(
                    badge = stringResource(R.string.detail_muscles_secondary),
                    tone = BadgeTone.NEUTRAL,
                    muscles = secondary,
                )
            }
        }
    }
}

/**
 * `PRIMARY` over `Chest`, or nothing.
 *
 * The badge sits *above* its list rather than beside it, which is the one place this card
 * departs from the wireframe. The wireframe draws a single front-view figure; two figures take
 * half the card, and a badge inline with them leaves about 80dp for the names — enough to
 * break `Shoulders, Triceps` across three lines. Stacked, the same names fit on one.
 *
 * Absent rather than "None" when the half is empty: an exercise with no secondary movers is
 * the ordinary case for an isolation, and a line saying so is a line the reader has to read
 * to find out it says nothing.
 */
@Composable
private fun MuscleLine(
    badge: String,
    tone: BadgeTone,
    muscles: List<MuscleGroup>,
) {
    if (muscles.isEmpty()) return

    val separator = stringResource(R.string.detail_muscle_separator)
    // `map` first: `joinToString`'s transform is a nullable parameter, which makes it
    // non-inlinable and so closes it to a composable call.
    val names = muscles.map { it.displayName() }.joinToString(separator)
    val spoken = stringResource(R.string.detail_muscles_description, badge, names)

    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
        horizontalAlignment = Alignment.Start,
    ) {
        OpenGymBadge(text = badge, tone = tone)
        Text(
            text = names,
            style = MaterialTheme.typography.bodyMedium,
            color = if (tone == BadgeTone.ACCENT) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Shorter than `BodyDiagramDefaults.HEIGHT`, which is sized for a figure standing alone.
 * Two of them plus a legend have to share 360dp here.
 */
private val DIAGRAM_HEIGHT = 130.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private fun previewMuscle(name: String) = MuscleGroup(id = name.hashCode().toLong(), name = name)

@Preview(name = "Muscles worked", widthDp = 360)
@Composable
fun MusclesWorkedCardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MusclesWorkedCatalogue()
    }
}

@Preview(name = "Muscles worked, dark", widthDp = 360)
@Composable
fun MusclesWorkedCardDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        MusclesWorkedCatalogue()
    }
}

@Composable
private fun MusclesWorkedCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        MusclesWorkedCard(
            primary = listOf(previewMuscle("CHEST")),
            secondary = listOf(previewMuscle("TRICEPS"), previewMuscle("SHOULDERS")),
        )
        // An isolation: no secondary line at all, rather than a line saying there is none.
        MusclesWorkedCard(
            primary = listOf(previewMuscle("BICEPS")),
            secondary = emptyList(),
        )
    }
}
