package com.titan.core.domain.gamification

import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.StreakResult
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.stats.TrainingWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Whether a finished session extends the user's streak, breaks it, or leaves it alone.
 *
 * Pure arithmetic over a stored state, a timestamp and a goal — no repository, no clock — so the
 * rule can be pinned by a test without a database, and so the one place it is stated is here.
 * [com.titan.core.domain.usecase.AchievementTriggerUseCase] reads the streak this produces;
 * [com.titan.core.domain.usecase.SessionCompletionPipeline] decides when it runs.
 *
 * ### A streak is a run of weeks that met the weekly goal
 *
 * The user sets how many workouts a week they mean to do — [DEFAULT_WEEKLY_GOAL] unless they
 * change it, anywhere in [WEEKLY_GOAL_RANGE] — and a week that holds that many finished sessions
 * joins the streak. The streak is how many such weeks in a row the user has put together.
 *
 * Weeks rather than days, because a lifter is not supposed to train every day. A daily rule
 * forgives rest days only through a grace window, and any window short enough to mean something
 * breaks the streak of somebody training Monday, Wednesday and Friday every single week, who has
 * missed nothing. A weekly goal asks the question the user is actually trying to answer — did I
 * do what I planned this week — and lets them say what they planned.
 *
 * Sessions are counted, not days: a goal of four is four workouts, and a morning lift and an
 * evening run on the same day are two. The goal is the user's own, so a lifter who splits their
 * days is setting a goal that means that.
 *
 * ### The week in progress never breaks anything
 *
 * A week is only judged once it is over, which is the first time a session lands in a later
 * week. Until then the streak stands at the weeks already met, and the current week joins it on
 * the session that reaches the goal ([StreakResult.Incremented]). Every other session that week
 * is [StreakResult.Counted]. The streak is broken by the first session after a week that fell
 * short, or after a week with no training at all — which, like the protection that may save it,
 * is discovered when the user next trains rather than at midnight on Sunday.
 *
 * Weeks are [TrainingWeek]'s, Monday to Sunday in **UTC**, so the streak and the weekly quests
 * roll over at the same moment. It is the same approximation stated there: a lifter in UTC-5
 * finishing at 20:00 on a Sunday is counted in the following week. Correcting it means storing
 * the zone each session was logged in, which no table does; it is written down here rather than
 * fixed.
 *
 * ### A session counts once
 *
 * [StreakState.lastCountedAt] is the last session counted, and a session finished at or before it
 * is [StreakResult.Unchanged]. That is the property that keeps re-running the cascade for a
 * session from counting it twice, and it is why a backfilled session counts nothing: it cannot
 * add to a week that has already been judged.
 *
 * ### The goal in force is the goal at the time
 *
 * Changing the goal mid-week applies from the next session finished. The week remembers whether
 * it has met its goal ([StreakState.weekGoalMet]) rather than re-deriving that from a count and
 * whatever the setting says now, so lowering the goal never takes away a week already counted,
 * and raising it never makes one count twice.
 *
 * ### Two entry points, and which one to call
 *
 * [advance] is the *state transition*: it decides what a finished session did, whether a break is
 * one the protection allowance should pay for, and returns the whole of the state to store.
 * Everything that persists a streak calls it. [isLive] is the question a screen asks — whether a
 * stored streak is still standing now — and it is the same rule asked about a moment rather than
 * a session.
 */
object StreakCalculator {

    /**
     * The weekly goal of a user who has not chosen one.
     *
     * Four: the number of sessions a week most training programmes are built around, and a goal
     * that a three-day split does not meet by accident — the setting is there for the lifter
     * who plans three.
     */
    const val DEFAULT_WEEKLY_GOAL = 4

    /**
     * Every weekly goal a user can set: at least one session, at most one a day.
     *
     * Seven is a ceiling on the *goal*, not on training — sessions past the goal are counted and
     * simply add nothing — so a lifter who trains twice a day is not stopped from doing so, only
     * from setting a goal that one missed session breaks.
     */
    val WEEKLY_GOAL_RANGE: IntRange = 1..7

    /** The zone the week, and the month of the protection allowance, are judged in. */
    val STREAK_ZONE: ZoneOffset = ZoneOffset.UTC

    /**
     * The shortest streak worth spending a protection on.
     *
     * A streak of one week costs nothing to rebuild — meet the goal this week and you are back
     * where you were — so paying an allowance for it spends a scarce resource on a loss the user
     * would not have noticed. Two weeks is where a streak starts being something to lose.
     *
     * A starting default, like [StreakProtection.MONTHLY_ALLOWANCE]: named here, read from here,
     * retuned by editing this line.
     */
    const val MIN_PROTECTED_STREAK = 2

