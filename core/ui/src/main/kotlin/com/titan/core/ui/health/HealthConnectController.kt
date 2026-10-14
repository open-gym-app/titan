package com.titan.core.ui.health

import com.titan.core.domain.health.HealthConnectControls
import com.titan.core.domain.health.HealthConnectControls.EnableStep
import com.titan.core.domain.health.ImportFromHealthConnectUseCase
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthSyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The state behind the Health Connect rows, shared by every screen that draws them.
 *
 * A plain class that a ViewModel owns, rather than a ViewModel of its own: `:core:ui` carries
 * no dependency injection, and two screens — settings and body measurements — show the same
 * switch over the same stored toggle. Each constructs one over its own scope.
 *
 * ### The order of asking
 *
 * Turning sync on without the permission raises the rationale first; only its confirmation
 * sets [HealthConnectUiState.launchPermission], which the screen's effect turns into the
 * system prompt. A prompt with no explanation in front of it is the one most reliably denied,
 * and a denial is hard to take back. A denial leaves sync off and says so.
 */
class HealthConnectController(
    private val controls: HealthConnectControls,
    private val importer: ImportFromHealthConnectUseCase,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(HealthConnectUiState())
    val state: StateFlow<HealthConnectUiState> = mutableState.asStateFlow()

    fun onEvent(event: HealthConnectEvent) {
        when (event) {
            HealthConnectEvent.Refresh -> scope.launch { guarded { refresh() } }

            is HealthConnectEvent.SyncToggled -> scope.launch { guarded { toggle(event.on) } }

            HealthConnectEvent.RationaleConfirmed ->
                mutableState.update { it.copy(rationaleVisible = false, launchPermission = true) }

            HealthConnectEvent.RationaleDismissed -> mutableState.update { it.copy(rationaleVisible = false) }

            HealthConnectEvent.PermissionRequestLaunched -> mutableState.update { it.copy(launchPermission = false) }

            HealthConnectEvent.PermissionResult -> scope.launch { guarded { permissionResult() } }

            HealthConnectEvent.ImportTapped -> scope.launch { guarded { import() } }
        }
    }

    private suspend fun refresh() {
        val status = controls.status()
        mutableState.update { it.copy(status = status) }
    }

    private suspend fun toggle(on: Boolean) {
        mutableState.update { it.copy(message = null) }
        if (!on) {
            controls.disable()
        } else if (controls.requestEnable() == EnableStep.NeedsPermission) {
            mutableState.update { it.copy(rationaleVisible = true) }
        }
        refresh()
    }

    private suspend fun permissionResult() {
        val step = controls.permissionResult()
        val message = HealthConnectMessage.PermissionDenied.takeIf { step == EnableStep.Denied }
        mutableState.update { it.copy(message = message) }
        refresh()
    }

    private suspend fun import() {
        if (mutableState.value.importing) return
        mutableState.update { it.copy(importing = true, message = null) }
        try {
            val outcome = importer().getOrThrow()
            val imported = (outcome as? HealthSyncOutcome.Imported)?.imported ?: 0
            mutableState.update { it.copy(message = HealthConnectMessage.Imported(imported)) }
        } finally {
            mutableState.update { it.copy(importing = false) }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: HealthConnectException) {
            mutableState.update { it.copy(message = HealthConnectMessage.Unreachable) }
        } catch (_: IOException) {
            mutableState.update { it.copy(message = HealthConnectMessage.Unreachable) }
        }
    }
}
