package com.titan.core.domain.model

/*
 * How long the units a distance is written in are, in the centimetres everything is stored in.
 *
 * The distance counterpart of `Weights.kt`, for the same reason: the ratios are physical facts
 * rather than rendering decisions, and both the formatters in `:core:ui` and the import parsers
 * here convert with them. Every factor is exact by definition: a yard is 0.9144 m, a foot a
 * third of one, and a mile 1,760 of them.
 */

/** One metre in centimetres. */
const val CENTIMETRES_PER_METRE: Double = 100.0

/** One kilometre in centimetres. */
const val CENTIMETRES_PER_KILOMETRE: Double = 100_000.0

/** One foot in centimetres. */
const val CENTIMETRES_PER_FOOT: Double = 30.48

/** One yard in centimetres. */
const val CENTIMETRES_PER_YARD: Double = 91.44

/** One international mile in centimetres. */
const val CENTIMETRES_PER_MILE: Double = 160_934.4
