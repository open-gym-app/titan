package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.data.db.session
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.StreakResult
import kotlinx.coroutines.flow.first
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
 * The domain already pins what the curve and the streak rule *say* — `XpCalculatorTest`,
 * `StreakCalculatorTest` and `StreakProtectionTest` do that without a database — so what is
 * left for this class is the half only a store can get wrong: that both levels come from the
 * curve rather than from the level column, that a streak's timestamp and its protection
 * allowance reach the columns they belong in and come back the same, and that the two
 * idempotent writes answer `true` once and `false` afterwards.
 */
class GamificationRepositoryImplTest : RoomTestBase() {

    private companion object {
        val MONDAY_EVENING: Instant = Instant.parse("2026-03-02T19:00:00Z")
        val TUESDAY_LATER: Instant = Instant.parse("2026-03-03T21:30:00Z")
        val FRIDAY_EVENING: Instant = Instant.parse("2026-03-06T19:00:00Z")

        /** Mondays of the weeks after [MONDAY_EVENING]'s; the week of the 23rd goes untrained. */
        val SECOND_MONDAY: Instant = Instant.parse("2026-03-09T19:00:00Z")
        val THIRD_MONDAY: Instant = Instant.parse("2026-03-16T19:00:00Z")
        val FIFTH_MONDAY: Instant = Instant.parse("2026-03-30T19:00:00Z")
    }

    private val repository by lazy {
        GamificationRepositoryImpl(
            userProgressDao = db.userProgressDao(),
            achievementDao = db.achievementDao(),
            sessionXpClaimDao = db.sessionXpClaimDao(),
            sessionLootClaimDao = db.sessionLootClaimDao(),
            sessionPetClaimDao = db.sessionPetClaimDao(),
        )
    }

    /**
     * Applies the real rule, which is what the contract hands in rather than deciding here, with
     * a goal of one so that every session that counts is a week met.
     */
    private suspend fun finish(at: Instant) =
        repository.updateStreak { state -> StreakCalculator.advance(state, at, weeklyGoal = 1) }

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
            award(XpCalculator.xpRequiredForLevel(2).toInt() - 1)

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
            award(XpCalculator.xpRequiredForLevel(4).toInt())

            val stored = db.userProgressDao().getOnce()

