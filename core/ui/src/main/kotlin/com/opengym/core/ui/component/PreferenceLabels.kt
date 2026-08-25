package com.opengym.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.R

/*
 * The display names of the three *stored preferences* the user is asked about by name.
 *
 * The same rule as `DomainLabels.kt`, applied to the settings enums: an extension in
 * `:core:ui` rather than a `labelRes` on the enum, because `:core:domain` is a plain Kotlin
 * library with no Android on its classpath and no business knowing what a string resource is.
 *
 * They started life in `:feature:settings`, which was correct while settings was the only
 * screen that rendered them. Onboarding (#M4-22) is the second: it asks for the training goal
 * and the unit system on first launch, and settings offers the same two for the rest of the
 * app's life. Two features writing the same words twice is how "Hypertrophy — volume and time
 * under tension" becomes two different sentences about one stored value, so they moved here
 * the moment there was a second caller.
 *
 * Each label is paired with a *detail*: one line saying what the value actually means. It is
 * not decoration — `Auto` says nothing without "Follows your device", and `Hypertrophy` means
 * little to somebody three weeks into training, who is exactly the reader this app is for.
 *
 * Every `when` is exhaustive, so a value added to one of these enums is a compile error here
 * rather than a blank row on two screens.
 */

/** What a colour scheme is called: `Light`, `Dark`, `Auto`. */
@Composable
@ReadOnlyComposable
fun ThemePreference.label(): String = stringResource(labelRes())

@StringRes
internal fun ThemePreference.labelRes(): Int =
    when (this) {
        ThemePreference.LIGHT -> R.string.preference_theme_light
        ThemePreference.DARK -> R.string.preference_theme_dark
        ThemePreference.SYSTEM -> R.string.preference_theme_system
    }

/**
 * What following the device means, or **null** for the two schemes that are what they say.
 *
 * The one label of the three whose detail is optional: `Light` and `Dark` are self-describing
 * and a line under them would be filler, where `Auto` is a promise that has to be spelled out.
 */
@Composable
@ReadOnlyComposable
fun ThemePreference.detailOrNull(): String? =
    when (this) {
        ThemePreference.SYSTEM -> stringResource(R.string.preference_theme_system_detail)
        ThemePreference.LIGHT, ThemePreference.DARK -> null
    }

/** What a unit system is called: `Metric`, `Imperial`. */
@Composable
@ReadOnlyComposable
fun UnitSystem.label(): String = stringResource(labelRes())

@StringRes
internal fun UnitSystem.labelRes(): Int =
    when (this) {
        UnitSystem.METRIC -> R.string.preference_units_metric
        UnitSystem.IMPERIAL -> R.string.preference_units_imperial
    }

/**
 * The units themselves — `Kilograms and kilometres`.
 *
 * Named rather than left to `Metric` and `Imperial`, which are the words a user is least
 * likely to be sure about at the moment they are being asked to choose between them.
 */
@Composable
@ReadOnlyComposable
fun UnitSystem.detail(): String =
    stringResource(
        when (this) {
            UnitSystem.METRIC -> R.string.preference_units_metric_detail
            UnitSystem.IMPERIAL -> R.string.preference_units_imperial_detail
        },
    )

/** What a training goal is called: `Strength`, `Hypertrophy`, `Endurance`, `Custom`. */
@Composable
@ReadOnlyComposable
fun TrainingGoal.label(): String = stringResource(labelRes())

@StringRes
internal fun TrainingGoal.labelRes(): Int =
    when (this) {
        TrainingGoal.STRENGTH -> R.string.preference_goal_strength
        TrainingGoal.HYPERTROPHY -> R.string.preference_goal_hypertrophy
        TrainingGoal.ENDURANCE -> R.string.preference_goal_endurance
        TrainingGoal.CUSTOM -> R.string.preference_goal_custom
    }

/**
 * What training for that goal looks like — `Heavy, low reps, long rests`.
 *
 * A description of the *training*, deliberately, and never a description of what the app will
 * do about it. Nothing consumes [TrainingGoal] in v1 — see the note on the enum — so a detail
 * promising tailored rests or suggested plans would be a claim the app does not honour on the
 * one screen a new user has no reason yet to distrust.
 */
@Composable
@ReadOnlyComposable
fun TrainingGoal.detail(): String =
    stringResource(
        when (this) {
            TrainingGoal.STRENGTH -> R.string.preference_goal_strength_detail
            TrainingGoal.HYPERTROPHY -> R.string.preference_goal_hypertrophy_detail
            TrainingGoal.ENDURANCE -> R.string.preference_goal_endurance_detail
            TrainingGoal.CUSTOM -> R.string.preference_goal_custom_detail
        },
    )
