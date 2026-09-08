package com.opengym.core.domain.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Watches a workout nobody is working out, and says something about it once.
 *
 * ### Why this exists at all
 *
 * A session stays open until the user finishes it, and real gym sessions get abandoned — a
 * phone call, a change of plan, or simply forgetting. The app is what makes that possible:
 * `InProgressSessionRepository` persists the workout across process death, so a session left
 * open at six is still open at midnight, and the next launch resumes a workout that ended
 * hours ago with an elapsed time that is nonsense and a payoff that will eventually be
 * computed over it. This is a reliability problem wearing an engagement problem's clothes.
 *
 * ### The clock runs from the last set, not from the start
 *
 * A session open for two hours with sets logged throughout is not abandoned; one open for
 * thirty minutes with nothing logged is. So the deadline is measured from the most recently
 * completed set — see [IDLE_THRESHOLD] — and every set logged pushes it out.
 *
 * ### Once per idle period
 *
 * [arm] schedules nothing for a deadline that has already gone by, and that one rule is what
 * makes the nudge fire once: after it has been said, the deadline stays in the past until
 * another set moves it. A reminder that repeated every thirty minutes would get the whole
 * channel muted, which would cost the user every other thing the app has to say.
 *
 * Implemented in `:core:data`, which owns the alarm and the notification. Nothing about either
 * is decided here.
 */
interface IdleSessionMonitor {

    /**
     * Points the nudge at the session as it now stands, and takes down anything already said
     * about it.
     *
     * Reads the session and its sets itself rather than being handed them, which is the reason
     * it takes no arguments: the deadline is a fact about what is on disk, and a caller passing
     * a timestamp it read a moment ago would be passing one that a set logged in between has
     * already moved.
     *
     * Nothing is scheduled when there is no session in progress, or when the deadline has
     * already passed — see the note on the interface. Safe to call as often as the session
     * changes; it replaces whatever was scheduled rather than adding to it.
     */
    suspend fun arm()

    /**
     * Takes the nudge down completely: no alarm pending, and no notification showing.
     *
     * For the three moments there is nothing to nudge about — the session finished, the session
     * discarded, and the user looking at the session screen. The last is not tidiness: somebody
     * with the workout in front of them does not need to be told it is open.
     */
    suspend fun disarm()

    companion object {

        /**
         * How long a session may log nothing before the app says something.
         *
         * Thirty minutes, chosen against the two things it sits between. It is comfortably
         * longer than the longest rest anybody takes — the rest-timer picker tops out at five
         * minutes, and even a lifter ignoring it and resting through a phone call is well
         * inside this — so a nudge is never a nudge about a rest. And it is short enough that
         * the user could still plausibly come back to the gym floor, which is the only window
         * in which "Resume" is a real offer rather than a formality.
         *
         * Longer would make the notification arrive when the answer is always "finish it";
         * shorter would make it arrive while somebody is still resting between heavy singles.
         */
        val IDLE_THRESHOLD: Duration = 30.minutes
    }
}
