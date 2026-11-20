package com.titan.feature.settings.healthconnect

/** Everything the Health Connect screen can report. */
sealed interface HealthConnectEvent {

    /**
     * The screen came back to the foreground. Availability and the grant are both read again:
     * either can change in Health Connect's own settings while this screen is behind them.
     */
    data object Resumed : HealthConnectEvent

    /** "Connect" was tapped. Opens the rationale; nothing is asked of the system yet. */
    data object ConnectRequested : HealthConnectEvent

    /** The rationale was read and accepted. The system dialog comes next. */
    data object RationaleAccepted : HealthConnectEvent

    /** The rationale was dismissed. Nothing is asked. */
    data object RationaleDismissed : HealthConnectEvent

    /** The route has launched the system dialog. Clears the one-shot. */
    data object PermissionRequestLaunched : HealthConnectEvent

    /** The system dialog could not be launched at all. */
    data object PermissionRequestFailed : HealthConnectEvent

    /** The system dialog answered, with the permission names now granted. */
    data class PermissionResult(val granted: Set<String>) : HealthConnectEvent

    /** The workouts group's "Import the last 30 days" was tapped. */
    data object WorkoutsImportRequested : HealthConnectEvent

    /** The body group's "Import the last 30 days" was tapped. */
    data object BodyImportRequested : HealthConnectEvent

    /** "Disconnect" was tapped. */
    data object DisconnectRequested : HealthConnectEvent

    /** The snackbar for [HealthConnectUiState.message] is up. */
    data object MessageShown : HealthConnectEvent
}
