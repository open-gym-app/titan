package com.opengym.core.ui.chart

import androidx.compose.runtime.Immutable

/**
 * One mark on a chart: where it sits, what period it covers, and what it reads as.
 *
 * Three fields because a chart needs the value twice — once as a number to scale a line or a
 * bar against its neighbours, and once as the string a screen reader is given. Deriving the
 * second from the first here would mean the component formatting a weight, which is the one
 * thing this layer must not do: figures arrive **already formatted or already canonical**, so
 * the same chart draws kilograms and pounds without knowing which it holds. Unit conversion
 * stays in `core/ui/.../format/`.
 *
 * @property value the plotted magnitude, in whatever unit the caller is working in. Only its
 *   ratio to the other entries is used, so the unit never reaches the drawing code. Negative
 *   values are not supported — every series this app charts (volume, one-rep-max estimates,
 *   body weight, sessions per week) is a magnitude — and one is clamped to zero rather than
 *   drawn through the baseline.
 * @property label what period the mark covers, as the axis and a screen reader say it:
 *   `Feb`, `Apr`. Not necessarily unique — a twelve-week series labelled by month repeats
 *   each month three times, which is what [chartAxisLabels] collapses.
 * @property formattedValue the value as the user reads it, unit included: `12,480 kg`. What
 *   the chart's own description is assembled from.
 */
@Immutable
data class ChartEntry(
    val value: Float,
    val label: String,
    val formattedValue: String,
)

/**
 * The readings a chart takes of its own series, computed once and shared by the drawing and
 * the semantics so the two cannot disagree about which mark is the peak.
 *
 * ### Two scales, because two charts measure from different places
 *
 * A **line** is read against itself: the question is which way it is going, so the band runs
 * from the series' own minimum to its maximum and a 4,000 kg week against a 4,200 kg one is a
 * visible rise. A **bar** is read against zero: the bars are quantities standing side by side,
 * and a track that started at the smallest of them would draw a week that lifted almost as
 * much as its neighbour as a stub. [lineFraction] and [barFraction] are that difference, and
 * it is why they are two functions rather than one with a flag.
 */
internal class ChartStats(entries: List<ChartEntry>) {

    private val values: List<Float> = entries.map { it.value.coerceAtLeast(0f) }

    /** The series' own floor and ceiling. Both zero for an empty series, which draws nothing. */
    val min: Float = values.minOrNull() ?: 0f
    val max: Float = values.maxOrNull() ?: 0f

    /**
     * Every value equal — a chart with nothing to show but its own level.
     *
     * Worth naming rather than letting it fall out of the arithmetic, because it changes what
     * is *said* as well as what is drawn: "highest 4,000 kg, lowest 4,000 kg" is two sentences
     * restating one fact, and there is no peak to accent.
     */
    val isFlat: Boolean = max - min <= FLAT_TOLERANCE

    /**
     * The index of the single highest mark, or **null** when nothing is uniquely highest.
     *
     * Null on a flat series and on a tie, which is the whole reason it is nullable: the accent
     * means *this one*, and painting the first of three equal bars would be the component
     * inventing a distinction the data does not carry. One accent per chart, the rule
     * `OpenGymCard` already states for accented cards.
     */
    val peakIndex: Int? = values
        .indexOf(max)
        .takeIf { !isFlat && values.count { value -> value == max } == 1 }

    /**
     * Where [value] sits in a line's band, `0f..1f` measured from the bottom.
     *
     * A flat series comes back at [FLAT_LEVEL] rather than dividing by a zero range — the
     * crash #144 names first — and drawing it down the middle is also the honest picture: the
     * line did not rise and it did not fall.
     */
    fun lineFraction(value: Float): Float =
        if (isFlat) FLAT_LEVEL else ((value.coerceAtLeast(0f) - min) / (max - min)).coerceIn(0f, 1f)

    /**
     * How much of a bar's track [value] fills, `0f..1f` measured from zero.
     *
     * A series whose every value is zero — a fortnight nobody trained in — fills nothing, and
     * that is the answer rather than a division by zero. [BarSeriesChart] still draws the
     * track's baseline, so the weeks are present as the zeroes they are.
     */
    fun barFraction(value: Float): Float = if (max <= 0f) 0f else (value.coerceAtLeast(0f) / max).coerceIn(0f, 1f)

    private companion object {
        /**
         * How close two values have to be to count as the same level.
         *
         * Exact equality, in effect: the values a caller hands over are computed from integer
         * milligrams and integer counts, so a series that is flat is flat to the bit. The
         * tolerance is here so a caller charting an average — sessions per week, a body weight
         * in kilograms — does not fall through to the ratio path over a rounding error.
         */
        const val FLAT_TOLERANCE = 1e-6f

        /** Where a flat line sits in its band: halfway, because it went neither way. */
        const val FLAT_LEVEL = 0.5f
    }
}

/**
 * How many marks a series needs before there is anything honest to draw.
 *
 * **Two.** One point is not a trend, and every one of these charts is drawn to answer "which
 * way is this going": a single bar fills its track and claims to be a peak, a single dot has
 * no line to belong to, and both read as *a chart* to somebody glancing at the screen. Four of
 * the five callers carry an empty-state acceptance criterion, so the refusal lives here rather
 * than as a size check each screen remembers to write — see [ChartPlaceholder].
 */
internal const val MIN_PLOTTABLE_ENTRIES = 2

/** Whether there is enough of this series to plot — see [MIN_PLOTTABLE_ENTRIES]. */
internal fun List<ChartEntry>.isPlottable(): Boolean = size >= MIN_PLOTTABLE_ENTRIES
