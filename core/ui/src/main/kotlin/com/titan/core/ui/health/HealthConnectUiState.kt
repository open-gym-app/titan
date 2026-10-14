package com.titan.core.ui.health

import com.titan.core.domain.model.HealthConnectStatus

/**
 * What the Health Connect rows draw.
 *
 * [status] is null until the first read lands, and a null status draws nothing: the rows appear
 * once Health Connect is known to be there, rather than flashing in and out on a device that
 * has no such thing.
 */
data class HealthConnectUiState(
    val status: HealthConnectStatus? = null,
    val rationaleVisible: Boolean = false,
    val launchPermission: Boolean = false,
    val importing: Boolean = false,
    val message: HealthConnectMessage? = null,
) {
    val visible: Boolean get() = status?.visible == true

    val syncOn: Boolean get() = status?.active == true
}

/** The outcome worth telling the user about; each is shown beside the row that caused it. */
sealed interface HealthConnectMessage {
    data object PermissionDenied : HealthConnectMessage

    data object Unreachable : HealthConnectMessage

    data class Imported(val count: Int) : HealthConnectMessage
}

sealed interface HealthConnectEvent {
    data object Refresh : HealthConnectEvent

    data class SyncToggled(val on: Boolean) : HealthConnectEvent

    data object RationaleConfirmed : HealthConnectEvent

    data object RationaleDismissed : HealthConnectEvent

    data object PermissionRequestLaunched : HealthConnectEvent

    data object PermissionResult : HealthConnectEvent

    data object ImportTapped : HealthConnectEvent
}
