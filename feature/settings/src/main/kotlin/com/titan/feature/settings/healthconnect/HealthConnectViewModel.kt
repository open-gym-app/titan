package com.titan.feature.settings.healthconnect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.healthconnect.availabilityOrUnsupported
import com.titan.core.domain.usecase.ImportBodyFromHealthConnectUseCase
import com.titan.core.domain.usecase.ImportFromHealthConnectUseCase
import com.titan.core.ui.state.savedScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * The Health Connect screen: what is granted, the conversation that grants it, and the two
 * imports — workouts, and weight and body fat.
 *
 * ### The rationale comes first, every time
 *
 * "Connect" opens an explanation of what will be read and written, and only its "Continue"
 * launches the system dialog. Health Connect lists permissions by name and nothing else; a user
 * who has not been told that Titan writes the type and the times of a workout and not its sets,
 * or a weight and not a waist, is being asked to grant something they cannot judge — and Health Connect stops showing its
 * dialog after the second refusal, so an unexplained first ask is one of only two chances.
 *
 * ### Nothing is cached
 *
 * The grant is read on every resume. It changes in Health Connect's own settings, which this
 * screen links to, and the user comes back from there to a screen that has to say what is true
 * now. See [HealthConnect] for why there is no preference to read instead.
 */
@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val healthConnect: HealthConnect,
    private val importFromHealthConnect: ImportFromHealthConnectUseCase,
    private val importBodyFromHealthConnect: ImportBodyFromHealthConnectUseCase,
) : ViewModel() {

    private val screenState = savedStateHandle.savedScreenState(viewModelScope) { ScreenState() }

    private val state = MutableStateFlow(
        HealthConnectUiState(
            availability = healthConnect.availabilityOrUnsupported(),
            permissions = healthConnect.permissions,
            rationaleOpen = screenState.value.rationaleOpen,
            refused = screenState.value.refused,
            canDisconnect = healthConnect.canRevokeAccess,
        ),
    )

    val uiState: StateFlow<HealthConnectUiState> = state.asStateFlow()

    private var importJob: Job? = null

    private var bodyImportJob: Job? = null

    /**
     * Messages waiting behind [HealthConnectUiState.message]. The two imports run side by side
     * and the screen only collects while it is started, so both can finish while the user is in
     * Health Connect's settings; one slot would keep only the last of them.
     */
    private val pendingMessages = ArrayDeque<HealthConnectMessage>()

    /**
     * The grant read in flight. One at a time, the newest winning: a resume, a dialog's answer
     * and a disconnect can each ask within the same moment, and an older answer landing last
     * would draw a grant that is no longer true.
     */
    private var recheckJob: Job? = null

    fun onEvent(event: HealthConnectEvent) {
        when (event) {
            HealthConnectEvent.Resumed -> recheck()

            HealthConnectEvent.ConnectRequested -> setRationaleOpen(true)

            HealthConnectEvent.RationaleDismissed -> setRationaleOpen(false)

            HealthConnectEvent.RationaleAccepted -> {
                setRationaleOpen(false)
                state.update { it.copy(permissionRequest = true) }
            }

            HealthConnectEvent.PermissionRequestLaunched -> state.update { it.copy(permissionRequest = false) }

            HealthConnectEvent.PermissionRequestFailed -> {
                state.update { it.copy(permissionRequest = false) }
                post(HealthConnectMessage.RequestFailed)
            }

            is HealthConnectEvent.PermissionResult -> onPermissionResult(event.granted)

            HealthConnectEvent.WorkoutsImportRequested -> import()

            HealthConnectEvent.BodyImportRequested -> importBody()

            HealthConnectEvent.DisconnectRequested -> disconnect()

            HealthConnectEvent.MessageShown -> state.update { it.copy(message = pendingMessages.removeFirstOrNull()) }
        }
    }

    /**
     * What the system dialog answered.
     *
     * Read back from Health Connect rather than taken from [granted]: the dialog reports what it
     * granted *this time*, and a user who had allowed writing before and now allowed reading has
     * both. A request that came back empty is remembered as a refusal, so the screen can point at
     * Health Connect's settings instead of at a dialog that may no longer appear.
     */
    private fun onPermissionResult(granted: Set<String>) {
        val refused = granted.isEmpty()
        screenState.update { it.copy(refused = refused) }
        state.update { it.copy(refused = refused) }
        recheck()
    }

    private fun recheck() {
        recheckJob?.cancel()
        val availability = healthConnect.availabilityOrUnsupported()
        state.update { it.copy(availability = availability) }
        if (availability != HealthConnectAvailability.AVAILABLE) {
            state.update { it.copy(granted = emptySet()) }
            return
        }
        recheckJob = viewModelScope.launch {
            val granted = try {
                healthConnect.grantedAccess()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // A provider that will not answer is drawn as nothing granted: every control
                // behind a grant would fail the same way, and "Connect" is the one that can fix it.
                emptySet()
            }
            state.update { current ->
                // A grant clears an earlier refusal — the user went to Health Connect's settings
                // and allowed it there.
                current.copy(granted = granted, refused = current.refused && granted.isEmpty())
            }
            if (granted.isNotEmpty()) screenState.update { it.copy(refused = false) }
        }
    }

    /**
     * Runs the workout import, one at a time.
     *
     * A double tap would otherwise start two that read the same month and race to store it. The
     * unique index would keep the history right, but the first one's count would be followed by a
     * "nothing new" that reads as if it took the count back.
     */
    private fun import() {
        if (importJob?.isActive == true || !state.value.canReadExercise) return
        state.update { it.copy(isImporting = true) }
        importJob = viewModelScope.launch {
            val result = importFromHealthConnect()
            val message = result.fold(
                onSuccess = { HealthConnectMessage.Imported(it.imported) },
                onFailure = { HealthConnectMessage.ImportFailed },
            )
            state.update { it.copy(isImporting = false) }
            post(message)
            // A refusal means the grant or the provider changed under the screen — read access
            // taken back in Health Connect's settings, or the app uninstalled — so what it draws
            // is read again rather than left offering an import that will fail the same way.
            if (result.exceptionOrNull() is HealthConnectError) recheck()
        }
    }

    /**
     * Runs the body import, one at a time, for [import]'s reasons — and more so here: a weigh-in
     * has no record id stored against it, so two imports racing each other would both find it
     * new and store it twice. Separate from the workout import, which can run beside it.
     */
    private fun importBody() {
        if (bodyImportJob?.isActive == true || !state.value.canReadBody) return
        state.update { it.copy(isImportingBody = true) }
        bodyImportJob = viewModelScope.launch {
            val result = importBodyFromHealthConnect()
            val message = result.fold(
                onSuccess = { HealthConnectMessage.ImportedMeasurements(it.imported) },
                onFailure = { HealthConnectMessage.ImportFailed },
            )
            state.update { it.copy(isImportingBody = false) }
            post(message)
            if (result.exceptionOrNull() is HealthConnectError) recheck()
        }
    }

    /**
     * Takes the grant back, where the app can. From Android 14 the screen sends the user to
     * Health Connect's permission page instead and this is never asked; the guard keeps a stray
     * event from reporting a failure for a button that was not there.
     */
    private fun disconnect() {
        if (!state.value.canDisconnect) return
        viewModelScope.launch {
            val failed = try {
                healthConnect.revokeAccess()
                false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                true
            }
            if (failed) post(HealthConnectMessage.DisconnectFailed)
            recheck()
        }
    }

    /**
     * Shows [message] now if nothing is waiting to be shown, or queues it behind what is.
     *
     * A message equal to one already waiting is dropped: the same sentence twice says nothing the
     * first did not, and the screen only shows a message it has not just shown, so an equal one
     * moved up by [HealthConnectEvent.MessageShown] would sit in the slot forever.
     */
    private fun post(message: HealthConnectMessage) {
        val current = state.value.message
        when {
            current == null -> state.update { it.copy(message = message) }
            current != message && message !in pendingMessages -> pendingMessages.addLast(message)
        }
    }

    private fun setRationaleOpen(open: Boolean) {
        screenState.update { it.copy(rationaleOpen = open) }
        state.update { it.copy(rationaleOpen = open) }
    }

    /**
     * What survives a process death: whether the rationale was up, and whether the last request
     * was refused. Not the one-shot — re-launching the system dialog unasked on the way back in
     * is what the notification prompt is careful never to do either — and not the grant, which is
     * read again on the resume that follows a restore.
     */
    @Serializable
    private data class ScreenState(
        val rationaleOpen: Boolean = false,
        val refused: Boolean = false,
    )
}
