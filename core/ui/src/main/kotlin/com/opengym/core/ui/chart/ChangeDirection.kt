package com.opengym.core.ui.chart

/**
 * Which way a figure moved against the period before it.
 *
 * Three cases and not two: "no change" is a real answer — a lifter holding a plateau on
 * purpose is not the same as one who has no comparison — and it is what keeps a chip from
 * claiming a rise off a rounding error.
 */
enum class ChangeDirection {

    /** More than last period. The one case that spends the accent. */
    UP,

    /** Less than last period. */
    DOWN,

    /** The same, to within what the figure is drawn to. */
    NONE,
    ;

    companion object {

        /**
         * Which way [delta] points, treating anything under [tolerance] as no change at all.
         *
         * @param tolerance the smallest move worth reporting, in [delta]'s own units. The
         *   default is a hundredth, which is below the precision every figure on the stats hub
         *   is drawn to — a caller charting something coarser should raise it rather than draw
         *   an arrow for a change its own display rounds away.
         */
        fun of(
            delta: Double,
            tolerance: Double = DEFAULT_TOLERANCE,
        ): ChangeDirection =
            when {
                delta > tolerance -> UP
                delta < -tolerance -> DOWN
                else -> NONE
            }

        private const val DEFAULT_TOLERANCE = 0.01
    }
}
