package com.titan.core.domain.gamification

import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.StreakResult
import com.titan.core.domain.model.StreakState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

/**
 * The streak transition, allowance and all — [StreakCalculator.advance].
 *
 * `StreakCalculatorTest` pins the rule that decides *whether* a session broke a streak; this
 * pins what happens to the stored state once it has, which is where the protection mechanic
 * lives. Every case starts from a twelve-week streak whose last counted week — the one starting
 * Monday 2 March — held two of its three sessions, so the first session of any later week
 * reveals a short week.
 */
class StreakProtectionTest {

    // ─── Spending one ───────────────────────────────────────────────────────────────────

    @Test
    fun `a short week is paid for out of the allowance and the streak survives`() {
        val update = advance(onFile(streak = 12), NEXT_MONDAY)

        assertEquals(StreakResult.Protected(newStreak = 12, protectionsRemaining = 0), update.result)
        assertEquals(12, update.days)
        assertTrue(update.protectionConsumed)
    }

    @Test
    fun `the covered week is carried over, not added`() {
        val update = advance(onFile(streak = 12), NEXT_MONDAY)

        // Not 13: the covered week did not meet its goal, so it is not a week of the streak.
        assertEquals(12, update.days)
        assertEquals(1, update.state.weekSessions)
    }

    @Test
    fun `a protected session that meets its own week's goal adds that week`() {
        val update = advance(onFile(streak = 12), NEXT_MONDAY, goal = 1)

        assertEquals(StreakResult.Protected(newStreak = 13, protectionsRemaining = 0), update.result)
        assertEquals(13, update.state.longestStreak)
    }

    @Test
    fun `an empty week after a met one is covered the same way`() {
        val met = onFile(streak = 12).copy(weekSessions = 3, weekGoalMet = true)

        val update = advance(met, TWO_MONDAYS_ON)

        assertEquals(StreakResult.Protected(newStreak = 12, protectionsRemaining = 0), update.result)
    }

