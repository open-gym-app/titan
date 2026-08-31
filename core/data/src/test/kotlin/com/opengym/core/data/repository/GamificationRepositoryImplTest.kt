package com.opengym.core.data.repository

import com.opengym.core.data.db.RoomTestBase
import com.opengym.core.data.db.entity.UserProgressEntity
import com.opengym.core.data.db.session
import com.opengym.core.domain.gamification.StreakCalculator
import com.opengym.core.domain.gamification.XpCalculator
import com.opengym.core.domain.model.StreakResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The gamification store through the repository, over a real SQLite.
 *
 * The domain already pins what the curve and the streak rule *say* — `XpCalculatorTest` and
 * `StreakCalculatorTest` do that without a database — so what is left for this class is the
 * half only a store can get wrong: that both levels come from the curve rather than from the
 * level column, that a streak's timestamp advances on exactly the sessions it should, and that
 * the two idempotent writes answer `true` once and `false` afterwards.
 */
class GamificationRepositoryImplTest : RoomTestBase() {

    private companion object {
        val MONDAY_EVENING: Instant = Instant.parse("2026-03-02T19:00:00Z")
        val TUESDAY_EVENING: Instant = Instant.parse("2026-03-03T19:00:00Z")
        val TUESDAY_LATER: Instant = Instant.parse("2026-03-03T21:30:00Z")
        val FRIDAY_EVENING: Instant = Instant.parse("2026-03-06T19:00:00Z")
    }

    private val repository by lazy {
        GamificationRepositoryImpl(
            userProgressDao = db.userProgressDao(),
            achievementDao = db.achievementDao(),
            sessionXpClaimDao = db.sessionXpClaimDao(),
        )
    }

    /** Applies the real rule, which is what the contract hands in rather than deciding here. */
    private suspend fun finish(at: Instant) =
        repository.updateStreak(at) { lastCountedAt, currentStreak ->
            StreakCalculator.onSessionFinished(at, lastCountedAt, currentStreak)
        }

    private suspend fun award(delta: Int) = repository.addXp(delta, XpCalculator::levelForTotalXp)

    @Test
    fun `the first award banks against a row that did not exist`() =
        runBlocking {
            val awarded = award(XpCalculator.XP_PER_SESSION)

            assertEquals(XpCalculator.XP_PER_SESSION.toLong(), awarded.totalXp)
            assertEquals(XpCalculator.FIRST_LEVEL, awarded.level)
            assertEquals(XpCalculator.FIRST_LEVEL, awarded.previousLevel)
            assertFalse(awarded.leveledUp)
        }

    @Test
    fun `awards accumulate on the stored total rather than restating it`() =
        runBlocking {
            award(30)
            award(45)

            assertEquals(75L, repository.addXp(0, XpCalculator::levelForTotalXp).totalXp)
        }

    @Test
    fun `the award that crosses a threshold is the one that reports the level-up`() =
        runBlocking {
            award(XpCalculator.XP_PER_LEVEL - 1)

            val crossing = award(1)
            val after = award(1)

            assertTrue(crossing.leveledUp)
            assertEquals(XpCalculator.FIRST_LEVEL, crossing.previousLevel)
            assertEquals(XpCalculator.FIRST_LEVEL + 1, crossing.level)
            assertFalse(after.leveledUp)
        }

    @Test
    fun `the level stored beside the total is the one the curve buys`() =
        runBlocking {
            award(XpCalculator.XP_PER_LEVEL * 3)

            val stored = db.userProgressDao().getOnce()

            assertEquals(XpCalculator.XP_PER_LEVEL * 3L, stored?.totalXp)
            assertEquals(XpCalculator.levelForTotalXp(stored?.totalXp ?: 0), stored?.currentLevel)
        }

    @Test
    fun `the previous level is derived from the curve, not read off the level column`() =
        runBlocking {
            // A row banked under a curve that has since been retuned: 250 points with a level
            // column saying 1. The award must compare the level 250 buys today against the
            // level 251 buys today, and not against the stale column, which would report a
            // two-level jump the user never earned.
            db.userProgressDao().upsert(UserProgressEntity(totalXp = 250, currentLevel = 1))

            val awarded = award(1)

            assertEquals(XpCalculator.levelForTotalXp(250), awarded.previousLevel)
            assertEquals(XpCalculator.levelForTotalXp(251), awarded.level)
            assertFalse(awarded.leveledUp)
        }

