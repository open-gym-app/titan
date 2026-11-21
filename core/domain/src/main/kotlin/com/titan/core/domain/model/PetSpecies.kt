package com.titan.core.domain.model

/**
 * One species a pet egg can hatch into, as **data**: what it is called in storage and how rare
 * it is.
 *
 * The shape is [Collectible]'s, for [Collectible]'s reason. A species is a line in
 * [com.titan.core.domain.gamification.PetSpeciesCatalogue] and no behaviour: the hatch weighs a
 * [rarity], and the odds in [com.titan.core.domain.gamification.HatchRates] do not move because
 * a species joined a band.
 *
 * ### "Titan" on screen, `Pet` in code
 *
 * `Titan` is already this codebase's prefix for app-level things — `TitanCard`, `TitanDialog`,
 * `TitanDatabase`. A `TitanRepository` would read as the app's repository. So the companion is a
 * **pet** everywhere in code and storage (the egg's stored id is already `pet_egg`), and the
 * word *titan* lives in string resources only.
 *
 * @property id the stable key a pet row stores as its species and the string-resource key its
 *   name is drawn from. **Part of the storage contract**: a rename orphans every pet hatched
 *   under the old spelling, which is why a test pins the set.
 * @property rarity the hatch band this species sits in.
 */
data class PetSpecies(
    val id: String,
    val rarity: Rarity,
) {

    init {
        require(id.isNotBlank()) { "a species needs an id: it is the key its pets are stored under" }
    }
}
