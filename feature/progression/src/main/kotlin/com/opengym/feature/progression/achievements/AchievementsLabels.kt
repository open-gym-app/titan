package com.opengym.feature.progression.achievements

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.gamification.AchievementTier
import com.opengym.core.domain.model.Achievement
import com.opengym.feature.progression.R

/**
 * The milestone's words.
 *
 * A `when` over ids rather than a name carried on `Achievements`: `:core:domain` is a plain Kotlin
 * library with no `Context`, and putting user-visible English on the catalogue would make the app
 * untranslatable at one of the places it is most visible. The same call `QuestLogLabels` makes for
 * the quests, and `:feature:session` for the six it draws on the summary.
 *
 * The `else` names the thing generically rather than blanking. An id with no string behind it
 * means a catalogue entry added or renamed without its resource — a build-time mistake, not a
 * user-facing state — and a cell reading "Achievement" is a far better failure than an empty one.
 */
@Composable
internal fun Achievement.label(): String =
    when (id) {
        "first_session" -> stringResource(R.string.achievement_first_session)
        "ten_sessions" -> stringResource(R.string.achievement_ten_sessions)
        "hundred_sessions" -> stringResource(R.string.achievement_hundred_sessions)
        "seven_day_streak" -> stringResource(R.string.achievement_seven_day_streak)
        "thirty_day_streak" -> stringResource(R.string.achievement_thirty_day_streak)
        "first_record" -> stringResource(R.string.achievement_first_record)
        else -> stringResource(R.string.achievement_unknown)
    }

/**
 * The metal's name — `Gold`, `Silver`, `Bronze`.
 *
 * Spoken rather than drawn. The medal already *is* the metal, so printing the word under it would
 * be the screen labelling its own picture; what needs it is the content description, where the
 * rank is otherwise carried by a colour a screen reader cannot see. See `MedalShelf`.
 *
 * A `when` with no `else`: [AchievementTier] is a closed enum, so a fourth metal stops this
 * compiling rather than shipping a medal nobody can name.
 */
@Composable
internal fun AchievementTier.label(): String =
    when (this) {
        AchievementTier.GOLD -> stringResource(R.string.achievements_tier_gold)
        AchievementTier.SILVER -> stringResource(R.string.achievements_tier_silver)
        AchievementTier.BRONZE -> stringResource(R.string.achievements_tier_bronze)
    }
