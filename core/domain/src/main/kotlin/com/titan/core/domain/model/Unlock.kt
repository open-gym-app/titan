package com.titan.core.domain.model

/**
 * What the user's training has to reach for something to unlock. Data, so a place or an item
 * names its condition and the evaluator walks them all.
 */
sealed interface UnlockCondition {

    /** Owned the moment a titan hatches. Never stored: it is always met. */
    data object FromHatch : UnlockCondition

    /** A weekly streak of at least [weeks], at any point — the best one held counts. */
    data class StreakWeeks(val weeks: Int) : UnlockCondition

    /** At least [kilometres] logged in all, across every distance set. */
    data class DistanceKm(val kilometres: Int) : UnlockCondition

    /** At least [count] spars won. */
    data class SparsWon(val count: Int) : UnlockCondition

    /** The user at [level] or above. */
    data class Level(val level: Int) : UnlockCondition

    /** Whether [progress] meets this. */
    fun isMetBy(progress: UnlockProgress): Boolean =
        when (this) {
            FromHatch -> true
            is StreakWeeks -> progress.bestStreakWeeks >= weeks
            is DistanceKm -> progress.distanceCentimetres >= kilometres * CENTIMETRES_PER_KILOMETRE
            is SparsWon -> progress.sparsWon >= count
            is Level -> progress.level >= level
        }

    private companion object {
        const val CENTIMETRES_PER_KILOMETRE = 100_000L
    }
}

/**
 * The figures unlocks are judged against: the user's training, read, never written.
 *
 * @property bestStreakWeeks the longer of the current weekly streak and the best one held.
 * @property distanceCentimetres every distance set's distance, in all.
 * @property level the user's level.
 * @property sparsWon how many spars the titan has won.
 */
data class UnlockProgress(
    val bestStreakWeeks: Int,
    val distanceCentimetres: Long,
    val level: Int,
    val sparsWon: Int,
)

/** Something that unlocks: a [key] stored once earned, and the [condition] that earns it. */
interface Unlockable {
    val key: String
    val condition: UnlockCondition
}
