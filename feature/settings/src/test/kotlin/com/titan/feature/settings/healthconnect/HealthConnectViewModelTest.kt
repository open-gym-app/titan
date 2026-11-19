package com.titan.feature.settings.healthconnect

import androidx.lifecycle.SavedStateHandle
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.WorkoutActivity
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.FinishedSessionActivity
import com.titan.core.domain.repository.HealthConnectSessionRepository
import com.titan.core.domain.usecase.ImportFromHealthConnectUseCase
import com.titan.feature.settings.FakeHealthConnect
import com.titan.feature.settings.afterProcessDeath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Health Connect screen's ViewModel: the conversation that grants access, and the import.
 *
 * The claim asserted hardest is that **the system dialog never comes before the rationale**: no
 * event but the rationale's "Continue" may raise the one-shot that launches it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthConnectViewModelTest {

    private val healthConnect = FakeHealthConnect()
    private val sessions = RecordingSessions()
    private val clock = 100L * 86_400_000L

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        HealthConnectViewModel(
            savedStateHandle = savedState,
            healthConnect = healthConnect,
            importFromHealthConnect = ImportFromHealthConnectUseCase(healthConnect, sessions) { clock },
        ).also { it.onEvent(HealthConnectEvent.Resumed) }

    // --- what is granted ------------------------------------------------------

    @Test
    fun `nothing is drawn until the grant has been read`() {
        val viewModel = HealthConnectViewModel(
            SavedStateHandle(),
            healthConnect,
            ImportFromHealthConnectUseCase(healthConnect, sessions) { clock },
        )

        assertNull(viewModel.uiState.value.granted)
    }

    @Test
    fun `a user who never connected is offered the way in`() {
        val state = viewModel().uiState.value

        assertEquals(emptySet<HealthConnectAccess>(), state.granted)
        assertFalse(state.isConnected)
        assertEquals(setOf(FakeHealthConnect.READ, FakeHealthConnect.WRITE), state.permissions)
    }

    @Test
    fun `a grant made in Health Connect's settings shows on the way back`() {
        val viewModel = viewModel()

        healthConnect.granted = setOf(HealthConnectAccess.WRITE)
        viewModel.onEvent(HealthConnectEvent.Resumed)

        assertTrue(viewModel.uiState.value.canWrite)
        assertFalse(viewModel.uiState.value.canRead)
    }

    @Test
    fun `Health Connect uninstalled underneath the screen is said, and nothing is granted`() {
        healthConnect.granted = HealthConnectAccess.entries.toSet()
        val viewModel = viewModel()

        healthConnect.availability = HealthConnectAvailability.NOT_INSTALLED
        viewModel.onEvent(HealthConnectEvent.Resumed)

        assertEquals(HealthConnectAvailability.NOT_INSTALLED, viewModel.uiState.value.availability)
        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `a provider that will not answer reads as nothing granted rather than a crash`() {
        healthConnect.failure = IllegalStateException("provider died")

        assertEquals(emptySet<HealthConnectAccess>(), viewModel().uiState.value.granted)
    }

    // --- the rationale, then the dialog --------------------------------------------------

    @Test
    fun `connect opens the rationale and does not ask the system yet`() {
        val viewModel = viewModel()

        viewModel.onEvent(HealthConnectEvent.ConnectRequested)

        assertTrue(viewModel.uiState.value.rationaleOpen)
        assertFalse(viewModel.uiState.value.permissionRequest)
    }

    @Test
    fun `continuing from the rationale is what asks the system`() {
        val viewModel = viewModel()
        viewModel.onEvent(HealthConnectEvent.ConnectRequested)

        viewModel.onEvent(HealthConnectEvent.RationaleAccepted)

        assertFalse(viewModel.uiState.value.rationaleOpen)
        assertTrue(viewModel.uiState.value.permissionRequest)

        viewModel.onEvent(HealthConnectEvent.PermissionRequestLaunched)

        assertFalse(viewModel.uiState.value.permissionRequest)
    }

    @Test
    fun `dismissing the rationale asks nothing`() {
        val viewModel = viewModel()
        viewModel.onEvent(HealthConnectEvent.ConnectRequested)

        viewModel.onEvent(HealthConnectEvent.RationaleDismissed)

        assertFalse(viewModel.uiState.value.rationaleOpen)
        assertFalse(viewModel.uiState.value.permissionRequest)
    }

    @Test
    fun `an open rationale survives a process death, and the system dialog is not relaunched`() {
        val handle = SavedStateHandle()
        val before = viewModel(handle)
        before.onEvent(HealthConnectEvent.ConnectRequested)

        val restored = viewModel(handle.afterProcessDeath())

        assertTrue(restored.uiState.value.rationaleOpen)
        assertFalse(restored.uiState.value.permissionRequest)
    }

    @Test
    fun `a granted request reads the grant back from Health Connect`() {
        val viewModel = viewModel()

        healthConnect.granted = HealthConnectAccess.entries.toSet()
        viewModel.onEvent(HealthConnectEvent.PermissionResult(setOf(FakeHealthConnect.READ)))

        // Both, though the dialog reported one: the write was granted on an earlier visit.
        assertEquals(HealthConnectAccess.entries.toSet(), viewModel.uiState.value.granted)
        assertFalse(viewModel.uiState.value.refused)
    }

    @Test
    fun `a refusal is remembered, so the screen can point at Health Connect's settings`() {
        val viewModel = viewModel()

        viewModel.onEvent(HealthConnectEvent.PermissionResult(emptySet()))

        assertTrue(viewModel.uiState.value.refused)
        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `a grant made after a refusal clears it`() {
        val viewModel = viewModel()
        viewModel.onEvent(HealthConnectEvent.PermissionResult(emptySet()))

        healthConnect.granted = setOf(HealthConnectAccess.READ)
        viewModel.onEvent(HealthConnectEvent.Resumed)

        assertFalse(viewModel.uiState.value.refused)
    }

    @Test
    fun `a dialog that cannot be opened is said, and the one-shot is cleared`() {
        val viewModel = viewModel()
        viewModel.onEvent(HealthConnectEvent.RationaleAccepted)

        viewModel.onEvent(HealthConnectEvent.PermissionRequestFailed)

        assertFalse(viewModel.uiState.value.permissionRequest)
        assertEquals(HealthConnectMessage.RequestFailed, viewModel.uiState.value.message)
    }

    // --- the import ------------------------------------------------------------------

    @Test
    fun `an import reports how many workouts were new`() {
        healthConnect.granted = setOf(HealthConnectAccess.READ)
        healthConnect.records += record("run-1")
        healthConnect.records += record("run-2", startedAt = clock - 7_200_000L)
        val viewModel = viewModel()

        viewModel.onEvent(HealthConnectEvent.ImportRequested)

        assertEquals(HealthConnectMessage.Imported(2), viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isImporting)
        assertEquals(2, sessions.inserted.size)
    }

    @Test
    fun `a second tap while an import runs starts no second import`() =
        runTest {
            healthConnect.granted = setOf(HealthConnectAccess.READ)
            healthConnect.records += record("run-1")
            val viewModel = viewModel()
            sessions.gate = CompletableDeferred()

            viewModel.onEvent(HealthConnectEvent.ImportRequested)
            assertTrue(viewModel.uiState.value.isImporting)
            viewModel.onEvent(HealthConnectEvent.ImportRequested)
            sessions.gate!!.complete(Unit)

            assertEquals(1, sessions.batches)
            assertEquals(HealthConnectMessage.Imported(1), viewModel.uiState.value.message)
        }

    @Test
    fun `an import without the read permission does nothing`() {
        healthConnect.granted = setOf(HealthConnectAccess.WRITE)
        val viewModel = viewModel()

        viewModel.onEvent(HealthConnectEvent.ImportRequested)

        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `an import that fails says so`() {
        healthConnect.granted = setOf(HealthConnectAccess.READ)
        val viewModel = viewModel()
        healthConnect.failure = IllegalStateException("provider died")

        viewModel.onEvent(HealthConnectEvent.ImportRequested)

        assertEquals(HealthConnectMessage.ImportFailed, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isImporting)
    }

    @Test
    fun `a message is shown once`() {
        healthConnect.granted = setOf(HealthConnectAccess.READ)
        val viewModel = viewModel()
        viewModel.onEvent(HealthConnectEvent.ImportRequested)

        viewModel.onEvent(HealthConnectEvent.MessageShown)

        assertNull(viewModel.uiState.value.message)
    }

    // --- disconnecting ---------------------------------------------------------------

    @Test
    fun `disconnecting revokes everything and draws the way back in`() {
        healthConnect.granted = HealthConnectAccess.entries.toSet()
        val viewModel = viewModel()

        viewModel.onEvent(HealthConnectEvent.DisconnectRequested)

        assertEquals(1, healthConnect.revokeCount)
        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `a disconnect that fails says so`() {
        healthConnect.granted = HealthConnectAccess.entries.toSet()
        val viewModel = viewModel()
        healthConnect.failure = IllegalStateException("provider died")

        viewModel.onEvent(HealthConnectEvent.DisconnectRequested)

        assertEquals(HealthConnectMessage.DisconnectFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `from Android 14 the screen disconnects through Health Connect's settings`() {
        healthConnect.granted = HealthConnectAccess.entries.toSet()
        healthConnect.canRevokeAccess = false

        assertFalse(viewModel().uiState.value.canDisconnect)
    }

    @Test
    fun `a disconnect the app cannot make is ignored, not reported as failed`() {
        healthConnect.granted = HealthConnectAccess.entries.toSet()
        healthConnect.canRevokeAccess = false
        val viewModel = viewModel()

        viewModel.onEvent(HealthConnectEvent.DisconnectRequested)

        assertEquals(0, healthConnect.revokeCount)
        assertNull(viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.isConnected)
    }

    // --- helpers -----------------------------------------------------------------------

    private fun record(
        id: String,
        startedAt: Long = clock - 3_600_000L,
    ) = HealthConnectRecord(
        id = id,
        session = HealthConnectSession(
            startedAt = startedAt,
            endedAt = startedAt + 1_800_000L,
            activity = WorkoutActivity.RUNNING,
        ),
        writtenByThisApp = false,
    )

    /** The local side, keeping what it was handed; [gate] holds an insert open when set. */
    private class RecordingSessions : HealthConnectSessionRepository {
        val inserted = mutableListOf<WorkoutSession>()
        var batches = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getFinished(sessionId: Long): FinishedSessionActivity? = null

        override suspend fun getFinishedStartedBetween(
            startedFrom: Long,
            startedUntil: Long,
        ): List<FinishedSessionActivity> = inserted.map { FinishedSessionActivity(it, emptyList()) }

        override suspend fun insertImported(sessions: List<WorkoutSession>): Int {
            batches++
            gate?.await()
            inserted += sessions
            return sessions.size
        }
    }
}
