package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.notification.NotificationCommand
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.timer.BackgroundRestTimer
import com.titan.core.domain.timer.RestSkipRequests
import com.titan.core.domain.timer.RestTimerSnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two things that can happen to a rest while nothing of this app is on screen: it runs out,
 * and the user skips it from the notification.
 *
 * ### Why a receiver rather than a service
 *
 * Both are a few milliseconds of work with no user interface. A broadcast receiver is what the
 * platform provides for that, it can be started from a cold process, and "skip rest" pressed on
 * a lock screen must **not** open an activity — the user said they were going back under the
 * bar, not that they wanted to look at their phone.
 *
 * ### There is nothing here but plumbing
 *
 * The handling is [RestTimerBroadcasts], reached through an entry point rather than injected
 * into this class with `@AndroidEntryPoint`. Two reasons, and the second is the one that
 * decided it: a receiver's dependencies have to be fetched inside `onReceive` anyway, since
 * that is the first moment a `Context` exists; and a class the system instantiates by name is
 * the one shape a test cannot construct with its own doubles. Everything worth testing is on
 * the other side of the entry point.
 */
class RestTimerReceiver : SuspendingBroadcastReceiver() {

    override suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, RestTimerEntryPoint::class.java)
            .restTimerBroadcasts()
            .handle(intent)
    }
}

/** How [RestTimerReceiver] reaches the graph from inside `onReceive`. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RestTimerEntryPoint {
    fun restTimerBroadcasts(): RestTimerBroadcasts
}

/**
 * What [RestTimerReceiver] does, with none of the reasons it cannot be tested.
 *
 * ### Both handlers must work with every screen of this app destroyed
 *
 * That is the whole test of this class. A broadcast is delivered to the app's process whether
 * or not anything else of it is alive, so the graph is here and a `ViewModel` may not be. The
 * skip therefore does its durable half itself — the draft forgets the countdown — and
 * *publishes* the press for a live screen to act on if there is one. See [RestSkipRequests] for
 * why those are two separate jobs rather than one call.
 *
 * ### Only about the workout that is open
 *
 * The intent names a session, and by the time it arrives that session may be gone: erased by
 * "delete all data", or replaced by a restore that wrote another workout back under the same id.
 * Both of those take the note and the alarm down, so this is the second line rather than the
 * first — but an alarm already being delivered when they ran, or a note from a build that did not
 * take them down, still lands here. So both handlers first ask [isOpenWorkout], and an expiry for
 * any other session is dropped: a "Rest over" for a workout that no longer exists, with a link to
 * an id that now means nothing or something else, is worse than silence.
 */
@Singleton
class RestTimerBroadcasts @Inject constructor(
    private val backgroundRestTimer: BackgroundRestTimer,
    private val skipRequests: RestSkipRequests,
    private val drafts: InProgressSessionRepository,
    private val sessions: SessionRepository,
) {

    suspend fun handle(intent: Intent) {
        expiredRestOf(intent)?.let { expired ->
            // The rest ran out. Whether that is announced or quietly cleared is
            // `AndroidBackgroundRestTimer`'s decision, taken against the process lifecycle —
            // the same decision the in-process countdown reaches, so the two cannot disagree.
            if (isOpenWorkout(expired.session)) backgroundRestTimer.set(expired)
            return
        }

        when (val command = NotificationCommands.of(intent)) {
            is NotificationCommand.SkipRest -> skip(command.session)

            // Not this receiver's — the session commands go to `IdleSessionReceiver` — or from
            // a build that knew something this one does not, as a notification left in the
            // shade across an upgrade would be. Neither is worth failing over.
            is NotificationCommand.FinishSession,
            is NotificationCommand.DiscardSession,
            null,
            -> Unit
        }
    }

    /**
     * Ends the rest, in both places it exists.
     *
     * The notification and the alarm go first, and whatever session the press names, because
     * that is the feedback: the user pressed a button and the note should be gone before
     * anything reads a file.
     *
     * Then, if [session] is the workout that is open, the press is published, for a screen that
     * is alive to stop its own countdown — and then the draft is written, so that a rest skipped
     * with nothing running does not come back counting when the app is next opened. Both happen
     * whether or not a screen is listening: a live screen writes the same `Idle` through its own
     * save, and two writes of one value are not worth a conditional that would have to guess
     * whether anybody was listening. A press about any other session stops there, so it cannot
     * end the rest of a workout that happens to carry the same id.
     */
    private suspend fun skip(session: SessionRef) {
        backgroundRestTimer.set(null)
        if (!isOpenWorkout(session)) return
        skipRequests.skip(session.sessionId)
        forgetRest(session)
    }

    /**
     * Rewrites the draft with its countdown cleared, if it is the draft of [session].
     *
     * Guarded because the notification can outlive the workout it was posted for: a session
     * finished in the app while a stale note sat in the shade is reachable, and clearing the rest
     * of *whatever draft happens to be there* would cancel a rest in a different workout.
     *
     * Best-effort. The alternative to swallowing a read failure here is crashing a process the
     * user did not open, over a draft that will be rewritten by the next thing they do.
     */
    private suspend fun forgetRest(session: SessionRef) {
        runCatching {
            val draft = drafts.load()
            if (draft != null && draft.isOf(session) && draft.restTimer != RestTimerSnapshot.Idle) {
                drafts.save(draft.copy(restTimer = RestTimerSnapshot.Idle))
            }
        }
    }

    /**
     * Whether [session] is the workout this device has open: the one the draft was written for,
     * or the session row still in progress.
     *
     * The draft first, because it is a file read and it is there for every workout with a rest
     * worth announcing. The row is the authority when the draft does not say yes — the draft is
     * written a moment after the screen changes, and a workout whose draft write failed is still
     * in progress.
     *
     * A row that cannot be read counts as a yes. The expiry is the whole of the feature, a store
     * that failed once is no evidence the workout is gone, and the cost of being wrong that way
     * is the announcement this would have made before it asked.
     */
    private suspend fun isOpenWorkout(session: SessionRef): Boolean {
        val draft = runCatching { drafts.load() }.getOrNull()
        if (draft != null && draft.isOf(session)) return true
        return runCatching { sessions.getActive().first() }
            .fold(onSuccess = { it != null && session.matches(it) }, onFailure = { true })
    }

    /**
     * Whether this draft is of [session]. A draft's `startedAt` is its session row's
     * `created_at` — every writer copies it from there — so it carries the same token.
     */
    private fun ActiveSession.isOf(session: SessionRef): Boolean =
        sessionId == session.sessionId && (session.createdAt == null || startedAt == session.createdAt)
}
