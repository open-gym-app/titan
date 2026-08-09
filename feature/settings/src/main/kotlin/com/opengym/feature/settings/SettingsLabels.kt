package com.opengym.feature.settings

import androidx.annotation.StringRes
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem

/*
 * The domain enums' display names.
 *
 * Extension functions in the feature rather than a `labelRes` property on the enum: the domain
 * is a plain Kotlin library with no Android on its classpath and no business knowing what a
 * string resource is. Exhaustive `when`s, so adding a value to one of these enums fails to
 * compile here rather than rendering a blank row.
 */

@StringRes
internal fun ThemePreference.labelRes(): Int = when (this) {
    ThemePreference.LIGHT -> R.string.settings_theme_light
    ThemePreference.DARK -> R.string.settings_theme_dark
    ThemePreference.SYSTEM -> R.string.settings_theme_system
}

@StringRes
internal fun ThemePreference.detailRes(): Int? = when (this) {
    ThemePreference.SYSTEM -> R.string.settings_theme_system_detail
    else -> null
}

@StringRes
internal fun UnitSystem.labelRes(): Int = when (this) {
    UnitSystem.METRIC -> R.string.settings_units_metric
    UnitSystem.IMPERIAL -> R.string.settings_units_imperial
}

@StringRes
internal fun UnitSystem.detailRes(): Int = when (this) {
    UnitSystem.METRIC -> R.string.settings_units_metric_detail
    UnitSystem.IMPERIAL -> R.string.settings_units_imperial_detail
}

@StringRes
internal fun TrainingGoal.labelRes(): Int = when (this) {
    TrainingGoal.STRENGTH -> R.string.settings_goal_strength
    TrainingGoal.HYPERTROPHY -> R.string.settings_goal_hypertrophy
    TrainingGoal.ENDURANCE -> R.string.settings_goal_endurance
    TrainingGoal.CUSTOM -> R.string.settings_goal_custom
}

@StringRes
internal fun TrainingGoal.detailRes(): Int = when (this) {
    TrainingGoal.STRENGTH -> R.string.settings_goal_strength_detail
    TrainingGoal.HYPERTROPHY -> R.string.settings_goal_hypertrophy_detail
    TrainingGoal.ENDURANCE -> R.string.settings_goal_endurance_detail
    TrainingGoal.CUSTOM -> R.string.settings_goal_custom_detail
}

@StringRes
internal fun SettingsMessage.textRes(): Int = when (this) {
    SettingsMessage.SAVE_FAILED -> R.string.settings_save_failed
    SettingsMessage.DELETE_SUCCEEDED -> R.string.settings_delete_done
    SettingsMessage.DELETE_FAILED -> R.string.settings_delete_failed
}
