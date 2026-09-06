package com.opengym.core.domain.backup.model

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
 * [unitSystem], [theme], [trainingGoal] and [oneRepMaxFormula] are stored as enum *names*, for
 * the reason the store stores them that way: an ordinal is meaningless the moment a constant is
 * inserted. A name this build does not have — a downgrade, or a value since renamed — falls
 * back to the documented default rather than failing the import, which is exactly what the
 * store's own reader does with the same value. A settings restore must not be the thing that
 * refuses somebody's training history.
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
)

/** One denomination of [PreferencesPayload.plateInventory]: a plate weight in milligrams, and how many pairs of it. */
@Serializable
data class PlateStockPayload(
    @SerialName("weight_milligrams") val weight: Long,
    @SerialName("pairs") val pairs: Int,
)
