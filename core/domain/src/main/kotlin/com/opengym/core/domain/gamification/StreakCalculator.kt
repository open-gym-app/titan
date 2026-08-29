package com.opengym.core.domain.gamification

import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.stats.TrainingWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Whether a finished session extends the user's streak, breaks it, or leaves it alone.
 *
 * Pure arithmetic over two timestamps and a count — no repository, no clock — so the rule can
 * be pinned by a test without a database, and so the one place it is stated is here.
 * [com.opengym.core.domain.usecase.AchievementTriggerUseCase] reads the streak this produces;
 * *storing* it, and deciding when this runs relative to PR detection and XP, is #145's.
 *
 * ### Consecutive days, or a rolling window — whichever saves the streak
 *
 * A streak survives a session landing on the calendar day after the last counted one, **and**
 * it survives one no more than [STREAK_GRACE_HOURS] after it whatever the dates say. Either
 * arm alone breaks a streak nobody missed a day of, and they do it for opposite reasons.
 *
 * The window is there for the user this mechanic exists to keep: somebody who trains Monday
 * evening and Wednesday morning has missed a calendar day and has not missed a workout, and
 * zeroing a five-week streak for that would punish a rest day taken at the wrong hour. Thirty-
 * six hours is the window that lets a lifter training every ~1.5 days keep a streak alive and
 * still breaks one for a lifter who takes two clear days off. So a session can extend a streak
 * without landing on the next calendar day at all: Monday 23:00 to Wednesday 09:00 is 34 hours
 * and counts, even though Tuesday held no training.
 *
 * The calendar arm is there because `lastCountedAt` is the *first* counted session of its day
 * and deliberately does not advance for the ones after it (see below). The window is therefore
 * measured from whatever hour that day happened to be counted at, not from the end of the day
 * itself — so without this arm a lifter who trained at 02:00 on the Tuesday and again at 20:00
 * on the Wednesday would be 42 hours apart and lose a streak they had trained every day of.
 * Consecutive days always count; the window only ever adds to that.
 *
 * **[STREAK_GRACE_HOURS] is a starting default.** It has not been validated against real
 * usage — it is named here, read from here, and tuning it is an edit to one line.
 *
 * ### The same day counts once
 *
 * A second session finished on a day the streak has already been counted for leaves it
 * [StreakResult.Unchanged]. A streak is a count of days shown up, so a lifter who benches in
 * the morning and runs in the evening must not out-pace one who trains once — the same
 * distinction [com.opengym.core.domain.usecase.ConsistencyUseCase] draws between days and
 * sessions, and the property that keeps re-finishing a session from inflating anything.
 *
 * "The same day" is read in **UTC**, matching [TrainingWeek] and the epoch-millis stamps the
 * rows carry, and it is the same approximation stated there: a lifter in UTC-5 finishing at
 * 20:00 crosses a day boundary the calendar on their phone has not. Correcting it means
 * storing the zone each session was logged in, which no table does; it is written down here
 * rather than fixed.
 */
object StreakCalculator {

    /**
     * How long a streak survives without a session, in hours — the whole of the rule the
     * class doc argues for, and the only number to change to retune it.
     */
    const val STREAK_GRACE_HOURS = 36L

    /** [STREAK_GRACE_HOURS] as the duration the comparison actually uses. */
    val GRACE_PERIOD: Duration = Duration.ofHours(STREAK_GRACE_HOURS)

    /** The zone "the same calendar day" is judged in — see the class doc. */
    val STREAK_ZONE: ZoneOffset = ZoneOffset.UTC

    /**
     * What finishing a session at [finishedAt] does to a streak of [currentStreak] whose last
     * counted session was [lastCountedAt].
     *
     * @param lastCountedAt when the session that last *counted* towards the streak finished —
     *   null for a user with no streak history, whose session is day one whatever
     *   [currentStreak] says. Not "the last session finished": a second session on an already
     *   counted day does not move this timestamp, which is what keeps the same-day rule from
     *   sliding the window forward every workout.
     * @param currentStreak the streak before this session, zero for a user who has none.
     * @throws IllegalArgumentException if [currentStreak] is negative — a streak is only ever
     *   counted up or restarted, so that is a corrupt row rather than a state to extend.
     */
    fun onSessionFinished(
        finishedAt: Instant,
        lastCountedAt: Instant?,
        currentStreak: Int,
    ): StreakResult {
        require(currentStreak >= 0) { "currentStreak cannot be negative, was $currentStreak" }
        // Nothing on file to continue from, so this session opens a streak rather than
        // extending one — including in the contradictory case of a stored count with no
        // timestamp behind it, where the count is the half that cannot be trusted.
        if (lastCountedAt == null) return StreakResult.Incremented(StreakResult.FIRST_DAY)

        val gap = Duration.between(lastCountedAt, finishedAt)
        return when {
            // A session finished *before* the one the streak was last counted from — a
            // backfill, or a device clock that went backwards. It cannot extend a streak it
            // predates, and it must not break one either.
            gap.isNegative -> StreakResult.Unchanged

            isSameDay(lastCountedAt, finishedAt) -> StreakResult.Unchanged

            // Either arm is enough, and neither is enough on its own — see the class doc.
            gap <= GRACE_PERIOD || isNextDay(lastCountedAt, finishedAt) ->
                StreakResult.Incremented(currentStreak + 1)

            else -> StreakResult.Reset
        }
    }

    private fun isSameDay(
        first: Instant,
        second: Instant,
    ): Boolean = first.streakDate() == second.streakDate()

    /** Whether [second] falls on the calendar day directly after [first]'s. */
    private fun isNextDay(
        first: Instant,
        second: Instant,
    ): Boolean = first.streakDate().plusDays(1) == second.streakDate()

    /** The day an instant counts on, in the zone the class doc argues for. */
    private fun Instant.streakDate(): LocalDate = atZone(STREAK_ZONE).toLocalDate()
}
