package com.opengym.feature.history.detail

import com.opengym.core.domain.model.DifficultyLevel
import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MechanicType
import com.opengym.feature.history.library.CHEST
import com.opengym.feature.history.library.LATS
import com.opengym.feature.history.library.TRICEPS

/*
 * Two exercises: one with everything the seed supplies, one with everything a user can.
 *
 * They are the two shapes the detail screen has to survive — see the issue's acceptance
 * criteria — and keeping them here rather than in each test class is what stops the "sparse"
 * case quietly acquiring an instruction or a photo when somebody extends a fixture.
 */

/** The seeded shape: a photo, a how-to, every metadata column, and a note already written. */
val DETAIL_BENCH = Exercise(
    id = 10,
    name = "Bench press",
    discipline = Discipline.POWERLIFTING,
    equipmentType = EquipmentType.BARBELL,
    mechanicType = MechanicType.COMPOUND,
    difficultyLevel = DifficultyLevel.INTERMEDIATE,
    notes = "Seat on 3.",
    instructions = "Unrack the bar.\nLower it to the chest.\nPress back to lockout.",
    icon = "seed/images/Barbell_Bench_Press.webp",
)

/** The authored shape: a name, some kit, one muscle, and nothing else at all. */
val DETAIL_CUSTOM = Exercise(
    id = 11,
    name = "Hammer press",
    equipmentType = EquipmentType.MACHINE,
    isCustom = true,
)

val DETAIL_BENCH_LINKS = listOf(
    ExerciseMuscleGroupLink(CHEST, isPrimary = true),
    ExerciseMuscleGroupLink(TRICEPS, isPrimary = false),
)

val DETAIL_CUSTOM_LINKS = listOf(ExerciseMuscleGroupLink(LATS, isPrimary = true))

/** The state the screen draws for a fully-populated seeded exercise. */
fun benchState() =
    ExerciseDetailUiState(
        exercise = DETAIL_BENCH,
        muscleLinks = DETAIL_BENCH_LINKS,
        isLoading = false,
    )

/** The state the screen draws for a user-authored one: every optional section absent. */
fun customState() =
    ExerciseDetailUiState(
        exercise = DETAIL_CUSTOM,
        muscleLinks = DETAIL_CUSTOM_LINKS,
        isLoading = false,
    )
