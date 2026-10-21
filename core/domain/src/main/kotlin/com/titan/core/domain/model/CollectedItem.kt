package com.titan.core.domain.model

import java.time.Instant

/**
 * One collectible the user actually holds, and the drop that handed it to them.
 *
 * ### Not an achievement
 *
 * [Achievement] is a set: a milestone is held or it is not, and unlocking it twice is one row.
 * This is an **inventory** — the same collectible can drop again next week, and a user who has
 * pulled three of something holds three of them. That is the whole difference, and it is why a
 * collected item is identified by [id] rather than by [collectibleId].
 *
 * ### What a collectible *is* lives in the catalogue
 *
 * The rarity, and everything else that describes the thing itself, is data in `:core:domain`
 * (#107) keyed by [collectibleId] — the call [Achievement] and [Quest] both make. A row of this
 * kind says only that a drop happened, so a build that no longer recognises [collectibleId] is
 * looking at a drop from a newer version rather than at a corrupt row.
 *
 * ### No user-facing strings, here or on the row behind it
 *
 * Both [collectibleId] and [source] are stable ids that double as string-resource keys, the way
 * [Achievement.id] and [Quest.id] already do. `:core:domain` is a plain Kotlin library with no
 * `Context`: a name or a "found while exploring a new gym" on this class would make the app
 * untranslatable at the one place a translation is most obviously needed.
 *
 * @property id the drop's own local row id, and the only thing that identifies it. Not portable
 *   across installs or exports — see `docs/PERSISTENCE.md`.
 * @property collectibleId the catalogue's stable key for the thing that dropped. Part of the
 *   storage contract: renaming one orphans every drop already written under the old spelling.
 * @property source the stable key of what *caused* this drop — the trigger, not a description
 *   of it. A plain id rather than an enum for the same reason [collectibleId] is one: the
 *   taxonomy of triggers belongs to the drop engine (#107), it can grow without a schema change,
 *   and a value this build does not recognise reads back as it was written rather than as null.
 * @property acquiredAt when the drop was **earned** — the triggering event's own moment, not
 *   when the row was written, so a cascade retried an hour later dates the drop to the workout
 *   that produced it. The rule `PrDetectionUseCase`, `AchievementTriggerUseCase` and
 *   `QuestCompletionUseCase` all already follow.
 */
data class CollectedItem(
    val id: Long,
    val collectibleId: String,
    val source: String,
    val acquiredAt: Instant,
)
