package com.titan.feature.progression.photos

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.ProgressPhoto

/**
 * The progress photo gallery.
 *
 * @property photos every photo, most recently taken first.
 * @property viewing the photo open full screen, or null.
 * @property comparingFrom the photo a comparison was started from: while it is set, the next photo
 *   tapped in the grid becomes the other half of [comparison] rather than opening on its own.
 * @property comparison the two photos side by side, older first, or null.
 * @property confirmingDelete the photo the user has asked to delete, awaiting the confirmation.
 * @property isAdding whether a photo is being stored — the add action goes inert, so a second tap
 *   cannot store the same capture twice.
 */
@Immutable
data class ProgressPhotosUiState(
    val photos: List<ProgressPhoto> = emptyList(),
    val isLoading: Boolean = true,
    val viewing: ProgressPhoto? = null,
    val comparingFrom: ProgressPhoto? = null,
    val comparison: Pair<ProgressPhoto, ProgressPhoto>? = null,
    val confirmingDelete: ProgressPhoto? = null,
    val isAdding: Boolean = false,
    val message: ProgressPhotosMessage? = null,
) {
    val isEmpty: Boolean get() = !isLoading && photos.isEmpty()
}

/** Something to say once. */
enum class ProgressPhotosMessage {

    /** The picked or captured image could not be read or stored. Nothing was added. */
    ADD_FAILED,

    /** The photo could not be deleted. It is still there. */
    DELETE_FAILED,
}
