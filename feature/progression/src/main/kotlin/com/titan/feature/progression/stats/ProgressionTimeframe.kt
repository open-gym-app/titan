package com.titan.feature.progression.stats

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * making. `@Singleton` gets that lifetime for free — for as long as the process lives.
 *
 * ### And why the process is nevertheless not quite the right lifetime (#183)
 *
 * Because a process killed under a backgrounded app is not the user leaving. From where they are
 * standing they never went anywhere: they set the hub to `1Y`, answered a phone call, came back,
 * and the segment they chose is the one they expect to still be lit. A fresh launch is the other
 * case and does want the default.
 *
 * [restoreThrough] is what separates the two, and it costs one string per progression screen.
 * Saved instance state is exactly the lifetime wanted here — it outlives the process and dies
 * with the task — so each screen writes the shared selection into its own handle and pushes back
 * whatever it finds there when it is rebuilt. Whichever screen is recreated first restores the
 * selection for all of them, and only that one does — a screen rebuilt after somebody has since
 * moved the segments would be pushing back an answer that is out of date. A launch with no saved
 * state finds nothing and opens on the default, which is the behaviour above, unchanged.
 */
@Singleton
class ProgressionTimeframe @Inject constructor() {

    private val state = MutableStateFlow(StatsTimeframe.DEFAULT)

    /**
     * Whether this holds a selection rather than the opening default.
     *
     * Set by the first [select] of the process, whoever makes it — a restore or a thumb. It is
     * what stops a screen rebuilt *later* from overwriting a choice made *since*; see
     * [restoreThrough].
     */
    private var isSelected = false

    /** What every progression screen is currently reading over. */
    val selected: StateFlow<StatsTimeframe> = state.asStateFlow()

    /** Change it, for every screen at once. */
    fun select(timeframe: StatsTimeframe) {
        isSelected = true
        state.value = timeframe
    }

    /**
     * Seed this from [savedState] if it holds a selection, and keep writing back to it.
     *
     * Called by every progression ViewModel with its own handle. **The first restore of the
     * process wins**, and every later one only mirrors. Each handle holds what the selection was
     * when *that destination* was last torn down, which is not the same as what it is now: a
     * process death restores onto whichever screen was on top, and a user who moves the segments
     * there and then presses back would otherwise be handed the stale answer the screen behind
     * had filed away — the shared selection walked backwards by the screen that was supposed to
     * be reading it.
     *
     * A name rather than an ordinal, so that reordering [StatsTimeframe] cannot silently change
     * what a saved selection means, and an unknown name — a build that dropped a case — reads as
     * no selection rather than as an exception on the way to the first frame.
     *
     * @param scope the caller's `viewModelScope`. The mirror lives as long as the ViewModel does.
     */
    fun restoreThrough(
        savedState: SavedStateHandle,
        scope: CoroutineScope,
    ) {
        if (!isSelected) {
            savedState.get<String>(SAVED_STATE_KEY)
                ?.let { name -> StatsTimeframe.entries.firstOrNull { it.name == name } }
                ?.let(::select)
        }

        scope.launch { selected.collect { savedState[SAVED_STATE_KEY] = it.name } }
    }

    private companion object {

        /**
         * Where the selection is written in each screen's handle.
         *
         * Prefixed so it cannot collide with a route argument, which navigation puts in the same
         * handle under the name of the property it fills.
         */
        const val SAVED_STATE_KEY = "com.titan.progressionTimeframe"
    }
}
