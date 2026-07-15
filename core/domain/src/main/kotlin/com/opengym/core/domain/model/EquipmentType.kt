package com.opengym.core.domain.model

/**
 * Equipment an exercise is performed with. Stored in the exercise.equipment_type
 * column, which is the single source of truth for equipment (the former
 * `category` column was redundant and has been removed).
 */
enum class EquipmentType {
    BARBELL,
    DUMBBELL,
    MACHINE,
    BODYWEIGHT,
    CABLE,
    KETTLEBELL,
    BAND,
}
