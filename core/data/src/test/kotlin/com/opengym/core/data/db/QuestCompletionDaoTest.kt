package com.opengym.core.data.db

import com.opengym.core.data.db.entity.QuestCompletionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quest completion table, whose job is to happen once **per period**.
 *
 * The idempotency cases are the point, as they are for `AchievementDaoTest`: the boolean
 * `insertIfAbsent` returns is what the completion pass pays XP on, so a write reporting success
 * twice would pay for one quest twice. What is tested here beyond that table is the extra half
 * of the key — a quest completed last week must not block this week's, or a recurring quest
 * would recur exactly once.
 */
class QuestCompletionDaoTest : RoomTestBase() {

    private companion object {
        const val WEEKLY_SESSIONS = "weekly_sessions"
        const val WEEKLY_RECORD = "weekly_record"

        /** 2025-09-01, a Monday, as an epoch day. */
        const val THIS_WEEK = 20_332L
        const val LAST_WEEK = THIS_WEEK - 7
    }

    private val dao by lazy { db.questCompletionDao() }

    @Test
    fun `a completion is written once and reports that it wrote`() =
        runBlocking {
            val wrote = dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 1_000))

            assertTrue(wrote)
            assertEquals(listOf(WEEKLY_SESSIONS), dao.getCompletedIdsOnce(THIS_WEEK))
        }

    @Test
    fun `completing the same quest twice in one period inserts exactly one row`() =
        runBlocking {
            dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 1_000))

            val wroteAgain = dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 5_000))

            assertFalse("the second completion claimed to have written the row", wroteAgain)
            assertEquals(1, countRows("quest_completion"))
        }

    @Test
    fun `a repeat completion leaves the moment it was first earned alone`() =
        runBlocking {
            dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 1_000))
            dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 5_000))

            assertEquals(1_000L, singleRow().completedAt)
        }

    @Test
    fun `the same quest completes again in the next period`() =
        runBlocking {
            // The whole difference from an achievement, and the reason the period is half of the
            // primary key: a quest recurs.
            dao.insertIfAbsent(completion(WEEKLY_SESSIONS, LAST_WEEK, completedAt = 1_000))

            val wrote = dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 9_000))

            assertTrue("last week's completion blocked this week's", wrote)
            assertEquals(2, countRows("quest_completion"))
        }

    @Test
    fun `a period only reports its own completions`() =
        runBlocking {
            dao.insertIfAbsent(completion(WEEKLY_SESSIONS, LAST_WEEK, completedAt = 1_000))
            dao.insertIfAbsent(completion(WEEKLY_RECORD, THIS_WEEK, completedAt = 2_000))

            assertEquals(listOf(WEEKLY_RECORD), dao.getCompletedIdsOnce(THIS_WEEK))
            assertEquals(listOf(WEEKLY_SESSIONS), dao.getCompletedIdsOnce(LAST_WEEK))
        }

    @Test
    fun `two quests can complete in the same period`() =
        runBlocking {
            dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 1_000))
            val wrote = dao.insertIfAbsent(completion(WEEKLY_RECORD, THIS_WEEK, completedAt = 2_000))

            assertTrue(wrote)
            assertEquals(setOf(WEEKLY_SESSIONS, WEEKLY_RECORD), dao.getCompletedIdsOnce(THIS_WEEK).toSet())
        }

    @Test
    fun `a period with nothing completed reports nothing rather than failing`() =
        runBlocking {
            assertEquals(emptyList<String>(), dao.getCompletedIdsOnce(THIS_WEEK))
            assertEquals(emptyList<String>(), dao.observeCompletedIds(THIS_WEEK).first())
        }

    @Test
    fun `the flow reports a completion written under it`() =
        runBlocking {
            // What a board open while a session finishes behind it depends on.
            assertEquals(emptyList<String>(), dao.observeCompletedIds(THIS_WEEK).first())

            dao.insertIfAbsent(completion(WEEKLY_SESSIONS, THIS_WEEK, completedAt = 1_000))

            assertEquals(listOf(WEEKLY_SESSIONS), dao.observeCompletedIds(THIS_WEEK).first())
        }

    private suspend fun singleRow(): QuestCompletionEntity =
        db.questCompletionDao().observeCompletedIds(THIS_WEEK).first().let {
            // The flow returns ids only, so read the row back through the raw query the base
            // class exposes rather than adding a DAO method nothing in production needs.
            rawCompletion(it.single(), THIS_WEEK)
        }

    private fun rawCompletion(
        questId: String,
        periodStart: Long,
    ): QuestCompletionEntity =
        db.openHelper.readableDatabase
            .query(
                "SELECT quest_id, period_start, completed_at FROM quest_completion " +
                    "WHERE quest_id = ? AND period_start = ?",
                arrayOf<Any>(questId, periodStart),
            )
            .use { cursor ->
                cursor.moveToFirst()
                QuestCompletionEntity(
                    questId = cursor.getString(0),
                    periodStart = cursor.getLong(1),
                    completedAt = cursor.getLong(2),
                )
            }

    private fun completion(
        questId: String,
        periodStart: Long,
        completedAt: Long,
    ) = QuestCompletionEntity(questId = questId, periodStart = periodStart, completedAt = completedAt)
}
