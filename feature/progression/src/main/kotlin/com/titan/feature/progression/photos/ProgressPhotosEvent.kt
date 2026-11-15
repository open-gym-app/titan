package com.titan.feature.progression.photos

import com.titan.core.domain.repository.PhotoSource

sealed interface ProgressPhotosEvent {

    /**
     * An image arrived from the camera or the picker.
     *
     * @param cleanup run once the image has been stored or refused — the camera's capture is a
     *   scratch file in the cache, and a photo of the user's body should not outlive its copy.
     */
    data class PhotoPicked(
        val source: PhotoSource,
        val cleanup: (() -> Unit)? = null,
    ) : ProgressPhotosEvent

    /** The camera could not be started, or gave nothing back: say so, add nothing. */
    data object PhotoUnavailable : ProgressPhotosEvent

    /** A thumbnail was tapped: open it, or finish a comparison when one was started. */
    data class PhotoTapped(val id: Long) : ProgressPhotosEvent

    data object ViewerClosed : ProgressPhotosEvent

    /** The viewer's "Compare": the next photo tapped is compared with this one. */
    data class CompareStarted(val id: Long) : ProgressPhotosEvent

    data object CompareCancelled : ProgressPhotosEvent

    data class DeleteRequested(val id: Long) : ProgressPhotosEvent

    data object DeleteConfirmed : ProgressPhotosEvent

    data object DeleteCancelled : ProgressPhotosEvent

    data object MessageShown : ProgressPhotosEvent
}
