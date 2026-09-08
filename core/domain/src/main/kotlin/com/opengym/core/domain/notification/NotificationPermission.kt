package com.opengym.core.domain.notification

/**
 * Whether the system will carry anything this app posts.
 *
 * Read rather than stored: the answer changes outside the app — in system settings, at any
 * moment, with the app not running — so there is no version of this that can be cached and
 * still be true. Every caller asks again.
 *
 * The one Android concept the domain does keep hold of, because both halves of the app need
 * it and neither can reach the platform: `Notifier` refuses to post without it, and the
 * settings screen has to render a toggle whose permission is missing as *blocked* rather than
 * as on. Implemented in `:core:data`, which is where `NotificationManagerCompat` lives.
 */
interface NotificationPermission {

    /**
     * Whether a notification posted right now would be delivered.
     *
     * Broader than the `POST_NOTIFICATIONS` grant, deliberately: a user who never denied the
     * permission but switched the app's notifications off in system settings is in exactly
     * the same position, and on Android 12 and below that switch is the *only* way to be in
     * it. One question with one answer, on every version.
     */
    fun areNotificationsAllowed(): Boolean

    /**
     * Whether this device has a runtime permission to ask for at all — Android 13 and up.
     *
     * Below that, notifications are granted at install time and [areNotificationsAllowed]
     * returning false means the user turned them off by hand. Prompting there does nothing,
     * so the settings screen sends them to system settings instead.
     */
    val isRuntimePermissionRequired: Boolean
}

/**
 * The three positions a user can be in, as far as a notification-backed setting is concerned.
 *
 * Three rather than two because "we have not asked yet" and "they said no" need different
 * answers from the UI, and the platform conflates them — see
 * [NotificationPreferences.permissionRequested]. Derived rather than stored: [of] is the whole
 * of the rule, and it is here so that the screen and its tests agree on it.
 */
enum class NotificationPermissionState {

    /** Notifications get through. Nothing to ask and nothing to say. */
    GRANTED,

    /** Android 13+, never prompted. The next setting the user turns on is what prompts them. */
    NOT_REQUESTED,

    /**
     * Refused, or switched off in system settings. The prompt will not appear again, so the
     * only thing left to offer is a way into the system settings screen.
     */
    DENIED,
    ;

    /** Whether anything posted now would actually arrive. */
    val isGranted: Boolean get() = this == GRANTED

    companion object {

        /**
         * @param allowed [NotificationPermission.areNotificationsAllowed], read just now.
         * @param runtimePermissionRequired [NotificationPermission.isRuntimePermissionRequired].
         * @param alreadyRequested [NotificationPreferences.permissionRequested].
         */
        fun of(
            allowed: Boolean,
            runtimePermissionRequired: Boolean,
            alreadyRequested: Boolean,
        ): NotificationPermissionState =
            when {
                allowed -> GRANTED
                runtimePermissionRequired && !alreadyRequested -> NOT_REQUESTED
                else -> DENIED
            }
    }
}
