package com.opengym.core.domain.gamification

import com.opengym.core.domain.model.StreakProtection
import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.model.StreakState
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.stats.TrainingWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

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
 *
 * ### Two entry points, and which one to call
 *
 * [onSessionFinished] is the rule above and nothing else: two timestamps, a count, an answer.
 * [advance] is the *state transition* — it runs that rule, then decides whether a break it
 * reports is one the protection allowance should pay for, and returns the whole of the state
 * to store. Everything that persists a streak calls [advance]; [onSessionFinished] stays
 * public because it is the rule worth pinning on its own, without a state around it.
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

    /**
     * The shortest streak worth spending a protection on.
     *
     * A streak of one or two days costs nothing to rebuild — train tomorrow and you are back
     * where you were — so paying an allowance for one spends a scarce resource on a loss the
     * user would not have noticed, and leaves them without it in the month they break a
     * thirty-day run. Three is where a streak starts being something to lose.
     *
     * A starting default, like [STREAK_GRACE_HOURS] and
     * [StreakProtection.MONTHLY_ALLOWANCE]: named here, read from here, retuned by editing
     * this line.
     */
    const val MIN_PROTECTED_STREAK = 3

    /**
     * How many missed calendar days one protection covers.
     *
     * One. A protection is for the day life got in the way, and it sits directly above the
     * grace window: the window already carries a rest day taken at an awkward hour (see the
     * class doc), and this carries the day that was genuinely missed. A break spanning two or
     * more missed days is not saved at all — not by two protections, not by one — because
     * that is a lapse rather than a slip, and an allowance that could cover a fortnight away
     * would make the streak a measure of absence.
     *
     * The consequence worth stating: a user with three protections left and a four-day gap
     * loses the streak and keeps all three. Nothing is spent on a break it cannot save.
     */
    const val MAX_PROTECTED_MISSED_DAYS = 1L

    /**
     * The whole streak transition for a session finished at [finishedAt]: what it did, and
     * the state to store afterwards.
     *
     * Three things happen here, in this order, and the order matters:
     *
     * 1. **The allowance rolls over.** [StreakProtection.replenishedFor] is applied for the
     *    month [finishedAt] falls in *before* anything is spent, so a user whose first
     *    session of April breaks a streak spends April's allowance rather than whatever was
     *    left of March's. It is applied on every transition, not only on a break, which is
     *    what makes the stored count correct without a scheduled job — see [StreakProtection].
     *    The one exception is a session finished *before* the anchor — a backfill, or a device
     *    clock that went backwards. It cannot move the streak (step 2 answers
     *    [StreakResult.Unchanged] for it) and it must not move the allowance either: stamping
     *    its month over a later one already on file would refill a count that month has
     *    already spent from, handing back a protection the user used. Such a session leaves
     *    the allowance exactly as it found it, for the same reason it leaves `lastCountedAt`
     *    alone.
     * 2. **The rule runs**, unchanged: [onSessionFinished] over the timestamps and the count.
     * 3. **A break is offered to the allowance.** A [StreakResult.Reset] becomes a
     *    [StreakResult.Protected] when, and only when, there is a protection left, the streak
     *    was at least [MIN_PROTECTED_STREAK] days, and no more than
     *    [MAX_PROTECTED_MISSED_DAYS] calendar days were missed. Every other result passes
     *    through untouched — a protection is never spent on a session that did not break
     *    anything.
     *
     * ### Spending is automatic, and that is the design
     *
     * There is no prompt. The break is discovered when the *next* session is finished, which
     * is hours or days after the day it happened, so a dialog asking "spend a protection on
     * last Tuesday?" would be asking about a decision the user can no longer influence. The
     * app spends it and says so afterwards — which is why [StreakUpdate.protectionConsumed]
     * and [StreakUpdate.protectionsRemaining] exist as readable state rather than as an
     * announcement.
     *
     * ### Why re-running this cannot spend a second protection
     *
     * The same property that makes the cascade re-entrant (see
     * [com.opengym.core.domain.usecase.SessionCompletionPipeline]), and it needs no marker of
     * its own. A protected session advances `lastCountedAt` to [finishedAt] exactly as an
     * ordinary counted one does, so a second pass for the same session reads the timestamp
     * the first one wrote, lands on the same calendar day, and comes back
     * [StreakResult.Unchanged] — which spends nothing. The same holds for a *second session*
     * on that day, and for a backfilled session finished before the anchor. The only path to
     * a second protection is a genuine second break on a later day.
     *
     * [StreakProtection.lastConsumedAt] therefore records when one was spent for the surfaces
     * to draw; the idempotency does not rest on it, and a fence keyed on it would be a second
     * statement of a guarantee the same-day rule already gives.
     *
     * @param state the streak as stored — [StreakState]'s defaults for a user with no history.
     * @param finishedAt when the session being applied finished. The workout's own moment,
     *   never a clock read at the point of writing, so a cascade retried an hour later still
     *   counts the day the training happened on and still spends the right month's allowance.
     */
    fun advance(
        state: StreakState,
        finishedAt: Instant,
    ): StreakUpdate {
        // A session that predates the anchor is not this state's latest word on the month, so
        // it rolls nothing over — see the first step in the doc above.
        val protection =
            if (state.lastCountedAt?.isAfter(finishedAt) == true) {
                state.protection
            } else {
                state.protection.replenishedFor(YearMonth.from(finishedAt.atZone(STREAK_ZONE)))
            }
        val rule = onSessionFinished(finishedAt, state.lastCountedAt, state.currentStreak)
        val result =
            if (rule == StreakResult.Reset && protection.canCover(state, finishedAt)) {
                StreakResult.Protected(protectionsRemaining = protection.remaining - 1)
            } else {
                rule
            }

        val days = result.streakAfter(state.currentStreak)
        val spent =
            if (result is StreakResult.Protected) protection.consumedAt(finishedAt) else protection

        return StreakUpdate(
            result = result,
            state = state.copy(
                currentStreak = days,
                longestStreak = maxOf(state.longestStreak, days),
                // Only a session that counted moves the anchor, which is the whole of the
                // same-day guarantee — and, above, the whole of the idempotency.
                lastCountedAt = if (result == StreakResult.Unchanged) state.lastCountedAt else finishedAt,
                protection = spent,
            ),
        )
    }

    /**
     * Whether this allowance can pay for the break [state] just suffered — the three
     * conditions [advance] documents, in one place so they are read together.
     *
     * `lastCountedAt` is non-null on every path that reaches here: [onSessionFinished] only
     * answers [StreakResult.Reset] for a state that had an anchor to break away from. It is
     * read defensively anyway rather than forced, because a `!!` in a rule this quiet is a
     * crash waiting on a refactor of the case above it.
     */
    private fun StreakProtection.canCover(
        state: StreakState,
        finishedAt: Instant,
    ): Boolean {
        val anchor = state.lastCountedAt ?: return false
        return hasProtection &&
            state.currentStreak >= MIN_PROTECTED_STREAK &&
            missedDaysBetween(anchor, finishedAt) <= MAX_PROTECTED_MISSED_DAYS
    }

    /**
     * How many whole calendar days went untrained between two counted sessions.
     *
     * The days *strictly between* the two dates: Monday to Wednesday misses one — the
     * Tuesday — and Monday to Tuesday misses none. Never negative on the path that calls it,
     * since a break is only ever reported for a session that came after the anchor.
     */
    private fun missedDaysBetween(
        anchor: Instant,
        finishedAt: Instant,
    ): Long = ChronoUnit.DAYS.between(anchor.streakDate(), finishedAt.streakDate()) - 1

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
