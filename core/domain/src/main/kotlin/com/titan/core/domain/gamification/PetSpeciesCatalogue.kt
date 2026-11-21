package com.titan.core.domain.gamification

import com.titan.core.domain.model.PetSpecies
import com.titan.core.domain.model.Rarity

/**
 * The species a pet egg can hatch into, and the only place they are written down.
 *
 * A list of values beside [Collectibles], [Achievements] and [Quests], and for their reason:
 * everything that hatches, stores or draws a pet reads [ALL], so a species added below needs no
 * other edit — no branch, no schema change, and no adjustment to the odds, because a hatch is
 * priced per [Rarity] in [HatchRates] rather than per species.
 *
 * ### Every band is stocked
 *
 * A hatch always produces something, so a band [HatchRates] prices and this catalogue does not
 * stock would be an egg with no answer. `PetSpeciesCatalogueTest` holds every priced rarity to at
 * least one species.
 *
 * ### The ids are a storage contract
 *
 * [PetSpecies.id] is what a pet row holds. Renaming one does not rename the pets already hatched
 * under the old spelling; the test pins the set, and a failure there is the reminder that a
 * rename needs a migration.
 */
object PetSpeciesCatalogue {

    // ─── Common ─────────────────────────────────────────────────────────────────────────────

    /** The one most eggs hold. */
    val IRON_CUB = PetSpecies("iron_cub", Rarity.COMMON)

    /** Small, pale, and leaves marks on everything. */
    val CHALK_SPRITE = PetSpecies("chalk_sprite", Rarity.COMMON)

    // ─── Uncommon ───────────────────────────────────────────────────────────────────────────

    /** Slow, and carries its plates on its back. */
    val PLATE_TORTOISE = PetSpecies("plate_tortoise", Rarity.UNCOMMON)

    // ─── Rare ───────────────────────────────────────────────────────────────────────────────

    /** Horns like a kettlebell's handle. */
    val KETTLE_RAM = PetSpecies("kettle_ram", Rarity.RARE)

    // ─── Epic ───────────────────────────────────────────────────────────────────────────────

    /** A drake with a bar for a spine. */
    val BARBELL_DRAKE = PetSpecies("barbell_drake", Rarity.EPIC)

    // ─── Legendary ──────────────────────────────────────────────────────────────────────────

    /** The one that holds the sky up. */
    val ATLAS = PetSpecies("atlas", Rarity.LEGENDARY)

    /** Every species this build knows about, commonest band first. */
    val ALL: List<PetSpecies> = listOf(
        IRON_CUB,
        CHALK_SPRITE,
        PLATE_TORTOISE,
        KETTLE_RAM,
        BARBELL_DRAKE,
        ATLAS,
    )

    /**
     * The species [id] names, or **null** if this build has none.
     *
     * Null rather than a throw, the shape of [Collectibles.byId]: a pet hatched on a newer build
     * and restored here names a species with no entry behind it, and that is a state the store
     * can genuinely be in.
     */
    fun byId(id: String): PetSpecies? = ALL.firstOrNull { it.id == id }

    /** The species in [rarity]'s band, in catalogue order — the pool the hatch's second draw indexes. */
    fun inBand(rarity: Rarity): List<PetSpecies> = ALL.filter { it.rarity == rarity }
}
