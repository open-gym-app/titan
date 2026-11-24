package com.titan.core.domain.model

/*
 * How long the units a body circumference is written in are, in the millimetres it is stored in.
 *
 * The tape-measure counterpart of `Distances.kt`, for the same reason: the ratios are physical
 * facts rather than rendering decisions, and both the length formatter in `:core:ui` and the
 * import parsers here convert with them. Both factors are exact by definition: an inch has been
 * 25.4 mm since 1959.
 */

/** One centimetre in millimetres. */
const val MILLIMETRES_PER_CENTIMETRE: Double = 10.0

/** One international inch in millimetres. */
const val MILLIMETRES_PER_INCH: Double = 25.4
