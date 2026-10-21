package com.titan.core.domain.notification

/**
 * A kind of thing the app says to somebody who is not looking at it.
 *
 * Four kinds, and on Android four *channels* — one each, rather than one channel for the
 * whole app. Channel importance is the only place the platform lets a user say "buzz for
 * this, stay quiet for that", so a single channel would make silencing a loot drop and
 * silencing the rest timer the same action. They are also the unit the user's own
 * preference is stored against, which is why this enum is in the domain rather than being
 * a set of channel-id strings in the data layer: a use case decides *whether* to say
 * something, and the kind is what that decision is made against.
 *
 * The channel each one maps to, its importance and the id it is registered under all live
 * in `:core:data` — see `NotificationChannels`. Nothing here knows what an Android channel
 * is.
 *
 * Adding an entry is deliberately a compile error in four places: the channel table, the
 * stored key, the settings row, and [NotificationPreferences.DEFAULT_ENABLED]. A kind that
 * could be added without saying how it is registered, how it is stored and how it is turned
 * off is a kind that ships un-silenceable.
 */
enum class NotificationKind {

    /** "Train today." Scheduled by the user for the days they pick — #39. */
    REMINDERS,

    /**
     * "The rest is over." The one kind the app posts *because the screen cannot be seen*:
     * the phone is in a pocket or face-down on a bench, which is exactly when a rest ends.
     */
    REST_TIMER,

    /**
     * "You left a workout open." The nudge about a session nobody is working out — #178.
     *
     * Quiet by construction rather than by choice: the channel is registered at low importance
     * with no sound, because the user is not waiting on this and an interruption would be a
     * reminder behaving like an alarm.
     */
    SESSION,

    /** "New level, quest complete, loot dropped." The reward announcements — #179. */
    PROGRESSION,
}
