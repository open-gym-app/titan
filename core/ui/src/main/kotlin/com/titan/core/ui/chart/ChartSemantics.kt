package com.titan.core.ui.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.titan.core.ui.R

/**
 * What a chart tells a screen reader: one sentence built from the series it is holding.
 *
 * ### The component's job, not the caller's
 *
 * Three separate M5 issues carry the acceptance criterion *"charts are accessible — values
 * reachable via TalkBack, not image-only"*. Assembled in each feature that is three sentences
 * with three shapes; assembled here it is one, and a screen that adds a fourth chart gets it
 * without writing anything. The chart components pair this with `clearAndSetSemantics` over
 * their canvas, so the marks are **not** announced one by one — a twelve-week series would
 * otherwise be twelve swipes to reach the figure the card is about, exactly as
 * `TrainingCalendar` avoids for its day cells.
 *
 * ### What it says, and what it leaves out
 *
 * The shape, the range and the latest value — the three things a sighted reader takes from a
 * glance at a trend. Not every point: a description that read all twelve is a paragraph
 * nobody can hold, and the figures are on the screen elsewhere (the hero number above the
 * chart, the rows below it) for anybody who wants one of them exactly.
 *
 * A flat series gets its own sentence rather than "highest 4,000, lowest 4,000", which is two
 * clauses restating one fact. A series too short to plot says so, matching [ChartPlaceholder],
 * because "not enough data yet" is the honest reading of an empty card and silence is not.
 *
 * The clauses are joined by the format string rather than concatenated here, so a translator
 * can reorder them; the *choice* of which of the three strings applies is the only decision
 * this function makes.
 *
 * @param label what the series is, in the caller's own words: `Cumulative volume`,
 *   `Sessions per week`. Sentence case — it opens the sentence.
 */
@Composable
@ReadOnlyComposable
internal fun chartDescription(
    label: String,
    entries: List<ChartEntry>,
): String {
    if (!entries.isPlottable()) return stringResource(R.string.chart_description_empty, label)

    val stats = ChartStats(entries)
    val first = entries.first()
    val last = entries.last()

    if (stats.isFlat) {
        return stringResource(
            R.string.chart_description_flat,
            label,
            first.label,
            last.label,
            last.formattedValue,
        )
    }

    // Highest and lowest by the same values the drawing scales against, so what is announced
    // as the peak is the mark that is painted as one.
    val highest = entries.maxBy { it.value }
    val lowest = entries.minBy { it.value }

    return stringResource(
        R.string.chart_description,
        label,
        first.label,
        last.label,
        last.formattedValue,
        highest.formattedValue,
        highest.label,
        lowest.formattedValue,
        lowest.label,
    )
}
