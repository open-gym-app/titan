package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.notification.NotificationCommand
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.timer.BackgroundRest
import com.titan.core.domain.timer.BackgroundRestTimer
import com.titan.core.domain.timer.RestSkipRequests
import com.titan.core.domain.usecase.SessionLifecycleLock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds
import com.titan.core.domain.timer.RestTimerSnapshot as Snapshot

/**
 * What arrives at the app when the phone is in a pocket: the alarm going off, and "Skip rest"
 * pressed on the lock screen.
 *
 * Every test here runs with **nothing else of the app alive** — no ViewModel collecting, no
 * screen on the stack — because that is the case the whole design is for. A skip that only
 * worked while the session screen happened to exist would pass a manual test and fail the one
 * time it mattered.
 *
 * `SessionRepository` is mocked for the reason `AndroidIdleSessionMonitorTest` gives: one of its
 * members is read here, and a fake would be twenty stubs around it. Unless a test says otherwise
 * no session row is in progress, so the draft is what says which workout is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestTimerBroadcastsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val restTimer = RecordingBackgroundRestTimer()
    private val skipRequests = TestRestSkipRequests()
    private val drafts = FakeDraftStore(draft(rest = Snapshot.Running(DEADLINE, 90.seconds)))
    private val sessions = mockk<SessionRepository> { every { getActive() } returns flowOf(null) }

    private val broadcasts = RestTimerBroadcasts(restTimer, skipRequests, drafts, sessions)

    // --- the alarm ----------------------------------------------------------

    @Test
    fun `the alarm hands the expired rest straight back`() =
        runTest {
            broadcasts.handle(restExpiredBroadcast(context, REST))

            // Whether that is announced or quietly cleared is the scheduler's decision, taken
            // against the process lifecycle. This side only says the rest is over.
            assertEquals(listOf(REST.copy(rest = Snapshot.Finished)), restTimer.published)
        }

    @Test
    fun `what the note says survives the trip through the alarm`() =
        runTest {
            // The extras are the whole of what survives: the alarm can fire at a process that
            // has been dead for the length of the rest.
            broadcasts.handle(restExpiredBroadcast(context, REST))

            val expired = restTimer.published.single()!!
            assertEquals("Bench press", expired.exerciseName)
            assertEquals(3, expired.setNumber)
            assertEquals(SESSION_ID, expired.sessionId)
            assertEquals(STARTED_AT, expired.sessionCreatedAt)
        }

    @Test
    fun `a rest that follows no set survives the trip without one`() =
        runTest {
            val untied = REST.copy(exerciseName = null, setNumber = null)

            broadcasts.handle(restExpiredBroadcast(context, untied))

            assertEquals(listOf(untied.copy(rest = Snapshot.Finished)), restTimer.published)
        }

    @Test
    fun `an alarm carrying no rest is ignored rather than acted on`() =
        runTest {
            // The cancellation intent is built without extras on purpose — `AlarmManager`
            // matches on action and component — so an empty one must not be mistaken for a rest.
            broadcasts.handle(restExpiredBroadcast(context, rest = null))

            assertEquals(emptyList<BackgroundRest?>(), restTimer.published)
        }

    // --- an expiry about a workout that is not open --------------------------

    /** The workout was erased: no draft and no session in progress. Nothing may be said. */
    @Test
    fun `an expiry for a session that no longer exists only takes the note down`() =
        runTest {
            drafts.clear()

            broadcasts.handle(restExpiredBroadcast(context, REST))

            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
        }

    /** A restore wrote another workout back under the same id. The number matches; the workout does not. */
    @Test
    fun `an expiry for an id another workout now carries only takes the note down`() =
        runTest {
            drafts.save(ActiveSession(sessionId = SESSION_ID, startedAt = OTHER_STARTED_AT))
            every { sessions.getActive() } returns flowOf(SESSION.copy(createdAt = OTHER_STARTED_AT))

            broadcasts.handle(restExpiredBroadcast(context, REST))

            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
        }

    /** The draft is written a moment after the screen changes. The row is the authority. */
    @Test
    fun `an expiry for the session in progress is announced with no draft written yet`() =
        runTest {
            drafts.clear()
            every { sessions.getActive() } returns flowOf(SESSION)

            broadcasts.handle(restExpiredBroadcast(context, REST))

            assertEquals(listOf(REST.copy(rest = Snapshot.Finished)), restTimer.published)
        }

    /** An alarm set before the row was read names the session by its id alone, as before. */
    @Test
    fun `an expiry with no creation time matches the open workout by its id`() =
        runTest {
            val untimed = REST.copy(sessionCreatedAt = null)

            broadcasts.handle(restExpiredBroadcast(context, untimed))

            assertEquals(listOf(untimed.copy(rest = Snapshot.Finished)), restTimer.published)
        }

    /** A store that failed once is no evidence the workout is gone, and the expiry is the feature. */
    @Test
    fun `an expiry is still announced when the session cannot be read`() =
        runTest {
            drafts.loadFailure = IllegalStateException("the draft is unreadable")
            every { sessions.getActive() } returns flow { throw IllegalStateException("the database is locked") }

            broadcasts.handle(restExpiredBroadcast(context, REST))

            assertEquals(listOf(REST.copy(rest = Snapshot.Finished)), restTimer.published)
        }

    // --- skipping from the notification -------------------------------------

    @Test
    fun `skipping takes the note and the alarm down`() =
        runTest {
            broadcasts.handle(skipIntent(SESSION_ID))

            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
        }

    @Test
    fun `skipping writes the rest out of the draft, with no screen alive to do it`() =
        runTest {
            broadcasts.handle(skipIntent(SESSION_ID))

            // The durable half. Without it the app reopens counting down a rest the user ended
            // on their lock screen twenty minutes ago.
            assertEquals(Snapshot.Idle, drafts.load()?.restTimer)
        }

    @Test
    fun `skipping tells a screen that is listening`() =
        runTest {
            val heard = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                skipRequests.skipped.collect { heard += it }
            }

            broadcasts.handle(skipIntent(SESSION_ID))

            assertEquals(listOf(SESSION_ID), heard)
        }

    @Test
    fun `a press for a session that is not the one running leaves its draft alone`() =
        runTest {
            // A notification can outlive the workout it was posted for. Clearing the rest of
            // whatever draft happens to be there would cancel a rest in a different workout.
            broadcasts.handle(skipIntent(SESSION_ID + 1))

            assertEquals(Snapshot.Running(DEADLINE, 90.seconds), drafts.load()?.restTimer)
        }

    /**
     * A restore wrote another workout back under the same id. The stale note comes down, which
     * is what the user asked for, and the workout that now carries the number keeps its rest.
     */
    @Test
    fun `a press for an id another workout now carries only takes the note down`() =
        runTest {
            val heard = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                skipRequests.skipped.collect { heard += it }
            }

            broadcasts.handle(skipIntent(SessionRef(SESSION_ID, createdAt = OTHER_STARTED_AT)))

            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
            assertEquals(Snapshot.Running(DEADLINE, 90.seconds), drafts.load()?.restTimer)
            assertEquals(emptyList<Long>(), heard)
        }

    /** A note posted by an earlier build carries no creation time, and its button still works. */
    @Test
    fun `a press with no creation time still ends the open workout's rest`() =
        runTest {
            broadcasts.handle(skipIntent(SessionRef(SESSION_ID)))

            assertEquals(Snapshot.Idle, drafts.load()?.restTimer)
        }

    @Test
    fun `a press with no draft at all is not an error`() =
        runTest {
            val empty = FakeDraftStore(null)

            RestTimerBroadcasts(restTimer, skipRequests, empty, sessions).handle(skipIntent(SESSION_ID))

            assertNull(empty.load())
            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
        }

    @Test
    fun `a draft that cannot be read still gets the note taken down`() =
        runTest {
            // The feedback the user asked for comes first and does not depend on a store. The
            // alternative is a button that appears to do nothing because a file was locked.
            val broken = FakeDraftStore(draft(Snapshot.Running(DEADLINE, 90.seconds)))
            broken.loadFailure = IllegalStateException("the draft is unreadable")

            RestTimerBroadcasts(restTimer, skipRequests, broken, sessions).handle(skipIntent(SESSION_ID))

            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
        }

    // --- during a wipe -------------------------------------------------------

    /**
     * A delete all or a restore takes the rest down before it clears the draft. An expiry that
     * asked in between would still be told yes; it waits for the wipe instead, and then finds
     * nothing open.
     */
    @Test
    fun `an expiry delivered during a wipe waits for it and announces nothing`() =
        runTest {
            val wipeMayFinish = CompletableDeferred<Unit>()
            launch {
                SessionLifecycleLock.SHARED.withTransition {
                    restTimer.set(null)
                    wipeMayFinish.await()
                    drafts.clear()
                }
            }
            runCurrent()

            launch { broadcasts.handle(restExpiredBroadcast(context, REST)) }
            runCurrent()
            wipeMayFinish.complete(Unit)
            runCurrent()

            assertEquals(listOf<BackgroundRest?>(null, null), restTimer.published)
        }

    /** The note comes down at once; the draft is left to the wipe, so it cannot be saved back. */
    @Test
    fun `a press during a wipe takes the note down and leaves the draft to it`() =
        runTest {
            val wipeMayFinish = CompletableDeferred<Unit>()
            launch {
                SessionLifecycleLock.SHARED.withTransition {
                    wipeMayFinish.await()
                    drafts.clear()
                }
            }
            runCurrent()

            launch { broadcasts.handle(skipIntent(SESSION_ID)) }
            runCurrent()

            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
            assertEquals(Snapshot.Running(DEADLINE, 90.seconds), drafts.load()?.restTimer)

            wipeMayFinish.complete(Unit)
            runCurrent()

            assertNull(drafts.load())
        }

    // --- anything else ------------------------------------------------------

    @Test
    fun `a broadcast this build does not understand does nothing`() =
        runTest {
            broadcasts.handle(Intent("com.titan.SOMETHING_ELSE"))

            assertEquals(emptyList<BackgroundRest?>(), restTimer.published)
            assertEquals(Snapshot.Running(DEADLINE, 90.seconds), drafts.load()?.restTimer)
        }

    private fun skipIntent(sessionId: Long): Intent = skipIntent(SessionRef(sessionId, createdAt = STARTED_AT))

    private fun skipIntent(session: SessionRef): Intent =
        NotificationCommands.intent(context, NotificationCommand.SkipRest(session))

    private fun draft(rest: Snapshot) = ActiveSession(sessionId = SESSION_ID, startedAt = STARTED_AT, restTimer = rest)

    /** [BackgroundRestTimer] as a list of what it was told. */
    private class RecordingBackgroundRestTimer : BackgroundRestTimer {
        val published = mutableListOf<BackgroundRest?>()

        override suspend fun set(rest: BackgroundRest?) {
            published += rest
        }
    }

    /** The real channel's shape: hot, unbuffered, dropped when nobody is listening. */
    private class TestRestSkipRequests : RestSkipRequests {
        private val requests = MutableSharedFlow<Long>()

        override val skipped: Flow<Long> = requests

        override suspend fun skip(sessionId: Long) = requests.emit(sessionId)
    }

    /** The draft slot, in memory, with a failure a test can arm. */
    private class FakeDraftStore(private var draft: ActiveSession?) : InProgressSessionRepository {

        var loadFailure: Throwable? = null

        override suspend fun save(session: ActiveSession) {
            draft = session
        }

        override suspend fun load(): ActiveSession? {
            loadFailure?.let { throw it }
            return draft
        }

        override suspend fun clear() {
            draft = null
        }
    }

    private companion object {
        const val SESSION_ID = 7L
        const val STARTED_AT = 1_700_000_000_000L
        const val OTHER_STARTED_AT = 1_690_000_000_000L
        const val DEADLINE = 1_700_000_090_000L

        val REST = BackgroundRest(
            sessionId = SESSION_ID,
            exerciseName = "Bench press",
            setNumber = 3,
            rest = Snapshot.Running(DEADLINE, 90.seconds),
            sessionCreatedAt = STARTED_AT,
        )

        /** The draft's session as its row, in progress. */
        val SESSION = WorkoutSession(
            id = SESSION_ID,
            lastActiveAt = STARTED_AT,
            createdAt = STARTED_AT,
            updatedAt = STARTED_AT,
        )
    }
}
