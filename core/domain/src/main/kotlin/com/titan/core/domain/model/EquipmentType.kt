package com.titan.core.domain.model

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
    ;

    /**
     * Whether this is a bar the user loads plates onto by hand.
     *
     * What the plate calculator is offered from: working out which discs go on each end is a
     * question about a barbell and about nothing else, so a "plate calculator" button on a
     * cable stack or a press-up is furniture in the way of the screen it sits on.
     *
     * The straight bar and the EZ curl bar, and no others. [MACHINE] is the near miss and is
     * deliberately excluded: some plate-loaded machines exist, but the seed catalogue's
     * `MACHINE` is overwhelmingly the selectorised stack — see the `EQUIPMENT` table in
     * `tools/seed/generate_seed.py` — so including it would put the button on hundreds of
     * exercises that have no plates and miss none that do.
     */
    val isPlateLoaded: Boolean get() = this == BARBELL || this == EZ_CURL_BAR
}
