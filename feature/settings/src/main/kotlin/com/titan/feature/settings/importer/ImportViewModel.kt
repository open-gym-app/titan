package com.titan.feature.settings.importer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportRun
import com.titan.core.domain.importer.HistoryImportRunner
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.ui.state.savedScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The import from another app (#252): which app, how to get its export out, and the import
 * itself — run by [HistoryImportRunner] and only *observed* here.
 *
 * ### Why this class never collects the import
 *
 * The engine writes the whole history in one transaction that a cancelled collector rolls back,
 * and `viewModelScope` is cancelled the moment the user leaves the screen. A large history takes
 * minutes, so collecting it here would make leaving the screen undo the import. The runner runs
 * it in a foreground worker instead, and everything from [ImportStage.Running] on is read back
 * from it — which is also what lets the screen, opened from the notification in a process
 * started after the import's, show where the import is.
 *
 * ### A workout in progress is asked about first
 *
 * The engine leaves the workout alone, but while the import runs every database write waits
 * behind it, a set being ticked off included. For a large history that is minutes. So a file
 * picked while a workout is open is held in [ImportUiState.pendingRequest] until the user says
 * to go ahead; without one, the import starts at once.
 *
 * ### Counts only move forward
 *
 * The engine already drops progress it cannot deliver rather than queueing it, so its counts
 * skip but never go back, and WorkManager delivers the worker's records in order. The screen
 * holds itself to the same promise anyway ([notBehind]): a bar that slid back would read as
 * work being undone, and costs nothing to rule out here.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val runner: HistoryImportRunner,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    /**
     * What the screen owns. The chosen app and a picked file awaiting the workout question
     * survive a process death (#183) — the second is a document the picker handed over, and
     * losing it means going back through the picker. The rest is about this composition only.
     */
    private val screenState = savedStateHandle.savedScreenState(viewModelScope) { ScreenState() }

    val uiState: StateFlow<ImportUiState> = combine(runner.observe().forwardOnly(), screenState) { run, screen ->
        ImportUiState(
            stage = stageOf(run, screen),
            pendingRequest = screen.pendingRequest,
            confirmingCancel = screen.confirmingCancel && run is HistoryImportRun.Running,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        // Nothing yet: the runner is a WorkManager read, and drawing the list of apps for the
        // frames before it answers would flash a choice over an import that is running.
        initialValue = ImportUiState(stage = ImportStage.Loading),
    )

    fun onEvent(event: ImportEvent) {
        when (event) {
            is ImportEvent.SourceChosen -> screenState.update {
                it.copy(
                    chosenSource = event.source,
                    startFailed = false,
                )
            }

            ImportEvent.GuideClosed -> screenState.update { it.copy(chosenSource = null, startFailed = false) }

            is ImportEvent.FileChosen -> {
                val source = screenState.value.chosenSource ?: return
                // The picker only ever hands back a `content://` document. Anything else — a
                // path read with this app's own permissions — is not something a user picked.
                if (!event.uri.startsWith(CONTENT_SCHEME)) return
                askThenStart(HistoryImportRequest(source, event.uri))
            }

            ImportEvent.StartConfirmed -> {
                val request = screenState.value.pendingRequest ?: return
                screenState.update { it.copy(pendingRequest = null) }
                start(request)
            }

            ImportEvent.StartDismissed -> screenState.update { it.copy(pendingRequest = null) }

            ImportEvent.CancelRequested -> screenState.update { it.copy(confirmingCancel = true) }

            ImportEvent.CancelConfirmed -> cancel()

            ImportEvent.CancelDismissed -> screenState.update { it.copy(confirmingCancel = false) }

            ImportEvent.RunAgain -> (uiState.value.stage as? ImportStage.Interrupted)?.let { askThenStart(it.request) }

            ImportEvent.TryAgain -> {
                val source = (uiState.value.stage as? ImportStage.Failed)?.source ?: return
                dismiss(thenShow = source)
            }

            ImportEvent.ChooseAnotherApp -> dismiss(thenShow = null)

            ImportEvent.OutcomeDismissed -> dismiss(thenShow = null)
        }
    }

    /** Starts [request], unless a workout is in progress — then it waits on the question. */
    private fun askThenStart(request: HistoryImportRequest) {
        viewModelScope.launch {
            val workoutInProgress = runCatching { sessionRepository.getActive().first() != null }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                // A store that cannot say is asked about rather than assumed empty: the
                // question costs one tap, a surprise wait mid-workout costs more.
                true
            }
            if (workoutInProgress) {
                screenState.update { it.copy(pendingRequest = request) }
            } else {
                start(request)
            }
        }
    }

    private fun start(request: HistoryImportRequest) {
        // `confirmingCancel` too: a question left up when the last import ended under it must not
        // pop up again over this one.
        screenState.update {
            it.copy(
                chosenSource = request.source,
                confirmingCancel = false,
                cancelledDuring = null,
                startFailed = false,
            )
        }
        viewModelScope.launch {
            // Broad: enqueueing is a platform call whose failures this build cannot enumerate,
            // and every one of them means the same to the user — the import did not start, and
            // nothing was written.
            runCatching { runner.start(request) }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                screenState.update { it.copy(startFailed = true) }
            }
        }
    }

    /**
     * Stops the import, remembering which phase it was in: stopped while the photos are being
     * stored, the history is already in and only photos were skipped, which the screen says.
     */
    private fun cancel() {
        val progress = (uiState.value.stage as? ImportStage.Running)?.progress
        screenState.update { it.copy(confirmingCancel = false, cancelledDuring = progress?.let(Phase::of)) }
        viewModelScope.launch {
            runCatching { runner.cancel() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** Forgets how the last import ended and shows [thenShow]'s guide, or the list of apps. */
    private fun dismiss(thenShow: HistoryImportSource?) {
        screenState.update {
            it.copy(chosenSource = thenShow, confirmingCancel = false, cancelledDuring = null, startFailed = false)
        }
        viewModelScope.launch {
            runCatching { runner.dismiss() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /**
     * What the screen owns and the worker does not.
     *
     * @property chosenSource the app whose guide is open, or the last import's.
     * @property pendingRequest a picked file waiting on the "a workout is in progress" question.
     * @property cancelledDuring the phase the user stopped the import in, for the sentence after.
     *   Transient: after a process death the screen no longer knows, and says the sentence that is
     *   true either way.
     * @property startFailed the last start could not be handed to the runner at all.
     */
    @Serializable
    private data class ScreenState(
        val chosenSource: HistoryImportSource? = null,
        val pendingRequest: HistoryImportRequest? = null,
        @Transient
        val confirmingCancel: Boolean = false,
        @Transient
        val cancelledDuring: Phase? = null,
        @Transient
        val startFailed: Boolean = false,
    )

    /** The two phases a stop means different things in. */
    private enum class Phase {
        HISTORY,
        PHOTOS,
        ;

        companion object {
            fun of(progress: HistoryImportProgress): Phase =
                if (progress is HistoryImportProgress.StoringPhotos) PHOTOS else HISTORY
        }
    }

    private companion object {

        const val CONTENT_SCHEME = "content://"

        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds

        fun stageOf(
            run: HistoryImportRun,
            screen: ScreenState,
        ): ImportStage =
            when (run) {
                HistoryImportRun.None -> {
                    val source = screen.chosenSource
                    when {
                        source == null -> ImportStage.ChooseSource
                        screen.startFailed -> ImportStage.Failed(source, HistoryImportError.StorageFailure(""))
                        else -> ImportStage.Guide(source)
                    }
                }

                is HistoryImportRun.Running -> ImportStage.Running(run.request.source, run.progress)

                is HistoryImportRun.Completed -> ImportStage.Completed(run.request.source, run.report)

                is HistoryImportRun.Failed -> ImportStage.Failed(run.request.source, run.error)

                is HistoryImportRun.Interrupted -> ImportStage.Interrupted(run.request)

                is HistoryImportRun.Cancelled -> ImportStage.Stopped(
                    source = run.request.source,
                    stop = when (screen.cancelledDuring) {
                        Phase.PHOTOS -> ImportStop.PHOTOS_SKIPPED
                        Phase.HISTORY -> ImportStop.NOTHING_IMPORTED
                        null -> ImportStop.UNKNOWN
                    },
                )
            }
    }
}

/** [this] with every count held at or above the last one of the same phase of the same import. */
internal fun Flow<HistoryImportRun>.forwardOnly(): Flow<HistoryImportRun> =
    runningFold<HistoryImportRun, HistoryImportRun?>(
        null,
    ) { previous, next -> next.notBehind(previous) }.filterNotNull()

/**
 * [this], with its counts raised to [previous]'s where both are the same phase of the same
 * import — so a bar never slides back. Anything else passes through.
 */
internal fun HistoryImportRun.notBehind(previous: HistoryImportRun?): HistoryImportRun {
    if (this !is HistoryImportRun.Running || previous !is HistoryImportRun.Running ||
        request != previous.request
    ) {
        return this
    }
    val now = progress
    val before = previous.progress
    return when {
        now is HistoryImportProgress.Writing && before is HistoryImportProgress.Writing ->
            copy(progress = now.copy(workoutsWritten = maxOf(now.workoutsWritten, before.workoutsWritten)))

        now is HistoryImportProgress.StoringPhotos && before is HistoryImportProgress.StoringPhotos ->
            copy(progress = now.copy(photosStored = maxOf(now.photosStored, before.photosStored)))

        else -> this
    }
}
