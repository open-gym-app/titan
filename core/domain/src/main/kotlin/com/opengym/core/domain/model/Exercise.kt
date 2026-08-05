package com.opengym.core.domain.model

data class Exercise(
    val id: Long = 0,
    val name: String,
    val type: ExerciseType = ExerciseType.STRENGTH,
    val discipline: Discipline? = null,
    val equipmentType: EquipmentType? = null,
    val machineBrand: String? = null,
    val mechanicType: MechanicType? = null,
    val difficultyLevel: DifficultyLevel? = null,
    val isUnilateral: Boolean = false,
    val defaultRest: Long? = null,
    /** The user's own remarks. [instructions] is the canonical how-to. */
    val notes: String? = null,
    val instructions: String? = null,
    val isCustom: Boolean = false,
    val icon: String? = null,
)
