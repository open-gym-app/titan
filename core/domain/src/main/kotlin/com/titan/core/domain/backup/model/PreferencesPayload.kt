package com.titan.core.domain.backup.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user's settings as they are written into an export.
 *
 * ### Keyed by the store's own key names
 *
 * Every [SerialName] below is the Preferences DataStore key `UserPreferencesRepositoryImpl`
 * writes, not the Kotlin property name — `default_rest_duration_seconds`, not
 * `defaultRestDuration`. docs/PERSISTENCE.md already calls those key names a persisted format
 * that a rename silently resets; this reuses them rather than inventing a second set that could
 * drift from the first, and it means a reader can line an export up against a `.preferences_pb`
 * dump without a translation table.
 *
 * ### Enums travel as their names, and an unknown one is not an error
 *
 * [unitSystem], [theme], [trainingGoal], [fitnessLevel] and [oneRepMaxFormula] are stored as
 * enum *names*, for the reason the store stores them that way: an ordinal is meaningless the
 * moment a constant is inserted. A name this build does not have — a downgrade, or a value since
 * renamed — falls back to the documented default rather than failing the import, which is
 * exactly what the store's own reader does with the same value. A settings restore must not be
 * the thing that refuses somebody's training history.
 *
 * ### A key added later is nullable, and its absence is not a bump
 *
 * [fitnessLevel] arrived after the first documents were written (#M7-11), so it defaults to
 * null and a file without it reads back as the setting's default. That is docs/export-schema.md's
 * rule for a nullable addition — the older file restores into exactly what it held, which is a
 * device that had never been asked — and it is why `schema_version` stayed at 1. The CUSTOM
 * goal's rep range and increment (#M7-15) followed the same shape, and every key added from
 * here on takes it.
 *
 * ### Units are canonical
 *
 * [barWeight] is milligrams and [defaultRestDurationSeconds] is whole seconds, as in the store.
 * Nothing here is written in the user's display units: [unitSystem] decides how a number is
 * rendered and never what is held.
 */
@Serializable
data class PreferencesPayload(
    @SerialName("unit_system") val unitSystem: String,
    @SerialName("theme") val theme: String,
    @SerialName("default_rest_duration_seconds") val defaultRestDurationSeconds: Long,
    @SerialName("bar_weight_milligrams") val barWeight: Long,
    @SerialName("training_goal") val trainingGoal: String,
    @SerialName("one_rep_max_formula") val oneRepMaxFormula: String,
    @SerialName("onboarding_completed") val onboardingCompleted: Boolean,
    /**
     * The rack the user said they own, or **null** while they have never said — which is a
     * value rather than a gap, and the difference matters: null means "derive the standard rack
     * for my unit system", so restoring it as an empty list would hand somebody a barbell and
     * no plates. Held under the store's `plate_inventory_milligrams` key.
     */
    @SerialName("plate_inventory_milligrams") val plateInventory: List<PlateStockPayload>? = null,
    /**
     * The user's fitness level, or **null** in a file written before the question existed —
     * which reads back as the default, `BEGINNER`. Held under the store's `fitness_level` key.
     * Last rather than beside [trainingGoal], because the written order is the declaration
     * order and a key that may be absent belongs after the ones that never are.
     */
    @SerialName("fitness_level") val fitnessLevel: String? = null,
    /**
     * The bottom and top of the rep range the CUSTOM goal targets, or **null** in a file from
     * before the setting existed (#M7-15) — which reads back as the default range. Two keys
     * rather than one `"6-10"` string, because two integers need no parser and cannot be
     * half-valid; held under the store's `custom_rep_range_min` and `custom_rep_range_max`.
     * A file holding one without the other, or a range that ends before it starts, reads back
     * as the default too.
     */
    @SerialName("custom_rep_range_min") val customRepRangeMin: Int? = null,
    @SerialName("custom_rep_range_max") val customRepRangeMax: Int? = null,
    /**
     * The weight the CUSTOM goal adds between sessions, in milligrams, or **null** — which is
     * a value rather than a gap, as it is for [plateInventory]: null means "the rack's own
     * smallest step", so it restores as null and not as a copy of whatever that step was on
     * the exporting device. Held under the store's `custom_increment_milligrams` key.
     */
    @SerialName("custom_increment_milligrams") val customIncrement: Long? = null,
    /**
     * Whether the warm-up and cool-down cards are offered, or **null** in a file from before
     * the setting existed (#214) — which reads back as on, the setting's own default. Held
     * under the store's `warmup_suggestions` key.
     */
    @SerialName("warmup_suggestions") val warmupSuggestions: Boolean? = null,
)

/** One denomination of [PreferencesPayload.plateInventory]: a plate weight in milligrams, and how many pairs of it. */
@Serializable
data class PlateStockPayload(
    @SerialName("weight_milligrams") val weight: Long,
    @SerialName("pairs") val pairs: Int,
)
