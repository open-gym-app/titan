package com.opengym.core.data.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.notification.NotificationCommand
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.timer.BackgroundRest
import com.opengym.core.domain.timer.BackgroundRestTimer
import com.opengym.core.domain.timer.RestSkipRequests
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds
import com.opengym.core.domain.timer.RestTimerSnapshot as Snapshot

/**
 * What arrives at the app when the phone is in a pocket: the alarm going off, and "Skip rest"
 * pressed on the lock screen.
 *
 * Every test here runs with **nothing else of the app alive** — no ViewModel collecting, no
 * screen on the stack — because that is the case the whole design is for. A skip that only
 * worked while the session screen happened to exist would pass a manual test and fail the one
 * time it mattered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestTimerBroadcastsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val restTimer = RecordingBackgroundRestTimer()
    private val skipRequests = TestRestSkipRequests()
    private val drafts = FakeDraftStore(draft(rest = Snapshot.Running(DEADLINE, 90.seconds)))

    private val broadcasts = RestTimerBroadcasts(restTimer, skipRequests, drafts)

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
        }

    @Test
    fun `an alarm carrying no rest is ignored rather than acted on`() =
        runTest {
            // The cancellation intent is built without extras on purpose — `AlarmManager`
            // matches on action and component — so an empty one must not be mistaken for a rest.
            broadcasts.handle(restExpiredBroadcast(context, rest = null))

            assertEquals(emptyList<BackgroundRest?>(), restTimer.published)
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

    @Test
    fun `a press with no draft at all is not an error`() =
        runTest {
            val empty = FakeDraftStore(null)

            RestTimerBroadcasts(restTimer, skipRequests, empty).handle(skipIntent(SESSION_ID))

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

            RestTimerBroadcasts(restTimer, skipRequests, broken).handle(skipIntent(SESSION_ID))

            assertEquals(listOf<BackgroundRest?>(null), restTimer.published)
        }

    // --- anything else ------------------------------------------------------

    @Test
    fun `a broadcast this build does not understand does nothing`() =
        runTest {
            broadcasts.handle(Intent("com.opengym.SOMETHING_ELSE"))

            assertEquals(emptyList<BackgroundRest?>(), restTimer.published)
            assertEquals(Snapshot.Running(DEADLINE, 90.seconds), drafts.load()?.restTimer)
        }

    private fun skipIntent(sessionId: Long): Intent =
        NotificationCommands.intent(context, NotificationCommand.SkipRest(sessionId))

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
        const val DEADLINE = 1_700_000_090_000L

        val REST = BackgroundRest(
            sessionId = SESSION_ID,
            exerciseName = "Bench press",
            setNumber = 3,
            rest = Snapshot.Running(DEADLINE, 90.seconds),
        )
    }
}
