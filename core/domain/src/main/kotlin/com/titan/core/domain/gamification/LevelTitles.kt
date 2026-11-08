package com.titan.core.domain.gamification

/**
 * The rank a level carries, as a table rather than a ladder of conditions.
 *
 * #104 asks for this shape explicitly, and for the same reason [Achievements] and [Quests] have
 * it: a rank added or a threshold retuned is a line here and no branch anywhere. Everything that
 * draws a rank reads [ALL] or calls [forLevel].
 *
 * ### Ids, not words
 *
 * [LevelTitle.id] is a string-resource key, not a name. `:core:domain` is a plain Kotlin library
 * with no `Context`, so an English rank on this object would make the app untranslatable at one
 * of the places it is most visible — the same call [Achievements] and [Quests] make. The wording
 * lives in `:feature:progression`.
 *
 * Unlike those two, these ids are **not** a storage contract: no table holds a rank, because a
 * rank is derived from the level and the level is derived from the XP. Renaming one changes what
 * a screen looks up and nothing that is already written down, so it needs no migration — only a
 * matching string resource.
 *
 * ### The ranks
 *
 * Seven bands over the first fifty levels, spaced so the early ones arrive quickly — the first
 * three land inside the first few weeks of training, which is when a new user is deciding
 * whether any of this is for them — and the later ones stretch out. A rank every five levels
 * forever would make the word meaningless; a rank at 1 and the next at 40 would make it
 * invisible.
 *
 * **These bands are starting defaults**, like the XP constants and the quest targets: nothing has
 * been validated against real usage. Each threshold is named below and read from nowhere else.
 */
object LevelTitles {

    /** Level 1 and up: everybody starts here. */
    val NEWCOMER = LevelTitle(id = "newcomer", fromLevel = XpCalculator.FIRST_LEVEL)

    /** Level 3: past the point where the app was a thing being tried out. */
    val REGULAR = LevelTitle(id = "regular", fromLevel = 3)

    /** Level 5. */
    val IRON_DISCIPLE = LevelTitle(id = "iron_disciple", fromLevel = 5)

    /** Level 10. */
    val STEEL_FORGED = LevelTitle(id = "steel_forged", fromLevel = 10)

    /** Level 20. */
    val RELENTLESS = LevelTitle(id = "relentless", fromLevel = 20)

    /** Level 35. */
    val UNBREAKABLE = LevelTitle(id = "unbreakable", fromLevel = 35)

    /** Level 50, and every level after it — see [forLevel]. */
    val LEGEND = LevelTitle(id = "legend", fromLevel = 50)

    /**
     * Every rank, lowest first. The order is what [forLevel] walks and what a table of ranks
     * would draw.
     */
    val ALL: List<LevelTitle> = listOf(
        NEWCOMER,
        REGULAR,
        IRON_DISCIPLE,
        STEEL_FORGED,
        RELENTLESS,
        UNBREAKABLE,
        LEGEND,
    )

    /**
     * The rank [level] holds.
     *
     * A level past the last band keeps the last rank rather than falling off the end — #104 asks
     * for exactly this, and the alternative is a blank where a user who has out-levelled the
     * table should see the highest one there is. A level *below* the first band cannot happen —
     * [XpCalculator.FIRST_LEVEL] is where the table opens — but it returns [NEWCOMER] rather
     * than throwing, because a corrupt row should cost a user their rank and not their screen.
     */
    fun forLevel(level: Int): LevelTitle = ALL.lastOrNull { level >= it.fromLevel } ?: NEWCOMER
}

/**
 * One rank and the level it opens at.
 *
 * @property id the stable key the screen resolves to words. See [LevelTitles] for why it is not
 *   the words themselves, and why this id is not a storage contract.
 * @property fromLevel the lowest level holding this rank. Held until the next band opens, and
 *   for the last band, held forever.
 */
data class LevelTitle(
    val id: String,
    val fromLevel: Int,
) {

    init {
        require(fromLevel >= XpCalculator.FIRST_LEVEL) {
            "a rank cannot open below level ${XpCalculator.FIRST_LEVEL}, was $fromLevel for $id"
        }
    }
}
