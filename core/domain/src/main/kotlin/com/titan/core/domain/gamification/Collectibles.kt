package com.titan.core.domain.gamification

import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.CollectibleKind
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.Rarity

/**
 * The collectibles this build can drop, and the only place they are written down.
 *
 * A list of values, like [Achievements] and [Quests] and for the same reason: everything that
 * rolls, stores or draws a collectible reads [ALL], so a collectible added below needs no other
 * edit anywhere — no branch, no schema change, and no adjustment to the odds, because the rates
 * are priced per [Rarity] in [DropRates] rather than per entry. Adding a sixth common is a line
 * here and does not make commons any likelier; it makes the common band wider.
 *
 * ### Trophies, an egg, and food
 *
 * The trophies have no stat, use or effect, and the naming keeps it that way: a "lifting strap"
 * here is a souvenir of a session, not a piece of kit — `EquipmentType` is where equipment
 * lives. The egg hatches (#276). Food ([FOOD]) is eaten by the titan and is drawn from a pool of
 * its own, so it moves no trophy or egg odds: [TROPHY_POOL] is what the trophy roll reads.
 *
 * ### Every band is stocked, and that is a rule rather than an accident
 *
 * [DropRates] prices five bands; a band the catalogue does not stock for a trigger is a share of
 * the roll that silently drops nothing, which reads on the numbers as a drop rate lower than the
 * table says. `CollectiblesTest` holds every priced rarity to at least one entry per trigger, so
 * adding a rarity without stocking it fails there rather than quietly deflating the economy.
 *
 * ### The ids are a storage contract
 *
 * [Collectible.id] is what `CollectedItemEntity.collectibleId` holds (#170). Renaming one does
 * not rename the rows already written under the old spelling: the user's drop stops matching any
 * catalogue entry, and an inventory they earned reads back as something this build cannot name
 * or style. `CollectiblesTest` pins the id set for exactly that reason — a failure there is the
 * reminder that a rename needs a migration, not a bug in the test.
 */
object Collectibles {

    // ─── Common ─────────────────────────────────────────────────────────────────────────────

    /** A block of chalk. The first thing most users will pull, and the least of them. */
    val CHALK_BLOCK = collectible("chalk_block", Rarity.COMMON)

    /** A spare barbell collar, of the kind every gym has a bucket of and nobody owns. */
    val SPARE_COLLAR = collectible("spare_collar", Rarity.COMMON)

    /** A wristband, worn through. Somebody trained in this. */
    val WORN_WRISTBAND = collectible("worn_wristband", Rarity.COMMON)

    // ─── Uncommon ───────────────────────────────────────────────────────────────────────────

    /** A lifting strap, single, its pair long gone. */
    val LIFTING_STRAP = collectible("lifting_strap", Rarity.UNCOMMON)

    /** A steel shaker, dented. */
    val STEEL_SHAKER = collectible("steel_shaker", Rarity.UNCOMMON)

    // ─── Rare ───────────────────────────────────────────────────────────────────────────────

    /**
     * The wireframe's own example — "Pet egg · Rare".
     *
     * It hatches into a pet when the user asks it to — see
     * [com.titan.core.domain.usecase.HatchEggUseCase]. Kept at the rarity the wireframe gave it
     * so the mechanic the drawing describes is the mechanic that shipped.
     */
    val PET_EGG = collectible("pet_egg", Rarity.RARE, CollectibleKind.EGG)

    // ─── Epic ───────────────────────────────────────────────────────────────────────────────

    /** A dumbbell cast in gold. Useless for training, which is rather the point of a trophy. */
    val GOLDEN_DUMBBELL = collectible("golden_dumbbell", Rarity.EPIC)

    // ─── Legendary ──────────────────────────────────────────────────────────────────────────

    /** The top of the table: a crown of iron, and a story about the session that produced it. */
    val IRON_CROWN = collectible("iron_crown", Rarity.LEGENDARY)

    // ─── Food ───────────────────────────────────────────────────────────────────────────
    //
    // Eaten by the titan. Not in the trophy pool: a session pays food on a draw of its own (#288).

    val STEAK = food("steak", fuel = 40)
    val RICE = food("rice", fuel = 30)
    val SHAKE = food("shake", fuel = 25)
    val EGGS = food("eggs", fuel = 20)
    val OATS = food("oats", fuel = 15)
    val HONEY = food("honey", fuel = 12)
    val BANANA = food("banana", fuel = 10)
    val BERRIES = food("berries", fuel = 8)

    /** Every food, most filling first: the pantry's order. */
    val FOOD: List<Collectible> = listOf(STEAK, RICE, SHAKE, EGGS, OATS, HONEY, BANANA, BERRIES)

    /**
     * What the trophy roll draws from: the trophies and the egg, commonest band first.
     *
     * Kept apart from [FOOD] so that adding food moves no band's odds.
     */
    val TROPHY_POOL: List<Collectible> = listOf(
        CHALK_BLOCK,
        SPARE_COLLAR,
        WORN_WRISTBAND,
        LIFTING_STRAP,
        STEEL_SHAKER,
        PET_EGG,
        GOLDEN_DUMBBELL,
        IRON_CROWN,
    )

    /**
     * Every collectible this build knows about, commonest band first.
     *
     * The order is not read by the roll — [DropRates] walks [Rarity] itself, so a band's odds do
     * not depend on where its entries sit in this list — but it is the order a full-collection
     * screen would draw, and keeping it in step with [Rarity] is what makes the table above
     * legible as a ladder rather than as a bag. `CollectiblesTest` holds it.
     */
    val ALL: List<Collectible> = TROPHY_POOL + FOOD

    /**
     * The catalogue entry [id] names, or **null** if this build has none.
     *
     * Null rather than a throw, and that is the whole reason this exists rather than callers
     * indexing [ALL] themselves. A drop row holds an id and nothing else (see [Collectible]), so
     * a row written by a newer build — or by one whose spelling was changed without a migration —
     * reads back as an id with no entry behind it. That is a state the store can genuinely be in,
     * so the read has to have an answer for it: #104's recent-loot section draws such a drop
     * generically instead of crashing on the inventory the user actually earned.
     *
     * A scan rather than a map. The catalogue is eight entries and the caller is a screen drawing
     * at most a handful of rows; an index built to serve that would be a second copy of [ALL] to
     * keep in step for no measurable gain.
     */
    fun byId(id: String): Collectible? = ALL.firstOrNull { it.id == id }
}

/**
 * A catalogue entry, with the one trigger this build rolls filled in.
 *
 * Every collectible here drops from a finished session — see [DropSource], which ships one
 * constant and says why — so naming it on all eight entries would be eight copies of a fact
 * nothing varies, and the table above would read as a list of triggers rather than as a ladder
 * of rarities. The second trigger arrives as a parameter here, not as a rewrite of the entries
 * that already have one.
 */
private fun collectible(
    id: String,
    rarity: Rarity,
    kind: CollectibleKind = CollectibleKind.TROPHY,
): Collectible = Collectible(id = id, rarity = rarity, source = DropSource.SESSION_FINISHED, kind = kind)

/** A food. Common, for styling only: food is never in a rarity roll. */
private fun food(
    id: String,
    fuel: Int,
): Collectible =
    Collectible(
        id = id,
        rarity = Rarity.COMMON,
        source = DropSource.SESSION_FINISHED,
        kind = CollectibleKind.FOOD,
        fuel = fuel,
    )
