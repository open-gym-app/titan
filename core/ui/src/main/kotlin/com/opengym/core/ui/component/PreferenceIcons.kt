package com.opengym.core.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector
import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem

/*
 * The mark that stands for a stored preference.
 *
 * The same arrangement as `PreferenceLabels.kt` and for the same reason: the icon lives beside
 * the words rather than on the enum, because `:core:domain` has no business knowing what an
 * `ImageVector` is — and because a value described by one picture on the first-run screen and
 * a different one in settings is a value the user has to learn twice.
 *
 * They are illustrations, not controls. Every call site draws one next to the label it stands
 * for, so none of them carries a content description — see [IconTile].
 *
 * Every `when` is exhaustive, so a value added to any of these enums is a compile error here
 * rather than a blank square in a list of options.
 */

/**
 * What a training goal looks like: a barbell, a bolt, a runner, a slider.
 *
 * Chosen for what the *training* looks like rather than for what the app does about it — the
 * goal only picks a rep range, see the enum — so `Custom` gets the tuning slider that means
 * "you set the terms", not a question mark that would read as the app not knowing.
 */
fun TrainingGoal.icon(): ImageVector =
    when (this) {
        TrainingGoal.STRENGTH -> Icons.Outlined.FitnessCenter
        TrainingGoal.HYPERTROPHY -> Icons.Outlined.Bolt
        TrainingGoal.ENDURANCE -> Icons.AutoMirrored.Outlined.DirectionsRun
        TrainingGoal.CUSTOM -> Icons.Outlined.Tune
    }

/**
 * What a fitness level looks like: a seedling, a staircase, a ribbon.
 *
 * A picture of *where the person is* rather than of a rank — new growth, a climb under way,
 * something earned — because the question is asked in years of training and not in medals. Not
 * a filled-bar ramp: three bars in a row read as signal strength on a phone, and a "level" the
 * app grades the user on is exactly the impression to avoid on the screen that asks.
 */
fun FitnessLevel.icon(): ImageVector =
    when (this) {
        FitnessLevel.BEGINNER -> Icons.Outlined.Eco
        FitnessLevel.INTERMEDIATE -> Icons.Outlined.Stairs
        FitnessLevel.ADVANCED -> Icons.Outlined.WorkspacePremium
    }

/** What a unit system looks like: a balance for kilograms, a ruler for pounds and miles. */
fun UnitSystem.icon(): ImageVector =
    when (this) {
        UnitSystem.METRIC -> Icons.Outlined.Scale
        UnitSystem.IMPERIAL -> Icons.Outlined.Straighten
    }
