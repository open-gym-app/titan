package com.opengym.feature.progression.exercise

import com.opengym.feature.progression.stats.StatsTimeframe

/**
 * What the user can do to the per-exercise drill-down.
 *
 * Three, and all three change what the screen is reading rather than writing anything: this is
 * a readout, and everything else on it is either drawn from the reads or is navigation, which
 * is a hoisted lambda because the destination is `:app`'s business. See CONTRIBUTING.md §
 * Screens.
 */
sealed interface ExerciseProgressionEvent {

    /**
     * A timeframe was tapped.
     *
     * It changes both series here *and* the window the hub is showing, because the selection
     * is shared — see [com.opengym.feature.progression.stats.ProgressionTimeframe].
     */
    data class TimeframeSelected(val timeframe: StatsTimeframe) : ExerciseProgressionEvent

    /** A lift was picked out of the chooser. */
    data class ExerciseSelected(val exerciseId: Long) : ExerciseProgressionEvent

    /**
     * The switcher in the top bar: put the chooser back up.
     *
     * Not navigation — the chooser is a state of this screen rather than a destination of its
     * own, so backing out of it leaves the screen entirely, which is what somebody who arrived
     * from the hub's explore row expects.
     */
    data object ExerciseCleared : ExerciseProgressionEvent
}
