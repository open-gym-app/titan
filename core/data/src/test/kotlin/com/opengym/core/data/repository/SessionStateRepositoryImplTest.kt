package com.opengym.core.data.repository

import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No Robolectric and no database: the state lives in memory by design, so the tests
 * that matter are about the Flow's conflation and replay, which need neither.
 */
class SessionStateRepositoryImplTest {

    private val repository = SessionStateRepositoryImpl()

    @Test
    fun `a fresh repository observes Idle`() =
        runTest {
            assertEquals(SessionState.Idle, repository.observe().first())
        }

    @Test
    fun `a collector that arrives after the write still sees the current state`() =
        runTest {
            // What lets the use cases read the state with observe().first() without a
            // write having to happen while they are collecting.
            repository.update(SessionState.Active(sessionId = 3, startedAt = 1_000))

            assertEquals(SessionState.Active(sessionId = 3, startedAt = 1_000), repository.observe().first())
        }

    @Test
    fun `update replaces the state rather than accumulating it`() =
        runTest {
            repository.update(SessionState.Active(sessionId = 3, startedAt = 1_000))
            repository.update(SessionState.Paused(sessionId = 3, pausedAt = 2_000))

            assertEquals(SessionState.Paused(sessionId = 3, pausedAt = 2_000), repository.observe().first())
        }

    // runCurrent is what makes the emissions deterministic: it drains the collector
    // between updates so a conflating StateFlow cannot legitimately skip one.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `observe re-emits on every change`() =
        runTest {
            val seen = mutableListOf<SessionState>()
            val collector = backgroundScope.launch { repository.observe().toList(seen) }
            runCurrent()

            repository.update(SessionState.Active(sessionId = 1, startedAt = 10))
            runCurrent()
            repository.update(SessionState.Paused(sessionId = 1, pausedAt = 20))
            runCurrent()
            collector.cancel()

            assertEquals(
                listOf(
                    SessionState.Idle,
                    SessionState.Active(sessionId = 1, startedAt = 10),
                    SessionState.Paused(sessionId = 1, pausedAt = 20),
                ),
                seen,
            )
        }

    @Test
    fun `a finished state carries its summary through unchanged`() =
        runTest {
            val summary = SessionSummary(
                sessionId = 4,
                startedAt = 1_000,
                finishedAt = 5_000,
                durationMillis = 4_000,
                exerciseCount = 2,
                totalSets = 6,
                totalVolume = 1_000_000,
            )

            repository.update(SessionState.Finished(summary))

            assertEquals(SessionState.Finished(summary), repository.observe().first())
        }
}
