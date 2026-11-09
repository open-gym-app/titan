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
            // Ten sets and the session are worth 100; started 105 short of level 3 — inside
            // level 2, which is 110 wide — they leave the total five short, and the record is
            // the crossing.
            repository.addXp(XpCalculator.xpRequiredForLevel(3).toInt() - 105, XpCalculator::levelForTotalXp)
            val awards = finishSession(sets = 10, prTypes = listOf(PrType.MAX_WEIGHT))

            assertEquals(1, awards.count(XpAward::leveledUp))
            assertTrue(awards.last().leveledUp)
            assertEquals(3, awards.last().level)
            assertEquals(2, awards.last().previousLevel)
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
            // From level 3 up, where every level is wider than an achievement, so each top-up
            // lands inside the level below the one it is short of.
            val awards = (3..6).map { level ->
                val shortOfThreshold = XpCalculator.xpRequiredForLevel(level) - repository.totalXp
                repository.addXp(
                    shortOfThreshold.toInt() - XpCalculator.XP_PER_ACHIEVEMENT,
                    XpCalculator::levelForTotalXp,
                )
                awardXp(XpEvent.AchievementUnlocked("achievement_$level"))
            }

            assertEquals(listOf(3, 4, 5, 6), awards.map(XpAward::level))
            assertTrue(awards.all(XpAward::leveledUp))
            assertEquals(XpCalculator.xpRequiredForLevel(6), repository.totalXp)
        }

    @Test
    fun `events on either side of a crossing report no level-up`() =
        runTest {
            // One set short of level 2, the set that crosses, and one inside level 2.
            val setsToLevel = (XpCalculator.xpRequiredForLevel(2) / XpCalculator.XP_PER_SET).toInt()
            val awards = (1..setsToLevel + 1).map { awardXp(XpEvent.SetLogged) }

            assertFalse(awards[setsToLevel - 2].leveledUp)
            assertTrue(awards[setsToLevel - 1].leveledUp)
            assertFalse(awards[setsToLevel].leveledUp)
            assertEquals(2, awards.last().level)
        }

    @Test
    fun `a level-up is reported against the level the user was actually on`() =
        runTest {
            // 3,840 is exactly level 17's threshold; the record moves the total inside it.
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 3_840))

            val award = started(XpEvent.PrBroken(PrType.MAX_VOLUME))

            assertEquals(3_850L, award.totalXp)
            assertEquals(17, award.previousLevel)
            assertEquals(17, award.level)
            assertFalse(award.leveledUp)
        }

    @Test
    fun `an award that starts mid-level still reports the crossing it caused`() =
        runTest {
            // 1,106 is five short of level 8 (1,111); the achievement lands on 1,206, over the
            // threshold but not up to the next one (1,358) — one crossing, from a total that was
            // not on a boundary to start with.
            val started = AwardXpUseCase(FakeGamificationRepository(initialXp = 1_106))

            val award = started(XpEvent.AchievementUnlocked("twelve_week_streak"))

            assertEquals(1_206L, award.totalXp)
            assertEquals(7, award.previousLevel)
            assertEquals(8, award.level)
            assertTrue(award.leveledUp)
        }

    @Test
    fun `an award that clears two thresholds reports the whole jump`() =
        runTest {
            // The first levels are cheaper than the largest awards, so a monthly quest landing on
            // a new install clears two at once. It is still one `leveledUp` award, carrying both
            // ends of the jump — the summary's sweep counts the levels between them.
            val award = awardXp(XpEvent.QuestCompleted("monthly_sessions", LARGEST_QUEST_REWARD))

            assertEquals(1, award.previousLevel)
            assertEquals(XpCalculator.levelForTotalXp(LARGEST_QUEST_REWARD.toLong()), award.level)
            assertTrue(award.level - award.previousLevel >= 2)
            assertTrue(award.leveledUp)
        }

    @Test
    fun `past the first fortnight no single award can clear two thresholds`() {
        // What keeps `leveledUp` "one event per level gained" for everybody but a new install:
        // from [SINGLE_CROSSING_FROM_LEVEL] on, every level is wider than the largest award — a
        // quest, which prices itself — and levels only grow from there (see XpCalculatorTest).
        // A retune that moves the multi-level window later should fail here, not on a
        // celebration that has started counting several levels a session for months.
        val largestAward = maxOf(
            XpCalculator.XP_PER_SET,
            XpCalculator.XP_PER_SESSION,
            XpCalculator.XP_PER_PR,
            XpCalculator.XP_PER_ACHIEVEMENT,
            LARGEST_QUEST_REWARD,
        )

        assertTrue(XpCalculator.xpToNextLevel(SINGLE_CROSSING_FROM_LEVEL) >= largestAward)
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

    private companion object {

        /** The largest award there is: the catalogue's dearest quest. */
        val LARGEST_QUEST_REWARD = Quests.ALL.maxOf { it.xpReward }

        /** The first level wider than any single award — about a fortnight in. */
        const val SINGLE_CROSSING_FROM_LEVEL = 12
    }
}
