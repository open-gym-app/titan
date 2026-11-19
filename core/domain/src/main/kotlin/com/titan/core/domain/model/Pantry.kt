package com.titan.core.domain.model

/**
 * One food in the pantry, whether or not any is in stock — the canvas lists empty ones dashed.
 *
 * @property food the catalogue entry.
 * @property count how many are in stock: dropped and not yet eaten.
 * @property isNew whether one dropped since the user last looked at this food.
 * @property newestFrom the session the newest one in stock came from, if it is known.
 */
data class PantryEntry(
    val food: Collectible,
    val count: Int,
    val isNew: Boolean,
    val newestFrom: PantrySource?,
)

/**
 * Where a drop came from, for a line like "from Push Day, Nov 12".
 *
 * @property sessionName what the session was called, or null for one that had no name.
 * @property finishedAt when it finished, epoch millis UTC, or null if it has not.
 */
data class PantrySource(
    val sessionId: Long,
    val sessionName: String?,
    val finishedAt: Long?,
)

/** What a feed does to the titan's vitals: each one before and after. */
data class FeedEffect(
    val before: TitanVitals,
    val after: TitanVitals,
) {

    /** How much [vital] moves. A screen shows only the ones that do. */
    fun change(vital: TitanVital): Int = after[vital] - before[vital]

    /** The vitals this feed moves, in [TitanVital] order. */
    val moved: List<TitanVital> get() = TitanVital.entries.filter { change(it) != 0 }
}

/** The answer to feeding the titan one food. Every answer is returned, none thrown. */
sealed interface FeedResult {

    /** Eaten: one fewer in stock, and [effect] applied. */
    data class Fed(val effect: FeedEffect) : FeedResult

    /** None of it in stock. */
    data object OutOfStock : FeedResult

    /** The titan's fuel is already full. Nothing is eaten. */
    data object Full : FeedResult

    /** That id is not a food. */
    data object NotFood : FeedResult

    /** There is no such pet. */
    data object NotFound : FeedResult
}