            assertEquals(XpCalculator.xpRequiredForLevel(4), stored?.totalXp)
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
    fun `a session's loot can be claimed once`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = false, completedAt = 1_000))

            assertTrue(repository.claimSessionLoot(sessionId))
            assertFalse(repository.claimSessionLoot(sessionId))
        }

    @Test
    fun `the two claims fence their own steps and not each other`() =
        runBlocking {
            // Separate markers, because they fence separate steps: a run that died after the
            // awards and before the roll must still be able to roll on re-entry.
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = false, completedAt = 1_000))

            assertTrue(repository.claimSessionXp(sessionId))

            assertTrue(repository.claimSessionLoot(sessionId))
        }

    @Test
    fun `both claims go with the session they fence`() =
        runBlocking {
            // A claim outliving its workout would fence a session that no longer exists.
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = false, completedAt = 1_000))
            repository.claimSessionXp(sessionId)
            repository.claimSessionLoot(sessionId)

            db.workoutSessionDao().delete(sessionId)

            assertEquals(0, countRows("session_xp_claim"))
            assertEquals(0, countRows("session_loot_claim"))
        }

    @Test
    fun `the first session of a lifter's life opens a streak`() =
        runBlocking {
            val update = finish(MONDAY_EVENING)

            assertEquals(StreakResult.Incremented(1), update.result)
            assertEquals(1, update.weeks)
            assertEquals(MONDAY_EVENING.toEpochMilli(), db.userProgressDao().getOnce()?.lastCountedAt)
        }

    @Test
    fun `a session the next week extends the streak`() =
        runBlocking {
            finish(MONDAY_EVENING)

            val update = finish(SECOND_MONDAY)

            assertEquals(StreakResult.Incremented(2), update.result)
            assertEquals(2, update.weeks)
        }

    @Test
    fun `a second session the same week counts without adding the week twice`() =
        runBlocking {
            finish(MONDAY_EVENING)

            val update = finish(TUESDAY_LATER)

            assertEquals(StreakResult.Counted, update.result)
            assertEquals(1, update.weeks)
            val stored = db.userProgressDao().getOnce()
            assertEquals(2, stored?.weekSessions)
            assertEquals(true, stored?.weekGoalMet)
            assertEquals(TUESDAY_LATER.toEpochMilli(), stored?.lastCountedAt)
        }

    @Test
    fun `a lapse restarts the streak and leaves the best one standing`() =
        runBlocking {
            finish(MONDAY_EVENING)
            finish(SECOND_MONDAY)

            // The weeks of the 16th and the 23rd went untrained: a lapse, not a slip.
            val update = finish(FIFTH_MONDAY)

            assertEquals(StreakResult.Reset(newStreak = 1), update.result)
            assertEquals(1, update.weeks)
            assertEquals(2, db.userProgressDao().getOnce()?.longestStreak)
            assertEquals(FIFTH_MONDAY.toEpochMilli(), db.userProgressDao().getOnce()?.lastCountedAt)
        }

    @Test
    fun `a missed week is paid for out of the allowance, and the row says so`() =
        runBlocking {
            finish(MONDAY_EVENING)
            finish(SECOND_MONDAY)
            finish(THIRD_MONDAY)

            // The week of the 23rd went untrained. The streak is three weeks old, so it is
            // worth saving, and the session in hand meets its own week's goal.
            val update = finish(FIFTH_MONDAY)

            assertEquals(StreakResult.Protected(newStreak = 4, protectionsRemaining = 0), update.result)
            assertEquals(4, update.weeks)
            val stored = db.userProgressDao().getOnce()
            assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, stored?.protectionRemaining)
            assertEquals("2026-03", stored?.protectionPeriod)
            assertEquals(FIFTH_MONDAY.toEpochMilli(), stored?.protectionConsumedAt)
        }

    @Test
    fun `re-applying a protected session spends no second protection`() =
        runBlocking {
            finish(MONDAY_EVENING)
            finish(SECOND_MONDAY)
            finish(THIRD_MONDAY)
            finish(FIFTH_MONDAY)

            // The cascade re-entered for the same workout — a crash-recovery boot, or a retry.
            val again = finish(FIFTH_MONDAY)

            assertEquals(StreakResult.Unchanged, again.result)
            assertEquals(4, again.weeks)
            assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, again.protectionsRemaining)
        }

    @Test
    fun `the streak state is readable, allowance and all`() =
        runBlocking {
            finish(MONDAY_EVENING)
            finish(SECOND_MONDAY)
            finish(THIRD_MONDAY)
            finish(FIFTH_MONDAY)

            val state = repository.observeStreak().first()

            assertEquals(4, state.currentStreak)
            assertEquals(4, state.longestStreak)
            assertEquals(FIFTH_MONDAY, state.lastCountedAt)
            assertEquals(1, state.weekSessions)
            assertTrue(state.weekGoalMet)
            assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, state.protection.remaining)
            assertEquals(FIFTH_MONDAY, state.protection.lastConsumedAt)
        }

    @Test
    fun `a lifter who has never trained reads as a full allowance rather than as nothing`() =
        runBlocking {
            val state = repository.observeStreak().first()

            assertEquals(0, state.currentStreak)
            assertNull(state.lastCountedAt)
            assertEquals(StreakProtection(), state.protection)
        }

    @Test
    fun `the streak and the XP total are the same row and do not overwrite each other`() =
        runBlocking {
            award(XpCalculator.XP_PER_SESSION)
            finish(MONDAY_EVENING)
            award(XpCalculator.XP_PER_SET)

            val stored = db.userProgressDao().getOnce()

            assertEquals((XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET).toLong(), stored?.totalXp)
            assertEquals(1, stored?.currentStreak)
            assertEquals(MONDAY_EVENING.toEpochMilli(), stored?.lastCountedAt)
        }

    @Test
    fun `nothing is written for a user who has done nothing`() =
        runBlocking {
            assertNull(db.userProgressDao().getOnce())
        }

    // ─── Reading the row back ───────────────────────────────────────────────────────────

    @Test
    fun `a user with no row reads as no progress at all`() =
        runBlocking {
            // Null rather than a zeroed row: Home draws no level header for somebody who has
            // never banked a point, and a synthesised `level 1, 0 XP` would take that choice
            // away from every caller. See GamificationRepository.observeProgress.
            assertNull(repository.observeProgress().first())
        }

    @Test
    fun `the standing reads back everything a screen draws`() =
        runBlocking {
            award(XpCalculator.xpRequiredForLevel(4).toInt() + 40)
            finish(MONDAY_EVENING)

            val progress = requireNotNull(repository.observeProgress().first())

            assertEquals(XpCalculator.xpRequiredForLevel(4) + 40, progress.totalXp)
            assertEquals(XpCalculator.FIRST_LEVEL + 3, progress.level)
            assertEquals(1, progress.currentStreak)
            assertEquals(MONDAY_EVENING, progress.lastCountedAt)
        }

    @Test
    fun `the standing re-emits when something is banked behind the screen`() =
        runBlocking {
            // The whole reason this is a flow: the post-session cascade writes while Home is
            // the screen underneath, and a one-shot read would leave the header a level behind.
            val progress = repository.observeProgress()
            assertNull(progress.first())

            award(XpCalculator.XP_PER_SESSION)

            assertEquals(XpCalculator.XP_PER_SESSION.toLong(), progress.first()?.totalXp)
        }

    @Test
    fun `a streak with no session behind it reads as no timestamp`() =
        runBlocking {
            // The contradictory row: XP banked, nothing counted. `lastCountedAt` is the half a
            // reader can trust, and it is null — see StreakCalculator.isLive, which answers
            // false for it however long the stored streak says it is.
            award(XpCalculator.XP_PER_SET)

            val progress = requireNotNull(repository.observeProgress().first())

            assertEquals(0, progress.currentStreak)
            assertNull(progress.lastCountedAt)
        }
}
