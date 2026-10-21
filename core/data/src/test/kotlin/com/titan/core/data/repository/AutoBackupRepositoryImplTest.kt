package com.titan.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.titan.core.domain.backup.AutoBackupSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [AutoBackupRepositoryImpl] against a real Preferences DataStore over a real file.
 *
 * Not a fake store, for `UserPreferencesRepositoryImplTest`'s reason: the two properties that
 * matter most here — that the toggle survives a relaunch and that a background job's record of
 * itself outlives the process that was not there for it — are properties of DataStore's file
 * handling, and a fake would only assert that the fake has them.
 *
 * [withStore] uses the same process-death rig: build a store over [storeFile], run the block,
 * then cancel its scope and wait, which releases the file and drops the in-memory copy so the
 * next store reads cold off disk.
 */
class AutoBackupRepositoryImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storeFile: File get() = File(temporaryFolder.root, "auto-backup.preferences_pb")

    /** Off, which is the only safe default for something that writes files unattended. */
    @Test
    fun `a device that has never been asked reads it off, with no runs`() =
        withStore { repository ->
            assertEquals(AutoBackupSchedule(), repository.observe().first())
        }

    @Test
    fun `the toggle survives process death`() {
        withStore { repository -> repository.setEnabled(true) }

        withStore { repository -> assertTrue(repository.observe().first().enabled) }
    }

    @Test
    fun `turning it back off is stored too`() =
        withStore { repository ->
            repository.setEnabled(true)
            repository.setEnabled(false)

            assertFalse(repository.observe().first().enabled)
        }

    /**
     * The record a worker leaves for a screen that was not running when it ran.
     *
     * Written by one process and read by the next, which is the whole reason this is on disk
     * rather than in the ViewModel that draws it.
     */
    @Test
    fun `a run recorded in one process is read back in the next`() {
        withStore { repository -> repository.recordSuccess(LAST_SUCCESS_AT) }

        withStore { repository ->
            assertEquals(LAST_SUCCESS_AT, repository.observe().first().lastSuccessAt)
        }
    }

    /** A failure says something is wrong; the earlier success says how much it has cost. */
    @Test
    fun `a failure keeps the last success beside it`() =
        withStore { repository ->
            repository.recordSuccess(LAST_SUCCESS_AT)
            repository.recordFailure(LAST_FAILURE_AT)

            val schedule = repository.observe().first()
            assertEquals(LAST_SUCCESS_AT, schedule.lastSuccessAt)
            assertEquals(LAST_FAILURE_AT, schedule.lastFailureAt)
            assertTrue(schedule.hasFailed)
        }

    /** A run that worked supersedes the one that did not: the row stops warning about it. */
    @Test
    fun `a success clears the failure before it`() =
        withStore { repository ->
            repository.recordFailure(LAST_FAILURE_AT)
            repository.recordSuccess(LAST_SUCCESS_AT)

            val schedule = repository.observe().first()
            assertNull(schedule.lastFailureAt)
            assertFalse(schedule.hasFailed)
            assertEquals(LAST_SUCCESS_AT, schedule.lastSuccessAt)
        }

    /** Recording a run does not turn the feature on, and turning it off does not erase the record. */
    @Test
    fun `the flag and the timestamps are independent`() =
        withStore { repository ->
            repository.recordSuccess(LAST_SUCCESS_AT)
            assertFalse(repository.observe().first().enabled)

            repository.setEnabled(true)
            repository.setEnabled(false)
            assertEquals(LAST_SUCCESS_AT, repository.observe().first().lastSuccessAt)
        }

    /** The strict read agrees with the flow whenever there is nothing wrong with the file. */
    @Test
    fun `the strict read returns what the flow shows`() =
        withStore { repository ->
            repository.setEnabled(true)
            repository.recordSuccess(LAST_SUCCESS_AT)

            assertEquals(repository.observe().first(), repository.read())
        }

    /**
     * The two reads over a file neither of them can parse, which is the point of having two.
     *
     * `observe()` falls back so the backup screen has a first frame to draw. `read()` throws so
     * the worker retries instead of mistaking an unreadable store for a toggle the user turned
     * off — which would cost that week's backup with nothing anywhere saying so. Asserted
     * together, because it is the *pair* that is the contract.
     */
    @Test
    fun `an unreadable store falls back for the flow and fails for the strict read`() {
        withStore { repository -> repository.setEnabled(true) }
        storeFile.writeText("not a preferences file")

        withStore { repository ->
            assertEquals(AutoBackupSchedule(), repository.observe().first())

            val failure = runCatching { repository.read() }.exceptionOrNull()
            assertTrue("the strict read fell back instead of failing: $failure", failure is IOException)
        }
    }

    /**
     * The corrupt-file case, which no setter could have produced.
     *
     * A stamp at or before the epoch is not an instant this app wrote, and reading it as one
     * would put "Last backup 1 Jan" under a switch that has never run. Absent is the honest
     * answer and the one the row already draws.
     */
    @Test
    fun `a stored timestamp that is not a plausible instant reads as no run`() =
        withRawStore { repository, store ->
            store.edit {
                it[longPreferencesKey("auto_backup_last_success_at")] = 0
                it[longPreferencesKey("auto_backup_last_failure_at")] = -1
            }

            val schedule = repository.observe().first()
            assertNull(schedule.lastSuccessAt)
            assertNull(schedule.lastFailureAt)
        }

    /**
     * The key names are a persisted format.
     *
     * Asserted as the keys themselves, not only through the repository: renaming one turns the
     * schedule off on every device that already had it on, and forgets when it last ran, with
     * nothing in the diff saying so.
     */
    @Test
    fun `the store is keyed by the names the format documents`() =
        withRawStore { repository, store ->
            repository.setEnabled(true)
            repository.recordSuccess(LAST_SUCCESS_AT)
            repository.recordFailure(LAST_FAILURE_AT)

            val stored = store.data.first()
            assertEquals(true, stored[booleanPreferencesKey("auto_backup_enabled")])
            assertEquals(LAST_SUCCESS_AT, stored[longPreferencesKey("auto_backup_last_success_at")])
            assertEquals(LAST_FAILURE_AT, stored[longPreferencesKey("auto_backup_last_failure_at")])
        }

    private fun withStore(block: suspend CoroutineScope.(AutoBackupRepositoryImpl) -> Unit) =
        withRawStore { repository, _ -> block(repository) }

    /**
     * Runs [block] against a repository over [storeFile] and the store behind it, then shuts
     * the store down — the shutdown is what makes the next call a genuinely cold read.
     */
    private fun withRawStore(
        block: suspend CoroutineScope.(AutoBackupRepositoryImpl, DataStore<Preferences>) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                block(AutoBackupRepositoryImpl(store), store)
            }
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    private companion object {

        /** 2026-04-26, the Sunday the wireframe's "last: APR 26" names. */
        const val LAST_SUCCESS_AT = 1_777_161_600_000L

        /** A week later, so a failure is the newer of the two facts. */
        const val LAST_FAILURE_AT = 1_777_766_400_000L

        const val TEST_TIMEOUT_MILLIS = 10_000L
    }
}
