package com.titan.feature.settings.healthconnect

import androidx.compose.runtime.Immutable
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability

/**
 * Everything the Health Connect screen draws, as one value.
 *
 * @param availability whether Health Connect is there to talk to. Settings hides the row that
 *   leads here when it is not, but the screen can still be on the back stack when Health Connect
 *   is uninstalled underneath it, and says so rather than offering controls that would fail.
 * @param granted what the user has allowed, or **null** before the first answer arrives — drawn
 *   as nothing at all rather than as "not connected" for the frame it takes to ask.
 * @param permissions the platform permission names to hand the system dialog. See
 *   [com.titan.core.domain.healthconnect.HealthConnect.permissions].
 * @param rationaleOpen whether the explanation that comes before the system dialog is up.
 * @param permissionRequest a one-shot: launch the system dialog. The ViewModel decides when, the
 *   route is the only thing that can launch it, so the decision travels as state and is cleared by
 *   [HealthConnectEvent.PermissionRequestLaunched] — the pattern the notification prompt uses.
 * @param refused whether the last request came back with nothing granted. Health Connect stops
 *   showing its dialog after two refusals, so from here the only way forward is its own settings,
 *   and the screen says that instead of offering a button that would do nothing.
 * @param isImporting an import is running; the row is inert until it ends.
 * @param message a sentence to show once, cleared by [HealthConnectEvent.MessageShown].
 */
@Immutable
data class HealthConnectUiState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE,
    val granted: Set<HealthConnectAccess>? = null,
    val permissions: Set<String> = emptySet(),
    val rationaleOpen: Boolean = false,
    val permissionRequest: Boolean = false,
    val refused: Boolean = false,
    val isImporting: Boolean = false,
    val message: HealthConnectMessage? = null,
) {

    /** Whether the user has allowed anything at all — the line between the two layouts. */
    val isConnected: Boolean get() = !granted.isNullOrEmpty()

    val canWrite: Boolean get() = granted.orEmpty().contains(HealthConnectAccess.WRITE)

    val canRead: Boolean get() = granted.orEmpty().contains(HealthConnectAccess.READ)
}

/** The outcomes worth a snackbar. */
sealed interface HealthConnectMessage {

    /** An import finished; [count] sessions were new. Zero is said too, as "nothing new". */
    data class Imported(val count: Int) : HealthConnectMessage

    data object ImportFailed : HealthConnectMessage

    data object DisconnectFailed : HealthConnectMessage

    /** The system dialog could not be opened — Health Connect went away between check and tap. */
    data object RequestFailed : HealthConnectMessage
}
