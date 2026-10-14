package com.titan.core.domain.model

/**
 * Everything a screen needs to draw the Health Connect controls.
 *
 * @property enabled the user turned sync on.
 * @property permitted both exercise permissions are currently granted. Held apart from
 *   [enabled]: permissions can be revoked in system settings behind the app's back.
 */
data class HealthConnectStatus(
    val availability: HealthAvailability,
    val enabled: Boolean,
    val permitted: Boolean,
) {
    /** Whether the controls are shown at all. */
    val visible: Boolean get() = availability == HealthAvailability.AVAILABLE

    /** Whether a finished session will be written and an import will run. */
    val active: Boolean get() = visible && enabled && permitted
}
