package com.opengym.feature.progression.quests

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import com.opengym.core.domain.gamification.LevelTitle
import com.opengym.core.domain.model.Achievement
import com.opengym.core.domain.model.Quest
import com.opengym.feature.progression.R
import java.text.NumberFormat

/**
 * The words behind the domain's ids.
 *
 * Three `when`s over string keys rather than names carried on the catalogues: `:core:domain` is a
 * plain Kotlin library with no `Context`, and putting user-visible English on `Quests`,
 * `Achievements` or `LevelTitles` would make the app untranslatable at three of the places it is
 * most visible. The same call `:feature:session` makes for the milestones it draws.
 *
 * Each has an `else` that names the thing generically rather than blanking. An id with no string
 * behind it means a catalogue entry added or renamed without its resource — a build-time mistake,
 * not a user-facing state — and a row reading "Quest" is a far better failure than an empty one.
 */
@Composable
internal fun Quest.label(): String =
    when (id) {
        "weekly_sessions" -> stringResource(R.string.quest_weekly_sessions)
        "weekly_sessions_plus" -> stringResource(R.string.quest_weekly_sessions_plus)
        "weekly_record" -> stringResource(R.string.quest_weekly_record)
        "weekly_volume" -> stringResource(R.string.quest_weekly_volume)
        "monthly_sessions" -> stringResource(R.string.quest_monthly_sessions)
        "monthly_records" -> stringResource(R.string.quest_monthly_records)
        "monthly_volume" -> stringResource(R.string.quest_monthly_volume)
        else -> stringResource(R.string.quest_unknown)
    }

/**
 * The milestone's words.
 *
 * Duplicated from `:feature:session`, which draws the same six on the session summary. A string
 * resource belongs to one module, and the alternative — moving them to `:core:ui` — would give
 * the design-system module knowledge of the gamification catalogue, which is a worse edge than
 * six repeated lines.
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

/** The rank's words. */
@Composable
internal fun LevelTitle.label(): String =
    when (id) {
        "newcomer" -> stringResource(R.string.level_title_newcomer)
        "regular" -> stringResource(R.string.level_title_regular)
        "iron_disciple" -> stringResource(R.string.level_title_iron_disciple)
        "steel_forged" -> stringResource(R.string.level_title_steel_forged)
        "relentless" -> stringResource(R.string.level_title_relentless)
        "unbreakable" -> stringResource(R.string.level_title_unbreakable)
        "legend" -> stringResource(R.string.level_title_legend)
        else -> stringResource(R.string.level_title_unknown)
    }

/**
 * A count as the user reads it, with thousands grouped: `2,140`.
 *
 * XP totals and volume targets reach five and six figures, and an ungrouped `20000` is a number
 * the eye has to count digits on. Grouped in the *formatting* locale rather than with a literal
 * comma, the rule this codebase applies to every figure it draws.
 */
@Composable
internal fun formatCount(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.current.platformLocale).format(value)
