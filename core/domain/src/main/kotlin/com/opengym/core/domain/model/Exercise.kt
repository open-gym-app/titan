package com.opengym.core.domain.model

data class Exercise(
    val id: Long = 0,
    val name: String,
    val type: ExerciseType = ExerciseType.STRENGTH,
    val category: ExerciseCategory,
    val equipmentType: String? = null,
    val mechanicType: MechanicType? = null,
    val difficultyLevel: String? = null,
    val isUnilateral: Boolean = false,
    val defaultRest: Long? = null,
    val description: String? = null,
    val instructions: String? = null,
    val isCustom: Boolean = false,
    val icon: String? = null,
)
