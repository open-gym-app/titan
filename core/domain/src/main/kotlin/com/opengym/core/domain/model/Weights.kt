package com.opengym.core.domain.model

/*
 * How big a kilogram and a pound are, in the milligrams everything is stored in.
 *
 * These used to live beside the formatters in `:core:ui`, which is where the *rendering* of a
 * weight belongs and is still where it happens. The ratio itself is not a rendering decision
 * though — it is a physical fact — and the domain grew a second caller for it the moment the
 * plate calculator needed to say what a 45 lb plate weighs. Two copies of `453_592.37` in two
 * modules is the kind of duplication that stays correct right up until somebody fixes a
 * rounding bug in one of them.
 *
 * `:core:ui` re-exports these through its `api(project(":core:domain"))` dependency, so a call
 * site that imported them from the formatters keeps compiling with a changed import and
 * nothing else.
 */

/** One kilogram in milligrams. The unit [WorkoutSet.weight] is stored in. */
const val MILLIGRAMS_PER_KILOGRAM: Double = 1_000_000.0

/** One pound in milligrams, exactly: a pound is defined as 453.59237 g. */
const val MILLIGRAMS_PER_POUND: Double = 453_592.37

/**
 * How many milligrams one of this system's display units is.
 *
 * The one place the two constants above are chosen between. A caller that writes its own
 * `when` over [UnitSystem] to pick a divisor is a caller that will be missed when a third
 * system is added.
 */
val UnitSystem.milligramsPerUnit: Double
    get() = when (this) {
        UnitSystem.METRIC -> MILLIGRAMS_PER_KILOGRAM
        UnitSystem.IMPERIAL -> MILLIGRAMS_PER_POUND
    }
