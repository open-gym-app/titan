package com.opengym.core.data.notification

import android.content.Context
import android.content.Intent
import com.opengym.core.data.R
import com.opengym.core.domain.model.FinishOrigin
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.navigation.DeepLinks
import com.opengym.core.domain.notification.AppNotification
import com.opengym.core.domain.notification.NotificationAction
import com.opengym.core.domain.notification.NotificationCommand
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationTarget
import com.opengym.core.domain.notification.Notifier
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.session.IdleSessionMonitor
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.FinishSessionUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The idle nudge going off, and either of its buttons being pressed.
 *
 * Plumbing only — the handling is [IdleSessionBroadcasts], reached through an entry point for
 * the reason `RestTimerReceiver` reaches its own that way: a class the system instantiates by
 * name is the one shape a test cannot construct with its own doubles, and everything worth
 * testing here is on the other side of it.
 */
class IdleSessionReceiver : SuspendingBroadcastReceiver() {

    override suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, IdleSessionEntryPoint::class.java)
            .idleSessionBroadcasts()
            .handle(intent)
    }
}

/** How [IdleSessionReceiver] reaches the graph from inside `onReceive`. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IdleSessionEntryPoint {
    fun idleSessionBroadcasts(): IdleSessionBroadcasts
}

/**
 * What [IdleSessionReceiver] does, with none of the reasons it cannot be tested.
 *
 * ### Everything here runs with no screen of the app alive
 *
 * That is the case it exists for: a session abandoned at the gym is one whose process has long
 * since been killed. So the state machine is `Idle` when a button is pressed, and every
 * lifecycle use case reads its session from there — which is why [attach] comes first. It is
 * the same recovery `ActiveSessionViewModel.reattach` performs on open, for the same reason:
 * the row is the authority on what is in progress, and the state machine has forgotten.
 *
 * ### Both buttons are safe to press twice
 *
 * A double tap, or a tap racing the user finishing the workout in the app. Nothing here
 * arranges that; the session lifecycle already does. After a finish the row is no longer in
 * progress, so [ResumeSessionUseCase] has nothing to adopt and [attach] refuses — and in the
 * same process the state is `SessionState.Finished`, whose open session id is null, so the
 * finish itself refuses too. The completion pipeline is never reached a second time, which is
 * what makes "awards nothing on a second invocation" true rather than hoped for.
 */
