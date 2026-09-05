package com.opengym.core.domain.model

/**
 * One collectible the drop table can hand out, as **data**: what it is, how rare it is, and
 * what drops it.
 *
 * The shape is [Achievement]'s and [Quest]'s, for [Achievement]'s reason. A reward economy
 * written as branching logic — `if (roll < 5) drop("pet_egg")` — grows a code path per
 * collectible, and every one of those paths is a place for the weights to drift out of step
 * with each other until nobody can say what the drop rates actually are. Here, adding a
 * collectible is a line in [com.opengym.core.domain.gamification.Collectibles] and no new
 * behaviour at all: the evaluator already knows how to weigh a [rarity], and the rates in
 * [com.opengym.core.domain.gamification.DropRates] do not move because a new entry joined the
 * pool.
 *
 * ### What a collectible *does* is nothing, deliberately
 *
 * There is no effect, no consumption and no "equipped" flag, because there is no mechanic yet
 * for one to feed. A collectible that hatches, or that buys something, is a feature line of its
 * own — see #107, which says so — and the shape here is what keeps that a later addition rather
 * than a rewrite: the drop foundation stores *that* a thing was pulled, and anything the thing
 * does is a property added to this class when something reads it.
 *
 * ### No display name
 *
 * [id] is the string-resource key, which is the call [Achievement] and [Quest] both make and
 * for the same reason: `:core:domain` is a plain Kotlin library with no `Context`, and a name
 * here would make the app untranslatable at the one place a translation is most obviously
 * needed. #104 resolves [id] and [DropSource.key] to strings when it draws the recent-loot
 * section.
 *
 * @property id the stable key the drop is stored under (`CollectedItemEntity.collectibleId`,
 *   #170) and the string-resource key the quest log draws. **Part of the storage contract**: a
 *   rename orphans every drop already written under the old spelling, which is why a test pins
 *   the set the same way the persisted enum names are pinned.
 * @property rarity how rare this one is, and therefore how often it drops — see [Rarity] and
 *   [com.opengym.core.domain.gamification.DropRates].
 * @property source what has to happen for it to drop at all. One trigger per collectible, so a
 *   trigger's whole loot table is a filter over the catalogue and its odds can be read off that
 *   list. Something obtainable two ways turns this into a set of sources — a change confined to
 *   this field and the evaluator's filter, and not one to make before a second trigger exists.
 */
data class Collectible(
    val id: String,
    val rarity: Rarity,
    val source: DropSource,
) {

    init {
        require(id.isNotBlank()) { "a collectible needs an id: it is the key its drops are stored under" }
    }
}

/**
 * How rare a [Collectible] is — **ordered**, commonest first.
 *
 * The order is the whole of the type's meaning beyond its names, and two things read it. The
 * drop table lays its bands out in this order — `DropRates.WEIGHTS` is held to it by a test —
 * so declaration order *is* the shape of the odds curve; and #104 styles a drop by it, where
 * "rarer than" has to be a comparison rather than a lookup table of which colour outranks
 * which.
 *
 * That makes reordering these a behaviour change in two places at once. Adding a band in the
 * middle is a deliberate act — it needs a weight in
 * [com.opengym.core.domain.gamification.DropRates] and at least one collectible per trigger, or
 * the band is priced and unobtainable.
 *
 * Not persisted: what a drop row holds is [Collectible.id], and the rarity is looked back up
 * from the catalogue, so a constant here can be renamed without a migration.
 */
enum class Rarity {

    /** The floor of the table: what most drops are, and what a first drop almost always is. */
    COMMON,

    /** A step up. Frequent enough to be expected, uncommon enough to register. */
    UNCOMMON,

    /** The wireframe's own example band — "Pet egg · Rare". Roughly a monthly pull. */
    RARE,

    /** A pull worth remembering: months of training apart, on a normal schedule. */
    EPIC,

    /** Years, not months. The band that exists so the top of the table has no ceiling in sight. */
    LEGENDARY,
}

/**
 * What has to happen for a drop to be rolled at all.
 *
 * A trigger, not a description of one — the taxonomy lives here, in the drop engine, which is
 * why `CollectedItemRepository.recordDrop` takes a plain id and validates nothing: this can grow
 * without a schema change, and a row naming a trigger an older build does not know reads back as
 * it was written.
 *
 * ### One value, on purpose
 *
 * #107 named three triggers — a session finished, an achievement unlocked, a quest completed —
 * and #171 narrowed the wiring to the first, because each trigger needs its own idempotency
 * fence and three triggers is three of those rather than one. So the enum ships the trigger that
 * has a call site and no others: a constant here with nothing rolling it would be a vocabulary
 * for a mechanic that does not exist, which is the thing `AchievementMetric` and `QuestMetric`
 * both refuse. The second trigger arrives with its fence, its catalogue entries and its wiring
 * together.
 *
 * @property key the stable id written to `collected_item.source` and resolved to a string
 *   resource by #104's recent-loot section. Spelled out rather than taken from [name] for the
 *   reason every other stored id in this app is lower-snake: the column is a key, and a
 *   `SESSION_FINISHED` sitting beside `first_session` and `weekly_volume` would be the one
 *   spelling that came from a language's syntax rather than from a decision.
 */
enum class DropSource(val key: String) {

    /** A workout was closed out. Rolled by the post-session cascade, once per session (#171). */
    SESSION_FINISHED("session_finished"),
}
