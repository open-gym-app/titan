package com.titan.feature.progression.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.model.ProgressPhoto
import com.titan.core.domain.repository.ProgressPhotoRepository
import com.titan.core.domain.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The progress photo gallery (#304): every photo, adding one, opening one, comparing two and
 * deleting one.
 *
 * ### Nothing here is saved across a process death
 *
 * Unlike the body screen's open form, nothing on this screen is typing: a viewer or a comparison
 * that does not come back after the process died costs a tap, and a pending delete is exactly the
 * kind of question that should be asked again rather than answered from memory.
 */
@HiltViewModel
class ProgressPhotosViewModel @Inject constructor(
    private val repository: ProgressPhotoRepository,
    private val clock: Clock,
) : ViewModel() {

    private val screen = MutableStateFlow(Screen())

    val uiState: StateFlow<ProgressPhotosUiState> =
        combine(repository.getAll(), screen) { photos, screen ->
            val byId = photos.associateBy(ProgressPhoto::id)
            ProgressPhotosUiState(
                photos = photos,
                isLoading = false,
                // Resolved against the live list, so a photo deleted from under an open viewer
                // closes the viewer rather than drawing a file that is no longer there.
                viewing = screen.viewing?.let(byId::get),
                comparingFrom = screen.comparingFrom?.let(byId::get),
                comparison = screen.comparison?.let { (a, b) ->
                    val first = byId[a] ?: return@let null
                    val second = byId[b] ?: return@let null
                    listOf(first, second).sortedBy(ProgressPhoto::takenAt).let { it[0] to it[1] }
                },
                confirmingDelete = screen.confirmingDelete?.let(byId::get),
                isAdding = screen.isAdding,
                message = screen.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ProgressPhotosUiState())

    fun onEvent(event: ProgressPhotosEvent) {
        when (event) {
            is ProgressPhotosEvent.PhotoPicked -> add(event)

            ProgressPhotosEvent.PhotoUnavailable -> screen.update {
                it.copy(
                    message = ProgressPhotosMessage.ADD_FAILED,
                )
            }

            is ProgressPhotosEvent.PhotoTapped -> tap(event.id)

            ProgressPhotosEvent.ViewerClosed -> screen.update { it.copy(viewing = null) }

            is ProgressPhotosEvent.CompareStarted ->
                screen.update { it.copy(viewing = null, comparingFrom = event.id, comparison = null) }

            ProgressPhotosEvent.CompareCancelled -> screen.update { it.copy(comparingFrom = null, comparison = null) }

            is ProgressPhotosEvent.DeleteRequested -> screen.update { it.copy(confirmingDelete = event.id) }

            ProgressPhotosEvent.DeleteCancelled -> screen.update { it.copy(confirmingDelete = null) }

            ProgressPhotosEvent.DeleteConfirmed -> delete()

            ProgressPhotosEvent.MessageShown -> screen.update { it.copy(message = null) }
        }
    }

    /** Opens [id], or — while a comparison is being started — pairs it with the first photo. */
    private fun tap(id: Long) {
        screen.update { state ->
            val from = state.comparingFrom
            when {
                from == null -> state.copy(viewing = id)
                from == id -> state
                else -> state.copy(comparingFrom = null, comparison = from to id)
            }
        }
    }

    /** Stores the picked image, stamped now unless the image says when it was taken. */
    private fun add(event: ProgressPhotosEvent.PhotoPicked) {
        if (screen.value.isAdding) {
            event.cleanup?.invoke()
            return
        }
        screen.update { it.copy(isAdding = true) }
        viewModelScope.launch {
            try {
                repository.add(event.source, takenAt = clock.nowMillis(), preferImageTime = true)
                screen.update { it.copy(isAdding = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Anything the store or the decoder threw: the bytes were not an image, the disk
                // was full. One sentence for all of them, and nothing was added.
                screen.update { it.copy(isAdding = false, message = ProgressPhotosMessage.ADD_FAILED) }
            } finally {
                event.cleanup?.invoke()
            }
        }
    }

    private fun delete() {
        val id = screen.value.confirmingDelete ?: return
        screen.update { it.copy(confirmingDelete = null, viewing = null) }
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                screen.update { it.copy(message = ProgressPhotosMessage.DELETE_FAILED) }
            }
        }
    }

    /** What the user is in the middle of, by photo id; resolved against the live list. */
    private data class Screen(
        val viewing: Long? = null,
        val comparingFrom: Long? = null,
        val comparison: Pair<Long, Long>? = null,
        val confirmingDelete: Long? = null,
        val isAdding: Boolean = false,
        val message: ProgressPhotosMessage? = null,
    )

    private companion object {
        /** The other screens' number, for their reasons. */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
