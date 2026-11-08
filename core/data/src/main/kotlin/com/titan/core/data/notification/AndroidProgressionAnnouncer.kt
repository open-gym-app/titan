package com.titan.core.data.notification

import android.content.Context
import com.titan.core.data.R
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.FinishOrigin
import com.titan.core.domain.model.Rarity
import com.titan.core.domain.model.SessionProgression
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.navigation.DeepLinks
import com.titan.core.domain.notification.AppNotification
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.Notifier
import com.titan.core.domain.notification.ProgressionAnnouncer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The streak save and the loot drop, said out loud to somebody who was not watching.
 *
 * ### Nothing here computes anything
 *
 * Every figure in the body is read straight off the [SessionProgression] — `streak.weeks`,
 * `streak.protectionsRemaining`, the dropped [Collectible]'s id and rarity. There is not a
 * repository or a use case in this file, and that is the acceptance criterion rather than a
 * style preference: the cascade produced those numbers inside the writes that persisted them,
 * so anything re-read here could disagree with what the session actually paid.
 *
 * ### The words are a second copy, and knowingly so
 *
 * The collectible names and the rarity bands are also spelled out in `:feature:progression`,
 * which is where the quest log draws them. There is no module that can see both: Android
 * merges a library's resources into its *dependents*, and `:core:data` and a feature module
 * are siblings — neither may depend on the other, by the rule `checkArchitecture` enforces.
 * So the choice is between two copies of fifteen short nouns and putting a notification poster
 * in the presentation layer, and the copies are the smaller wrong thing.
 *
 * What that costs is stated so it is not discovered: #41 has two sets to translate, and a
 * reword of a collectible in one place leaves the other saying the old name. What it does not
 * cost is correctness — the id each name is keyed on is part of the storage contract and is
 * pinned by a test, so the mapping cannot silently break.
 */
@Singleton
class AndroidProgressionAnnouncer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notifier: Notifier,
) : ProgressionAnnouncer {

    override suspend fun announce(
        progression: SessionProgression,
        origin: FinishOrigin,
    ) {
        // Only a protection actually spent is news. `streak` is non-null on nearly every
        // session — it carries the standing allowance whether or not this one moved it — so
        // asking for the update is not the same as asking whether anything happened.
        val saved = progression.streak?.takeIf(StreakUpdate::protectionConsumed)
        val dropped = progression.lootDropped
        if (saved == null && dropped == null) return

        // A finish from the workout screen has a summary screen celebrating both of these on
        // the frame it returns. A notification beside it is an app talking over itself, and the
        // fastest way to get the channel muted — which would cost the announcements that *are*
        // worth having, the ones for a workout closed out from the shade.
        //
        // Exhaustive with no `else`, like the rarity band below: an origin added to the domain
        // has to answer this question rather than inherit an answer. And it is the origin
        // rather than the process lifecycle for the reason `FinishOrigin` gives — a finish from
        // the shade with the app on some other tab is foreground and has no recap coming, so
        // reading the process would silence the one announcement nothing else would make.
        val celebratedOnScreen = when (origin) {
            FinishOrigin.WORKOUT_SCREEN -> true
            FinishOrigin.NOTIFICATION_ACTION -> false
        }
        if (celebratedOnScreen) return

        notifier.post(
            AppNotification(
                kind = NotificationKind.PROGRESSION,
                title = titleFor(saved, dropped),
                // One notification with both facts rather than two posts or a group: there are
                // at most two, and `BigTextStyle` — which `AndroidNotifier` sets on every
                // body — is what makes both readable expanded.
                body = joinSentences(saved?.let(::streakBody), dropped?.let(::lootBody)),
                deepLink = DeepLinks.QUEST_LOG,
            ),
        )
    }

    /**
     * The headline, which says which of the three things happened.
     *
     * A third string for the combined case rather than the two joined: "Streak protection used
     * · New loot unlocked" is longer than a notification title gets to be, and the platform
     * would truncate it to the first half.
     */
    private fun titleFor(
        saved: StreakUpdate?,
        dropped: Collectible?,
    ): String =
        when {
            saved != null && dropped != null -> context.getString(R.string.progression_notification_both)
            saved != null -> context.getString(R.string.progression_notification_streak)
            else -> context.getString(R.string.progression_notification_loot)
        }

    /** "Saved your 12-week streak. 0 protections left this month." */
    private fun streakBody(saved: StreakUpdate): String {
        val weeks = context.resources.getQuantityString(
            R.plurals.progression_notification_streak_saved,
            saved.weeks,
            saved.weeks,
        )
        val left = context.resources.getQuantityString(
            R.plurals.progression_notification_protections_left,
            saved.protectionsRemaining,
            saved.protectionsRemaining,
        )
        return joinSentences(weeks, left)
    }

    /**
     * [first] and [second] as one body, or whichever of them there is.
     *
     * The join is a resource rather than a space for the reason `MeasureFormats.kt` gives about
     * units: what sits between two sentences belongs to the language, and a `+ " "` here is a
     * decision no translator can reach.
     */
    private fun joinSentences(
        first: String?,
        second: String?,
    ): String =
        when {
            first != null && second != null ->
                context.getString(R.string.progression_notification_body_join, first, second)

            else -> first ?: second.orEmpty()
        }

    /**
     * "You found a Pet egg · Rare."
     *
     * The rarity is in words and not only in the wireframe's `◈`, which is decoration: a glyph
     * is not readable by a screen reader and not distinguishable by somebody who cannot tell
     * the colours apart. See #40.
     */
    private fun lootBody(dropped: Collectible): String =
        context.getString(
            R.string.progression_notification_loot_found,
            context.getString(dropped.nameRes()),
            context.getString(dropped.rarity.labelRes()),
        )
}

/**
 * A collectible's name, from the id the drop was stored under.
 *
 * A `when` over the id rather than over a catalogue value, matching the quest log's own: the id
 * *is* the string key, per [Collectible], and the `else` is a drop this build has no entry for —
 * a newer version's collectible, which is still the user's loot and is named generically rather
 * than left out of the sentence.
 */
private fun Collectible.nameRes(): Int =
    when (id) {
        "chalk_block" -> R.string.progression_collectible_chalk_block
        "spare_collar" -> R.string.progression_collectible_spare_collar
        "worn_wristband" -> R.string.progression_collectible_worn_wristband
        "lifting_strap" -> R.string.progression_collectible_lifting_strap
        "steel_shaker" -> R.string.progression_collectible_steel_shaker
        "pet_egg" -> R.string.progression_collectible_pet_egg
        "golden_dumbbell" -> R.string.progression_collectible_golden_dumbbell
        "iron_crown" -> R.string.progression_collectible_iron_crown
        else -> R.string.progression_collectible_unknown
    }

/**
 * The rarity band's word.
 *
 * Exhaustive over [Rarity] with no `else`, so a band added to the domain stops this compiling
 * rather than shipping a drop announced without one.
 */
private fun Rarity.labelRes(): Int =
    when (this) {
        Rarity.COMMON -> R.string.progression_rarity_common
        Rarity.UNCOMMON -> R.string.progression_rarity_uncommon
        Rarity.RARE -> R.string.progression_rarity_rare
        Rarity.EPIC -> R.string.progression_rarity_epic
        Rarity.LEGENDARY -> R.string.progression_rarity_legendary
    }
