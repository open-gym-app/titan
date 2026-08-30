package com.opengym.feature.progression.stats

/**
 * What the user can do to the stats hub.
 *
 * One event, because the screen is a readout: everything else on it either navigates — which
 * is a hoisted lambda, not an event, since the destination is `:app`'s business — or is drawn
 * from the reads. See CONTRIBUTING.md § Screens.
 */
sealed interface StatsEvent {

    /**
     * A timeframe was tapped.
     *
     * It changes every figure on the screen *and* the one the exercise drill-down (#102) will
     * open on, because the selection is shared — see [ProgressionTimeframe].
     */
    data class TimeframeSelected(val timeframe: StatsTimeframe) : StatsEvent
}
