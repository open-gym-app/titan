package com.titan.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.format.formatAsLength
import com.titan.core.ui.format.formatClockValue
import com.titan.core.ui.format.formatDayOfMonth
import com.titan.core.ui.format.formatDistance
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.format.formatTimeOfDay
import com.titan.core.ui.format.formatVolume
import com.titan.core.ui.format.formatWeekday
import com.titan.core.ui.format.localDateOf
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One logged session, as a list shows it.
 *
 * ```text
 * ┌────┐
 * │ 28 │  Push Day · Upper   PR
 * │ MON│  52m · 12 sets · 4,820 kg          ›
 * └────┘
 * ```
 *
 * Shared rather than owned by the sessions screen because two of them draw it — the history
 * tab (#24) and the home screen's recent list (#23) — and the figures on it are the kind that
 * drift: a set count that counts a warm-up on one screen and not on the other is worse than
 * no count.
 *
 * ### The date is a rail, not a line of the row
 *
 * The wireframes put the day in its own ruled column at the start of the row rather than in
 * the meta line, and that is load-bearing rather than decorative: a list of sessions is
 * scanned down the left edge for *when*, and a date buried in a mono line that also holds
 * three figures is not scannable. The day number and its weekday are the whole rail; the
 * month is the section heading above, which is what keeps the rail two glyphs wide.
 *
 * ### What it does not take
 *
 * No [com.titan.core.domain.model.WorkoutSession]. The row needs four figures that do not
 * live on that row — the duration is derived from two of its columns, the totals come from an
 * aggregate over the sets — so taking the domain type would mean taking it *and* everything
 * beside it, and a caller could then pass a session whose totals belong to a different one.
 *
 * @param name what the user called it, or **null** for a session started empty and never
 *   named. The fallback is the time it started, which is the date-derived title
 *   [com.titan.core.domain.model.WorkoutSession.name] documents — and the one that stays
 *   distinct when a lifter trains twice in a day, which "Workout" would not.
 * @param startedAt epoch millis UTC, rendered in the device's zone.
 * @param duration how long the session ran, paused stretches included, matching
 *   [com.titan.core.domain.model.SessionSummary.durationMillis].
 * @param totalVolume milligram-reps, formatted in the ambient unit system.
 * @param timedSeconds the session's timed sets summed — see
 *   [com.titan.core.domain.model.SessionTotals.timedSeconds]. Drawn after the volume where it
 *   is not zero, and in place of a zero volume, so a run's row says what the run was (#M7-17).
 * @param totalDistance the ground those sets covered, in centimetres. Drawn the same way.
 * @param hasRecord whether a personal record was set here. The accent badge, and the only
 *   colour on the row.
 * @param isToday draws the rail in the accent. Passed rather than read off a clock in here:
 *   a composable that asked `System.currentTimeMillis()` would be right when it composed and
 *   quietly wrong at midnight, and this codebase keeps its clocks where they can be pinned.
 * @param onClick opens the session. Null draws the row inert — no ripple, no chevron, no
 *   click role — which is what a row being *shown* rather than offered should be.
 */
@Composable
fun SessionRow(
    name: String?,
    startedAt: Long,
    duration: Duration,
    totalSets: Int,
    totalVolume: Long,
    modifier: Modifier = Modifier,
    timedSeconds: Long = 0,
    totalDistance: Long = 0,
    hasRecord: Boolean = false,
    isToday: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val title = name ?: formatTimeOfDay(startedAt)
    val work = workFigures(totalVolume = totalVolume, timedSeconds = timedSeconds, totalDistance = totalDistance)
    val meta = sessionMeta(duration = duration, totalSets = totalSets, work = work)
    // Resolved before the modifier chain: `clearAndSetSemantics` takes a plain lambda rather
    // than a composable one, so the strings cannot be read inside it.
    val description = sessionDescription(
        title = title,
        startedAt = startedAt,
        duration = duration,
        totalSets = totalSets,
        work = work,
        hasRecord = hasRecord,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // One node, not six. A screen reader walking a history list should hear the
            // whole row and move to the next one, not step through a day number, a weekday,
            // a name, a badge and three figures.
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = TitanTheme.spacing.card, vertical = TitanTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateRail(startedAt = startedAt, isToday = isToday)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hasRecord) PrBadge()
            }
            Text(
                text = meta,
                style = TitanTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                // The row already says where it goes; a chevron announced beside it is the
                // decoration being read out.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * The day and its weekday, in their own column.
 *
 * Accent for today, which is the row a user opening the tab mid-workout is looking for, and
 * the ordinary use of the accent: marking the one thing that matters right now. There is at
 * most one of these on screen by construction — there is only one today.
 */
@Composable
private fun DateRail(
    startedAt: Long,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val date = localDateOf(startedAt)
    val color = if (isToday) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier.width(RAIL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatDayOfMonth(date),
            style = TitanTheme.typography.numeric,
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Eyebrow(text = formatWeekday(date), color = color)
    }
}

/**
 * `52m · 12 sets · 4,820 kg`, or `35m · 1 set · 5 km · 27:14` for a run — the figures in the
 * order a lifter reads them.
 *
 * A session with no sets at all is its length and nothing else. That is a workout imported from
 * Health Connect (#54), which recorded a span and no lifts, and `0 sets · 0 kg` beside it would
 * read as a workout in which nothing was done rather than one whose sets were never known.
 */
@Composable
private fun sessionMeta(
    duration: Duration,
    totalSets: Int,
    work: List<String>,
): String {
    if (totalSets == 0) return duration.formatAsLength()

    val separator = stringResource(R.string.exercise_meta_separator)
    val sets = pluralStringResource(R.plurals.session_set_count, totalSets, totalSets)

    return (listOf(duration.formatAsLength(), sets) + work).joinToString(separator)
}

/**
 * What the session's sets did, as the figures that say so: the volume, the distance and the
 * time, each where there is any of it.
 *
 * The volume is dropped only for a session that has a distance or a time to quote instead — a
 * run is not a session that lifted 0 kg, and quoting the zero beside its five kilometres is the
 * noise #M7-17 removes. A session with none of the three keeps its zero, which is the truth
 * about a workout of unweighted, untimed sets.
 */
@Composable
private fun workFigures(
    totalVolume: Long,
    timedSeconds: Long,
    totalDistance: Long,
): List<String> =
    listOfNotNull(
        formatVolume(totalVolume).takeIf { showsVolume(totalVolume, timedSeconds, totalDistance) },
        totalDistance.takeIf { it > 0 }?.let { formatDistance(it) },
        timedSeconds.takeIf { it > 0 }?.let(::formatClockValue),
    )

/** The whole row as one sentence, with the words the drawn row abbreviates. */
@Composable
private fun sessionDescription(
    title: String,
    startedAt: Long,
    duration: Duration,
    totalSets: Int,
    work: List<String>,
    hasRecord: Boolean,
): String {
    // The record straight after the name, where the badge is drawn: the row's first line reads
    // "2:38 PM · PR", and a spoken row that keeps those words together is one Accessibility
    // Scanner matches to the pixels and a Voice Access user can say as they see it.
    // Without sets, without the figures about them — the drawn row's rule, in words.
    val parts = listOfNotNull(
        title,
        stringResource(R.string.pr_description_short).takeIf { hasRecord },
        formatSessionDate(startedAt),
        duration.formatAsLength(),
        pluralStringResource(R.plurals.session_set_count_description, totalSets, totalSets).takeIf { totalSets > 0 },
    ) + work.takeIf { totalSets > 0 }.orEmpty()

    return parts.joinToString(stringResource(R.string.plate_list_separator))
}

/**
 * Wide enough for two mono digits and a three-letter weekday under them, which is every day
 * of every month in every locale this renders in. One measurement, used once — not a scale
 * step.
 */
private val RAIL_WIDTH = 44.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

/**
 * Midday UTC on a fixed date, so the day the rail draws is the same one in every zone a
 * screenshot might be recorded in. An instant near midnight would render as two different
 * days on two machines, and the reference would fail on whichever one did not record it.
 */
private const val PREVIEW_STARTED_AT = 1_777_377_600_000L
private const val PREVIEW_VOLUME = 4_820_000_000L
private const val PREVIEW_SETS = 12

@Preview(name = "Session row", widthDp = 360)
@Composable
fun SessionRowPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        SessionRowCatalogue()
    }
}

@Preview(name = "Session row - dark", widthDp = 360)
@Composable
fun SessionRowDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        SessionRowCatalogue()
    }
}

/** The four shapes a row comes in: named, today, record-setting, and never named. */
@Composable
private fun SessionRowCatalogue() {
    TitanCard {
        SessionRow(
            name = "Push Day · Upper",
            startedAt = PREVIEW_STARTED_AT,
            duration = 52.minutes,
            totalSets = PREVIEW_SETS,
            totalVolume = PREVIEW_VOLUME,
            hasRecord = true,
            isToday = true,
            onClick = {},
        )
        HairlineDivider()
        SessionRow(
            name = "Pull Day",
            startedAt = 1_777_118_400_000L,
            duration = 48.minutes,
            totalSets = 14,
            totalVolume = 3_210_000_000L,
            onClick = {},
        )
        HairlineDivider()
        SessionRow(
            name = null,
            startedAt = 1_776_945_600_000L,
            duration = 94.minutes,
            totalSets = 21,
            totalVolume = 9_140_000_000L,
            onClick = {},
        )
    }
}
