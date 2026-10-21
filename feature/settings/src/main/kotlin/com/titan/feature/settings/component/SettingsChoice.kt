package com.titan.feature.settings.component

/**
 * One option in a [ChoiceDialog]: what it is called, what it means, and the value it stands
 * for.
 *
 * [detail] is optional and is worth writing wherever the label alone is a guess — `Auto` says
 * nothing without "Follows your device", and `Hypertrophy` means little to somebody who is
 * three weeks into training, which is exactly who this app is for.
 */
data class SettingsChoice<T>(
    val value: T,
    val label: String,
    val detail: String? = null,
)
