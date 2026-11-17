package com.titan.core.domain.model

/**
 * What one finished session is worth to a pet, before it is paid to one.
 *
 * @property xp the XP the session pays.
 * @property stats how much each stat grows.
 */
data class PetGain(
    val xp: Int,
    val stats: PetStats,
) {

    init {
        require(xp >= 0) { "a session never costs a pet xp, was $xp" }
    }

    /** True when the session pays nothing at all: no paid set in it. */
    val isNothing: Boolean get() = xp == 0 && stats == PetStats.ZERO
}

/**
 * What a finished session did to the active pet — produced **inside the write that persisted
 * it**, so nothing re-reads the pet to learn these figures and none of them can disagree with
 * what was stored.
 *
 * @property pet the pet as it stands after the session.
 * @property xpGained the XP actually added — less than the session paid only at the XP ceiling.
 * @property statsGained how much each stat actually grew — less than paid only at a stat's bound.
 * @property levelBefore the pet's level going in.
 * @property levelAfter its level coming out.
 * @property stageBefore the pet's stage going in.
 * @property stageAfter its stage coming out.
 */
data class PetGrowth(
    val pet: Pet,
    val xpGained: Long,
    val statsGained: PetStats,
    val levelBefore: Int,
    val levelAfter: Int,
    val stageBefore: PetStage,
    val stageAfter: PetStage,
) {

    /** True when the session carried the pet over at least one level threshold. */
    val leveledUp: Boolean get() = levelAfter > levelBefore

    /** True when the session carried the pet into a new stage — what the drawing changes on. */
    val stageChanged: Boolean get() = stageAfter != stageBefore
}
