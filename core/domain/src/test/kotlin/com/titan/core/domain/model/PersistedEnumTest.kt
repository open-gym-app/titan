package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the constant names of the enums the schema stores.
 *
 * These are written to `TEXT` columns as `Enum.name` and read back with
 * `decodeEnum`, which answers **null** for a name this build does not have (see
 * `:core:data`'s `EnumColumn`). That combination makes a rename a silent data
 * migration: every stored row keeps the old spelling, every read of it falls back to
 * the column's default or to null, and nothing anywhere fails. Renaming
 * `EquipmentType.EZ_CURL_BAR` would quietly turn every EZ-bar exercise in the user's
 * catalogue into `OTHER`.
 *
 * So the names are part of the storage contract, not an implementation detail, and
 * they are pinned in the module that owns them. A test failing here is not a bug —
 * it is the reminder that the change needs a migration, or a `@SerialName`-style
 * alias, before it can ship.
 *
 * The comparisons are against **sets**, because the vocabulary is the contract and the
 * order is not: nothing stores an ordinal — that is why the columns hold names in the
 * first place — so reordering the constants changes nothing on disk and should not
 * fail a test. Adding one costs a line in the expected set and nothing on disk, since
 * no stored row can hold a name this build has only just introduced. Removals and
 * renames are what these guard, because those are the two a stored row outlives.
 */
class PersistedEnumTest {

    @Test
    fun `exercise type names are the ones exercise_type holds`() {
        assertEquals(
            setOf("STRENGTH", "ENDURANCE", "ISOMETRIC"),
            ExerciseType.entries.map(ExerciseType::name).toSet(),
        )
    }

    @Test
    fun `set type names are the ones workout_set set_type holds`() {
        assertEquals(
            setOf("NORMAL", "WARMUP", "DROP_SET", "FAILURE", "INTERVAL"),
            SetType.entries.map(SetType::name).toSet(),
        )
    }

    @Test
    fun `discipline names are the ones exercise discipline holds`() {
        assertEquals(
            setOf(
                "STRENGTH",
                "POWERLIFTING",
                "OLYMPIC_WEIGHTLIFTING",
                "STRONGMAN",
                "PLYOMETRICS",
                "STRETCHING",
                "CARDIO",
            ),
            Discipline.entries.map(Discipline::name).toSet(),
        )
    }

    @Test
    fun `equipment type names are the ones exercise equipment_type holds`() {
        // These also have to keep matching the seed generator's EQUIPMENT vocabulary,
        // or a regenerated catalogue imports its kit into OTHER.
        //
        // OTHER is not just another value: the column is never NULL, because the unique
        // index on (name, equipment_type, machine_brand) stops deduplicating once it is
        // — SQLite treats NULLs as distinct — and OTHER is the catch-all that lets a
        // piece of kit the seed does not name still have a value to store.
        assertEquals(
            setOf(
                "BARBELL",
                "DUMBBELL",
                "MACHINE",
                "BODYWEIGHT",
                "CABLE",
                "KETTLEBELL",
                "BAND",
                "EZ_CURL_BAR",
                "MEDICINE_BALL",
                "EXERCISE_BALL",
                "FOAM_ROLL",
                "OTHER",
            ),
            EquipmentType.entries.map(EquipmentType::name).toSet(),
        )
    }

    @Test
    fun `difficulty level names are the ones exercise difficulty_level holds`() {
        assertEquals(
            setOf("BEGINNER", "INTERMEDIATE", "EXPERT"),
            DifficultyLevel.entries.map(DifficultyLevel::name).toSet(),
        )
    }

    @Test
    fun `mechanic type names are the ones exercise mechanic_type holds`() {
        assertEquals(setOf("COMPOUND", "ISOLATION"), MechanicType.entries.map(MechanicType::name).toSet())
    }

    @Test
    fun `template category names are the ones workout_template category holds`() {
        // The column is nullable and null is not GENERAL, so a rename does not fall back
        // to a neighbouring value — it turns every template categorised under the old
        // spelling into an uncategorised one, chip and all.
        assertEquals(
            setOf("STRENGTH", "HYPERTROPHY", "ENDURANCE", "MOBILITY", "GENERAL"),
            TemplateCategory.entries.map(TemplateCategory::name).toSet(),
        )
    }

    @Test
    fun `pr type names are the ones personal_record pr_type holds`() {
        assertEquals(
            setOf(
                "MAX_WEIGHT",
                "MAX_REPS",
                "MAX_VOLUME",
                "ESTIMATED_1RM",
                "MAX_DISTANCE",
                "BEST_PACE",
                "MAX_DURATION",
                "MAX_WEIGHTED_HOLD",
            ),
            PrType.entries.map(PrType::name).toSet(),
        )
    }

    @Test
    fun `one rep max formula names are the ones the settings store holds`() {
        assertEquals(
            setOf("EPLEY", "BRZYCKI"),
            OneRepMaxFormula.entries.map(OneRepMaxFormula::name).toSet(),
        )
    }

    @Test
    fun `workout activity names are the ones workout_session health_connect_activity holds`() {
        assertEquals(
            setOf(
                "STRENGTH_TRAINING",
                "WEIGHTLIFTING",
                "CALISTHENICS",
                "STRETCHING",
                "YOGA",
                "PILATES",
                "HIGH_INTENSITY_INTERVAL_TRAINING",
                "RUNNING",
                "WALKING",
                "HIKING",
                "BIKING",
                "SWIMMING",
                "ROWING",
                "ELLIPTICAL",
                "OTHER",
            ),
            WorkoutActivity.entries.map(WorkoutActivity::name).toSet(),
        )
    }
}
