package com.titan.core.data.db

import com.titan.core.data.db.entity.AchievementEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unlock table, whose whole job is to happen once.
 *
 * The idempotency cases are the point: the boolean `insertIfAbsent` returns is what the
 * trigger use case pays XP on, so a write that reported success twice would pay for one
 * milestone twice, and one that silently replaced the row would re-date an unlock the user
 * earned months ago.
 */
class AchievementDaoTest : RoomTestBase() {

    private companion object {
        const val FIRST_SESSION = "first_session"
        const val FOUR_WEEK_STREAK = "four_week_streak"
    }

    private val dao by lazy { db.achievementDao() }

    @Test
    fun `an unlock is written once and reports that it wrote`() =
        runBlocking {
            val wrote = dao.insertIfAbsent(AchievementEntity(FIRST_SESSION, unlockedAt = 1_000))

            assertTrue(wrote)
            assertEquals(listOf(FIRST_SESSION), dao.getAllIdsOnce())
        }

    @Test
    fun `unlocking the same achievement twice inserts exactly one row`() =
        runBlocking {
            dao.insertIfAbsent(AchievementEntity(FIRST_SESSION, unlockedAt = 1_000))

            val wroteAgain = dao.insertIfAbsent(AchievementEntity(FIRST_SESSION, unlockedAt = 5_000))

            assertFalse("the second unlock claimed to have written the row", wroteAgain)
            assertEquals(1, countRows("achievement"))
        }

    @Test
    fun `a repeat unlock leaves the moment it was first earned alone`() =
        runBlocking {
            dao.insertIfAbsent(AchievementEntity(FIRST_SESSION, unlockedAt = 1_000))
            dao.insertIfAbsent(AchievementEntity(FIRST_SESSION, unlockedAt = 5_000))

            assertEquals(1_000L, dao.getAll().first().single().unlockedAt)
        }

    @Test
    fun `unlocks come back earliest first`() =
        runBlocking {
            dao.insertIfAbsent(AchievementEntity(FOUR_WEEK_STREAK, unlockedAt = 9_000))
            dao.insertIfAbsent(AchievementEntity(FIRST_SESSION, unlockedAt = 1_000))

            assertEquals(
                listOf(FIRST_SESSION, FOUR_WEEK_STREAK),
                dao.getAll().first().map { it.achievementId },
            )
        }
}
