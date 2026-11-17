package com.titan.core.domain.model

/**
 * Which past self a rival is. The titan stays offline, so every rival is a titan the user used to
 * be, rebuilt from their own history.
 *
 * @property key the stable id a stored bout names. Pinned by a test.
 * @property weeksAgo how far back it looks, or null for the titan's own first week.
 */
enum class SparRivalKind(
    val key: String,
    val weeksAgo: Int?,
) {
    LAST_WEEK("last_week", weeksAgo = 1),
    FOUR_WEEKS_AGO("four_weeks_ago", weeksAgo = 4),
    TWELVE_WEEKS_AGO("twelve_weeks_ago", weeksAgo = 12),
    FIRST_WEEK("first_week", weeksAgo = null),
    ;

    companion object {

        /** The kind [key] names, or null for one this build does not know. */
        fun byKey(key: String): SparRivalKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A pet's level and power as of one week, kept so a past self can be rebuilt exactly — and
 * survive a later change to the power formula.
 *
 * @property weekStart the week, as its Monday at 00:00 UTC in epoch millis.
 */
data class PowerSnapshot(
    val weekStart: Long,
    val level: Int,
    val power: Int,
)

/**
 * One past self on offer.
 *
 * @property weekStart the week it is rebuilt from.
 * @property winChance the odds of beating it, as a whole percentage.
 * @property beaten whether this titan has already won against this rival, as of this week.
 */
data class SparRival(
    val kind: SparRivalKind,
    val weekStart: Long,
    val level: Int,
    val power: Int,
    val winChance: Int,
    val beaten: Boolean,
)

/** One bout, as stored. */
data class SparBout(
    val kind: String,
    val rivalWeek: Long,
    val won: Boolean,
)

/** The answer to a spar. Every answer is returned, none thrown. */
sealed interface SparResult {

    /**
     * Fought. [roundsWon] to [roundsLost] — "3 rounds to 1", or "lost 2 to 3".
     *
     * @property spiritGained what the bout added to spirit: more for a win than a loss.
     */
    data class Fought(
        val rival: SparRival,
        val won: Boolean,
        val roundsWon: Int,
        val roundsLost: Int,
        val spiritGained: Int,
    ) : SparResult

    /** It costs [needs] energy and the titan has less. Nothing changes. */
    data class NotAffordable(val needs: Int) : SparResult

    /** The titan has no ring to spar in. Nothing changes. */
    data object RingLocked : SparResult

    /** That rival is not on offer for this titan now. */
    data object NoSuchRival : SparResult

    /** There is no such pet. */
    data object NotFound : SparResult
}
