package com.titan.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.model.ActiveExercise
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.ActiveSessionFormat
import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.SetType
import com.titan.core.domain.timer.RestTimerSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [InProgressSessionRepositoryImpl] against a real file in a Robolectric app's
 * private storage. Robolectric rather than a temporary folder because the class
 * takes the application context on purpose — where the draft lives is part of what
 * is being asserted, and a hand-supplied directory would test a path production
 * never uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class InProgressSessionRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var repository: InProgressSessionRepositoryImpl

    private val document: File get() = File(context.filesDir, "in-progress-session.json")
    private val scratch: File get() = File(context.filesDir, "in-progress-session.json.tmp")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The real IO dispatcher rather than a TestDispatcher: what is under test
        // here is a file on disk, and `concurrent saves` below needs writers that
        // genuinely overlap, which a single-threaded test dispatcher would remove.
        repository = InProgressSessionRepositoryImpl(context, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        document.delete()
        scratch.delete()
    }

    private fun draft(
        sessionId: Long = 7,
        currentExerciseIndex: Int? = null,
        restTimer: RestTimerSnapshot = RestTimerSnapshot.Idle,
    ) = ActiveSession(
        sessionId = sessionId,
        startedAt = 1_700_000_000_000,
        pausedDuration = 3.minutes,
        exercises = listOf(
            ActiveExercise(
                exerciseId = 11,
                sessionExerciseId = 21,
                notes = "felt fine",
                sets = listOf(
                    ActiveSet(
                        id = 31,
                        reps = 5,
                        weight = 100_000_000,
                        isCompleted = true,
                        completedAt = 1_700_000_060_000,
                    ),
                    ActiveSet(reps = 5, setType = SetType.DROP_SET),
                ),
            ),
            ActiveExercise(exerciseId = 12),
        ),
        currentExerciseIndex = currentExerciseIndex,
        restTimer = restTimer,
    )

    @Test
    fun `load returns null when nothing has ever been saved`() =
        runBlocking {
            assertNull(repository.load())
        }

    @Test
    fun `a saved draft comes back whole`() =
        runBlocking {
            val session = draft(currentExerciseIndex = 1, restTimer = RestTimerSnapshot.Running(9_000, 90.seconds))

            repository.save(session)

            assertEquals(session, repository.load())
        }

    @Test
    fun `save writes the document the domain defines`() =
        runBlocking {
            val session = draft()

            repository.save(session)

            // The format is the domain's; all this owes is storing it verbatim.
            assertEquals(session, ActiveSessionFormat.decode(document.readText()))
        }

    @Test
    fun `save overwrites the previous draft rather than appending to it`() =
        runBlocking {
            repository.save(draft(sessionId = 7))
            repository.save(draft(sessionId = 8, currentExerciseIndex = 1))

            val loaded = repository.load()
            assertEquals(8L, loaded?.sessionId)
            assertEquals(1, loaded?.currentExerciseIndex)
            // The document holds the second draft alone — a save that appended would
            // still start with valid JSON and still load, so assert on the file.
            assertEquals(loaded, ActiveSessionFormat.decode(document.readText()))
        }

    @Test
    fun `save leaves no scratch file behind`() =
        runBlocking {
            repository.save(draft())

            assertTrue(document.exists())
            assertFalse("the scratch file must be renamed onto the document, not copied", scratch.exists())
        }

    @Test
    fun `concurrent saves leave one whole draft, not a mixture`() =
        runBlocking {
            val sessionIds = (1L..20L).toList()

            sessionIds.map { id -> async { repository.save(draft(sessionId = id)) } }.awaitAll()

            val loaded = repository.load()
            assertNotNull("a draft written by concurrent savers must still be readable", loaded)
            assertTrue(loaded!!.sessionId in sessionIds)
            assertFalse(scratch.exists())
        }

    @Test
    fun `clear discards the draft`() =
        runBlocking {
            repository.save(draft())

            repository.clear()

            assertNull(repository.load())
            assertFalse(document.exists())
        }

    @Test
    fun `clearing twice is not an error`() =
        runBlocking {
            repository.save(draft())

            repository.clear()
            repository.clear()

            assertNull(repository.load())
        }

    @Test
    fun `clearing when there was never a draft is not an error`() =
        runBlocking {
            repository.clear()

            assertNull(repository.load())
        }

    @Test
    fun `clear removes a scratch file a killed save left behind`() =
        runBlocking {
            scratch.writeText("{\"version\":1,\"session\":{")

            repository.clear()

            assertFalse(scratch.exists())
        }

    @Test
    fun `a truncated document reads as no draft`() =
        runBlocking {
            repository.save(draft())
            document.writeText(document.readText().take(40))

            assertNull(repository.load())
        }

    @Test
    fun `a document from a version this build does not read is refused`() =
        runBlocking {
            repository.save(draft())
            val future = document.readText().replace("\"version\":${ActiveSessionFormat.VERSION}", "\"version\":9999")
            document.writeText(future)

            assertNull(repository.load())
        }

    @Test
    fun `a draft can be saved again after being cleared`() =
        runBlocking {
            repository.save(draft(sessionId = 7))
            repository.clear()

            repository.save(draft(sessionId = 8))

            assertEquals(8L, repository.load()?.sessionId)
        }
}
