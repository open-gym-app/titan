package com.titan.feature.session

import com.titan.core.domain.model.SessionProgression
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries what a finish earned from the workout screen to the recap that celebrates it.
 *
 * ### Why this exists at all
 *
 * `FinishSessionUseCase` returns a `SessionProgression` — the records, the XP, the level
 * crossing, the achievements — and it is the **only** place that value can be obtained. Its own
 * doc says why: each figure was produced inside the write that persisted it, so a screen that
 * re-read the progress row afterwards could observe a total that has since moved and report a
 * level-up against the wrong session. #111 states the same requirement from the other side: the
 * summary draws one value from the cascade and does not re-query for any of it.
 *
 * But the recap is a *destination*, reached by navigating to a session id. A `SessionProgression`
 * is not something a route argument can carry, and the screen that has it — the active workout —
 * is popped off the stack on the way. Something has to hold the value across that hop.
 *
 * ### Why it is not a repository
 *
 * Because nothing is stored. This is a hand-off between two screens that live in the same
 * feature module, which is the smallest scope that can hold it: `ActiveSessionViewModel` writes,
 * `SessionSummaryViewModel` takes, and no other module can see either. Modelling it as a
 * repository would put a presentation-layer relay in the domain's vocabulary and invite a
 * second, non-transient reader.
 *
 * ### Taken once, and keyed
 *
 * [take] clears the slot, and it only answers for the session it was asked about. Both halves
 * matter, and each stops a different bug:
 *
 * - **Once**, so backing out of the recap and opening the same workout from history does not
 *   throw confetti at a week-old session. The celebration marks a thing that just happened, and
 *   a value that could be read twice would make it a property of the session instead.
 * - **Keyed**, so a progression left behind by a finish the user navigated away from is never
 *   handed to a different session's recap. A stale slot answers nothing rather than answering
 *   wrongly.
 *
 * A recap that finds the slot empty — after process death, or on a build with no cascade wired —
 * draws the session's figures and no celebration. That is the same "nothing to announce" state a
 * historical recap is in, and it is a correct screen rather than a degraded one.
 *
 * ### Thread safety
 *
 * An [AtomicReference], because the write happens on whichever dispatcher the finish completed
 * on and the read happens on the main thread as the recap composes. `getAndSet` is what makes
 * "take" a single operation rather than a read the next caller could interleave with.
 */
@Singleton
class SessionProgressionHandoff @Inject constructor() {

    private val slot = AtomicReference<SessionProgression?>(null)

    /**
     * Leaves [progression] for the recap of the session it describes.
     *
     * Overwrites whatever was there. A second finish before the first was collected means the
     * first recap was never opened, and the older value is then for a session the user has
     * already left behind.
     */
    fun offer(progression: SessionProgression) {
        slot.set(progression)
    }

    /**
     * Takes the progression for [sessionId], or **null** if there is none for it.
     *
     * The slot is cleared either way when it matched, and left alone when it did not — a value
     * waiting for its own recap must survive another session's summary being opened first.
     */
    fun take(sessionId: Long): SessionProgression? {
        val held = slot.get() ?: return null
        if (held.sessionId != sessionId) return null
        // Compare-and-set rather than a plain clear: two collectors racing must not both come
        // away with the value, and the one that loses correctly sees nothing to celebrate.
        return if (slot.compareAndSet(held, null)) held else null
    }
}