    @Test
    fun `spending one records when`() {
        val update = advance(onFile(streak = 12), NEXT_MONDAY)

        assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, update.protectionsRemaining)
        assertEquals(NEXT_MONDAY, update.state.protection.lastConsumedAt)
    }

    // ─── Not spending one ───────────────────────────────────────────────────────────────

    @Test
    fun `with nothing left in the month the streak breaks as it always did`() {
        val update = advance(onFile(streak = 12, protection = spent()), NEXT_MONDAY)

        assertEquals(StreakResult.Reset(newStreak = 0), update.result)
        assertFalse(update.protectionConsumed)
    }

    @Test
    fun `a streak too short to be worth protecting is not protected`() {
        val update = advance(onFile(streak = StreakCalculator.MIN_PROTECTED_STREAK - 1), NEXT_MONDAY)

        assertEquals(StreakResult.Reset(newStreak = 0), update.result)
        // And nothing was spent on it: the allowance is there for a streak worth losing.
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `two short weeks are a lapse, and nothing is spent on a break it cannot save`() {
        // The short week of the 2nd, then nothing at all the week of the 9th.
        val update = advance(onFile(streak = 12), TWO_MONDAYS_ON)

        assertEquals(StreakResult.Reset(newStreak = 0), update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `a session that breaks nothing spends nothing`() {
        val update = advance(onFile(streak = 12), SAME_WEEK, goal = GOAL + 1)

        assertEquals(StreakResult.Counted, update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    // ─── Re-running cannot spend twice ──────────────────────────────────────────────────

    @Test
    fun `re-applying the protected session spends nothing more`() {
        val first = advance(onFile(streak = 12), NEXT_MONDAY)

        val again = advance(first.state, NEXT_MONDAY)

        assertEquals(StreakResult.Unchanged, again.result)
        assertEquals(first.state, again.state)
    }

    // ─── The monthly boundary ───────────────────────────────────────────────────────────

    @Test
    fun `the allowance refills in the month the session lands in`() {
        val update = advance(onFile(streak = 12, protection = spent(FEBRUARY)), NEXT_MONDAY)

        // March's session finds March's allowance, and pays for the break out of it.
        assertEquals(MARCH, update.state.protection.period)
        assertTrue(update.protectionConsumed)
    }

    @Test
    fun `a session that changes nothing still rolls the month over`() {
        val update = advance(onFile(streak = 12, protection = spent(FEBRUARY)), SAME_WEEK, goal = GOAL + 1)

        assertEquals(StreakResult.Counted, update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `a session that predates the anchor rolls nothing over`() {
        // A phone set forward to April spent a protection there; the clock is corrected, and the
        // next session finishes back in March. It counts nothing, and must refill nothing.
        val april = StreakProtection(remaining = 0, period = YearMonth.of(2026, 4), lastConsumedAt = APRIL)
        val ahead = onFile(streak = 12, protection = april).copy(lastCountedAt = APRIL)

        val update = advance(ahead, NEXT_MONDAY)

        assertEquals(StreakResult.Unchanged, update.result)
        assertEquals(april, update.state.protection)
    }

    @Test
    fun `the same month is left exactly as it was found`() {
        val protection = StreakProtection(remaining = 0, period = MARCH, lastConsumedAt = ANCHOR)

        assertEquals(protection, protection.replenishedFor(MARCH))
    }

    // ─── The allowance's own invariants ─────────────────────────────────────────────────

    @Test
    fun `a fresh allowance is full and has no month yet`() {
        val fresh = StreakProtection()

        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, fresh.remaining)
        assertNull(fresh.period)
        assertNull(fresh.lastConsumedAt)
        assertTrue(fresh.hasProtection)
    }

    @Test
    fun `an exhausted allowance has nothing to spend`() {
        assertFalse(spent().hasProtection)
    }

    @Test
    fun `negative - spending what is not there is a caller's mistake`() {
        assertThrows(IllegalStateException::class.java) { spent().consumedAt(ANCHOR) }
    }

    @Test
    fun `negative - a negative remainder is a corrupt row, not a state to spend from`() {
        assertThrows(IllegalArgumentException::class.java) { StreakProtection(remaining = -1) }
    }

    private fun advance(
        state: StreakState,
        at: Instant,
        goal: Int = GOAL,
    ) = StreakCalculator.advance(state, at, weeklyGoal = goal)

    /**
     * A user [streak] weeks deep, last counted on Wednesday 4 March with two of the week's
     * [GOAL] sessions done, and the month's allowance as given.
     */
    private fun onFile(
        streak: Int,
        protection: StreakProtection = StreakProtection(period = MARCH),
    ): StreakState =
        StreakState(
            currentStreak = streak,
            longestStreak = streak,
            lastCountedAt = ANCHOR,
            weekSessions = 2,
            weekGoalMet = false,
            protection = protection,
        )

    private fun spent(period: YearMonth = MARCH): StreakProtection = StreakProtection(remaining = 0, period = period)

    private companion object {

        const val GOAL = 3

        val FEBRUARY: YearMonth = YearMonth.of(2026, 2)
        val MARCH: YearMonth = YearMonth.of(2026, 3)

        /** Wednesday 4 March, 19:00 UTC — the second session of a week that needed three. */
        val ANCHOR: Instant = Instant.parse("2026-03-04T19:00:00Z")

        /** Friday of the same week: the week is not over, so nothing can have broken. */
        val SAME_WEEK: Instant = Instant.parse("2026-03-06T19:00:00Z")

        /** The Monday after: the week of the 2nd is over, and fell short. */
        val NEXT_MONDAY: Instant = Instant.parse("2026-03-09T19:00:00Z")

        /** A Monday two weeks on, with the whole week of the 9th untrained between. */
        val TWO_MONDAYS_ON: Instant = Instant.parse("2026-03-16T19:00:00Z")

        /** A month ahead of every case above — a clock that ran forward and was corrected. */
        val APRIL: Instant = Instant.parse("2026-04-07T19:00:00Z")
    }
}
