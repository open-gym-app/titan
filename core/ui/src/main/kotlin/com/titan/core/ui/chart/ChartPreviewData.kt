/*
 * `MagicNumber`: every literal below is one mark on one chart. Naming them would be a constant
 * per data point, which says nothing the series does not already say — the same trade
 * `BodyShapes` records for its anatomical coordinates.
 */
@file:Suppress("MagicNumber")

package com.titan.core.ui.chart

import kotlin.math.roundToInt

/*
 * The series the previews and the screenshot catalogue are drawn from.
 *
 * Shared across the three chart files rather than declared privately in each, so the catalogue
 * and the per-component previews plot the *same* numbers: a reviewer comparing two references
 * is then looking at a difference in the drawing rather than at a difference in the fixture.
 *
 * They live in `main` alongside the previews that use them, which is where every other
 * component in this package keeps its preview data — `TrainingCalendar`'s month, `BodyDiagram`'s
 * muscles. Marked internal, so nothing outside `:core:ui` can build a screen on them.
 *
 * The values are milligram-reps scaled down to something a chart can hold, and the formatted
 * strings are what the volume formatter would produce for them. A preview does not read
 * `LocalUnitSystem` — see #144: values reach a chart already formatted.
 */

/** Twelve weeks of volume climbing with a plateau in the middle of it — the ordinary case. */
internal fun previewRising(): List<ChartEntry> =
    listOf(
        entry(40f, "Feb", "40,200 kg"),
        entry(55f, "Feb", "55,100 kg"),
        entry(48f, "Feb", "48,400 kg"),
        entry(62f, "Feb", "62,000 kg"),
        entry(58f, "Mar", "58,700 kg"),
        entry(72f, "Mar", "72,300 kg"),
        entry(68f, "Mar", "68,900 kg"),
        entry(80f, "Mar", "80,100 kg"),
        entry(76f, "Apr", "76,500 kg"),
        entry(88f, "Apr", "88,200 kg"),
        entry(92f, "Apr", "92,800 kg"),
        entry(108f, "Apr", "108,400 kg"),
    )

/** Eight weeks that lifted exactly the same amount — the divide-by-zero case, drawn. */
internal fun previewFlat(): List<ChartEntry> =
    List(FLAT_WEEKS) { index ->
        entry(80f, if (index < FLAT_WEEKS / 2) "Mar" else "Apr", "80.0 kg")
    }

/** One session logged, which is a figure rather than a trend. */
internal fun previewSinglePoint(): List<ChartEntry> = listOf(entry(92f, "Apr", "92 kg"))

/** Volume per session, with one week clearly the heaviest — what the peak accent is for. */
internal fun previewPeaked(): List<ChartEntry> =
    listOf(
        entry(8f, "Feb", "8,100 kg"),
        entry(10f, "Feb", "10,400 kg"),
        entry(9f, "Feb", "9,200 kg"),
        entry(12f, "Mar", "12,600 kg"),
        entry(11f, "Mar", "11,300 kg"),
        entry(16f, "Mar", "16,800 kg"),
        entry(13f, "Apr", "13,500 kg"),
        entry(14f, "Apr", "14,100 kg"),
    )

/** A fortnight off: present as the zeroes it is, rather than absent as a gap. */
internal fun previewZeroed(): List<ChartEntry> =
    List(FLAT_WEEKS) { index ->
        entry(0f, if (index < FLAT_WEEKS / 2) "Mar" else "Apr", "0 kg")
    }

/**
 * The formatter a preview hands its value axis.
 *
 * The fixtures' values are milligram-reps scaled down to something a chart can hold, so this
 * scales them back up and groups them — which is what the formatted strings beside them read
 * like. A preview does not reach for the real formatters: those follow `LocalUnitSystem`, and
 * a reference image that changed with a setting would be a reference image of the setting.
 */
internal fun previewFormat(value: Float): String = "%,d".format(value.roundToInt() * 1_000)

private fun entry(
    value: Float,
    label: String,
    formatted: String,
) = ChartEntry(value = value, label = label, formattedValue = formatted)

private const val FLAT_WEEKS = 8