    /**
     * How many weeks that fell short one protection covers.
     *
     * One. A protection is for the week life got in the way. A break spanning two or more short
     * weeks is not saved at all — not by two protections, not by one — because that is a lapse
     * rather than a slip, and an allowance that could cover a month away would make the streak a
     * measure of absence.
     *
     * The consequence worth stating: a user with a protection left and a three-week gap loses the
     * streak and keeps the protection. Nothing is spent on a break it cannot save.
     */
    const val MAX_PROTECTED_SHORT_WEEKS = 1L

    /**
     * The whole streak transition for a session finished at [finishedAt]: what it did, and the
     * state to store afterwards.
     *
     * Three things happen here, in this order, and the order matters:
     *
     * 1. **A session at or before the anchor stops here**, as [StreakResult.Unchanged] with the
     *    state exactly as it was — the allowance included. Stamping such a session's month over a
     *    later one already on file would refill a count that month has already spent from,
     *    handing back a protection the user used.
     * 2. **The allowance rolls over.** [StreakProtection.replenishedFor] is applied for the month
     *    [finishedAt] falls in *before* anything is spent, so a user whose first session of April
     *    breaks a streak spends April's allowance rather than whatever was left of March's. It is
     *    applied on every counted session, not only on a break, which is what makes the stored
     *    count correct without a scheduled job — see [StreakProtection].
     * 3. **The session is counted**, and a break it reveals is offered to the allowance. A break
     *    becomes [StreakResult.Protected] when, and only when, there is a protection left, the
     *    streak was at least [MIN_PROTECTED_STREAK] weeks, and no more than
     *    [MAX_PROTECTED_SHORT_WEEKS] weeks fell short. A protection is never spent on a session
     *    that did not break anything.
     *
     * ### Spending is automatic, and that is the design
     *
     * There is no prompt. The break is discovered when the *next* session is finished, which is
     * days after the week that fell short, so a dialog asking "spend a protection on last week?"
     * would be asking about a decision the user can no longer influence. The app spends it and
     * says so afterwards — which is why [StreakUpdate.protectionConsumed] and
     * [StreakUpdate.protectionsRemaining] exist as readable state rather than as an announcement.
     *
     * ### Why re-running this cannot spend a second protection
     *
     * The same property that makes the cascade re-entrant (see
     * [com.titan.core.domain.usecase.SessionCompletionPipeline]), and it needs no marker of its
     * own. A counted session moves `lastCountedAt` to [finishedAt], so a second pass for the same
     * session finds itself at the anchor and comes back [StreakResult.Unchanged] — which spends
     * nothing. The only path to a second protection is a genuine second break, in a later week.
     *
     * [StreakProtection.lastConsumedAt] therefore records when one was spent for the surfaces to
     * draw; the idempotency does not rest on it.
     *
     * @param state the streak as stored — [StreakState]'s defaults for a user with no history.
     * @param finishedAt when the session being applied finished. The workout's own moment, never
     *   a clock read at the point of writing, so a cascade retried an hour later still counts the
     *   week the training happened in and still spends the right month's allowance.
     * @param weeklyGoal how many sessions make a week count, as the user has it set now.
     * @throws IllegalArgumentException if [weeklyGoal] is outside [WEEKLY_GOAL_RANGE], or the
     *   stored streak is negative — a streak is only ever counted up or restarted, so that is a
     *   corrupt row rather than a state to extend.
     */
    fun advance(
        state: StreakState,
        finishedAt: Instant,
        weeklyGoal: Int,
    ): StreakUpdate {
        require(weeklyGoal in WEEKLY_GOAL_RANGE) { "weeklyGoal must be in $WEEKLY_GOAL_RANGE, was $weeklyGoal" }
        require(state.currentStreak >= 0) { "currentStreak cannot be negative, was ${state.currentStreak}" }

        val anchor = state.lastCountedAt
        if (anchor != null && !finishedAt.isAfter(anchor)) return StreakUpdate(StreakResult.Unchanged, state)

        val protection = state.protection.replenishedFor(YearMonth.from(finishedAt.atZone(STREAK_ZONE)))
        val sameWeek = anchor != null && anchor.streakWeek() == finishedAt.streakWeek()
        val weekSessions = if (sameWeek) state.weekSessions + 1 else 1
        val alreadyMet = sameWeek && state.weekGoalMet
        // Only the session that takes the week to its goal adds it to the streak.
        val reachesGoal = !alreadyMet && weekSessions >= weeklyGoal
        val shortWeeks = if (anchor == null || sameWeek) {
            0L
        } else {
            shortWeeksBetween(
                anchor,
                state.weekGoalMet,
                finishedAt,
            )
        }
        val result = decide(state.currentStreak, shortWeeks, reachesGoal, protection)

        val streak = result.streakAfter(state.currentStreak)
        return StreakUpdate(
            result = result,
            state = StreakState(
                currentStreak = streak,
                longestStreak = maxOf(state.longestStreak, streak),
                lastCountedAt = finishedAt,
                weekSessions = weekSessions,
                weekGoalMet = alreadyMet || reachesGoal,
                protection = if (result is StreakResult.Protected) protection.consumedAt(finishedAt) else protection,
            ),
        )
    }

