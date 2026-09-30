package com.titan.core.domain.gamification

import com.titan.core.domain.model.StreakResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StreakCalculatorTest {

    // ─── Opening a streak ───────────────────────────────────────────────────────────────

    @Test
    fun `a first session opens a streak at one`() {
        val result = StreakCalculator.onSessionFinished(
            finishedAt = MONDAY_EVENING,
            lastCountedAt = null,
            currentStreak = 0,
        )

        assertEquals(StreakResult.Incremented(1), result)
    }

    @Test
    fun `a stored streak with no session behind it starts over rather than continuing`() {
        val result = StreakCalculator.onSessionFinished(
            finishedAt = MONDAY_EVENING,
            lastCountedAt = null,
            currentStreak = 5,
        )

        assertEquals(StreakResult.Incremented(1), result)
    }

    // ─── Extending it ───────────────────────────────────────────────────────────────────

    @Test
    fun `a session the next day extends the streak`() {
        val result = finishedAfter(Duration.ofHours(20), currentStreak = 3)

        assertEquals(StreakResult.Incremented(4), result)
    }

    @Test
    fun `the last moment of the grace period still extends the streak`() {
        val result = finishedAfter(StreakCalculator.GRACE_PERIOD, currentStreak = 3)

        assertEquals(StreakResult.Incremented(4), result)
    }

    @Test
    fun `crossing midnight extends the streak, however few hours apart`() {
        // 23:00 to 01:00 the next day: two hours, and a new day, so it counts.
        val result = StreakCalculator.onSessionFinished(
            finishedAt = Instant.parse("2026-03-03T01:00:00Z"),
            lastCountedAt = Instant.parse("2026-03-02T23:00:00Z"),
            currentStreak = 6,
        )

        assertEquals(StreakResult.Incremented(7), result)
    }

    @Test
    fun `consecutive days count however far past the window they fall`() {
        // Counted at 02:00 on the Tuesday, trained again at 20:00 on the Wednesday: 42 hours
        // apart, and not one day missed. The anchor sits at whatever hour its day was counted
        // at, so a window measured from it is no answer here — the dates are.
        val result = StreakCalculator.onSessionFinished(
            finishedAt = Instant.parse("2026-03-04T20:00:00Z"),
            lastCountedAt = Instant.parse("2026-03-03T02:00:00Z"),
            currentStreak = 8,
        )

        assertEquals(StreakResult.Incremented(9), result)
    }

    @Test
    fun `a skipped calendar day inside the window still extends the streak`() {
        // Monday 23:00 to Wednesday 09:00 — nothing was trained on the Tuesday, and at 34
        // hours the window never lapsed. The rolling window is the rule, not the dates.
        val result = StreakCalculator.onSessionFinished(
            finishedAt = Instant.parse("2026-03-04T09:00:00Z"),
            lastCountedAt = Instant.parse("2026-03-02T23:00:00Z"),
            currentStreak = 2,
        )

        assertEquals(StreakResult.Incremented(3), result)
    }

    // ─── Breaking it ────────────────────────────────────────────────────────────────────

    @Test
    fun `a millisecond past the grace period breaks the streak`() {
        val result = finishedAfter(StreakCalculator.GRACE_PERIOD.plusMillis(1), currentStreak = 3)

        assertEquals(StreakResult.Reset, result)
    }

    @Test
    fun `two clear days off break the streak`() {
        val result = finishedAfter(Duration.ofHours(60), currentStreak = 41)

        assertEquals(StreakResult.Reset, result)
    }

    @Test
    fun `a day skipped past the window breaks the streak, consecutive dates or not`() {
        // Tuesday 02:00 to Thursday 20:00: the Wednesday held no training and 66 hours is
        // well past the window, so neither arm saves this one.
        val result = StreakCalculator.onSessionFinished(
            finishedAt = Instant.parse("2026-03-05T20:00:00Z"),
            lastCountedAt = Instant.parse("2026-03-03T02:00:00Z"),
            currentStreak = 8,
        )

        assertEquals(StreakResult.Reset, result)
    }

    @Test
    fun `a broken streak restarts at one rather than at zero`() {
        assertEquals(1, StreakResult.Reset.newStreak)
    }

    // ─── Leaving it alone ───────────────────────────────────────────────────────────────

    @Test
    fun `a second session on the same day does not count twice`() {
        val result = finishedAfter(Duration.ofHours(3), currentStreak = 4)

        assertEquals(StreakResult.Unchanged, result)
    }

    @Test
    fun `a whole day between two sessions on the same date is still one day`() {
        val result = StreakCalculator.onSessionFinished(
            finishedAt = Instant.parse("2026-03-02T23:30:00Z"),
            lastCountedAt = Instant.parse("2026-03-02T00:30:00Z"),
            currentStreak = 4,
        )

        assertEquals(StreakResult.Unchanged, result)
    }

    @Test
    fun `a session finished before the last counted one changes nothing`() {
        val result = finishedAfter(Duration.ofHours(-48), currentStreak = 9)

        assertEquals(StreakResult.Unchanged, result)
    }

    // ─── What the caller stores ─────────────────────────────────────────────────────────

    @Test
    fun `an increment stores its new streak`() {
        assertEquals(4, StreakResult.Incremented(4).streakAfter(currentStreak = 3))
    }

    @Test
    fun `a reset stores one, whatever the streak was`() {
        assertEquals(1, StreakResult.Reset.streakAfter(currentStreak = 41))
    }

    @Test
    fun `an unchanged result stores the streak the user already had`() {
        assertEquals(4, StreakResult.Unchanged.streakAfter(currentStreak = 4))
    }

    // ─── Is it still running? ───────────────────────────────────────────────────────────

    @Test
    fun `a streak counted today is still running`() {
        assertTrue(
            StreakCalculator.isLive(lastCountedAt = MONDAY_EVENING, asOf = MONDAY_EVENING.plus(Duration.ofHours(2))),
        )
    }

    @Test
    fun `a streak counted yesterday is still running, whatever the hour`() {
        // The calendar arm, asked of a moment rather than of a session: somebody who trained
        // at 19:00 yesterday has not lost anything by 09:00 today.
        assertTrue(
            StreakCalculator.isLive(lastCountedAt = MONDAY_EVENING, asOf = MONDAY_EVENING.plus(Duration.ofHours(14))),
        )
    }

    @Test
    fun `a streak inside the window is still running past the next calendar day`() {
        // Monday 19:00 to Wednesday 06:00 is 35 hours: a day was skipped and the window has
        // not closed, which is the case the grace period exists for.
        assertTrue(
            StreakCalculator.isLive(lastCountedAt = MONDAY_EVENING, asOf = MONDAY_EVENING.plus(Duration.ofHours(35))),
        )
    }

    @Test
    fun `a streak past the window has lapsed`() {
        // Nothing has written to the row — a streak is only recomputed when a session
        // finishes — so this is the whole of the difference between the stored figure and the
        // truth. See UserProgress.currentStreak.
        assertFalse(
            StreakCalculator.isLive(lastCountedAt = MONDAY_EVENING, asOf = MONDAY_EVENING.plus(Duration.ofHours(37))),
        )
    }

    @Test
    fun `a user who has never counted a session has no streak running`() {
        assertFalse(StreakCalculator.isLive(lastCountedAt = null, asOf = MONDAY_EVENING))
    }

    @Test
    fun `a clock that has gone backwards does not cut a streak`() {
        // A device whose clock jumped back says nothing about whether the streak lapsed, and
        // taking a running streak away over it would be the app punishing a timezone change.
        assertTrue(
            StreakCalculator.isLive(lastCountedAt = MONDAY_EVENING, asOf = MONDAY_EVENING.minus(Duration.ofHours(4))),
        )
    }

    @Test
    fun `it lapses exactly where a session would stop extending it`() {
        // The two questions are one rule — see `isLive`. This holds them to it at the boundary,
        // which is the only place they could drift apart.
        val lastMoment = MONDAY_EVENING.plus(StreakCalculator.GRACE_PERIOD).plus(Duration.ofDays(1))
        val extends = StreakCalculator.onSessionFinished(
            finishedAt = lastMoment,
            lastCountedAt = MONDAY_EVENING,
            currentStreak = 3,
        ) != StreakResult.Reset

        assertEquals(extends, StreakCalculator.isLive(lastCountedAt = MONDAY_EVENING, asOf = lastMoment))
    }

    // ─── The tuning dial ────────────────────────────────────────────────────────────────

    @Test
    fun `the grace period is the constant, not a second copy of it`() {
        assertEquals(36L, StreakCalculator.STREAK_GRACE_HOURS)
        assertEquals(Duration.ofHours(StreakCalculator.STREAK_GRACE_HOURS), StreakCalculator.GRACE_PERIOD)
    }

    @Test
    fun `a negative streak is a corrupt row, not a streak to extend`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreakCalculator.onSessionFinished(
                finishedAt = MONDAY_EVENING,
                lastCountedAt = null,
                currentStreak = -1,
            )
        }
    }

    private fun finishedAfter(
        gap: Duration,
        currentStreak: Int,
    ): StreakResult =
        StreakCalculator.onSessionFinished(
            finishedAt = MONDAY_EVENING.plus(gap),
            lastCountedAt = MONDAY_EVENING,
            currentStreak = currentStreak,
        )

    private companion object {

        /** A Monday, 19:00 UTC — far enough from midnight that a `± 3h` stays on the day. */
        val MONDAY_EVENING: Instant = Instant.parse("2026-03-02T19:00:00Z")
    }
}
