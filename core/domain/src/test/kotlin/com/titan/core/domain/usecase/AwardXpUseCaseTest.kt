package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.AchievementTier
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.XpAward
import com.titan.core.domain.model.XpEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwardXpUseCaseTest {

    private val repository = FakeGamificationRepository()
    private val awardXp = AwardXpUseCase(repository)

    // ─── One event, one award ───────────────────────────────────────────────────────────

    @Test
    fun `logging a set banks a set's worth`() =
        runTest {
            val award = awardXp(XpEvent.SetLogged)

            assertEquals(XpCalculator.XP_PER_SET, award.awarded)
            assertEquals(XpCalculator.XP_PER_SET.toLong(), award.totalXp)
            assertEquals(XpCalculator.XP_PER_SET.toLong(), repository.totalXp)
        }

    @Test
    fun `the first set of a user's life does not level them up`() =
        runTest {
            val award = awardXp(XpEvent.SetLogged)

            assertFalse(award.leveledUp)
            assertEquals(1, award.previousLevel)
            assertEquals(1, award.level)
        }

    @Test
    fun `each source banks its own constant`() =
        runTest {
            assertEquals(XpCalculator.XP_PER_SET, awardXp(XpEvent.SetLogged).awarded)
            assertEquals(XpCalculator.XP_PER_SESSION, awardXp(XpEvent.SessionFinished).awarded)
            assertEquals(
                XpCalculator.xpForRecord(PrType.MAX_WEIGHT),
                awardXp(XpEvent.PrBroken(PrType.MAX_WEIGHT)).awarded,
            )
            assertEquals(
                XpCalculator.XP_PER_SILVER_ACHIEVEMENT,
                awardXp(XpEvent.AchievementUnlocked("first_session", AchievementTier.SILVER)).awarded,
            )
        }

    // ─── A session's worth of events ────────────────────────────────────────────────────

    @Test
    fun `a session of ten sets with one record banks every event`() =
        runTest {
            finishSession(sets = 10, prTypes = listOf(PrType.MAX_WEIGHT))

            val expected = 10 * XpCalculator.XP_PER_SET +
                XpCalculator.XP_PER_SESSION +
                XpCalculator.xpForRecord(PrType.MAX_WEIGHT)
            assertEquals(expected.toLong(), repository.totalXp)
            assertEquals(12, repository.awards.size)
        }

    @Test
    fun `only the event that crosses the threshold reports a level-up`() =
        runTest {
            // 60 + 10 × 2 + 15 = 95 before the record, 115 after it: the record is the crossing.
            val awards = finishSession(
                sets = 10,
                prTypes = listOf(PrType.MAX_WEIGHT),
                award = AwardXpUseCase(FakeGamificationRepository(initialXp = 60)),
            )

            assertEquals(1, awards.count(XpAward::leveledUp))
            assertTrue(awards.last().leveledUp)
            assertEquals(2, awards.last().level)
            assertEquals(1, awards.last().previousLevel)
        }

    @Test
    fun `one event per record broken, not one per session that broke any`() =
        runTest {
            finishSession(sets = 3, prTypes = listOf(PrType.MAX_WEIGHT, PrType.MAX_REPS, PrType.ESTIMATED_1RM))

            val prAwards = repository.awards.map(XpAward::awarded).drop(3 + 1)
            assertEquals(
                listOf(PrType.MAX_WEIGHT, PrType.MAX_REPS, PrType.ESTIMATED_1RM).map(XpCalculator::xpForRecord),
                prAwards,
            )
        }

    // ─── Crossing thresholds ────────────────────────────────────────────────────────────

    @Test
    fun `the award that lands exactly on a threshold is the one that levels up`() =
        runTest {
            // 50 and a bronze achievement's 50 make exactly the 100 that level 2 costs.
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 50))

            val award = started(XpEvent.AchievementUnlocked("first_session", AchievementTier.BRONZE))

            assertEquals(100L, award.totalXp)
            assertEquals(2, award.level)
            assertTrue(award.leveledUp)
        }

    @Test
    fun `events on either side of a crossing report no level-up`() =
        runTest {
            // 98 is two short; the next set lands on 100 and crosses; the one after is inside level 2.
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 96))
            val awards = (1..3).map { started(XpEvent.SetLogged) }

            assertEquals(listOf(false, true, false), awards.map(XpAward::leveledUp))
            assertEquals(2, awards.last().level)
        }

    @Test
    fun `a level-up is reported against the level the user was actually on`() =
        runTest {
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 640))

            val award = started(XpEvent.PrBroken(PrType.MAX_VOLUME))

            assertEquals(650L, award.totalXp)
            assertEquals(4, award.previousLevel)
            assertEquals(4, award.level)
            assertFalse(award.leveledUp)
        }

    @Test
    fun `an award that starts mid-level still reports the crossing it caused`() =
        runTest {
            // 270 is thirteen short of level 3 (283); the achievement lands on 370, over the
            // threshold but not up to the next one (520) — one crossing, from a total that was
            // not on a boundary to start with.
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 270))

            val award = started(XpEvent.AchievementUnlocked("thirty_day_streak", AchievementTier.SILVER))

            assertEquals(370L, award.totalXp)
            assertEquals(2, award.previousLevel)
            assertEquals(3, award.level)
            assertTrue(award.leveledUp)
        }

    @Test
    fun `a gold achievement on a fresh account clears two thresholds at once`() =
        runTest {
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 99))

            val award = started(XpEvent.AchievementUnlocked("thirty_day_streak", AchievementTier.GOLD))

            assertEquals(349L, award.totalXp)
            assertEquals(1, award.previousLevel)
            assertEquals(3, award.level)
            assertTrue(award.leveledUp)
        }

    @Test
    fun `from level 4 on no award a session or a milestone pays can clear two thresholds`() {
        // What keeps `leveledUp` "one event per level gained" rather than merely "one event
        // per crossing" once the first few levels are behind the user: the largest award is
        // worth less than the cost of any later level. A retune that breaks this should break
        // this test, not the celebration.
        val largestAward = maxOf(
            XpCalculator.XP_PER_SET,
            XpCalculator.XP_PER_SESSION,
            PrType.entries.maxOf(XpCalculator::xpForRecord),
            AchievementTier.entries.maxOf(XpCalculator::xpForAchievement),
        )

        assertTrue(largestAward < XpCalculator.xpToNextLevel(4))
    }

    // ─── No bundling, no deduplication ──────────────────────────────────────────────────

    @Test
    fun `the same event twice is banked twice - deduplication is the emitter's job`() =
        runTest {
            awardXp(XpEvent.AchievementUnlocked("first_session", AchievementTier.BRONZE))
            awardXp(XpEvent.AchievementUnlocked("first_session", AchievementTier.BRONZE))

            assertEquals(2 * XpCalculator.XP_PER_BRONZE_ACHIEVEMENT.toLong(), repository.totalXp)
        }

    /** The events a finished session emits, in the order #145 will emit them. */
    private suspend fun finishSession(
        sets: Int,
        prTypes: List<PrType>,
        award: AwardXpUseCase = awardXp,
    ): List<XpAward> =
        buildList {
            repeat(sets) { add(award(XpEvent.SetLogged)) }
            add(award(XpEvent.SessionFinished))
            prTypes.forEach { add(award(XpEvent.PrBroken(it))) }
        }
}