    /**
     * What a counted session did to a streak of [streak] weeks, given the [shortWeeks] it revealed
     * and whether it [reachesGoal] of its own week — the third step of [advance].
     */
    private fun decide(
        streak: Int,
        shortWeeks: Long,
        reachesGoal: Boolean,
        protection: StreakProtection,
    ): StreakResult {
        val added = if (reachesGoal) 1 else 0
        return when {
            // Nothing was missed — the same week, the week after one that met its goal, or a
            // first session ever — or there was no streak to break.
            shortWeeks == 0L || streak == 0 ->
                if (reachesGoal) StreakResult.Incremented(streak + 1) else StreakResult.Counted

            protection.canCover(streak, shortWeeks) ->
                StreakResult.Protected(newStreak = streak + added, protectionsRemaining = protection.remaining - 1)

            else -> StreakResult.Reset(newStreak = added)
        }
    }

    /**
     * Whether a streak last counted at [lastCountedAt] is **still standing** at [asOf] — the
     * question a screen asks, as opposed to the question a finished session asks.
     *
     * A stored streak is only ever recomputed when a session finishes, so the figure on the row
     * stays at twelve for as long as nobody trains: it is what the streak *was*, not what it is.
     * Drawing it unasked tells a lifter who last trained in March that they are twelve weeks
     * deep, which is the one thing a streak must never do. This is that check, and it is here
     * rather than in the surface because it is the rule [advance] applies, asked about a moment:
     * the streak stands while the week it was counted in is still running, and through the week
     * after it if that week met its goal.
     *
     * A protection that a session now would spend does not keep the streak standing here. The
     * streak is saved when the user trains, and until they do it is not certain they will — so a
     * surface draws it as lapsed, and the session that spends the protection is the one that says
     * it was saved.
     *
     * @param lastCountedAt when the streak was last counted, or null for a user who has none — for
     *   whom the answer is false whatever the stored figure says.
     * @param weekGoalMet whether the week [lastCountedAt] falls in met its goal — see
     *   [StreakState.weekGoalMet].
     * @param asOf the moment being asked about, normally now.
     */
    fun isLive(
        lastCountedAt: Instant?,
        weekGoalMet: Boolean,
        asOf: Instant,
    ): Boolean {
        if (lastCountedAt == null) return false
        // A clock that has gone backwards past the last counted session says nothing about
        // whether the streak lapsed, so it is left standing rather than cut.
        if (asOf.isBefore(lastCountedAt)) return true

        val weeksLater = ChronoUnit.WEEKS.between(lastCountedAt.streakWeek(), asOf.streakWeek())
        return weeksLater == 0L || (weeksLater == 1L && weekGoalMet)
    }

    /**
     * How many sessions have been counted in the week [asOf] falls in — what a screen draws
     * beside the goal ("2 of 4 this week").
     *
     * [weekSessions] belongs to the week [lastCountedAt] falls in, so it is this week's count
     * only while that is this week; any later week has had nothing counted yet.
     */
    fun sessionsThisWeek(
        lastCountedAt: Instant?,
        weekSessions: Int,
        asOf: Instant,
    ): Int = if (lastCountedAt != null && lastCountedAt.streakWeek() == asOf.streakWeek()) weekSessions else 0

    /**
     * Whether this allowance can pay for a break of [shortWeeks] in a streak of [streak] weeks —
     * the three conditions [advance] documents, in one place so they are read together.
     */
    private fun StreakProtection.canCover(
        streak: Int,
        shortWeeks: Long,
    ): Boolean = hasProtection && streak >= MIN_PROTECTED_STREAK && shortWeeks <= MAX_PROTECTED_SHORT_WEEKS

    /**
     * How many weeks between the anchor's and [finishedAt]'s fell short of the goal: every week
     * strictly between the two, which held no training at all, plus the anchor's own week if it
     * did not meet its goal. Called only for two different weeks, the later one being
     * [finishedAt]'s, which is the week in progress and is not judged yet.
     */
    private fun shortWeeksBetween(
        anchor: Instant,
        anchorWeekMet: Boolean,
        finishedAt: Instant,
    ): Long {
        val empty = ChronoUnit.WEEKS.between(anchor.streakWeek(), finishedAt.streakWeek()) - 1
        return empty + if (anchorWeekMet) 0 else 1
    }

    /** The Monday opening the week an instant counts in, in the zone the class doc argues for. */
    private fun Instant.streakWeek(): LocalDate = TrainingWeek.startOf(atZone(STREAK_ZONE).toLocalDate())
}
