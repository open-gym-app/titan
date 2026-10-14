package com.titan.core.domain.health

import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.health.HealthConnectControls.EnableStep
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectControlsTest {

    private val gateway = FakeHealthConnectGateway(permitted = false)
    private val toggles = FakeFeatureToggles()
    private val controls = HealthConnectControls(gateway, toggles)

    @Test
    fun `status reflects availability, the toggle and the permissions`() =
        runTest {
            assertEquals(
                HealthConnectStatus(HealthAvailability.AVAILABLE, enabled = false, permitted = false),
                controls.status(),
            )

            gateway.permitted = true
            toggles.setEnabled(M8Feature.HEALTH_CONNECT, true)
            val status = controls.status()
            assertTrue(status.visible)
            assertTrue(status.active)
        }

    @Test
    fun `an unavailable device is hidden and never reads permissions as granted`() =
        runTest {
            gateway.availability = HealthAvailability.NOT_INSTALLED
            gateway.permitted = true
            toggles.setEnabled(M8Feature.HEALTH_CONNECT, true)

            val status = controls.status()

            assertFalse(status.visible)
            assertFalse(status.permitted)
            assertFalse(status.active)
        }

    @Test
    fun `enabling without permission asks for it and leaves sync off`() =
        runTest {
            assertEquals(EnableStep.NeedsPermission, controls.requestEnable())
            assertFalse(toggles.isEnabled(M8Feature.HEALTH_CONNECT))
        }

    @Test
    fun `enabling with permission already held turns sync on`() =
        runTest {
            gateway.permitted = true

            assertEquals(EnableStep.Enabled, controls.requestEnable())
            assertTrue(toggles.isEnabled(M8Feature.HEALTH_CONNECT))
        }

    @Test
    fun `enabling an unavailable health connect does nothing`() =
        runTest {
            gateway.availability = HealthAvailability.UNSUPPORTED

            assertEquals(EnableStep.Unavailable, controls.requestEnable())
            assertFalse(toggles.isEnabled(M8Feature.HEALTH_CONNECT))
        }

    @Test
    fun `a granted prompt turns sync on and a denied one leaves it off`() =
        runTest {
            gateway.permitted = true
            assertEquals(EnableStep.Enabled, controls.permissionResult())
            assertTrue(toggles.isEnabled(M8Feature.HEALTH_CONNECT))

            gateway.permitted = false
            assertEquals(EnableStep.Denied, controls.permissionResult())
            assertFalse(toggles.isEnabled(M8Feature.HEALTH_CONNECT))
        }

    @Test
    fun `disable turns sync off`() =
        runTest {
            toggles.setEnabled(M8Feature.HEALTH_CONNECT, true)

            controls.disable()

            assertFalse(toggles.isEnabled(M8Feature.HEALTH_CONNECT))
        }
}
