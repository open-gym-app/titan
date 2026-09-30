package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.exercise
import com.titan.core.data.db.session
import com.titan.core.data.db.sessionExercise
import com.titan.core.data.db.workoutSet
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.data.repository.SessionStateRepositoryImpl
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.notification.NotificationCommand
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationOutcome
import com.titan.core.domain.notification.NotificationTarget
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.session.IdleSessionMonitor
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.DiscardSessionUseCase
import com.titan.core.domain.usecase.FinishSessionUseCase
import com.titan.core.domain.usecase.ResumeSessionUseCase
import com.titan.core.domain.usecase.SessionLifecycleLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The nudge going off, and both of its buttons, over the database they actually change.
 *
 * ### Why a real database rather than doubles
 *
 * Because the claim worth testing is not "the receiver called the finish use case" — it is that
 * a button pressed at a process with **no screen of the app alive** closes a workout out, and
 * that pressing it again does nothing. Both of those are properties of the session lifecycle
 * reading its own state back: the state machine is in memory and therefore `Idle` here, the row
 * is the authority, and a second press finds no row in progress to adopt. A mocked repository
 * would let all of that pass while agreeing with itself.
 *
 * `SessionCompletionPipeline` is wired as null throughout, which is how the app wires it for a
 * session finished any other way too. That the cascade itself pays once per session is
 * `SessionCompletionCascadeTest`'s subject, over this same database; what is asserted here is
 * that this path reaches the close-out exactly once, which is the only thing it can get wrong.
 *
 * `@Config` without `manifest = Config.NONE`, unlike [RoomTestBase]'s own: the nudge's text
 * comes out of this module's `strings.xml`, and a test with no resources cannot read it.
 */
