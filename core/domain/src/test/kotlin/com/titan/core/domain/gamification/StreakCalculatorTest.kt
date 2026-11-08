package com.titan.core.domain.gamification

import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.StreakResult
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.StreakUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

/**
 * The weekly rule — [StreakCalculator.advance] over sessions that never need the protection
 * allowance, and [StreakCalculator.isLive].
 *
 * `StreakProtectionTest` pins what happens once a break is revealed and the allowance may pay
 * for it; every break here is one the allowance cannot touch, because the streak is too short or
 * there is nothing left to spend.
 *
 * The calendar is March 2026, whose Mondays are the 2nd, 9th, 16th, 23rd and 30th.
 */
class StreakCalculatorTest {

    // ─── A week joins the streak on the session that meets its goal ─────────────────────

    @Test
    fun `a first session ever counts towards its week without a streak yet`() {
        val update = StreakCalculator.advance(StreakState(), at("2026-03-02"), weeklyGoal = 4)

        assertEquals(StreakResult.Counted, update.result)
        assertEquals(0, update.days)
        assertEquals(1, update.state.weekSessions)
        assertFalse(update.state.weekGoalMet)
    }

    @Test
    fun `the session that reaches the goal adds the week to the streak`() {
        val update = train(goal = 4, "2026-03-02", "2026-03-03", "2026-03-05", "2026-03-07")

        assertEquals(StreakResult.Incremented(1), update.result)
        assertEquals(1, update.days)
        assertTrue(update.state.weekGoalMet)
    }

    @Test
    fun `sessions past the goal count and add nothing`() {
        val update = train(goal = 3, "2026-03-02", "2026-03-04", "2026-03-06", "2026-03-07")

        assertEquals(StreakResult.Counted, update.result)
        assertEquals(1, update.days)
        assertEquals(4, update.state.weekSessions)
    }

    @Test
    fun `two sessions on the same day are two workouts`() {
        val update = StreakCalculator.advance(
            train(goal = 2, "2026-03-02").state,
            at("2026-03-02", hour = 20),
            weeklyGoal = 2,
        )

        assertEquals(StreakResult.Incremented(1), update.result)
    }

    @Test
    fun `a goal of one is met by the session itself`() {
        val update = StreakCalculator.advance(StreakState(), at("2026-03-02"), weeklyGoal = 1)

        assertEquals(StreakResult.Incremented(1), update.result)
    }

    @Test
    fun `consecutive weeks that meet the goal build the streak`() {
        val update = train(
            goal = 3,
            "2026-03-02", "2026-03-04", "2026-03-06",
            "2026-03-09", "2026-03-11", "2026-03-13",
            "2026-03-16", "2026-03-18", "2026-03-20",
        )

        assertEquals(StreakResult.Incremented(3), update.result)
        assertEquals(3, update.state.longestStreak)
    }

    @Test
    fun `the week after a met week keeps the streak while it is in progress`() {
        val update = train(goal = 3, "2026-03-02", "2026-03-04", "2026-03-06", "2026-03-09")

        assertEquals(StreakResult.Counted, update.result)
        assertEquals(1, update.days)
        assertEquals(1, update.state.weekSessions)
    }

    @Test
    fun `a Monday-to-Friday lifter meeting a goal of three never loses the streak to rest days`() {
        // What the old 36-hour rule broke every week: Wednesday to Friday is fine, Friday to
        // Monday is three days, and nothing was missed.
        val update = train(goal = 3, "2026-03-06", "2026-03-09", "2026-03-11", "2026-03-13")

        assertEquals(StreakResult.Incremented(1), update.result)
    }

    // ─── Weeks are Monday to Sunday, in UTC ─────────────────────────────────────────────

    @Test
    fun `Sunday night and Monday morning are different weeks`() {
        val sunday = train(goal = 1, "2026-03-08")
        val monday = StreakCalculator.advance(sunday.state, Instant.parse("2026-03-09T00:30:00Z"), weeklyGoal = 1)

        assertEquals(StreakResult.Incremented(2), monday.result)
    }

