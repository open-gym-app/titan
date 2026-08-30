package com.opengym.feature.progression.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The timeframe the progression screens are all reading over, held in one place.
 *
 * The stats hub and the per-exercise drill-down (#102) each draw their own selector, and the
 * two have to agree: a lifter who sets the hub to `1Y`, opens an exercise and finds it showing
 * ninety days has been shown two different answers to one question. #32's acceptance criteria
 * name that explicitly — *"the selection is shared with #102"*.
 *
 * ### Why not a navigation argument
 *
 * The drill-downs are opened from the hub, so the selection *could* travel as a route argument.
 * It would then be stale in the other direction: change the timeframe on the exercise screen,
 * go back, and the hub is still on the one it pushed. It would also have to be threaded through
 * every future entry point into those screens — the PR list, an exercise's detail page — each
 * of which would have to know a timeframe it has no business holding.
 *
 * ### Why not a stored preference
 *
 * It is not a setting. A timeframe is a way of looking at a screen for a minute, not something
 * the user configures once; persisting it across launches would mean opening the app in
 * whatever window they last poked at, which is neither the default nor a choice they remember
 * making. `@Singleton` means "for as long as the process lives", which is exactly the lifetime
 * this selection wants.
 */
@Singleton
class ProgressionTimeframe @Inject constructor() {

    private val state = MutableStateFlow(StatsTimeframe.DEFAULT)

    /** What every progression screen is currently reading over. */
    val selected: StateFlow<StatsTimeframe> = state.asStateFlow()

    /** Change it, for every screen at once. */
    fun select(timeframe: StatsTimeframe) {
        state.value = timeframe
    }
}