@Config(sdk = [34])
class IdleSessionBroadcastsTest : RoomTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = RecordingNotifier()
    private val monitor = RecordingIdleSessionMonitor()
    private val drafts = FakeDraftStore()
    private var now = STARTED_AT

    private val sessions: SessionRepository by lazy {
        SessionRepositoryImpl(
            database = db,
            sessionMapper = SessionMapper(),
            sessionExerciseMapper = SessionExerciseMapper(),
            setMapper = WorkoutSetMapper(),
        )
    }

    private val states = SessionStateRepositoryImpl()

    // A lock of this suite's own rather than the process-wide default, matching the domain's
    // own integration tests: a shared mutex is not something a test should hold against every
    // other suite in the JVM.
    private val lock = SessionLifecycleLock()

    private val broadcasts by lazy {
        IdleSessionBroadcasts(
            context = context,
            sessionRepository = sessions,
            sessionStateRepository = states,
            idleSessionMonitor = monitor,
            notifier = notifier,
            resumeSession = ResumeSessionUseCase(sessions, states, Clock { now }, lock),
            finishSession = FinishSessionUseCase(sessions, states, drafts, Clock { now }, lock),
            discardSession = DiscardSessionUseCase(sessions, states, drafts, lock),
        )
    }

    // --- the nudge ----------------------------------------------------------

    @Test
    fun `an idle session is announced, with a way back and a way out`() =
        runBlocking {
            val sessionId = openSession(completedSets = 12)

            broadcasts.handle(idleSessionBroadcast(context, sessionId))

            val note = notifier.showing
            assertNotNull(note)
            assertEquals(NotificationKind.SESSION, note!!.kind)
            assertEquals("Workout still open", note.title)
            assertEquals("titan://session/active/$sessionId", note.deepLink)
            assertEquals(
                listOf(
                    NotificationTarget.Screen("titan://session/active/$sessionId"),
                    NotificationTarget.Command(NotificationCommand.FinishSession(sessionId)),
                ),
                note.actions.map { it.target },
            )
        }

    @Test
    fun `the nudge says what is in the session`() =
        runBlocking {
            val sessionId = openSession(completedSets = 12, name = "Push day")

            broadcasts.handle(idleSessionBroadcast(context, sessionId))

            assertEquals("Push day · 12 sets logged, nothing since", notifier.showing?.body)
        }

    @Test
    fun `a session nobody named says only what is in it`() =
        runBlocking {
            val sessionId = openSession(completedSets = 1, name = null)

            broadcasts.handle(idleSessionBroadcast(context, sessionId))

            // No separator with nothing on one side of it.
            assertEquals("1 set logged, nothing since", notifier.showing?.body)
        }

    @Test
    fun `a session with nothing in it is offered a discard rather than a finish`() =
        runBlocking {
            // A zero-set session is not worth writing to history — and `FinishSessionUseCase`
            // refuses it anyway, so offering "Finish now" would be offering a button that
            // cannot work.
            val sessionId = openSession(completedSets = 0)

            broadcasts.handle(idleSessionBroadcast(context, sessionId))

            assertEquals(
                NotificationTarget.Command(NotificationCommand.DiscardSession(sessionId)),
                notifier.showing?.actions?.last()?.target,
            )
            assertEquals("Push day · Nothing logged yet", notifier.showing?.body)
        }

    @Test
    fun `a session finished in the meantime is not announced`() =
        runBlocking {
            // Thirty minutes is long enough for the workout to have been closed out in the app.
            // A nudge about a session that is over is worse than none.
            val sessionId = openSession(completedSets = 3)
            sessions.update(sessions.getById(sessionId).first()!!.copy(isInProgress = false))

            broadcasts.handle(idleSessionBroadcast(context, sessionId))

            assertTrue(notifier.posted.isEmpty())
            assertEquals(RecordingNotifier.Event.Cancelled(NotificationKind.SESSION), notifier.log.last())
        }

    @Test
    fun `an alarm about a session that has been replaced is ignored`() =
        runBlocking {
            val stale = openSession(completedSets = 3)
            sessions.update(sessions.getById(stale).first()!!.copy(isInProgress = false))
            openSession(completedSets = 1)

            broadcasts.handle(idleSessionBroadcast(context, stale))

            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun `the session preference is what decides whether anything is posted`() =
        runBlocking {
            // `Notifier` owns that gate — see #176. What matters here is that this path goes
            // through it rather than around it.
            notifier.outcome = NotificationOutcome.SUPPRESSED_BY_PREFERENCE
            val sessionId = openSession(completedSets = 3)

            broadcasts.handle(idleSessionBroadcast(context, sessionId))

            assertTrue(notifier.posted.isEmpty())
        }

    // --- finish now ---------------------------------------------------------

    @Test
    fun `finish now closes the session out with no screen of the app alive`() =
        runBlocking {
            val sessionId = openSession(completedSets = 3)
            // What a cold process looks like: the row is in progress and the state machine has
            // never heard of it.
            assertEquals(SessionState.Idle, states.observe().first())

            broadcasts.handle(finish(sessionId))

            val closed = sessions.getById(sessionId).first()!!
            assertFalse(closed.isInProgress)
            assertEquals(now, closed.completedAt)
            assertTrue(states.observe().first() is SessionState.Finished)
        }

    @Test
    fun `a second press closes nothing out again`() =
        runBlocking {
            val sessionId = openSession(completedSets = 3)
            broadcasts.handle(finish(sessionId))
            val closedAt = sessions.getById(sessionId).first()!!.completedAt

            // A double tap, or a tap racing the user finishing in the app. The clock has moved,
            // so a second close-out would be visible.
            now += 1_000
            broadcasts.handle(finish(sessionId))

            assertEquals(closedAt, sessions.getById(sessionId).first()!!.completedAt)
        }

    @Test
    fun `a press that arrives at a process still holding the session finishes it once`() =
        runBlocking {
            val sessionId = openSession(completedSets = 3)
            states.update(SessionState.Active(sessionId = sessionId, startedAt = STARTED_AT))

            broadcasts.handle(finish(sessionId))

            assertFalse(sessions.getById(sessionId).first()!!.isInProgress)
        }

    @Test
    fun `a press about one session does not close out a different one that is running`() =
        runBlocking {
            // The notification outlived the workout it was posted for. Closing out somebody's
            // current workout because they pressed a button about the last one is the worst
            // thing this class could do.
            val stale = openSession(completedSets = 3)
            sessions.update(sessions.getById(stale).first()!!.copy(isInProgress = false))
            val running = openSession(completedSets = 1)
            states.update(SessionState.Active(sessionId = running, startedAt = STARTED_AT))

            broadcasts.handle(finish(stale))

            assertTrue(sessions.getById(running).first()!!.isInProgress)
        }

    @Test
    fun `pressing a button takes the nudge down`() =
        runBlocking {
            val sessionId = openSession(completedSets = 3)

            broadcasts.handle(finish(sessionId))

            // The acknowledgement, and it happens whether or not the transition then applies:
            // what the note was offering is gone either way.
            assertEquals(listOf(RecordingIdleSessionMonitor.Call.DISARMED), monitor.calls)
        }

    // --- discard ------------------------------------------------------------

    @Test
    fun `discard leaves no row in history`() =
        runBlocking {
            val sessionId = openSession(completedSets = 0)

            broadcasts.handle(discard(sessionId))

            assertNull(sessions.getById(sessionId).first())
            assertEquals(0, countRows("workout_set"))
            assertEquals(SessionState.Idle, states.observe().first())
        }

    @Test
    fun `a second discard is not an error`() =
        runBlocking {
            val sessionId = openSession(completedSets = 0)
            broadcasts.handle(discard(sessionId))

            broadcasts.handle(discard(sessionId))

            assertNull(sessions.getById(sessionId).first())
        }

    // --- anything else ------------------------------------------------------

    @Test
    fun `a broadcast this receiver does not own does nothing`() =
        runBlocking {
            val sessionId = openSession(completedSets = 3)

            // The rest timer's command, delivered here by mistake or by a newer build.
            broadcasts.handle(NotificationCommands.intent(context, NotificationCommand.SkipRest(sessionId)))

            assertTrue(sessions.getById(sessionId).first()!!.isInProgress)
            assertTrue(notifier.log.isEmpty())
        }

    @Test
    fun `an alarm carrying no session is ignored rather than acted on`() =
        runBlocking {
            openSession(completedSets = 3)

            // The cancellation intent is built without extras — `AlarmManager` matches on action
            // and component — so an empty one must not be mistaken for a session.
            broadcasts.handle(idleSessionBroadcast(context, sessionId = null))

            assertTrue(notifier.log.isEmpty())
        }

    private fun finish(sessionId: Long): Intent =
        NotificationCommands.intent(context, NotificationCommand.FinishSession(sessionId))

    private fun discard(sessionId: Long): Intent =
        NotificationCommands.intent(context, NotificationCommand.DiscardSession(sessionId))

    /** A session in progress with [completedSets] ticked sets against one exercise. */
    private suspend fun openSession(
        completedSets: Int,
        name: String? = "Push day",
    ): Long {
        val sessionId = db.workoutSessionDao().insert(
            session(name = name, isInProgress = true, lastActiveAt = STARTED_AT, createdAt = STARTED_AT),
        )
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionExerciseId = db.sessionExerciseDao().insert(
            sessionExercise(sessionId = sessionId, exerciseId = exerciseId, position = 0),
        )
        repeat(completedSets) { index ->
            db.workoutSetDao().insert(
                workoutSet(
                    sessionExerciseId = sessionExerciseId,
                    setNumber = index + 1,
                    isCompleted = true,
                    completedAt = STARTED_AT + index,
                    reps = 5,
                    weight = 80_000_000,
                ),
            )
        }
        return sessionId
    }

    /** [IdleSessionMonitor] as a list of what it was told. */
    private class RecordingIdleSessionMonitor : IdleSessionMonitor {
        val calls = mutableListOf<Call>()

        override suspend fun arm() {
            calls += Call.ARMED
        }

        override suspend fun disarm() {
            calls += Call.DISARMED
        }

        enum class Call { ARMED, DISARMED }
    }

    /** The draft slot, which the finish and the discard both clear on their way out. */
    private class FakeDraftStore : InProgressSessionRepository {
        private var draft: ActiveSession? = null

        override suspend fun save(session: ActiveSession) {
            draft = session
        }

        override suspend fun load(): ActiveSession? = draft

        override suspend fun clear() {
            draft = null
        }
    }

    private companion object {
        const val STARTED_AT = 1_700_000_000_000L
    }
}