    // ─── Breaking ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a week that fell short breaks the streak at the next session`() {
        // A one-week streak, below the protection threshold, then two of three the week after.
        val short = train(goal = 3, "2026-03-02", "2026-03-04", "2026-03-06", "2026-03-09", "2026-03-11")

        val update = StreakCalculator.advance(short.state, at("2026-03-16"), weeklyGoal = 3)

        assertEquals(StreakResult.Reset(newStreak = 0), update.result)
        assertEquals(0, update.days)
        assertEquals(1, update.state.weekSessions)
    }

    @Test
    fun `a week with no training at all breaks it too`() {
        val met = train(goal = 1, "2026-03-02")

        val update = StreakCalculator.advance(met.state, at("2026-03-16"), weeklyGoal = 1)

        // Reset rather than Counted: the week of the 9th held nothing. The session in hand meets
        // a goal of one on its own, so the next streak starts at one.
        assertEquals(StreakResult.Reset(newStreak = 1), update.result)
    }

    @Test
    fun `with no streak to lose a short week breaks nothing`() {
        val update = train(goal = 4, "2026-03-02", "2026-03-16")

        assertEquals(StreakResult.Counted, update.result)
        assertEquals(1, update.state.weekSessions)
    }

    @Test
    fun `the best streak survives a break`() {
        val built = train(goal = 1, "2026-03-02", "2026-03-09", "2026-03-16")
        val noProtection = built.state.copy(protection = StreakProtection(remaining = 0, period = MARCH))

        val update = StreakCalculator.advance(noProtection, at("2026-03-30"), weeklyGoal = 1)

        assertEquals(StreakResult.Reset(newStreak = 1), update.result)
        assertEquals(3, update.state.longestStreak)
    }

    // ─── A session counts once ──────────────────────────────────────────────────────────

    @Test
    fun `the same session applied twice counts once`() {
        val once = train(goal = 4, "2026-03-02")

        val twice = StreakCalculator.advance(once.state, at("2026-03-02"), weeklyGoal = 4)

        assertEquals(StreakResult.Unchanged, twice.result)
        assertSame(once.state, twice.state)
    }

    @Test
    fun `a session finished before the last one counted changes nothing`() {
        val counted = train(goal = 4, "2026-03-04")

        val backfill = StreakCalculator.advance(counted.state, at("2026-03-03"), weeklyGoal = 4)

        assertEquals(StreakResult.Unchanged, backfill.result)
        assertEquals(1, backfill.state.weekSessions)
    }

    // ─── The goal in force is the goal at the time ──────────────────────────────────────

    @Test
    fun `a goal lowered mid-week is met by the next session`() {
        val twoOfFour = train(goal = 4, "2026-03-02", "2026-03-03")

        val update = StreakCalculator.advance(twoOfFour.state, at("2026-03-04"), weeklyGoal = 2)

        assertEquals(StreakResult.Incremented(1), update.result)
    }

    @Test
    fun `a goal raised after the week met it does not count the week twice`() {
        val met = train(goal = 2, "2026-03-02", "2026-03-03")

        val update = StreakCalculator.advance(met.state, at("2026-03-04"), weeklyGoal = 3)

        assertEquals(StreakResult.Counted, update.result)
        assertEquals(1, update.days)
    }

    @Test
    fun `negative - a goal outside the range is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreakCalculator.advance(StreakState(), at("2026-03-02"), weeklyGoal = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreakCalculator.advance(StreakState(), at("2026-03-02"), weeklyGoal = 8)
        }
    }

    @Test
    fun `negative - a negative streak is a corrupt row, not a state to extend`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreakCalculator.advance(StreakState(currentStreak = -1), at("2026-03-02"), weeklyGoal = 4)
        }
    }

    // ─── Whether a stored streak is still standing ──────────────────────────────────────

    @Test
    fun `a user with no streak history has nothing live`() {
        assertFalse(StreakCalculator.isLive(lastCountedAt = null, weekGoalMet = true, asOf = at("2026-03-02")))
    }

    @Test
    fun `a streak stands for the rest of the week it was counted in, goal met or not`() {
        assertTrue(StreakCalculator.isLive(at("2026-03-02"), weekGoalMet = false, asOf = at("2026-03-08", hour = 23)))
    }

    @Test
    fun `a met week keeps the streak standing through the week after it`() {
        assertTrue(StreakCalculator.isLive(at("2026-03-02"), weekGoalMet = true, asOf = at("2026-03-15", hour = 23)))
    }

    @Test
    fun `a short week has lapsed the streak once it is over`() {
        assertFalse(StreakCalculator.isLive(at("2026-03-02"), weekGoalMet = false, asOf = at("2026-03-09")))
    }

    @Test
    fun `a week with no training lapses even a met one`() {
        assertFalse(StreakCalculator.isLive(at("2026-03-02"), weekGoalMet = true, asOf = at("2026-03-16")))
    }

    @Test
    fun `a clock behind the last counted session leaves the streak standing`() {
        assertTrue(StreakCalculator.isLive(at("2026-03-16"), weekGoalMet = false, asOf = at("2026-03-02")))
    }

    // ─── This week's count ──────────────────────────────────────────────────────────────

    @Test
    fun `this week's count is the stored one while the anchor is this week`() {
        assertEquals(3, StreakCalculator.sessionsThisWeek(at("2026-03-02"), weekSessions = 3, asOf = at("2026-03-06")))
    }

    @Test
    fun `a week nothing has been counted in yet reads zero`() {
        assertEquals(0, StreakCalculator.sessionsThisWeek(at("2026-03-02"), weekSessions = 3, asOf = at("2026-03-09")))
        assertEquals(0, StreakCalculator.sessionsThisWeek(null, weekSessions = 0, asOf = at("2026-03-09")))
    }

    @Test
    fun `this week is met only while the stored flag belongs to this week`() {
        assertTrue(StreakCalculator.isWeekGoalMet(at("2026-03-02"), weekGoalMet = true, asOf = at("2026-03-06")))
        assertFalse(StreakCalculator.isWeekGoalMet(at("2026-03-02"), weekGoalMet = false, asOf = at("2026-03-06")))
        assertFalse(StreakCalculator.isWeekGoalMet(at("2026-03-02"), weekGoalMet = true, asOf = at("2026-03-09")))
        assertFalse(StreakCalculator.isWeekGoalMet(null, weekGoalMet = false, asOf = at("2026-03-09")))
    }

    @Test
    fun `a goal lowered mid-week does not meet the week until a session applies it`() {
        // Three sessions against a goal of four, then the goal is lowered to three.
        val state = train(4, "2026-03-02", "2026-03-03", "2026-03-04").state
        assertFalse(StreakCalculator.isWeekGoalMet(state.lastCountedAt, state.weekGoalMet, asOf = at("2026-03-05")))

        val next = StreakCalculator.advance(state, at("2026-03-05"), weeklyGoal = 3).state
        assertTrue(StreakCalculator.isWeekGoalMet(next.lastCountedAt, next.weekGoalMet, asOf = at("2026-03-06")))
    }

    /** [days] applied in order from a fresh state, at 18:00 UTC each, against [goal]. */
    private fun train(
        goal: Int,
        vararg days: String,
    ): StreakUpdate =
        days.fold(StreakUpdate(StreakResult.Unchanged, StreakState())) { previous, day ->
            StreakCalculator.advance(previous.state, at(day), weeklyGoal = goal)
        }

    private fun at(
        date: String,
        hour: Int = 18,
    ): Instant = Instant.parse("${date}T%02d:00:00Z".format(hour))

    private companion object {
        val MARCH: YearMonth = YearMonth.of(2026, 3)
    }
}
