package com.opengym.core.domain.model

/**
 * Equipment an exercise is performed with. Stored in the exercise.equipment_type
 * column, which is the single source of truth for equipment (the former
 * `category` column was redundant and has been removed).
 *
 * The values mirror the seed dataset's equipment vocabulary so the catalogue
 * imports without collapsing distinct kit into one bucket - see the `EQUIPMENT`
 * table in `tools/seed/generate_seed.py`. [OTHER] covers both the dataset's own
 * catch-all and a missing value; the column is never left NULL, because the
 * unique index on (name, equipment_type, machine_brand) stops deduplicating
 * once it is (SQLite treats NULLs as distinct).
 */
enum class EquipmentType {
    BARBELL,
    DUMBBELL,
    MACHINE,
    BODYWEIGHT,
    CABLE,
    KETTLEBELL,
    BAND,
    EZ_CURL_BAR,
    MEDICINE_BALL,
    EXERCISE_BALL,
    FOAM_ROLL,
    OTHER,
}
