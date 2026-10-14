package com.titan.core.domain.model

/**
 * A gym the user saved, kept on the device.
 *
 * @property id the local rowid, or 0 for a gym that has not been saved yet — the convention
 *   [WorkoutTemplate] uses, so [com.titan.core.domain.repository.GymRepository.save] knows
 *   whether to insert or update.
 * @property address as the user typed it; never parsed or geocoded, since there is no network
 *   to ask.
 * @property coordinates where it is, for ranking by distance.
 * @property equipmentTags free-form labels for what the gym has ("squat rack", "cable
 *   machine"). Order is kept as entered.
 * @property userNotes the user's own remarks, or null for none.
 * @property createdAt epoch milliseconds, UTC.
 */
data class Gym(
    val id: Long = 0,
    val name: String,
    val address: String,
    val coordinates: Coordinates,
    val equipmentTags: List<String> = emptyList(),
    val userNotes: String? = null,
    val createdAt: Long,
)
