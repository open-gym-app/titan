package com.opengym.core.domain.gamification

import com.opengym.core.domain.model.Collectible
import com.opengym.core.domain.model.DropSource
import com.opengym.core.domain.model.Rarity

/**
 * The collectibles this build can drop, and the only place they are written down.
 *
 * A list of values, like [Achievements] and [Quests] and for the same reason: everything that
 * rolls, stores or draws a collectible reads [ALL], so a collectible added below needs no other
 * edit anywhere — no branch, no schema change, and no adjustment to the odds, because the rates
 * are priced per [Rarity] in [DropRates] rather than per entry. Adding a sixth common is a line
 * here and does not make commons any likelier; it makes the common band wider.
 *
 * ### They are trophies, not equipment
 *
 * Nothing here has a stat, a use or an effect, and the naming keeps it that way on purpose. A
 * "lifting strap" in this catalogue is a souvenir of a session, not a piece of kit the app
 * models — `EquipmentType` is where equipment lives, and a collectible that started modifying
 * anything would be a mechanic rather than a drop. See [Collectible] for why that is a milestone
 * of its own rather than a field waiting to be filled in.
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
     * It hatches into nothing, because nothing hatches anything yet; see [Collectible]. Kept at
     * the rarity the wireframe gave it so the mechanic the drawing describes is the mechanic
     * that shipped.
     */
    val PET_EGG = collectible("pet_egg", Rarity.RARE)

    // ─── Epic ───────────────────────────────────────────────────────────────────────────────

    /** A dumbbell cast in gold. Useless for training, which is rather the point of a trophy. */
    val GOLDEN_DUMBBELL = collectible("golden_dumbbell", Rarity.EPIC)

    // ─── Legendary ──────────────────────────────────────────────────────────────────────────

    /** The top of the table: a crown of iron, and a story about the session that produced it. */
    val IRON_CROWN = collectible("iron_crown", Rarity.LEGENDARY)

    /**
     * Every collectible this build knows about, commonest band first.
     *
     * The order is not read by the roll — [DropRates] walks [Rarity] itself, so a band's odds do
     * not depend on where its entries sit in this list — but it is the order a full-collection
     * screen would draw, and keeping it in step with [Rarity] is what makes the table above
     * legible as a ladder rather than as a bag. `CollectiblesTest` holds it.
     */
    val ALL: List<Collectible> = listOf(
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
): Collectible = Collectible(id = id, rarity = rarity, source = DropSource.SESSION_FINISHED)
