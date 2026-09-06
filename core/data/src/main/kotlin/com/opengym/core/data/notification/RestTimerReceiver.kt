package com.opengym.core.data.notification

import android.content.Context
import android.content.Intent
import com.opengym.core.domain.notification.NotificationCommand
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.timer.BackgroundRestTimer
import com.opengym.core.domain.timer.RestSkipRequests
import com.opengym.core.domain.timer.RestTimerSnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
 */
@Singleton
class RestTimerBroadcasts @Inject constructor(
    private val backgroundRestTimer: BackgroundRestTimer,
    private val skipRequests: RestSkipRequests,
    private val drafts: InProgressSessionRepository,
) {

    suspend fun handle(intent: Intent) {
        expiredRestOf(intent)?.let { expired ->
            // The rest ran out. Whether that is announced or quietly cleared is
            // `AndroidBackgroundRestTimer`'s decision, taken against the process lifecycle —
            // the same decision the in-process countdown reaches, so the two cannot disagree.
            backgroundRestTimer.set(expired)
            return
        }

        when (val command = NotificationCommands.of(intent)) {
            is NotificationCommand.SkipRest -> skip(command.sessionId)

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
     * The notification and the alarm go first, because that is the feedback: the user pressed a
     * button and the note should be gone before anything reads a file.
     *
     * Then the press is published, for a screen that is alive to stop its own countdown — and
     * then the draft is written, so that a rest skipped with nothing running does not come back
     * counting when the app is next opened. Both happen unconditionally: a live screen writes
     * the same `Idle` through its own save, and two writes of one value are not worth a
     * conditional that would have to guess whether anybody was listening.
     */
    private suspend fun skip(sessionId: Long) {
        backgroundRestTimer.set(null)
        skipRequests.skip(sessionId)
        forgetRest(sessionId)
    }

    /**
     * Rewrites the draft with its countdown cleared, if it is the draft of [sessionId].
     *
     * Guarded on the id because the notification can outlive the workout it was posted for: a
     * session finished in the app while a stale note sat in the shade is reachable, and
     * clearing the rest of *whatever draft happens to be there* would cancel a rest in a
     * different workout.
     *
     * Best-effort. The alternative to swallowing a read failure here is crashing a process the
     * user did not open, over a draft that will be rewritten by the next thing they do.
     */
    private suspend fun forgetRest(sessionId: Long) {
        runCatching {
            val draft = drafts.load()
            if (draft != null && draft.sessionId == sessionId && draft.restTimer != RestTimerSnapshot.Idle) {
                drafts.save(draft.copy(restTimer = RestTimerSnapshot.Idle))
            }
        }
    }
}
