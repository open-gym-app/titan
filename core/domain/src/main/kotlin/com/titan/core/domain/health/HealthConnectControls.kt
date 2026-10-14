package com.titan.core.domain.health

import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectStatus
import com.titan.core.domain.repository.HealthConnectGateway

/**
 * What the two Health Connect rows (settings and body measurements) drive.
 *
 * The permission prompt itself is the screen's — it needs an activity, and the rationale must be
 * on screen before it — so the flow is: [requestEnable]; if it says [EnableStep.NeedsPermission],
 * show the rationale, launch the prompt, then report the answer to [permissionResult].
 */
class HealthConnectControls(
    private val gateway: HealthConnectGateway,
    private val toggles: FeatureToggles,
) {

    suspend fun status(): HealthConnectStatus {
        val availability = gateway.availability()
        return HealthConnectStatus(
            availability = availability,
            enabled = toggles.isEnabled(M8Feature.HEALTH_CONNECT),
            permitted = availability == HealthAvailability.AVAILABLE &&
                gateway.hasPermissions(),
        )
    }

    suspend fun requestEnable(): EnableStep {
        val current = status()
        return when {
            !current.visible -> EnableStep.Unavailable

            current.permitted -> {
                toggles.setEnabled(M8Feature.HEALTH_CONNECT, true)
                EnableStep.Enabled
            }

            else -> EnableStep.NeedsPermission
        }
    }

    /** Turns sync on only if the prompt ended with both permissions held; a denial leaves it off. */
    suspend fun permissionResult(): EnableStep {
        val granted = gateway.hasPermissions()
        toggles.setEnabled(M8Feature.HEALTH_CONNECT, granted)
        return if (granted) EnableStep.Enabled else EnableStep.Denied
    }

    suspend fun disable() = toggles.setEnabled(M8Feature.HEALTH_CONNECT, false)

    enum class EnableStep { Enabled, NeedsPermission, Denied, Unavailable }
}