    @Test
    fun `an achievement unlocks once and is paid for once`() =
        runBlocking {
            val unlocked = repository.unlockAchievement("first_session", MONDAY_EVENING)
            val again = repository.unlockAchievement("first_session", FRIDAY_EVENING)

            assertTrue(unlocked)
            assertFalse(again)
            assertEquals(setOf("first_session"), repository.unlockedAchievementIds())
        }

    @Test
    fun `the unlocked set is empty for a user who has earned nothing`() =
        runBlocking {
            assertEquals(emptySet<String>(), repository.unlockedAchievementIds())
        }

    @Test
    fun `a session's XP can be claimed once`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = false, completedAt = 1_000))

            assertTrue(repository.claimSessionXp(sessionId))
            assertFalse(repository.claimSessionXp(sessionId))
        }

    @Test
    fun `two sessions are claimed independently`() =
        runBlocking {
            val first = db.workoutSessionDao().insert(session(isInProgress = false, completedAt = 1_000))
            val second = db.workoutSessionDao().insert(session(isInProgress = false, completedAt = 2_000))

            assertTrue(repository.claimSessionXp(first))
            assertTrue(repository.claimSessionXp(second))
        }

    @Test
    fun `the first session of a lifter's life opens a streak`() =
        runBlocking {
            val update = finish(MONDAY_EVENING)

            assertEquals(StreakResult.Incremented(StreakResult.FIRST_DAY), update.result)
            assertEquals(StreakResult.FIRST_DAY, update.days)
            assertEquals(MONDAY_EVENING.toEpochMilli(), db.userProgressDao().getOnce()?.lastCountedAt)
        }

    @Test
    fun `a session the next day extends the streak`() =
        runBlocking {
            finish(MONDAY_EVENING)

            val update = finish(TUESDAY_EVENING)

            assertEquals(StreakResult.Incremented(2), update.result)
            assertEquals(2, update.days)
        }

    @Test
    fun `a second session the same day leaves the streak and its timestamp alone`() =
        runBlocking {
            finish(MONDAY_EVENING)
            finish(TUESDAY_EVENING)

            val update = finish(TUESDAY_LATER)

            assertEquals(StreakResult.Unchanged, update.result)
            assertEquals(2, update.days)
            // The whole of the same-day guarantee: the window stays measured from the first
            // counted session of the day, so an evening workout does not push tomorrow's
            // deadline out by two and a half hours.
            assertEquals(TUESDAY_EVENING.toEpochMilli(), db.userProgressDao().getOnce()?.lastCountedAt)
        }

    @Test
    fun `a lapse restarts the streak at day one and leaves the best one standing`() =
        runBlocking {
            finish(MONDAY_EVENING)
            finish(TUESDAY_EVENING)

            val update = finish(FRIDAY_EVENING)

            assertEquals(StreakResult.Reset, update.result)
            assertEquals(StreakResult.FIRST_DAY, update.days)
            assertEquals(2, db.userProgressDao().getOnce()?.longestStreak)
            assertEquals(FRIDAY_EVENING.toEpochMilli(), db.userProgressDao().getOnce()?.lastCountedAt)
        }

    @Test
    fun `the streak and the XP total are the same row and do not overwrite each other`() =
        runBlocking {
            award(XpCalculator.XP_PER_SESSION)
            finish(MONDAY_EVENING)
            award(XpCalculator.XP_PER_SET)

            val stored = db.userProgressDao().getOnce()

            assertEquals((XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET).toLong(), stored?.totalXp)
            assertEquals(StreakResult.FIRST_DAY, stored?.currentStreak)
            assertEquals(MONDAY_EVENING.toEpochMilli(), stored?.lastCountedAt)
        }

    @Test
    fun `nothing is written for a user who has done nothing`() =
        runBlocking {
            assertNull(db.userProgressDao().getOnce())
        }
}
