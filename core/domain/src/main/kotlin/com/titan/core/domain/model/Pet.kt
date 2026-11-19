package com.titan.core.domain.model

import java.time.Instant

/**
 * A companion the user hatched from a pet egg — a *titan*, on screen.
 *
 * It grows with the user's training and is otherwise inert towards it: **nothing a pet has is
 * ever read by XP, records, the streak, quests or the drop table.** The pet reads the user's
 * training; the user's progression never reads the pet.
 *
 * ### No level and no stage here
 *
 * Both are derived from [xp] — the level on the user's own curve, the stage from [PetStage]'s
 * table — by [com.titan.core.domain.gamification.PetStages]. A stored level would be a second
 * figure to keep in step with the first.
 *
 * @property id the pet's own local row id. Not portable across installs or exports.
 * @property speciesId the catalogue's stable key for what hatched. A plain id rather than a
 *   [PetSpecies] because a row written by a newer build names a species this one does not know,
 *   and that pet must still be readable — see
 *   [com.titan.core.domain.gamification.PetSpeciesCatalogue.byId].
 * @property hatchedAt when the egg was hatched.
 * @property xp the pet's lifetime XP, never negative.
 * @property stats what training has built, before anything worn is counted.
 * @property nickname what the user calls it, already through
 *   [com.titan.core.domain.gamification.PetNickname]'s rule, or null for the species' own name.
 * @property isActive whether this is the one pet that grows when a workout finishes.
 */
data class Pet(
    val id: Long,
    val speciesId: String,
    val hatchedAt: Instant,
    val xp: Long,
    val stats: PetStats,
    val nickname: String?,
    val isActive: Boolean,
) {

    init {
        require(xp >= 0) { "a pet's xp cannot be negative, was $xp" }
    }
}
