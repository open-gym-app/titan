package com.opengym.feature.settings.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import com.opengym.core.domain.backup.AutoBackupSchedule
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.ui.format.formatDayAndMonth
import com.opengym.core.ui.format.formatMonthAndYear
import com.opengym.core.ui.format.formatPercentTenths
import com.opengym.feature.settings.R
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/*
 * How the backup screen's values read: the four refusals, the two figures and the date.
 *
 * Together in one file because they are one job — turning a domain value into words — and
 * because `BackupScreen.kt` is a screen rather than a phrasebook.
 */

/**
 * What each way a restore can be refused says to the user.
 *
 * A `when` over the sealed [ImportError] rather than a mapping table, so a fifth failure added
 * to the domain does not compile until somebody has decided what it reads as. That is the
 * whole reason `RestoreOutcome.Failed` carries the domain's own type instead of a message
 * enum: "import failed" for four different causes is not an error state, and two of the four
 * carry numbers the user needs to see.
 *
 * None of them quotes the technical detail the error also carries — a parser offset, a column
 * name, a SQLite message. That belongs in a bug report, not in a sentence somebody reads while
 * their backup has just been refused; the reasons are still on the error object for whoever
 * logs it.
 *
 * Every message ends by saying the device was **not** changed, except the one where that may
 * be untrue. `ImportError.IoFailure` is the only failure that can leave work done — the rows
 * restored and the settings not — so it is the only one that does not promise otherwise.
 */
@Composable
internal fun ImportError.message(): String =
    when (this) {
        is ImportError.MalformedJson -> stringResource(R.string.backup_error_malformed)

        is ImportError.UnsupportedSchemaVersion ->
            stringResource(R.string.backup_error_version, found, supported)

        is ImportError.ConstraintViolation -> stringResource(R.string.backup_error_constraint)

        is ImportError.IoFailure -> stringResource(R.string.backup_error_io)
    }

/**
 * `2,140` rather than `2140`.
 *
 * The composition's locale rather than the process default, so the grouping separator follows
 * a locale change without the screen having to be rebuilt — the rule `FootprintList` already
 * follows for the same numbers in the confirmation dialog.
 */
@Composable
internal fun Int.grouped(): String = NumberFormat.getIntegerInstance(Locale.current.platformLocale).format(this)

/**
 * `Training since April 2026`, or the sentence for a device that has logged nothing.
 *
 * The month rather than the day: a backup screen is asking *how long this device has been in
 * use*, and "since April 2024" answers it where "since 1 April 2024" invites a reader to work
 * out whether that was a Monday.
 */
@Composable
internal fun DataFootprint.trainingSince(): String {
    val startedAt = firstSessionAt ?: return stringResource(R.string.backup_footprint_since_never)
    val month = YearMonth.from(Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()))
    return stringResource(R.string.backup_footprint_since, formatMonthAndYear(month))
}

/**
 * The line under the auto-backup switch: off, waiting for its first run, or `Last backup 26 Apr`.
 *
 * The day and month rather than the month alone that [trainingSince] uses, and the difference is
 * what each is answering. That one asks how long the device has been in use, where this one is
 * the *only* report a job the user never sees ever makes — and at three kept backups the whole
 * span it can name is three weeks, inside which "April" identifies nothing.
 *
 * The failure has no case here. It is drawn as its own line, in the error role, *beside* this
 * one — see `AutoBackupSection`: a run that failed does not un-write the backup before it, and
 * folding the two into one sentence would make the user choose which of the two facts to lose.
 */
@Composable
internal fun AutoBackupSchedule.statusLine(): String {
    val lastSuccess = lastSuccessAt
    return when {
        !enabled -> stringResource(R.string.backup_auto_off)
        lastSuccess != null -> stringResource(R.string.backup_auto_last, formatDayAndMonth(lastSuccess))
        else -> stringResource(R.string.backup_auto_never)
    }
}

/**
 * How far along a run is, as the user reads it: `40%`, `40 %`, `٤٠٪`.
 *
 * **Rounded down**, so a bar that has not finished never announces 100%. Floored here rather
 * than left to the formatter, which rounds to nearest and would call 99.6% done.
 *
 * Through [formatPercentTenths] rather than a `%` glued to an `Int`: the symbol and its side
 * of the figure are both the locale's — Turkish writes `%40` — and the digits are too.
 */
internal fun progressPercent(
    completed: Long,
    total: Long,
): String = formatPercentTenths((((completed * PERCENT) / total) * TENTHS_PER_PERCENT).toInt())

private const val PERCENT = 100

/** [formatPercentTenths] counts in tenths of a percent; these figures are whole ones. */
private const val TENTHS_PER_PERCENT = 10
