package com.titan.core.domain.usecase

import com.titan.core.domain.backup.AutoBackupSchedule
import com.titan.core.domain.backup.AutoBackupScheduler
import com.titan.core.domain.repository.AutoBackupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * That the toggle's two halves — the stored flag and the scheduled work — move together, and
 * that a store which cannot be written moves neither.
 *
 * The whole reason this is a use case rather than two calls from a ViewModel is the pair of
 * states it makes unreachable: work enqueued against a flag that says off writes files nobody
 * asked for, and a flag that says on with nothing enqueued promises a backup that will never
 * happen. Both are one forgotten line away in a caller that does it itself.
 */
class SetAutoBackupEnabledUseCaseTest {

    private val repository = FakeAutoBackupRepository()
    private val scheduler = RecordingScheduler()
    private val setEnabled = SetAutoBackupEnabledUseCase(repository, scheduler)

    @Test
    fun `turning it on stores the flag and enqueues the work`() =
        runTest {
            setEnabled(true)

            assertTrue(repository.current.enabled)
            assertEquals(1, scheduler.scheduleCount)
            assertEquals(0, scheduler.cancelCount)
        }

    @Test
    fun `turning it off stores the flag and cancels the work`() =
        runTest {
            setEnabled(true)
            setEnabled(false)

            assertFalse(repository.current.enabled)
            assertEquals(1, scheduler.cancelCount)
        }

    /**
     * The idempotence the scheduler's contract promises, from this side: setting it on twice
     * asks twice, and `ExistingPeriodicWorkPolicy.KEEP` is what makes the second ask a no-op
     * rather than a restarted week. Nothing here may decide to skip the call instead — the
     * store's value is not proof that the *work* is still enqueued.
     */
    @Test
    fun `setting the value it already holds still asks the scheduler`() =
        runTest {
            setEnabled(true)
            setEnabled(true)

            assertTrue(repository.current.enabled)
            assertEquals(2, scheduler.scheduleCount)
        }

    /** A device that cannot persist the toggle must not quietly start writing backups anyway. */
    @Test
    fun `a store that cannot be written leaves the schedule alone`() =
        runTest {
            repository.writesFail = true

            val failure = runCatching { setEnabled(true) }.exceptionOrNull()

            assertTrue("the store's IOException should reach the caller, was $failure", failure is IOException)

            assertFalse(repository.current.enabled)
            assertEquals(0, scheduler.scheduleCount)
            assertEquals(0, scheduler.cancelCount)
        }

    /**
     * The other half failing, which is the same forbidden state from the other side.
     *
     * A `schedule()` that throws with the flag already stored leaves a switch the screen draws
     * *on* — it is drawn from the store — under a sentence saying the switch could not be
     * saved, over work nobody enqueued. The flag goes back, so the screen tells the truth.
     */
    @Test
    fun `a scheduler that refuses the work puts the flag back`() =
        runTest {
            scheduler.failsToSchedule = true

            val failure = runCatching { setEnabled(true) }.exceptionOrNull()

            assertTrue(
                "the scheduler's failure should reach the caller, was $failure",
                failure is IllegalStateException,
            )
            assertFalse("the toggle must not read on with nothing enqueued", repository.current.enabled)
        }
}

/** The schedule store, in memory, with the one failure the interface documents. */
private class FakeAutoBackupRepository : AutoBackupRepository {

    private val schedule = MutableStateFlow(AutoBackupSchedule())

    var writesFail = false

    val current: AutoBackupSchedule get() = schedule.value

    override fun observe(): Flow<AutoBackupSchedule> = schedule.asStateFlow()

    override suspend fun read(): AutoBackupSchedule = schedule.value

    override suspend fun setEnabled(enabled: Boolean) = write { it.copy(enabled = enabled) }

    override suspend fun recordSuccess(at: Long) = write { it.copy(lastSuccessAt = at, lastFailureAt = null) }

    override suspend fun recordFailure(at: Long) = write { it.copy(lastFailureAt = at) }

    override suspend fun forgetRuns() = write { it.copy(lastSuccessAt = null, lastFailureAt = null) }

    private fun write(transform: (AutoBackupSchedule) -> AutoBackupSchedule) {
        if (writesFail) throw IOException("the store is not writable")
        schedule.update(transform)
    }
}

/** The platform half, counted rather than performed. */
private class RecordingScheduler : AutoBackupScheduler {

    var scheduleCount = 0
        private set

    var cancelCount = 0
        private set

    /** When true, [schedule] throws the way `WorkManager.getInstance` does on a broken process. */
    var failsToSchedule = false

    override fun schedule() {
        if (failsToSchedule) error("WorkManager is not initialised")
        scheduleCount++
    }

    override fun cancel() {
        cancelCount++
    }
}
