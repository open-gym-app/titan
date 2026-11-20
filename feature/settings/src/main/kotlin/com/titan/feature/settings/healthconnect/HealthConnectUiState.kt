package com.titan.feature.settings.healthconnect

import androidx.compose.runtime.Immutable
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric

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
 *   showing its dialog after two refusals, and an empty answer does not say whether this was the
 *   first or the second, so the screen points at Health Connect's own settings — the one way
 *   forward that works either way — instead of offering a button that may do nothing.
 * @param canDisconnect whether "Disconnect" can take the grant back itself. False from Android
 *   14, where it opens Health Connect's permission page instead — see
 *   [com.titan.core.domain.healthconnect.HealthConnect.canRevokeAccess].
 * @param isImporting the workout import is running; its row is inert until it ends.
 * @param isImportingBody the body import is running; its row is inert until it ends.
 * @param message a sentence to show once. [HealthConnectEvent.MessageShown] replaces it with the
 *   next one waiting, or clears it.
 */
@Immutable
data class HealthConnectUiState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE,
    val granted: Set<HealthConnectAccess>? = null,
    val permissions: Set<String> = emptySet(),
    val rationaleOpen: Boolean = false,
    val permissionRequest: Boolean = false,
    val refused: Boolean = false,
    val canDisconnect: Boolean = true,
    val isImporting: Boolean = false,
    val isImportingBody: Boolean = false,
    val message: HealthConnectMessage? = null,
) {

    /** Whether the user has allowed anything at all — the line between the two layouts. */
    val isConnected: Boolean get() = !granted.isNullOrEmpty()

    val canWriteExercise: Boolean get() = granted.orEmpty().contains(HealthConnectAccess.WRITE_EXERCISE)

    val canReadExercise: Boolean get() = granted.orEmpty().contains(HealthConnectAccess.READ_EXERCISE)

    /** The body figures whose write is granted: weight, body fat, both or neither. */
    val writableBody: Set<HealthConnectBodyMetric>
        get() = HealthConnectBodyMetric.entries.filterTo(mutableSetOf()) { it.writeAccess in granted.orEmpty() }

    /** Whether either body figure can be read — the import brings in whichever is allowed. */
    val canReadBody: Boolean get() = HealthConnectBodyMetric.entries.any { it.readAccess in granted.orEmpty() }
}

/** The outcomes worth a snackbar. */
sealed interface HealthConnectMessage {

    /** A workout import finished; [count] sessions were new. Zero is said too, as "nothing new". */
    data class Imported(val count: Int) : HealthConnectMessage

    /** A body import finished; [count] entries were new. Zero is said too. */
    data class ImportedMeasurements(val count: Int) : HealthConnectMessage

    data object ImportFailed : HealthConnectMessage

    data object DisconnectFailed : HealthConnectMessage

    /** The system dialog could not be opened — Health Connect went away between check and tap. */
    data object RequestFailed : HealthConnectMessage
}
