package com.titan.core.domain.gamification

import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStage

/**
 * A pet's level and stage, read off its XP.
 *
 * ### No second curve
 *
 * A pet's level is [XpCalculator.levelForTotalXp] over the pet's own XP — the user's curve,
 * retuned in #243. A parallel curve would be a second set of numbers to keep honest.
 */
object PetStages {

    /**
     * The stage a pet at [level] has reached: the last one whose [PetStage.fromLevel] is at most
     * [level].
     *
     * A level below the first threshold reads as the first stage rather than throwing — a level
     * is derived, and a reader has no use for a refusal.
     */
    fun stageForLevel(level: Int): PetStage =
        PetStage.entries.lastOrNull { it.fromLevel <= level } ?: PetStage.entries.first()

    /** The stage after [stage], or null at the last one. */
    fun next(stage: PetStage): PetStage? = PetStage.entries.getOrNull(stage.ordinal + 1)
}

/** This pet's level, on the user's own curve. */
val Pet.level: Int get() = XpCalculator.levelForTotalXp(xp)

/** This pet's stage, from its [level]. */
val Pet.stage: PetStage get() = PetStages.stageForLevel(level)
