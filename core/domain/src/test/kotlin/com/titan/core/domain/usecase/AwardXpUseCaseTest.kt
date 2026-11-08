package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Quests
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
            assertEquals(XpCalculator.XP_PER_PR, awardXp(XpEvent.PrBroken(PrType.MAX_WEIGHT)).awarded)
            assertEquals(
                XpCalculator.XP_PER_ACHIEVEMENT,
                awardXp(XpEvent.AchievementUnlocked("first_session")).awarded,
            )
        }

    // ─── A session's worth of events ────────────────────────────────────────────────────

    @Test
    fun `a session of ten sets with one record banks every event`() =
        runTest {
            finishSession(sets = 10, prTypes = listOf(PrType.MAX_WEIGHT))

            val expected = 10 * XpCalculator.XP_PER_SET + XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_PR
            assertEquals(expected.toLong(), repository.totalXp)
            assertEquals(12, repository.awards.size)
        }

    @Test
    fun `only the event that crosses the threshold reports a level-up`() =
        runTest {
            // Ten sets and the session are worth 100; started 105 short of level 2, they leave
            // the total five short, and the record is the crossing.
            repository.addXp(XpCalculator.XP_PER_LEVEL - 105, XpCalculator::levelForTotalXp)
            val awards = finishSession(sets = 10, prTypes = listOf(PrType.MAX_WEIGHT))

            assertEquals(1, awards.count(XpAward::leveledUp))
            assertTrue(awards.last().leveledUp)
            assertEquals(2, awards.last().level)
            assertEquals(1, awards.last().previousLevel)
        }

    @Test
    fun `one event per record broken, not one per session that broke any`() =
        runTest {
            finishSession(sets = 3, prTypes = listOf(PrType.MAX_WEIGHT, PrType.MAX_REPS, PrType.ESTIMATED_1RM))

            val prAwards = repository.awards.count { it.awarded == XpCalculator.XP_PER_PR }
            assertEquals(3, prAwards)
        }

    // ─── Crossing thresholds ────────────────────────────────────────────────────────────

    @Test
    fun `the award that lands exactly on a threshold is the one that levels up`() =
        runTest {
            // An achievement short of each threshold, then the achievement: every one a crossing.
            val awards = (1..4).map {
                repository.addXp(
                    XpCalculator.XP_PER_LEVEL - XpCalculator.XP_PER_ACHIEVEMENT,
                    XpCalculator::levelForTotalXp,
                )
                awardXp(XpEvent.AchievementUnlocked("achievement_$it"))
            }

            assertEquals(listOf(2, 3, 4, 5), awards.map(XpAward::level))
            assertTrue(awards.all(XpAward::leveledUp))
            assertEquals(XpCalculator.xpRequiredForLevel(5), repository.totalXp)
        }

    @Test
    fun `events on either side of a crossing report no level-up`() =
        runTest {
            // One set short of level 2, the set that crosses, and one inside level 2.
            val setsToLevel = XpCalculator.XP_PER_LEVEL / XpCalculator.XP_PER_SET
            val awards = (1..setsToLevel + 1).map { awardXp(XpEvent.SetLogged) }

            assertFalse(awards[setsToLevel - 2].leveledUp)
            assertTrue(awards[setsToLevel - 1].leveledUp)
            assertFalse(awards[setsToLevel].leveledUp)
            assertEquals(2, awards.last().level)
        }

    @Test
    fun `a level-up is reported against the level the user was actually on`() =
        runTest {
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 3_840))

            val award = started(XpEvent.PrBroken(PrType.MAX_VOLUME))

            assertEquals(3_850L, award.totalXp)
            assertEquals(7, award.previousLevel)
            assertEquals(7, award.level)
            assertFalse(award.leveledUp)
        }

    @Test
    fun `an award that starts mid-level still reports the crossing it caused`() =
        runTest {
            // 1,195 is five short of level 3; the achievement lands on 1,295, over the threshold
            // but not up to the next one — one crossing, from a total that was not on a
            // boundary to start with.
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 1_195))

            val award = started(XpEvent.AchievementUnlocked("twelve_week_streak"))

            assertEquals(1_295L, award.totalXp)
            assertEquals(2, award.previousLevel)
            assertEquals(3, award.level)
            assertTrue(award.leveledUp)
        }

    @Test
    fun `no single award can clear two thresholds under today's constants`() {
        // What keeps `leveledUp` "one event per level gained" rather than merely "one event
        // per crossing": the largest award — a quest, which prices itself — is worth less than a
        // level, so no award can skip one. A retune that breaks this should break this test, not
        // #27's celebration.
        val largestAward = maxOf(
            XpCalculator.XP_PER_SET,
            XpCalculator.XP_PER_SESSION,
            XpCalculator.XP_PER_PR,
            XpCalculator.XP_PER_ACHIEVEMENT,
            Quests.ALL.maxOf { it.xpReward },
        )

        assertTrue(largestAward <= XpCalculator.XP_PER_LEVEL)
    }

    // ─── No bundling, no deduplication ──────────────────────────────────────────────────

    @Test
    fun `the same event twice is banked twice - deduplication is the emitter's job`() =
        runTest {
            awardXp(XpEvent.AchievementUnlocked("first_session"))
            awardXp(XpEvent.AchievementUnlocked("first_session"))

            assertEquals(2 * XpCalculator.XP_PER_ACHIEVEMENT.toLong(), repository.totalXp)
        }

    /** The events a finished session emits, in the order #145 will emit them. */
    private suspend fun finishSession(
        sets: Int,
        prTypes: List<PrType>,
    ): List<XpAward> =
        buildList {
            repeat(sets) { add(awardXp(XpEvent.SetLogged)) }
            add(awardXp(XpEvent.SessionFinished))
            prTypes.forEach { add(awardXp(XpEvent.PrBroken(it))) }
        }
}