@Singleton
class IdleSessionBroadcasts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val idleSessionMonitor: IdleSessionMonitor,
    private val notifier: Notifier,
    private val resumeSession: ResumeSessionUseCase,
    private val finishSession: FinishSessionUseCase,
    private val discardSession: DiscardSessionUseCase,
) {

    suspend fun handle(intent: Intent) {
        idleSessionOf(intent)?.let { sessionId ->
            nudge(sessionId)
            return
        }

        when (val command = NotificationCommands.of(intent)) {
            // Said explicitly, because this is the one caller for which it is not the default:
            // nothing here navigates to a recap, so what the session earned is announced by the
            // notification this origin permits or by nothing at all. See `FinishOrigin`.
            is NotificationCommand.FinishSession ->
                close(command.sessionId) { finishSession(FinishOrigin.NOTIFICATION_ACTION) }

            is NotificationCommand.DiscardSession -> close(command.sessionId) { discardSession() }

            // Not this receiver's, or from a build that knew something this one does not — a
            // notification left in the shade across an upgrade. Neither is worth failing over.
            is NotificationCommand.SkipRest, null -> Unit
        }
    }

    /**
     * Says the session has gone quiet, unless it has not.
     *
     * The session is re-read here rather than trusted from the alarm's extras, because thirty
     * minutes is long enough for it to have been finished, discarded, or replaced by another
     * one — and a nudge about a workout that is over is worse than none. The set count is read
     * for the same reason: it decides which of the two honest offers the second button makes.
     */
    private suspend fun nudge(sessionId: Long) {
        val session = sessionRepository.getActive().first()?.takeIf { it.id == sessionId }
        if (session == null) {
            notifier.cancel(NotificationKind.SESSION)
            return
        }

        val logged = sessionRepository.getSetsForSession(sessionId).first().count { it.isCompleted }
        val deepLink = DeepLinks.activeSession(sessionId)

        notifier.post(
            AppNotification(
                kind = NotificationKind.SESSION,
                title = context.getString(R.string.idle_session_title),
                body = idleBody(session.name, logged),
                // Not ongoing, unlike the rest countdown. A rest note describes something the
                // app is in the middle of and takes itself down when it ends; this is a
                // question, and a question the user may answer by deciding to ignore it. One
                // they cannot swipe away is one that gets the channel muted instead.
                deepLink = deepLink,
                actions = listOf(
                    NotificationAction(
                        label = context.getString(R.string.idle_session_resume),
                        target = NotificationTarget.Screen(deepLink),
                    ),
                    // The two honest outcomes, and only ever one of them: a session with
                    // nothing in it cannot be finished — `FinishSessionUseCase` refuses it —
                    // and there is no history worth writing for a workout that never started.
                    when {
                        logged > 0 -> NotificationAction(
                            label = context.getString(R.string.idle_session_finish),
                            target = NotificationTarget.Command(NotificationCommand.FinishSession(sessionId)),
                        )

                        else -> NotificationAction(
                            label = context.getString(R.string.idle_session_discard),
                            target = NotificationTarget.Command(NotificationCommand.DiscardSession(sessionId)),
                        )
                    },
                ),
            ),
        )
    }

    /**
     * Runs one of the two ways out of a session, having first got the state machine back over
     * it.
     *
     * The nudge comes down before the work rather than after: the user pressed a button, and
     * the notification going away is the acknowledgement. If the transition is then refused —
     * the session was finished in the app a second earlier — the right outcome is still a note
     * that has gone, because what it was offering is no longer available.
     *
     * Wrapped, because this runs in a process the user did not open. A store that refuses is not
     * worth a crash they would see as the app breaking on its own.
     */
    private suspend fun close(
        sessionId: Long,
        transition: suspend () -> Result<*>,
    ) {
        idleSessionMonitor.disarm()

        runCatching {
            if (attach(sessionId)) transition()
        }
    }

    /**
     * Puts the state machine over [sessionId], and answers whether it got there.
     *
     * Three cases, and only the first is the ordinary one:
     *
     * - nothing open, which is every press that arrives at a cold process: the in-progress row
     *   is adopted through [ResumeSessionUseCase], and the id is checked afterwards because
     *   that use case adopts whatever row it finds rather than the one asked for;
     * - this session already open, from a press that arrived while the app was running;
     * - a *different* session open, which means the notification outlived the workout it was
     *   posted for. Refused — closing out somebody's current workout because they pressed a
     *   button about the last one is the worst thing this class could do.
     */
    private suspend fun attach(sessionId: Long): Boolean {
        val open = when (val state = sessionStateRepository.observe().first()) {
            is SessionState.Active -> state.sessionId
            is SessionState.Paused -> state.sessionId
            SessionState.Idle, is SessionState.Finished -> null
        }

        if (open != null) return open == sessionId
        return resumeSession().getOrNull()?.sessionId == sessionId
    }

    /** "Push day · 12 sets logged, nothing since", with the name left out where there is none. */
    private fun idleBody(
        name: String?,
        logged: Int,
    ): String {
        val activity = when (logged) {
            // Its own sentence rather than the plural's zero case; see the strings file.
            0 -> context.getString(R.string.idle_session_nothing_logged)

            else -> context.resources.getQuantityString(R.plurals.idle_session_sets, logged, logged)
        }

        return when (name) {
            // A session started empty and never named — the body is the activity line alone
            // rather than a separator with nothing on one side of it.
            null -> activity

            else -> context.getString(R.string.idle_session_body, name, activity)
        }
    }
}
