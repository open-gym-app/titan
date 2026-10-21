package com.titan.core.domain.model

/**
 * How much practice a movement demands before it can be loaded safely. Stored in
 * the exercise.difficulty_level column, which is nullable: a user-created
 * exercise need not be graded.
 */
enum class DifficultyLevel {
    BEGINNER,
    INTERMEDIATE,
    EXPERT,
}
