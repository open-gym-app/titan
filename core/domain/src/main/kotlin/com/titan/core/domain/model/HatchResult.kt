package com.titan.core.domain.model

/**
 * What came of asking to hatch one drop row.
 *
 * Every answer is returned, none thrown: a double tap, a stale list and a row that is not an egg
 * are all things a screen can reach, and each has somewhere to go.
 */
sealed interface HatchResult {

    /** The row was an unhatched egg, and [pet] is what came out of it. */
    data class Hatched(val pet: Pet) : HatchResult

    /**
     * A pet already names this row. Carries that [pet], so a second tap lands on the same screen
     * as the first — this is not an error to show.
     */
    data class AlreadyHatched(val pet: Pet) : HatchResult

    /** The row exists and is something other than an egg. */
    data object NotAnEgg : HatchResult

    /** There is no such row. */
    data object NotFound : HatchResult
}

/**
 * What the store did with a hatch.
 *
 * @property pet the pet that names the egg now.
 * @property isNew false when another call had already hatched this egg and [pet] is that call's.
 */
data class HatchedPet(
    val pet: Pet,
    val isNew: Boolean,
)
