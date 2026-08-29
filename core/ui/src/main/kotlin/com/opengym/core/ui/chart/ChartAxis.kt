package com.opengym.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.theme.OpenGymTheme
import kotlin.math.roundToInt

/**
 * The sparse period labels under a chart: `FEB · MAR · APR` under twelve weekly marks.
 *
 * ### Runs, not marks
 *
 * A twelve-week series labelled by month carries `Feb` four times over. Drawing all twelve is
 * a wall of repeated text at 10sp; drawing the distinct values evenly spaced puts `Mar` over
 * the wrong bars. So consecutive equal labels are collapsed into a **run**, and each run is
 * given the width of the marks it covers — the label then sits over its own stretch of the
 * chart, which is the only reading of it that is true.
 *
 * ### Thinned when there are still too many
 *
 * A series whose every label differs — seven days, fifty-three weeks — has nothing to collapse,
 * so the runs past [MAX_AXIS_LABELS] are **blanked**: the first and the last are kept, the rest
 * are spread between them, and everything else is drawn as an empty label. Blanked rather than
 * dropped because the weights are what position the survivors — a shorter list would re-space
 * them over the whole width and put `Apr` in the middle of March.
 *
 * @return one entry per run, in order, ready to hand to [ChartAxisLabels]. Empty for a series
 *   too short to plot, which draws no axis at all — see [ChartPlaceholder].
 */
fun chartAxisLabels(entries: List<ChartEntry>): List<ChartAxisLabel> {
    if (!entries.isPlottable()) return emptyList()

    val runs = mutableListOf<ChartAxisLabel>()
    entries.forEach { entry ->
        val last = runs.lastOrNull()
        if (last != null && last.text == entry.label) {
            runs[runs.lastIndex] = last.copy(weight = last.weight + 1f)
        } else {
            runs += ChartAxisLabel(text = entry.label, weight = 1f)
        }
    }

    if (runs.size <= MAX_AXIS_LABELS) return runs

    // Evenly spaced across the runs, both ends included. Rounded rather than floored, so the
    // middle label sits in the middle rather than drifting towards the start; `toSet` collapses
    // the duplicates that rounding produces on a short list, which would draw one label twice.
    val kept = (0 until MAX_AXIS_LABELS)
        .map { step -> (step * (runs.size - 1).toFloat() / (MAX_AXIS_LABELS - 1)).roundToInt() }
        .toSet()

    return runs.mapIndexed { index, run -> if (index in kept) run else run.copy(text = "") }
}

/**
 * The axis, drawn: [chartAxisLabels]' runs laid out over the width they cover.
 *
 * Hidden from screen readers in one gesture. The chart above already names its own range and
 * its latest mark — see [chartDescription] — so an axis read aloud is `FEB`, `MAR`, `APR`
 * three swipes apart, with no chart between them to attach the months to.
 *
 * @param labels what [chartAxisLabels] returned. An empty list draws nothing, which is what a
 *   series too short to plot wants: [ChartPlaceholder] has already said there is no chart.
 */
@Composable
fun ChartAxisLabels(
    labels: List<ChartAxisLabel>,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return

    Row(modifier = modifier.fillMaxWidth().clearAndSetSemantics { }) {
        labels.forEach { label ->
            Eyebrow(
                text = label.text,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(label.weight),
            )
        }
    }
}

/**
 * How many labels an axis will draw before it starts blanking runs.
 *
 * Three, as the wireframes draw it. A 360dp card holds more, but the axis is a *hint* about
 * where in the year the chart sits rather than a scale to read values off — the figures are in
 * the description and in the rows below. Four labels at 10sp with 0.12em tracking start to
 * look like a table header, which is what the eyebrow role is for elsewhere on the screen.
 */
private const val MAX_AXIS_LABELS = 3

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Chart axis", widthDp = 360)
@Composable
fun ChartAxisLabelsPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ChartAxisCatalogue()
    }
}

@Preview(name = "Chart axis, dark", widthDp = 360)
@Composable
fun ChartAxisLabelsDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ChartAxisCatalogue()
    }
}

@Composable
private fun ChartAxisCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        LineSeriesChart(entries = previewRising(), label = "Cumulative volume")
        ChartAxisLabels(labels = chartAxisLabels(previewRising()))
        BarSeriesChart(entries = previewPeaked(), label = "Volume per session")
        ChartAxisLabels(labels = chartAxisLabels(previewPeaked()))
    }
}
