package com.opengym.core.domain.notification

/**
 * Which kinds of notification the user has agreed to, and whether they have ever been asked
 * for the permission behind them.
 *
 * ### Off is the default, with one exception
 *
 * Everything starts off except [NotificationKind.REST_TIMER]. Turning a notification on is a
 * decision, and a default-on notification is a decision the user did not make — they find out
 * about it when the phone buzzes. The rest timer is the exception because it is the one kind
 * the user has *already* asked for by starting a rest: the countdown is running, it is going
 * to end, and the only question is whether they hear about it when the screen is off.
 *
 * A permission is still required on top of this — see [NotificationPermissionState]. The
 * preference being on means the user wants the notification; it never means one will arrive.
 *
 * ### The set of what is on, rather than a flag per kind
 *
 * A [Set] instead of four named booleans, so a fifth kind defaults to off by simply not being
 * in [DEFAULT_ENABLED] rather than by somebody remembering to write `= false`. The stored
 * form is still one key per kind — see the data layer — because the *store* has to degrade
 * one setting at a time.
 *
 * @param enabled the kinds the user wants. Absent means off, which is also what an empty set
 *   and a fresh store mean.
 * @param permissionRequested whether the system permission prompt has already been shown
 *   once. Android cannot be asked this: `shouldShowRequestPermissionRationale` reads the same
 *   before the first prompt as it does after a refusal, so the only way to tell "not asked
 *   yet" from "asked and refused" is to remember asking. Without it a fresh install renders
 *   its notification rows as blocked, having never said a word to the user.
 */
data class NotificationPreferences(
    val enabled: Set<NotificationKind> = DEFAULT_ENABLED,
    val permissionRequested: Boolean = false,
) {

    /** Whether the user wants to hear about [kind]. */
    operator fun contains(kind: NotificationKind): Boolean = kind in enabled

    /** This, with [kind] turned on or off. */
    fun with(
        kind: NotificationKind,
        enabled: Boolean,
    ): NotificationPreferences = copy(enabled = if (enabled) this.enabled + kind else this.enabled - kind)

    companion object {

        /**
         * What a fresh install wants: the rest timer, and nothing else.
         *
         * See the class note — this is the one notification the user has already asked for by
         * the time it fires.
         */
        val DEFAULT_ENABLED: Set<NotificationKind> = setOf(NotificationKind.REST_TIMER)
    }
}
