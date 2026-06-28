package com.opengym.core.domain.model

data class Exercise(
    val id: Long = 0,
    val name: String,
    val type: ExerciseType,
    val primaryMuscleGroup: String,
)
