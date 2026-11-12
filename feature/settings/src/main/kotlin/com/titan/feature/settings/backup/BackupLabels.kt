package com.titan.feature.settings.backup

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.backup.AutoBackupSchedule
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.model.DataFootprint
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.format.formatMonthAndYear
import com.titan.core.ui.format.formatPercentTenths
import com.titan.feature.settings.R
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/*
 * How the backup screen's values read: the six refusals, the two figures and the date.
 *
 * Together in one file because they are one job — turning a domain value into words — and
 * because `BackupScreen.kt` is a screen rather than a phrasebook.
 */

/**
 * What each way a restore can be refused says to the user.
 *
 * A `when` over the sealed [ImportError] rather than a mapping table, so a seventh failure
 * added to the domain does not compile until somebody has decided what it reads as. That is
 * the whole reason `RestoreOutcome.Failed` carries the domain's own type instead of a message
 * enum: "import failed" for six different causes is not an error state, and three of the six
 * carry numbers the user needs to see.
 *
 * None of them quotes the technical detail the error also carries — a parser offset, a column
 * name, a SQLite message. That belongs in a bug report, not in a sentence somebody reads while
 * their backup has just been refused; the reasons are still on the error object for whoever
 * logs it.
 *
 * Every message but one says the device was **not** changed. The exception is
 * `ImportError.SettingsNotRestored`, the one failure that leaves work done — the rows restored
 * and the settings not — and it says exactly that, rather than either promise.
 */
@Composable
internal fun ImportError.message(): String =
    when (this) {
        is ImportError.DocumentTooLarge -> stringResource(
            R.string.backup_error_too_large,
            Formatter.formatShortFileSize(LocalContext.current, limitBytes),
        )

        is ImportError.MalformedJson -> stringResource(R.string.backup_error_malformed)

        is ImportError.UnsupportedSchemaVersion ->
            stringResource(R.string.backup_error_version, found, supported)

        is ImportError.ConstraintViolation -> stringResource(R.string.backup_error_constraint)

        is ImportError.IoFailure -> stringResource(R.string.backup_error_io)

        is ImportError.SettingsNotRestored -> stringResource(R.string.backup_error_settings, rowsRestored)
    }

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
 * How far along a run is, as a screen reader says it: `40%`, `40 %`, `٤٠٪`.
 *
 * *Says*, not reads: nothing draws this figure. The bar is the drawn progress, and this is the
 * description of the row it is in — a **live region**, which speaks every time its text
 * changes. The export emits a progress value per page of rows, and a run over a 50,000-set
 * history would have TalkBack announce a percentage a hundred times in the few seconds it
 * takes. So the figure moves in **tens**: at most ten announcements per run, and then the
 * outcome card says what came of it.
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
): String {
    val tens = (completed * PERCENT / total) / ANNOUNCEMENT_STEP_PERCENT * ANNOUNCEMENT_STEP_PERCENT
    return formatPercentTenths((tens * TENTHS_PER_PERCENT).toInt())
}

private const val PERCENT = 100

/** The step the spoken percentage moves in. See [progressPercent]. */
private const val ANNOUNCEMENT_STEP_PERCENT = 10

/** [formatPercentTenths] counts in tenths of a percent; these figures are whole ones. */
private const val TENTHS_PER_PERCENT = 10
