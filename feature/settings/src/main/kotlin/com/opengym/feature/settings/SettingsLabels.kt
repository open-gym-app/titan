package com.opengym.feature.settings

import androidx.annotation.StringRes

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
