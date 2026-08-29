package com.opengym.core.ui.chart

import androidx.compose.runtime.Immutable

/**
 * One label under a chart, and how much of the width it stands over.
 *
 * @property text what is drawn, or **empty** for a run the axis is skipping. An empty label
 *   still carries its weight, which is what keeps the labels that *are* drawn sitting over the
 *   marks they name — dropping the run instead would slide every later label left.
 * @property weight how many marks the run covers.
 */
@Immutable
data class ChartAxisLabel(
    val text: String,
    val weight: Float,
)
