package com.opengym.feature.settings

import androidx.annotation.StringRes
import com.opengym.core.domain.notification.NotificationKind

/*
 * The screen's own strings that are chosen rather than written inline.
 *
 * The *stored preferences* — the theme, the unit system and the training goal — are no longer
 * labelled here. Onboarding (#M4-22) asks for two of the three on first launch, which made
 * this file the second-best place for them: they moved to `:core:ui`'s `PreferenceLabels.kt`
 * under the rule the design system already stated for the domain enums, so two screens cannot
 * describe one stored value differently. Reach for `theme.label()`, `goal.detail()` and their
 * siblings.
 */

@StringRes
internal fun SettingsMessage.textRes(): Int =
    when (this) {
        SettingsMessage.SAVE_FAILED -> R.string.settings_save_failed
        SettingsMessage.DELETE_SUCCEEDED -> R.string.settings_delete_done
        SettingsMessage.DELETE_FAILED -> R.string.settings_delete_failed
    }

/**
 * What a notification switch is called.
 *
 * Here rather than in `:core:ui`'s `PreferenceLabels.kt` — where the theme, unit system and
 * training goal live — because this screen is the only one that names these: the channels have
 * their own names in the *system's* settings, written in `:core:data` beside the code that
 * registers them, and those are a different audience answering a different question ("what
 * would I lose by silencing this?" rather than "what is this switch?").
 *
 * "Rest timer alerts", not "Rest timer": the workout group two cards up already has a row
 * called Default rest timer, and two settings with one name is one of them being changed by
 * mistake.
 */
@StringRes
internal fun NotificationKind.labelRes(): Int =
    when (this) {
        NotificationKind.REMINDERS -> R.string.settings_reminders
        NotificationKind.REST_TIMER -> R.string.settings_rest_timer_alerts
        NotificationKind.SESSION -> R.string.settings_unfinished_session
        NotificationKind.PROGRESSION -> R.string.settings_streak_protection
    }

/** The line under the label: what stops arriving when the switch goes off. */
@StringRes
internal fun NotificationKind.detailRes(): Int =
    when (this) {
        NotificationKind.REMINDERS -> R.string.settings_reminders_detail
        NotificationKind.REST_TIMER -> R.string.settings_rest_timer_alerts_detail
        NotificationKind.SESSION -> R.string.settings_unfinished_session_detail
        NotificationKind.PROGRESSION -> R.string.settings_streak_protection_detail
    }
