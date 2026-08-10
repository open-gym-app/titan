package com.opengym.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SetType
import com.opengym.core.ui.R
import java.util.Locale

/*
 * The display names of the domain enums the shared components render.
 *
 * Extension functions in `:core:ui` rather than a `labelRes` on the enum, for the reason
 * `:feature:settings`' own labels file gives: the domain is a plain Kotlin library with no
 * Android on its classpath and no business knowing what a string resource is.
 *
 * They live *here* rather than in a feature because more than one feature renders each of
 * them — a set type appears in the active session and in the summary, a muscle group in the
 * library, the exercise detail and the stats hub — and the point of `:core:ui` is that the
 * second one does not get to spell it differently.
 *
 * Every `when` is exhaustive, so a value added to one of these enums is a compile error here
 * rather than a blank chip on three screens.
 */

/**
 * What a set type is called: `Warm-up`, `Drop set`.
 *
 * [SetType.NORMAL] has a name (`Working set`) because a screen reader has to say *something*,
 * but nothing draws it — see [SetType.markerOrNull], which is the visual half of this pair.
 */
@Composable
@ReadOnlyComposable
fun SetType.label(): String = stringResource(labelRes())

@StringRes
internal fun SetType.labelRes(): Int = when (this) {
    SetType.NORMAL -> R.string.set_type_normal
    SetType.WARMUP -> R.string.set_type_warmup
    SetType.DROP_SET -> R.string.set_type_drop
    SetType.FAILURE -> R.string.set_type_failure
    SetType.INTERVAL -> R.string.set_type_interval
}

/**
 * The single glyph a set-number cell shows in place of the number — `W`, `D`, `F`, `I` — or
 * null for [SetType.NORMAL], which shows the number itself.
 *
 * A letter rather than a colour or an icon, because the cell it replaces is 24dp wide and
 * already carries the only other thing that could go there. Null is the *normal* case, so a
 * caller reading `?: setNumber` is reading the rule.
 */
@Composable
@ReadOnlyComposable
fun SetType.markerOrNull(): String? = markerRes()?.let { stringResource(it) }

@StringRes
private fun SetType.markerRes(): Int? = when (this) {
    SetType.NORMAL -> null
    SetType.WARMUP -> R.string.set_type_warmup_marker
    SetType.DROP_SET -> R.string.set_type_drop_marker
    SetType.FAILURE -> R.string.set_type_failure_marker
    SetType.INTERVAL -> R.string.set_type_interval_marker
}

/**
 * The equipment an exercise uses, or null for [EquipmentType.OTHER].
 *
 * Null rather than "Other": the label is drawn beside an exercise's name as a qualifier, and
 * `Push-up · Other` tells a reader nothing the name did not already say.
 */
@Composable
@ReadOnlyComposable
fun EquipmentType.labelOrNull(): String? = labelRes()?.let { stringResource(it) }

@StringRes
private fun EquipmentType.labelRes(): Int? = when (this) {
    EquipmentType.BARBELL -> R.string.equipment_barbell
    EquipmentType.DUMBBELL -> R.string.equipment_dumbbell
    EquipmentType.MACHINE -> R.string.equipment_machine
    EquipmentType.BODYWEIGHT -> R.string.equipment_bodyweight
    EquipmentType.CABLE -> R.string.equipment_cable
    EquipmentType.KETTLEBELL -> R.string.equipment_kettlebell
    EquipmentType.BAND -> R.string.equipment_band
    EquipmentType.EZ_CURL_BAR -> R.string.equipment_ez_curl_bar
    EquipmentType.MEDICINE_BALL -> R.string.equipment_medicine_ball
    EquipmentType.EXERCISE_BALL -> R.string.equipment_exercise_ball
    EquipmentType.FOAM_ROLL -> R.string.equipment_foam_roll
    EquipmentType.OTHER -> null
}

/**
 * What a personal record is a record *of*: `Heaviest`, `Most reps`, `1RM`.
 *
 * The short form on purpose — it is read inside a badge next to the figure it qualifies, and
 * "Estimated one-rep max" in that position is a sentence where a tag belongs.
 */
@Composable
@ReadOnlyComposable
fun PrType.label(): String = stringResource(labelRes())

@StringRes
internal fun PrType.labelRes(): Int = when (this) {
    PrType.MAX_WEIGHT -> R.string.pr_max_weight
    PrType.MAX_REPS -> R.string.pr_max_reps
    PrType.MAX_VOLUME -> R.string.pr_max_volume
    PrType.ESTIMATED_1RM -> R.string.pr_estimated_1rm
    PrType.MAX_DISTANCE -> R.string.pr_max_distance
    PrType.BEST_PACE -> R.string.pr_best_pace
}

/**
 * A muscle group as a reader should see it: `Lats`, `Lower back`, `Quads`.
 *
 * Unlike everything else in this file the input is not an enum — [MuscleGroup] is a row, and
 * its [MuscleGroup.name] is the seed's `SCREAMING_SNAKE` key (`MIDDLE_BACK`, `QUADRICEPS`).
 * Those seventeen keys are the taxonomy in `tools/seed/generate_seed.py` and they are
 * translated; anything else is a group the *user* authored, and the only honest thing to show
 * for that is what they typed, tidied — see [prettifiedName].
 *
 * The lookup is on the name rather than on the row id because ids are local rowids assigned
 * by the seed's own ordering: reordering the list in the generator renumbers them, and a
 * table keyed on the number would then be quietly wrong on every device seeded before the
 * change.
 */
@Composable
@ReadOnlyComposable
fun MuscleGroup.displayName(): String {
    val labelRes = SEEDED_MUSCLE_LABELS[name.uppercase(Locale.ROOT)]
    return if (labelRes != null) stringResource(labelRes) else prettifiedName()
}

/**
 * `LOWER_BACK` -> `Lower back`, for a group the seed does not know.
 *
 * Sentence case rather than title case: it is a fallback for a name a user typed, and a
 * user who typed `rear delts` should not be shown `Rear Delts`.
 */
internal fun MuscleGroup.prettifiedName(): String = name
    .replace('_', ' ')
    .trim()
    .lowercase(Locale.getDefault())
    .replaceFirstChar { it.titlecase(Locale.getDefault()) }

private val SEEDED_MUSCLE_LABELS: Map<String, Int> = mapOf(
    "ABDOMINALS" to R.string.muscle_abdominals,
    "ABDUCTORS" to R.string.muscle_abductors,
    "ADDUCTORS" to R.string.muscle_adductors,
    "BICEPS" to R.string.muscle_biceps,
    "CALVES" to R.string.muscle_calves,
    "CHEST" to R.string.muscle_chest,
    "FOREARMS" to R.string.muscle_forearms,
    "GLUTES" to R.string.muscle_glutes,
    "HAMSTRINGS" to R.string.muscle_hamstrings,
    "LATS" to R.string.muscle_lats,
    "LOWER_BACK" to R.string.muscle_lower_back,
    "MIDDLE_BACK" to R.string.muscle_middle_back,
    "NECK" to R.string.muscle_neck,
    "QUADRICEPS" to R.string.muscle_quadriceps,
    "SHOULDERS" to R.string.muscle_shoulders,
    "TRAPS" to R.string.muscle_traps,
    "TRICEPS" to R.string.muscle_triceps,
)
