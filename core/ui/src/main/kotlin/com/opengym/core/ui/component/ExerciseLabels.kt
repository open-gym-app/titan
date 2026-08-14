package com.opengym.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.DifficultyLevel
import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import com.opengym.core.ui.R

/*
 * How an exercise describes itself: the kit, the tradition, the mechanic, the grading, and
 * what a set of it records.
 *
 * The same rule as `DomainLabels.kt`, which this was split out of — extension functions in
 * `:core:ui` rather than a `labelRes` on the enum, because `:core:domain` is a plain Kotlin
 * library with no Android on its classpath. Five enums describing one thing is enough to be
 * its own file; the split is by subject, not by size.
 *
 * Every `when` is exhaustive, so a value added to one of these enums is a compile error here
 * rather than a blank cell on the exercise detail screen.
 */

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
 * How an exercise is measured: `Weight & reps`, `Distance & time`, `Hold`.
 *
 * Named after what the user *logs* rather than after the constant, because that is the
 * question the label answers wherever it is drawn — a custom-exercise form choosing which set
 * row to show, a detail screen saying which one it will show. "Endurance" beside a treadmill
 * does not tell anybody they are about to be asked for a distance.
 */
@Composable
@ReadOnlyComposable
fun ExerciseType.label(): String = stringResource(labelRes())

@StringRes
internal fun ExerciseType.labelRes(): Int = when (this) {
    ExerciseType.STRENGTH -> R.string.exercise_type_strength
    ExerciseType.ENDURANCE -> R.string.exercise_type_endurance
    ExerciseType.ISOMETRIC -> R.string.exercise_type_isometric
}

/**
 * The fields a set of this exercise is logged with: `Weight, reps, RPE`.
 *
 * The long form of [label], for the one place that has room to spell it out — the exercise
 * detail's "tracked" cell, which exists to answer "what am I about to be asked for" before
 * the user is in the middle of a set being asked for it. [label] is the same fact as a chip.
 *
 * RPE is in all three because it is optional on every set type
 * ([com.opengym.core.domain.model.WorkoutSet.rpe] is nullable and the row always offers it),
 * not because a hold is graded the way a triple is.
 */
@Composable
@ReadOnlyComposable
fun ExerciseType.trackedLabel(): String = stringResource(trackedLabelRes())

@StringRes
internal fun ExerciseType.trackedLabelRes(): Int = when (this) {
    ExerciseType.STRENGTH -> R.string.exercise_tracked_strength
    ExerciseType.ENDURANCE -> R.string.exercise_tracked_endurance
    ExerciseType.ISOMETRIC -> R.string.exercise_tracked_isometric
}

/**
 * Whether the movement is a compound or an isolation: `Compound`, `Isolation`.
 *
 * Nullable at the source — the seed leaves it unset for the stretches and the carries, where
 * the distinction does not apply — so callers hold a `MechanicType?` and this is an extension
 * on the non-null value they unwrap to.
 */
@Composable
@ReadOnlyComposable
fun MechanicType.label(): String = stringResource(labelRes())

@StringRes
internal fun MechanicType.labelRes(): Int = when (this) {
    MechanicType.COMPOUND -> R.string.mechanic_compound
    MechanicType.ISOLATION -> R.string.mechanic_isolation
}

/**
 * How much practice the movement demands: `Beginner`, `Intermediate`, `Expert`.
 *
 * A grading of the *movement*, not of the reader — which is why it is drawn as a metadata
 * cell beside the equipment rather than as a badge on the exercise. Nobody needs their
 * library labelling half of itself above their level.
 */
@Composable
@ReadOnlyComposable
fun DifficultyLevel.label(): String = stringResource(labelRes())

@StringRes
internal fun DifficultyLevel.labelRes(): Int = when (this) {
    DifficultyLevel.BEGINNER -> R.string.difficulty_beginner
    DifficultyLevel.INTERMEDIATE -> R.string.difficulty_intermediate
    DifficultyLevel.EXPERT -> R.string.difficulty_expert
}

/**
 * The tradition the movement comes from: `Powerlifting`, `Stretching`, `Cardio`.
 *
 * Distinct from [ExerciseType.label], which says how a set is *recorded* — five of these are
 * all logged as weight and reps, so neither can be derived from the other. See [Discipline].
 */
@Composable
@ReadOnlyComposable
fun Discipline.label(): String = stringResource(labelRes())

@StringRes
internal fun Discipline.labelRes(): Int = when (this) {
    Discipline.STRENGTH -> R.string.discipline_strength
    Discipline.POWERLIFTING -> R.string.discipline_powerlifting
    Discipline.OLYMPIC_WEIGHTLIFTING -> R.string.discipline_olympic_weightlifting
    Discipline.STRONGMAN -> R.string.discipline_strongman
    Discipline.PLYOMETRICS -> R.string.discipline_plyometrics
    Discipline.STRETCHING -> R.string.discipline_stretching
    Discipline.CARDIO -> R.string.discipline_cardio
}
