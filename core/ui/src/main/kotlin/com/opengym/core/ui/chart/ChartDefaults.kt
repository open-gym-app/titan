package com.opengym.core.ui.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The measurements the charts are drawn at.
 *
 * Not the spacing scale: none of these is a gap between two things, they are the dimensions of
 * a drawing, and putting them on [com.opengym.core.ui.theme.OpenGymSpacing] would add steps to
 * a rhythm that six screens lay out against. They are named here for the reason the rhythm is
 * named at all — five surfaces draw one of these charts, and a height picked per call site is
 * how two cards on the same screen end up different sizes. Same arrangement as
 * `BodyDiagramDefaults` and `RestTimerDefaults`.
 */
object ChartDefaults {

    /**
     * The height of a chart sitting inside a card, under an eyebrow and a figure.
     *
     * The wireframes draw 70–80px at 360 wide; 72dp is that, on the 4dp rhythm. Tall enough
     * for a twelve-week line to have a shape, short enough that the hero figure above it is
     * still the first thing read.
     */
    val HEIGHT: Dp = 72.dp

    /** A chart that is the point of its card rather than a footnote under a figure. */
    val TALL_HEIGHT: Dp = 88.dp

    /** The line's own weight. Above the hairline, because it is data rather than chrome. */
    val LINE_WIDTH: Dp = 2.dp

    /** The emphasised last point. Big enough to find, small enough not to read as a control. */
    val POINT_RADIUS: Dp = 3.dp

    /** Every other point on the line — present, so the series' resolution is visible. */
    val MINOR_POINT_RADIUS: Dp = 1.5.dp

    /** The gap between two bars, taken out of the bar's own width. */
    val BAR_GAP: Dp = 2.dp

    /** The narrowest a bar is allowed to get before the gaps are given up instead. */
    val MIN_BAR_WIDTH: Dp = 2.dp

    /** A zero bar still draws this much, so an untrained week is a mark rather than a gap. */
    val BAR_BASELINE: Dp = 1.dp

    /** The diameter of the XP ring. Fits beside two lines of text on a 360dp screen. */
    val RING_SIZE: Dp = 80.dp

    /** The ring's stroke. Heavy enough to read as a gauge at arm's length. */
    val RING_WIDTH: Dp = 5.dp
}

/**
 * What a chart draws when there is not enough series to chart: a ruled band and a line saying
 * so.
 *
 * **Not a chart of nothing.** A zeroed line across the bottom of a card is a claim — that the
 * user trained and lifted nothing — and a single bar filling its track is another. Both are
 * what a brand-new user would otherwise be shown on the one screen that is meant to tell them
 * what they have done. Holding the chart's height rather than collapsing is deliberate too: a
 * card that changes size the moment a second session is logged reflows the screen under
 * somebody's thumb.
 *
 * Announced as one node carrying [label]'s own "nothing yet" sentence, so a screen reader is
 * told the same thing the pixels say — see [chartDescription].
 */
@Composable
internal fun ChartPlaceholder(
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = ChartDefaults.HEIGHT,
) {
    val spoken = stringResource(R.string.chart_description_empty, label)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.chart_placeholder),
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}
