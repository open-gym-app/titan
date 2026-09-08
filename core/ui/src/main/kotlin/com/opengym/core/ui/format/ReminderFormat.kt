package com.opengym.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.notification.ReminderPreferences
import com.opengym.core.ui.R
import java.time.DayOfWeek
import java.util.Locale

/*
 * A reminder schedule, as a line somebody can read at a glance: `MWF · 18:00`.
 *
 * The fifth quantity in the table in CONTRIBUTING.md § Using the design system, and here for
 * the same reason as the other four: two screens draw this — the settings row and the reminder
 * screen's own header — and a schedule that reads `MWF` in one place and `Mon, Wed, Fri` in the
 * other is two answers to one question.
 *
 * These are `@Composable` where the rest of the `format` package is not, because three of the
 * four cases are *words* rather than a rendering of the value — "Every day" is not something
 * `DayOfWeek` can be asked for — and words live in `strings.xml`. The pure half is
 * [reminderDayInitials], which a test can assert without a composition.
 */

/**
 * Which days, in three or four characters: `MWF`, `Every day`, `Weekdays`.
 *
 * The three named cases exist because the initials fail exactly where the schedule is most
 * ordinary. All seven days is `MTWTFSS`, which is a smear rather than a summary; Monday to
 * Friday is `MTWTF`, which nobody parses as "weekdays". Everything else is short enough that
 * the letters *are* the clearest form, and they carry information a phrase would lose — `MWF`
 * says which three.
 *
 * The empty case is a schedule the user has cleared every day out of. It says so rather than
 * rendering an empty string, because an empty right-hand column on a settings row reads as a
 * setting that failed to load.
 */
@Composable
@ReadOnlyComposable
fun formatReminderDays(days: Set<DayOfWeek>): String =
    when {
        days.isEmpty() -> stringResource(R.string.reminder_days_none)
        days.size == DAYS_PER_WEEK -> stringResource(R.string.reminder_days_every)
        days == WEEKDAYS -> stringResource(R.string.reminder_days_weekdays)
        days == WEEKEND -> stringResource(R.string.reminder_days_weekend)
        else -> reminderDayInitials(days)
    }

/**
 * The whole schedule on one line: `MWF · 18:00`.
 *
 * What the settings row's value column carries, and the reason [formatReminderDays] is kept
 * short — the row has a label and a chevron either side of it. The separator is the same
 * middle dot every other two-part value in the app uses.
 *
 * A schedule with no days chosen drops the time, because there is nothing for it to be the
 * time *of*: `No days · 18:00` states a fact about a reminder that will not fire.
 */
@Composable
@ReadOnlyComposable
fun formatReminderSchedule(reminders: ReminderPreferences): String =
    when {
        reminders.isSchedulable ->
            stringResource(
                R.string.reminder_schedule,
                formatReminderDays(reminders.days),
                formatTimeOfDay(reminders.timeOfDay),
            )

        else -> formatReminderDays(reminders.days)
    }

/**
 * The chosen days as their initials, in calendar order: `MWF`.
 *
 * Ordered from the locale's own first day rather than from Monday — see
 * [ReminderPreferences.daysFrom] — so a American user reads `SWF` for the same set an English
 * one reads as `WFS`. Both are the days they picked; neither starts the week in the middle.
 *
 * Joined with nothing between them, which is what makes it read as a code rather than as a
 * list. It is the one string here that is not in `strings.xml`, because it has no words in it:
 * the letters come from [weekdayInitial], which is already localized.
 */
fun reminderDayInitials(
    days: Set<DayOfWeek>,
    locale: Locale = formattingLocale(),
): String =
    ReminderPreferences(days = days)
        .daysFrom(firstDayOfWeek(locale))
        .joinToString(separator = "") { weekdayInitial(it, locale) }

private const val DAYS_PER_WEEK = 7

/**
 * Monday to Friday, as the *set* the phrase names.
 *
 * Not derived from the locale's first day: "weekdays" means the five working days, and those
 * are Monday to Friday whichever day a calendar in that locale starts drawing from. A locale
 * where the working week genuinely differs is #41's problem and needs a phrase of its own
 * rather than a different set behind the same word.
 */
private val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

/** The other two. */
private val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
