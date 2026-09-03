package com.opengym.feature.progression.quests

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import com.opengym.core.domain.gamification.LevelTitle
import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.QuestMetric
import com.opengym.core.domain.model.QuestPeriod
import com.opengym.core.ui.format.formatVolumeValue
import com.opengym.feature.progression.R
import java.text.NumberFormat

/**
 * The words behind the domain's ids.
 *
 * `when`s over string keys rather than names carried on the catalogues: `:core:domain` is a plain
 * Kotlin library with no `Context`, and putting user-visible English on `Quests` or `LevelTitles`
 * would make the app untranslatable at two of the places it is most visible. The same call
 * `AchievementsLabels` makes for the milestones, and `:feature:session` for the ones it draws.
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
 * The cadence's words — the heading over a group and the subject of its score.
 *
 * A `when` with no `else`, unlike the three catalogues below: [QuestPeriod] is a closed enum
 * rather than a table of ids, so a third cadence added to the domain stops this compiling instead
 * of shipping a group with no heading.
 */
@Composable
internal fun QuestPeriod.heading(): String =
    when (this) {
        QuestPeriod.WEEKLY -> stringResource(R.string.quest_log_quests_weekly)
        QuestPeriod.MONTHLY -> stringResource(R.string.quest_log_quests_monthly)
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

/**
 * One of a quest's two figures, in the unit its metric is *read* in rather than stored in.
 *
 * Sessions and records are counts and print as themselves. A volume target is milligrams — see
 * [QuestMetric.VOLUME_MILLIGRAMS], which is stored that way so nothing downstream rounds — and
 * printing that figure raw would put `20,000,000,000` under a quest called "Move 20 tonnes".
 * [formatVolumeValue] converts it to the user's own unit, which is what every other weight in the
 * app already does.
 *
 * A `when` with no `else`: a fourth metric stops this compiling rather than shipping a figure in
 * whatever unit it happens to be stored in.
 */
@Composable
internal fun formatQuestFigure(
    metric: QuestMetric,
    value: Long,
): String =
    when (metric) {
        QuestMetric.SESSIONS_FINISHED, QuestMetric.RECORDS_BROKEN -> formatCount(value)
        QuestMetric.VOLUME_MILLIGRAMS -> formatVolumeValue(value)
    }
