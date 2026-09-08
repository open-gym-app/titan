package com.opengym.core.domain.notification

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * When the user has asked to be told to train: which days, and at what time on them.
 *
 * ### It carries no "enabled" flag, and that is the point
 *
 * Whether reminders are delivered at all is [NotificationKind.REMINDERS] in
 * [NotificationPreferences], where every other kind's switch lives. A second boolean here
 * would be a second answer to one question — and the failure mode is the one users report as
 * "I turned it on and nothing happened", because the two ended up disagreeing and only one of
 * them is the gate `Notifier` actually checks. So this holds the *schedule* and nothing else,
 * and a screen that wants to know whether the reminder is on asks the notification
 * preferences.
 *
 * ### The days are a set, and it may be empty
 *
 * An empty set is a reminder with nowhere to fire, which is a state the user can reach by
 * clearing the last day — and is left reachable rather than guarded against. The alternative
 * is a picker that refuses the seventh tap, which is a control that silently does nothing;
 * this way [isSchedulable] is false, the scheduler cancels everything, and the screen says so
 * where it would otherwise say when the next one is due.
 *
 * ### Wall clock, not an instant
 *
 * A [LocalTime] and a set of weekdays rather than a list of epoch timestamps: "Monday at
 * 18:00" is an appointment that survives a flight, a daylight-saving change and a phone that
 * was off all week. [nextOccurrence] resolves it against a zone at the moment the alarm is
 * set, which is the only moment the answer is knowable.
 *
 * @param days the weekdays a reminder fires on. Empty means none — see above.
 * @param timeOfDay the time on each of them. Whole minutes: the picker offers minutes, the
 *   settings row reads back minutes, and a stored `18:00:30` would be a schedule the UI could
 *   not express or display.
 */
data class ReminderPreferences(
    val days: Set<DayOfWeek> = DEFAULT_DAYS,
    val timeOfDay: LocalTime = DEFAULT_TIME,
) {
    init {
        require(timeOfDay.second == 0 && timeOfDay.nano == 0) {
            "a reminder is set to the minute, was $timeOfDay"
        }
    }

    /** Whether there is anything to schedule. False for a reminder with no days chosen. */
    val isSchedulable: Boolean get() = days.isNotEmpty()

    /**
     * The days in calendar order, starting from [weekStart].
     *
     * [days] is a `Set` and carries no order, so every reader that draws or names them —
     * the picker, the settings row's `MWF · 18:00` — would otherwise pick its own, and two of
     * them would pick differently. The starting day is a parameter because the week does not
     * begin on Monday everywhere; the presentation layer reads it from the locale.
     */
    fun daysFrom(weekStart: DayOfWeek): List<DayOfWeek> =
        (0 until DAYS_PER_WEEK)
            .map { offset -> weekStart.plus(offset.toLong()) }
            .filter { it in days }

    /**
     * The next moment a reminder for [day] is due, as epoch millis UTC.
     *
     * Strictly after [afterMillis]: an alarm set for the instant it is being scheduled at is
     * one the system may fire immediately, and this is called from a receiver that has *just*
     * been woken by the previous occurrence. Today at 18:00:00 re-arms to next week, not to
     * a second alarm one millisecond later.
     *
     * Resolved through [ZonedDateTime], which is what makes the daylight-saving cases right
     * rather than lucky: on the spring-forward Sunday, `with(LocalTime)` moves a time that
     * does not exist to the offset that does, and on the autumn one the earlier of the two
     * 01:30s is taken. Both are one reminder, on the day the user asked for.
     *
     * @param day the weekday to look for. Need not be in [days] — the caller decides which
     *   days it is arming, and this answers for any of them.
     * @param afterMillis the moment to search from, normally now.
     * @param zone the zone the user's wall clock is in. The device's, except in a test.
     */
    fun nextOccurrence(
        day: DayOfWeek,
        afterMillis: Long,
        zone: ZoneId,
    ): Long {
        val now = Instant.ofEpochMilli(afterMillis).atZone(zone)
        val candidate = now.with(TemporalAdjusters.nextOrSame(day)).with(timeOfDay)

        // `nextOrSame` lands on today when today is the day, so the time may already have
        // gone; a week on is then the next one. Compared as instants rather than as local
        // date-times, so an hour that a zone transition repeated is not counted twice.
        val next = if (candidate.toInstant().isAfter(now.toInstant())) candidate else candidate.plusWeeks(1)
        return next.toInstant().toEpochMilli()
    }

    /**
     * Every enabled day's next occurrence, keyed by the day it belongs to.
     *
     * The key matters as much as the value: the scheduler holds one alarm per weekday so that
     * cancelling Wednesday does not disturb Friday, and it needs to know which is which.
     */
    fun nextOccurrences(
        afterMillis: Long,
        zone: ZoneId,
    ): Map<DayOfWeek, Long> = days.associateWith { nextOccurrence(it, afterMillis, zone) }

    /**
     * The soonest of them, or null when no day is chosen.
     *
     * What the reminder screen puts under the time: "next Monday, in 2 days" is the one line
     * that turns a set of switches into something a user can check they got right.
     */
    fun nextOccurrenceOrNull(
        afterMillis: Long,
        zone: ZoneId,
    ): Long? = nextOccurrences(afterMillis, zone).values.minOrNull()

    companion object {

        /**
         * Monday, Wednesday and Friday — the wireframe's own `MWF · 18:00`, and the split
         * nearly every beginner programme is written on.
         *
         * A default that is *a schedule* rather than an empty one, because these are only ever
         * read once the user has turned reminders on: at that moment they have said they want
         * a nudge, and offering them an empty picker to build one from is asking a question
         * they came here having already answered.
         */
        val DEFAULT_DAYS: Set<DayOfWeek> =
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

        /** Six in the evening: after work, before the gym empties. */
        val DEFAULT_TIME: LocalTime = LocalTime.of(18, 0)

        private const val DAYS_PER_WEEK = 7
    }
}
