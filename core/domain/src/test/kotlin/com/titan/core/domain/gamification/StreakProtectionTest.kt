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
 * lives. The two are separate files because they are separate questions: every case here
 * starts from a break the other file has already established, or from a day that never broke
 * anything and must therefore spend nothing.
 */
class StreakProtectionTest {

    // ─── Spending one ───────────────────────────────────────────────────────────────────

    @Test
    fun `a missed day is paid for out of the allowance and the streak survives`() {
        val update = StreakCalculator.advance(onFile(streak = 12), WEDNESDAY_EVENING)

        assertEquals(StreakResult.Protected(protectionsRemaining = 2), update.result)
        assertEquals(13, update.days)
        assertTrue(update.protectionConsumed)
    }

    @Test
    fun `the protected session is itself a day trained, so the streak advances by one`() {
        val update = StreakCalculator.advance(onFile(streak = 12), WEDNESDAY_EVENING)

        // Not 12 — the day in hand counted — and not 14: the covered day was rest, not training.
        assertEquals(13, update.days)
    }

    @Test
    fun `spending one leaves the rest of the month's allowance standing`() {
        val update = StreakCalculator.advance(onFile(streak = 12), WEDNESDAY_EVENING)

        assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, update.protectionsRemaining)
        assertEquals(WEDNESDAY_EVENING, update.state.protection.lastConsumedAt)
    }

    @Test
    fun `a protected day still counts as a best streak`() {
        val update = StreakCalculator.advance(onFile(streak = 12), WEDNESDAY_EVENING)

        assertEquals(13, update.state.longestStreak)
    }

    // ─── Not spending one ───────────────────────────────────────────────────────────────

    @Test
    fun `with nothing left in the month the streak breaks as it always did`() {
        val state = onFile(streak = 12, protection = spent())

        val update = StreakCalculator.advance(state, WEDNESDAY_EVENING)

        assertEquals(StreakResult.Reset, update.result)
        assertEquals(StreakResult.FIRST_DAY, update.days)
        assertFalse(update.protectionConsumed)
    }

    @Test
    fun `a streak too short to be worth protecting is not protected`() {
        val state = onFile(streak = StreakCalculator.MIN_PROTECTED_STREAK - 1)

        val update = StreakCalculator.advance(state, WEDNESDAY_EVENING)

        assertEquals(StreakResult.Reset, update.result)
        // And nothing was spent on it: the allowance is there for a streak worth losing.
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `the shortest protectable streak is protected`() {
        val state = onFile(streak = StreakCalculator.MIN_PROTECTED_STREAK)

        val update = StreakCalculator.advance(state, WEDNESDAY_EVENING)

        assertEquals(StreakResult.Protected(protectionsRemaining = 2), update.result)
    }

    @Test
    fun `two missed days are a lapse, and nothing is spent trying to save it`() {
        // Monday 19:00 to Thursday 19:00: the Tuesday and the Wednesday both went untrained.
        val update = StreakCalculator.advance(onFile(streak = 20), THURSDAY_EVENING)

        assertEquals(StreakResult.Reset, update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `a session inside the grace window spends nothing`() {
        // 34 hours, one calendar day skipped — the window already carries this one.
        val update = StreakCalculator.advance(onFile(streak = 12), MONDAY_EVENING.plusSeconds(34 * 3600))

        assertEquals(StreakResult.Incremented(13), update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `an ordinary next-day session spends nothing`() {
        val update = StreakCalculator.advance(onFile(streak = 12), TUESDAY_EVENING)

        assertEquals(StreakResult.Incremented(13), update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
        assertNull(update.state.protection.lastConsumedAt)
    }

    @Test
    fun `a first session has no streak to protect`() {
        val update = StreakCalculator.advance(StreakState(), MONDAY_EVENING)

        assertEquals(StreakResult.Incremented(StreakResult.FIRST_DAY), update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    // ─── Not spending a second one for the same day ─────────────────────────────────────

    @Test
    fun `re-running the same session spends no second protection`() {
        val first = StreakCalculator.advance(onFile(streak = 12), WEDNESDAY_EVENING)

        val second = StreakCalculator.advance(first.state, WEDNESDAY_EVENING)

        assertEquals(StreakResult.Unchanged, second.result)
        assertEquals(13, second.days)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, second.protectionsRemaining)
    }

    @Test
    fun `a second workout on a protected day spends nothing`() {
        val first = StreakCalculator.advance(onFile(streak = 12), WEDNESDAY_EVENING)

        val second = StreakCalculator.advance(first.state, WEDNESDAY_EVENING.plusSeconds(3 * 3600))

        assertEquals(StreakResult.Unchanged, second.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, second.protectionsRemaining)
    }

    @Test
    fun `a genuine second break later in the month spends a second protection`() {
        val first = StreakCalculator.advance(onFile(streak = 12), WEDNESDAY_EVENING)

        // Wednesday to Friday: the Thursday missed, a break of its own.
        val second = StreakCalculator.advance(first.state, FRIDAY_EVENING)

        assertEquals(StreakResult.Protected(protectionsRemaining = 1), second.result)
        assertEquals(14, second.days)
    }

    // ─── The monthly boundary ───────────────────────────────────────────────────────────

    @Test
    fun `the allowance refills in the month the session lands in`() {
        val state = onFile(streak = 12, protection = spent().copy(period = YearMonth.of(2026, 2)))

        val update = StreakCalculator.advance(state, WEDNESDAY_EVENING)

        // March's session finds March's allowance, and pays for the break out of it.
        assertEquals(YearMonth.of(2026, 3), update.state.protection.period)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, update.protectionsRemaining)
    }

    @Test
    fun `an unused allowance does not carry over into the next month`() {
        val state = onFile(streak = 12, protection = StreakProtection(remaining = 3, period = YearMonth.of(2026, 2)))

        val update = StreakCalculator.advance(state, TUESDAY_EVENING)

        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `a session that changes nothing still rolls the month over`() {
        val counted = StreakCalculator.advance(onFile(streak = 12), TUESDAY_EVENING)
        val stale = counted.state.copy(protection = spent().copy(period = YearMonth.of(2026, 2)))

        // A second workout the same evening: Unchanged, and still the write that refills.
        val update = StreakCalculator.advance(stale, TUESDAY_EVENING.plusSeconds(3600))

        assertEquals(StreakResult.Unchanged, update.result)
        assertEquals(StreakProtection.MONTHLY_ALLOWANCE, update.protectionsRemaining)
    }

    @Test
    fun `a session that predates the anchor rolls nothing over`() {
        // A phone set forward to April spent a protection there; the clock is corrected, and
        // the next session finishes back in March. It changes nothing, and must refill nothing.
        val april = StreakProtection(remaining = 2, period = YearMonth.of(2026, 4), lastConsumedAt = APRIL_EVENING)
        val ahead = onFile(streak = 12, protection = april).copy(lastCountedAt = APRIL_EVENING)

        val update = StreakCalculator.advance(ahead, TUESDAY_EVENING)

        assertEquals(StreakResult.Unchanged, update.result)
        // Not three, and still April's: the protection April spent is not handed back.
        assertEquals(2, update.protectionsRemaining)
        assertEquals(YearMonth.of(2026, 4), update.state.protection.period)
    }

    @Test
    fun `a backfilled session inside the same month leaves the allowance alone`() {
        // The cascade re-entered at boot for a session finished before the anchor. It cannot
        // move the streak, so it has no business writing the allowance either.
        val spentInMarch = StreakProtection(remaining = 1, period = MARCH, lastConsumedAt = MONDAY_EVENING)
        val state = onFile(streak = 12, protection = spentInMarch)

        val update = StreakCalculator.advance(state, MONDAY_EVENING.minusSeconds(24 * 3600))

        assertEquals(StreakResult.Unchanged, update.result)
        assertEquals(spentInMarch, update.state.protection)
    }

    @Test
    fun `the same month is left exactly as it was found`() {
        val protection = StreakProtection(
            remaining = 1,
            period = YearMonth.of(2026, 3),
            lastConsumedAt = MONDAY_EVENING,
        )

        assertEquals(protection, protection.replenishedFor(YearMonth.of(2026, 3)))
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
        assertThrows(IllegalStateException::class.java) { spent().consumedAt(MONDAY_EVENING) }
    }

    @Test
    fun `negative - a negative remainder is a corrupt row, not a state to spend from`() {
        assertThrows(IllegalArgumentException::class.java) { StreakProtection(remaining = -1) }
    }

    /** A user mid-streak, last counted on the Monday evening, with the month's allowance intact. */
    private fun onFile(
        streak: Int,
        protection: StreakProtection = StreakProtection(remaining = StreakProtection.MONTHLY_ALLOWANCE, period = MARCH),
    ): StreakState =
        StreakState(
            currentStreak = streak,
            longestStreak = streak,
            lastCountedAt = MONDAY_EVENING,
            protection = protection,
        )

    private fun spent(): StreakProtection = StreakProtection(remaining = 0, period = MARCH)

    private companion object {

        val MARCH: YearMonth = YearMonth.of(2026, 3)

        /** A Monday, 19:00 UTC — the anchor every case here breaks away from. */
        val MONDAY_EVENING: Instant = Instant.parse("2026-03-02T19:00:00Z")
        val TUESDAY_EVENING: Instant = Instant.parse("2026-03-03T19:00:00Z")

        /** One clear day missed: the Tuesday, and 48 hours past the anchor. */
        val WEDNESDAY_EVENING: Instant = Instant.parse("2026-03-04T19:00:00Z")
        val THURSDAY_EVENING: Instant = Instant.parse("2026-03-05T19:00:00Z")
        val FRIDAY_EVENING: Instant = Instant.parse("2026-03-06T19:00:00Z")

        /** A month ahead of every case above — a clock that ran forward and was corrected. */
        val APRIL_EVENING: Instant = Instant.parse("2026-04-07T19:00:00Z")
    }
}
