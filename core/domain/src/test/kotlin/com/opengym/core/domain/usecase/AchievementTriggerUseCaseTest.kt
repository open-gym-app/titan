package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.gamification.XpCalculator
import com.opengym.core.domain.model.Achievement
import com.opengym.core.domain.model.AchievementProgress
import com.opengym.core.domain.model.AchievementUnlock
import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.repository.GamificationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AchievementTriggerUseCaseTest {

    private val repository = FakeGamificationRepository()
    private val trigger = AchievementTriggerUseCase(repository, AwardXpUseCase(repository))

    // ─── Unlocking ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a first session unlocks the first-session achievement`() =
        runTest {
            val unlocks = trigger(progress(sessions = 1), FINISHED_AT)

            assertEquals(listOf(Achievements.FIRST_SESSION), unlocks.map(AchievementUnlock::achievement))
            assertEquals(mapOf("first_session" to FINISHED_AT), repository.unlockedAchievements)
        }

    @Test
    fun `an unlock is worth an achievement's XP and is dated to the session that earned it`() =
        runTest {
            val unlocks = trigger(progress(sessions = 1), FINISHED_AT)

            assertEquals(
                listOf(
                    AchievementUnlock(
                        achievement = Achievements.FIRST_SESSION,
                        unlockedAt = FINISHED_AT,
                        xp = XpAward(
                            awarded = XpCalculator.XP_PER_ACHIEVEMENT,
                            totalXp = XpCalculator.XP_PER_ACHIEVEMENT.toLong(),
                            previousLevel = 1,
                            level = 2,
                        ),
                    ),
                ),
                unlocks,
            )
        }

    @Test
    fun `a streak milestone unlocks off the streak alone`() =
        runTest {
            val unlocks = trigger(progress(sessions = 0, streak = 7), FINISHED_AT)

            assertEquals(listOf(Achievements.SEVEN_DAY_STREAK), unlocks.map(AchievementUnlock::achievement))
        }

    @Test
    fun `a first record unlocks off the records broken`() =
        runTest {
            val unlocks = trigger(progress(sessions = 0, records = 1), FINISHED_AT)

            assertEquals(listOf(Achievements.FIRST_RECORD), unlocks.map(AchievementUnlock::achievement))
        }

    @Test
    fun `a session that clears several milestones at once unlocks all of them, cheapest first`() =
        runTest {
            val unlocks = trigger(progress(sessions = 10, streak = 7, records = 1), FINISHED_AT)

            assertEquals(
                listOf(
                    Achievements.FIRST_SESSION,
                    Achievements.TEN_SESSIONS,
                    Achievements.SEVEN_DAY_STREAK,
                    Achievements.FIRST_RECORD,
                ),
                unlocks.map(AchievementUnlock::achievement),
            )
            assertEquals(4 * XpCalculator.XP_PER_ACHIEVEMENT.toLong(), repository.totalXp)
        }

    @Test
    fun `a session between two milestones unlocks nothing and writes nothing`() =
        runTest {
            trigger(progress(sessions = 1), FINISHED_AT)
            val banked = repository.totalXp
            val held = repository.unlockedAchievements

            val unlocks = trigger(progress(sessions = 5, streak = 3), LATER)

            assertTrue(unlocks.isEmpty())
            assertEquals(banked, repository.totalXp)
            assertEquals(held, repository.unlockedAchievements)
        }

    // ─── Unlocking once ─────────────────────────────────────────────────────────────────

    @Test
    fun `an achievement already held is not unlocked again`() =
        runTest {
            trigger(progress(sessions = 1), FINISHED_AT)

            val second = trigger(progress(sessions = 2), LATER)

            assertTrue(second.isEmpty())
            assertEquals(mapOf("first_session" to FINISHED_AT), repository.unlockedAchievements)
        }

    @Test
    fun `re-finishing the same session does not re-award an unlock's XP`() =
        runTest {
            // The re-entry #145 has to survive: the cascade runs twice for one session, with
            // the same figures, and the second run must cost nothing.
            trigger(progress(sessions = 1, streak = 1, records = 1), FINISHED_AT)
            val bankedAfterFirstRun = repository.totalXp

            trigger(progress(sessions = 1, streak = 1, records = 1), FINISHED_AT)

            assertEquals(2 * XpCalculator.XP_PER_ACHIEVEMENT.toLong(), bankedAfterFirstRun)
            assertEquals(bankedAfterFirstRun, repository.totalXp)
            assertEquals(2, repository.awards.size)
        }

    @Test
    fun `a milestone passed long ago is not re-unlocked as the counter climbs`() =
        runTest {
            trigger(progress(sessions = 10), FINISHED_AT)
            val afterTenth = repository.totalXp

            repeat(3) { trigger(progress(sessions = 11 + it), LATER) }

            assertEquals(afterTenth, repository.totalXp)
        }

    @Test
    fun `an unlock the store says was already written pays nothing`() =
        runTest {
            // The race the boolean exists for: two cascades evaluate the catalogue against a
            // store that has nothing on file, and only the one whose insert landed may pay.
            val raced = FakeGamificationRepository()
            val loser = AchievementTriggerUseCase(AlreadyWritten(raced), AwardXpUseCase(raced))

            val unlocks = loser(progress(sessions = 1), FINISHED_AT)

            assertTrue(unlocks.isEmpty())
            assertEquals(0L, raced.totalXp)
        }

    // ─── The catalogue is what is evaluated ─────────────────────────────────────────────

    @Test
    fun `an achievement added to the catalogue needs no other change to unlock`() =
        runTest {
            val added = Achievement(
                id = "five_hundred_sessions",
                metric = Achievements.HUNDRED_SESSIONS.metric,
                threshold = 500,
            )
            val extended = AchievementTriggerUseCase(
                repository,
                AwardXpUseCase(repository),
                catalogue = Achievements.ALL + added,
            )

            val unlocks = extended(progress(sessions = 500), FINISHED_AT)

            assertTrue(added in unlocks.map(AchievementUnlock::achievement))
        }

    private fun progress(
        sessions: Int = 0,
        streak: Int = 0,
        records: Int = 0,
    ): AchievementProgress =
        AchievementProgress(
            sessionsFinished = sessions,
            currentStreak = streak,
            recordsBroken = records,
        )

    /**
     * A store that reports nothing unlocked and then refuses every insert — what the loser of
     * a race against another cascade sees, and what #35's `insertIfAbsent` answers it with.
     */
    private class AlreadyWritten(delegate: GamificationRepository) : GamificationRepository by delegate {

        override suspend fun unlockedAchievementIds(): Set<String> = emptySet()

        override suspend fun unlockAchievement(
            achievementId: String,
            unlockedAt: Instant,
        ): Boolean = false
    }

    private companion object {

        val FINISHED_AT: Instant = Instant.parse("2026-03-02T19:00:00Z")
        val LATER: Instant = Instant.parse("2026-03-05T19:00:00Z")
    }
}
