package com.titan.core.domain.timer

import kotlinx.coroutines.flow.Flow

/**
 * A rest as the operating system is being asked to keep it, for a lifter who has put the phone
 * down.
 *
 * ### The rest itself is a [RestTimerSnapshot], and deliberately so
 *
 * Not a remainder, and not a second set of fields describing one. The snapshot is the app's
 * existing answer to "a rest timer in a form something outside this process can hold" — an
 * absolute deadline while running, a frozen remainder while paused — and it is the *same value*
 * the draft is written from. Sharing it means the alarm and the draft cannot disagree about
 * when the rest ends, which they would within a second of each other if the deadline were
 * computed twice. See [RestTimerSnapshot] for why a remainder cannot be persisted.
 *
 * [RestTimerSnapshot.Idle] and [RestTimerSnapshot.Finished] are both legal here and mean
 * different things — see [BackgroundRestTimer.set].
 *
 * @param sessionId the workout being rested in, so a tap on the notification lands on it.
 * @param exerciseName what the lifter comes back to. Already resolved to words: nothing below
 *   the presentation layer should be looking a catalogue row up to draw a line of text. **Null**
 *   for an exercise deleted from the library since the session started, which the workout
 *   carries on past — the note then says which set is next and leaves the name out, rather than
 *   naming nothing or disappearing.
 * @param setNumber which set is next, counting from one. Together with [exerciseName] this is
 *   the whole point of the notification: a glance at the lock screen that says what to do next,
 *   without unlocking. **Null** for a rest that follows no set — one started from the session
 *   clock — where there is no next set to name, and [exerciseName] is null with it.
 */
data class BackgroundRest(
    val sessionId: Long,
    val exerciseName: String?,
    val setNumber: Int?,
    val rest: RestTimerSnapshot,
) {
    init {
        require(sessionId > 0) { "a rest belongs to a session row, was $sessionId" }
        require(setNumber == null || setNumber > 0) { "sets are numbered from one, was $setNumber" }
        require(setNumber != null || exerciseName == null) { "an exercise is named with its next set" }
    }
}

/**
 * The rest timer as it exists outside the app: a note on the lock screen, and an alarm that
 * fires whether or not anything of this app is still running.
 *
 * ### Why this is not `RestTimerController` with a flag
 *
 * The controller owns the countdown and is the single source of truth about how much rest is
 * left; this owns nothing. Everything here is a *projection* of a state the controller already
 * published, pushed out to two places that keep it while the process is asleep — the
 * notification shade and `AlarmManager`. If the two ever disagree, the controller is right.
 *
 * That division is why [set] takes the whole rest every time rather than offering `start`,
 * `pause` and `stop`: the caller is not issuing commands, it is publishing a state it has
 * already reached, and an idempotent "make it look like this" cannot get out of step with a
 * countdown the way a sequence of edits can.
 *
 * ### Both halves are needed, and neither is enough
 *
 * A notification does not wake anything. A countdown reaching zero in the shade is a visual
 * event and nothing more, so a phone in a gym bag learns about it whenever it next happens to
 * wake. An exact alarm is what turns the deadline into something the device gets up for. And
 * an alarm on its own leaves a lifter with no way to see how long is left without unlocking,
 * which is most of what a rest timer is for.
 *
 * Implemented in `:core:data`. Nothing about channels, pending intents or `AlarmManager` is
 * decided here.
 */
interface BackgroundRestTimer {

    /**
     * Brings the notification and the alarm into line with [rest].
     *
     * Idempotent, and safe to call as often as the state changes — which is every command the
     * user gives the timer. What each rest means:
     *
     * - **null**, or [RestTimerSnapshot.Idle] — there is no rest. Both come down.
     * - [RestTimerSnapshot.Running] — an ongoing note counting down to the deadline, and an
     *   alarm set for it.
     * - [RestTimerSnapshot.Paused] — the note stays, frozen at the remainder, and the alarm is
     *   **cancelled**. A paused rest that still fires is the worst outcome this class has: the
     *   phone announces the end of a countdown the user stopped.
     * - [RestTimerSnapshot.Finished] — the rest has just run out *in this process*. The alarm
     *   is cancelled (a rest shortened past zero has one still pending for its original
     *   deadline) and the expiry is announced, or not, by the same rule the alarm itself
     *   follows — see the note on double-alerting below.
     *
     * ### Announcing exactly once
     *
     * The expiry can arrive twice: from the alarm, and from the countdown in this process
     * reaching zero. Both funnel into the same decision, which is taken against the *process*
     * lifecycle rather than against whether an activity happens to exist: with the app in
     * front of the user, the in-app alert says it and nothing is posted; with the app away,
     * the notification says it. Whichever of the two arrives second computes the same answer
     * and overwrites the first with it, so the ordering between them does not matter.
     *
     * Nothing is posted at all when the user has turned the rest timer notification off, or
     * has not granted the permission — both are `Notifier`'s to enforce, and an alarm is not
     * scheduled for a notification that would not be posted.
     */
    suspend fun set(rest: BackgroundRest?)
}

/**
 * "Skip rest", pressed on the notification rather than in the app.
 *
 * ### Why a stream rather than a call into the session
 *
 * Because the two things that have to happen are owned by different places and only one of
 * them is always there. The *durable* half — the draft on disk forgetting its countdown — is
 * done by whoever handles the button, since it must work with every screen of this app
 * destroyed. The *live* half is `ActiveSessionViewModel`'s: it holds the running
 * [RestTimerController], and nothing else can stop it. A screen that is not on the stack has
 * no ViewModel to tell, which is exactly the case a broadcast handled by a repository call
 * would have to invent an answer for.
 *
 * So the press is published, and whoever is listening acts. Nobody listening is a normal
 * outcome and not a lost event: the draft has already been written, and the next launch reads
 * a rest that is over.
 */
interface RestSkipRequests {

    /**
     * The sessions whose rest has been skipped from a notification.
     *
     * Hot, and with no replay: a press is a thing that happened at a moment, and a subscriber
     * arriving afterwards must not act on it — by then the draft is the truth, and re-playing
     * it would cancel a rest the user has since started.
     */
    val skipped: Flow<Long>

    /** Publishes a press. Returns once every current subscriber has been handed it. */
    suspend fun skip(sessionId: Long)
}
