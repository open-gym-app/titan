package com.opengym.core.domain.usecase

import com.opengym.core.domain.notification.NotificationPermission
import com.opengym.core.domain.notification.NotificationPermissionState
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.first

/**
 * The decision to raise the system's notification permission prompt, taken once and in one
 * place.
 *
 * ### Why a use case and not two copies of an `if`
 *
 * Two screens turn notifications on — the settings list, and #39's reminder screen — and both
 * have to answer the same question at the moment they do: is there a prompt worth raising, and
 * has it already been raised? Getting that wrong is not a bug that shows up in review. Asking
 * when the prompt cannot appear leaves a screen waiting on a callback that never comes; asking
 * *twice* is worse, because Android answers the second one for the user with the refusal they
 * gave the first — so the second screen to be written is the one that teaches people to say no.
 *
 * ### It records the asking before the answer
 *
 * Deliberately, and it is the reason this returns a boolean rather than being a pure query:
 * the flag records that the prompt *went up*, and a user who dismisses it with the back gesture
 * has still been asked. Recording on the answer instead would let every subsequent tap raise it
 * again for exactly the user who has already declined to engage with it.
 *
 * Android cannot be asked this. `shouldShowRequestPermissionRationale` reads the same before
 * the first prompt as after a refusal, so the app's own memory is the only way to tell "not
 * asked yet" from "asked and refused" — see
 * [com.opengym.core.domain.notification.NotificationPreferences.permissionRequested].
 *
 * ### It does not raise anything
 *
 * Only something attached to an Activity can, so the screen does it. This says whether it
 * should, which is what makes the decision testable without one.
 */
class RequestNotificationPermissionUseCase(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val permission: NotificationPermission,
) {

    /**
     * @return whether the caller should raise the system prompt now. False when notifications
     *   are already allowed (nothing to ask), when the device has no runtime permission to ask
     *   for (Android 12 and below), and when the prompt has been raised before — in which case
     *   the state is [NotificationPermissionState.DENIED] and the only thing left to offer is a
     *   way into the system settings.
     */
    suspend operator fun invoke(): Boolean {
        // Read from the store rather than from anything a screen is holding: the decision must
        // not depend on a collector having caught up with a write made a frame ago.
        val alreadyRequested = userPreferencesRepository.observe().first().notifications.permissionRequested
        val state = NotificationPermissionState.of(
            allowed = permission.areNotificationsAllowed(),
            runtimePermissionRequired = permission.isRuntimePermissionRequired,
            alreadyRequested = alreadyRequested,
        )
        if (state != NotificationPermissionState.NOT_REQUESTED) return false

        userPreferencesRepository.setNotificationPermissionRequested()
        return true
    }
}
