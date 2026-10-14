package com.titan.core.ui.health

import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.health.HealthConnectControls
import com.titan.core.domain.health.ImportFromHealthConnectUseCase
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.HealthConnectGateway
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.time.Clock
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectControllerTest {

    private val gateway = FakeGateway()
    private val toggles = FakeToggles()
    private val sessions = mockk<SessionRepository>(relaxed = true)

    private fun TestScope.controller(): HealthConnectController {
        every { sessions.getAll() } returns flowOf(emptyList<WorkoutSession>())
        return HealthConnectController(
            controls = HealthConnectControls(gateway, toggles),
            importer = ImportFromHealthConnectUseCase(sessions, gateway, toggles, Clock { NOW }),
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )
    }

    @Test
    fun `nothing is drawn before the first read, or where health connect is missing`() =
        runTest {
            val controller = controller()
            assertFalse(controller.state.value.visible)

            gateway.availability = HealthAvailability.NOT_INSTALLED
            controller.onEvent(HealthConnectEvent.Refresh)

            assertFalse(controller.state.value.visible)
        }

    @Test
    fun `turning sync on without the permission asks for an explanation first`() =
        runTest {
            val controller = controller()

            controller.onEvent(HealthConnectEvent.SyncToggled(true))

            assertTrue(controller.state.value.rationaleVisible)
            assertFalse("no prompt before the rationale is confirmed", controller.state.value.launchPermission)
            assertFalse(toggles.enabled)
        }

    @Test
    fun `confirming the rationale raises the prompt once`() =
        runTest {
            val controller = controller()
            controller.onEvent(HealthConnectEvent.SyncToggled(true))

            controller.onEvent(HealthConnectEvent.RationaleConfirmed)
            assertTrue(controller.state.value.launchPermission)
            assertFalse(controller.state.value.rationaleVisible)

            controller.onEvent(HealthConnectEvent.PermissionRequestLaunched)
            assertFalse(controller.state.value.launchPermission)
        }

    @Test
    fun `dismissing the rationale leaves sync off and asks for nothing`() =
        runTest {
            val controller = controller()
            controller.onEvent(HealthConnectEvent.SyncToggled(true))

            controller.onEvent(HealthConnectEvent.RationaleDismissed)

            assertFalse(controller.state.value.rationaleVisible)
            assertFalse(controller.state.value.launchPermission)
            assertFalse(toggles.enabled)
        }

    @Test
    fun `a granted prompt turns sync on`() =
        runTest {
            val controller = controller()
            controller.onEvent(HealthConnectEvent.SyncToggled(true))
            gateway.permitted = true

            controller.onEvent(HealthConnectEvent.PermissionResult)

            assertTrue(toggles.enabled)
            assertTrue(controller.state.value.syncOn)
            assertNull(controller.state.value.message)
        }

    @Test
    fun `a denied prompt leaves sync off and says so`() =
        runTest {
            val controller = controller()
            controller.onEvent(HealthConnectEvent.SyncToggled(true))

            controller.onEvent(HealthConnectEvent.PermissionResult)

            assertFalse(toggles.enabled)
            assertFalse(controller.state.value.syncOn)
            assertEquals(HealthConnectMessage.PermissionDenied, controller.state.value.message)
        }

    @Test
    fun `turning sync on with the permission already held needs no prompt`() =
        runTest {
            gateway.permitted = true
            val controller = controller()

            controller.onEvent(HealthConnectEvent.SyncToggled(true))

            assertTrue(controller.state.value.syncOn)
            assertFalse(controller.state.value.rationaleVisible)
        }

    @Test
    fun `turning sync off stores it`() =
        runTest {
            gateway.permitted = true
            toggles.enabled = true
            val controller = controller()
            controller.onEvent(HealthConnectEvent.Refresh)
            assertTrue(controller.state.value.syncOn)

            controller.onEvent(HealthConnectEvent.SyncToggled(false))

            assertFalse(toggles.enabled)
            assertFalse(controller.state.value.syncOn)
        }

    @Test
    fun `an import reports how many workouts came in`() =
        runTest {
            gateway.permitted = true
            toggles.enabled = true
            gateway.stored = listOf(HealthWorkout(NOW - 1_000_000, NOW - 500_000, HealthWorkoutType.STRENGTH_TRAINING))
            val controller = controller()

            controller.onEvent(HealthConnectEvent.ImportTapped)

            assertEquals(HealthConnectMessage.Imported(1), controller.state.value.message)
            assertFalse(controller.state.value.importing)
            coVerify(exactly = 1) { sessions.insert(any()) }
        }

    @Test
    fun `an import that finds nothing reports zero`() =
        runTest {
            gateway.permitted = true
            toggles.enabled = true
            val controller = controller()

            controller.onEvent(HealthConnectEvent.ImportTapped)

            assertEquals(HealthConnectMessage.Imported(0), controller.state.value.message)
        }

    @Test
    fun `an unreachable health connect is reported and never thrown`() =
        runTest {
            gateway.permitted = true
            toggles.enabled = true
            gateway.failReads = true
            val controller = controller()

            controller.onEvent(HealthConnectEvent.ImportTapped)

            assertEquals(HealthConnectMessage.Unreachable, controller.state.value.message)
            assertFalse(controller.state.value.importing)
        }

    private class FakeToggles(var enabled: Boolean = false) : FeatureToggles {
        override fun observe(feature: M8Feature): Flow<Boolean> = MutableStateFlow(enabled)

        override suspend fun isEnabled(feature: M8Feature): Boolean = enabled

        override suspend fun setEnabled(
            feature: M8Feature,
            enabled: Boolean,
        ) {
            this.enabled = enabled
        }
    }

    private class FakeGateway : HealthConnectGateway {
        var availability = HealthAvailability.AVAILABLE
        var permitted = false
        var stored: List<HealthWorkout> = emptyList()
        var failReads = false

        override fun availability(): HealthAvailability = availability

        override suspend fun hasPermissions(): Boolean = permitted

        override suspend fun write(workout: HealthWorkout) = Unit

        override suspend fun readBetween(
            sinceMillis: Long,
            untilMillis: Long,
        ): List<HealthWorkout> {
            if (failReads) throw HealthConnectException("unreachable")
            return stored
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
