package com.opengym.core.data.db

import app.cash.turbine.test
import com.opengym.core.data.db.entity.CollectedItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inventory table, whose job is the opposite of `achievement`'s.
 *
 * `AchievementDaoTest` and `QuestCompletionDaoTest` are both about a write that must happen
 * *once*, because the boolean they return is what XP is paid on. The cases worth having here
 * are the mirror image: a collectible that drops twice must leave two rows, or the inventory is
 * a set and a user who pulled three of something holds one. The rest is the read the quest log
 * draws — newest first, bounded, and live while a session finishes behind it.
 */
class CollectedItemDaoTest : RoomTestBase() {

    private companion object {
        const val PET_EGG = "pet_egg"
        const val DUMBBELL_CHARM = "dumbbell_charm"
        const val SESSION_FINISHED = "session_finished"
        const val NEW_GYM_EXPLORED = "new_gym_explored"

        /** Enough for every read here to be unbounded in practice. */
        const val PLENTY = 50
    }

    private val dao by lazy { db.collectedItemDao() }

    @Test
    fun `a drop comes back as it went in`() =
        runBlocking {
            val id = dao.insert(drop(PET_EGG, NEW_GYM_EXPLORED, acquiredAt = 1_000))

            val stored = recent().single()
            assertEquals(id, stored.id)
            assertEquals(PET_EGG, stored.collectibleId)
            assertEquals(NEW_GYM_EXPLORED, stored.source)
            assertEquals(1_000L, stored.acquiredAt)
        }

    @Test
    fun `the same collectible dropping twice leaves two rows`() =
        runBlocking {
            // The whole difference from an achievement, and the reason the rowid is the key:
            // an inventory holds duplicates.
            val first = dao.insert(drop(PET_EGG, SESSION_FINISHED, acquiredAt = 1_000))
            val second = dao.insert(drop(PET_EGG, SESSION_FINISHED, acquiredAt = 2_000))

            assertNotEquals("the second drop merged into the first", first, second)
            assertEquals(2, countRows("collected_item"))
            assertEquals(listOf(PET_EGG, PET_EGG), recent().map { it.collectibleId })
        }

    @Test
    fun `a repeat drop keeps its own moment rather than the first one's`() =
        runBlocking {
            dao.insert(drop(PET_EGG, SESSION_FINISHED, acquiredAt = 1_000))
            dao.insert(drop(PET_EGG, NEW_GYM_EXPLORED, acquiredAt = 2_000))

            // Nothing here is insert-if-absent, so neither row is written over the other.
            assertEquals(listOf(2_000L, 1_000L), recent().map { it.acquiredAt })
            assertEquals(listOf(NEW_GYM_EXPLORED, SESSION_FINISHED), recent().map { it.source })
        }

    @Test
    fun `drops come back newest first`() =
        runBlocking {
            dao.insert(drop(PET_EGG, SESSION_FINISHED, acquiredAt = 1_000))
            dao.insert(drop(DUMBBELL_CHARM, SESSION_FINISHED, acquiredAt = 3_000))
            dao.insert(drop(PET_EGG, NEW_GYM_EXPLORED, acquiredAt = 2_000))

            assertEquals(listOf(3_000L, 2_000L, 1_000L), recent().map { it.acquiredAt })
        }

    @Test
    fun `drops earned at the same moment fall back to the order they were written, latest first`() =
        runBlocking {
            // One cascade dates every drop it produces from the triggering session, so this is
            // the ordinary case rather than a contrived one: without the rowid tie-break their
            // order would be SQLite's to choose and could differ between two reads.
            val first = dao.insert(drop(PET_EGG, SESSION_FINISHED, acquiredAt = 5_000))
            val second = dao.insert(drop(DUMBBELL_CHARM, SESSION_FINISHED, acquiredAt = 5_000))

            assertEquals(listOf(second, first), recent().map { it.id })
        }

    @Test
    fun `the read returns only the newest few it was asked for`() =
        runBlocking {
            dao.insert(drop(PET_EGG, SESSION_FINISHED, acquiredAt = 1_000))
            dao.insert(drop(DUMBBELL_CHARM, SESSION_FINISHED, acquiredAt = 2_000))
            dao.insert(drop(PET_EGG, NEW_GYM_EXPLORED, acquiredAt = 3_000))

            val newest = dao.observeRecent(limit = 2).first()

            assertEquals(listOf(3_000L, 2_000L), newest.map { it.acquiredAt })
        }

    @Test
    fun `an empty inventory reports nothing rather than failing`() =
        runBlocking {
            assertTrue(recent().isEmpty())
        }

    @Test
    fun `the flow reports a drop written under it`() =
        runBlocking {
            // What a quest log open while a session finishes behind it depends on.
            dao.observeRecent(PLENTY).test {
                assertTrue(awaitItem().isEmpty())

                dao.insert(drop(PET_EGG, NEW_GYM_EXPLORED, acquiredAt = 1_000))

                assertEquals(listOf(PET_EGG), awaitItem().map { it.collectibleId })
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun recent(): List<CollectedItemEntity> = dao.observeRecent(PLENTY).first()

    private fun drop(
        collectibleId: String,
        source: String,
        acquiredAt: Long,
    ) = CollectedItemEntity(collectibleId = collectibleId, source = source, acquiredAt = acquiredAt)
}
