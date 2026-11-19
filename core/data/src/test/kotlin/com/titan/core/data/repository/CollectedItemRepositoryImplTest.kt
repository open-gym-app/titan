package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The inventory through the repository, over a real SQLite.
 *
 * `CollectedItemDaoTest` already pins what the table does with a drop, so what is left for this
 * class is the half only the mapping can get wrong: that an [Instant] reaches the column and
 * comes back as the same moment, that two ids the store does not interpret cross unchanged, and
 * that a limit SQLite would read as "no limit" is refused before it gets there.
 */
class CollectedItemRepositoryImplTest : RoomTestBase() {

    private companion object {
        const val PET_EGG = "pet_egg"
        const val DUMBBELL_CHARM = "dumbbell_charm"
        const val SESSION_FINISHED = "session_finished"
        val MONDAY_EVENING: Instant = Instant.parse("2026-03-02T19:00:00Z")
        val TUESDAY_EVENING: Instant = Instant.parse("2026-03-03T19:00:00Z")
        const val PLENTY = 50
    }

    private val repository by lazy { CollectedItemRepositoryImpl(db.collectedItemDao()) }

    @Test
    fun `a drop carries the session that dropped it, and outlives that session`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(
                com.titan.core.data.db.entity.WorkoutSessionEntity(
                    completedAt = 2_000,
                    isInProgress = false,
                    lastActiveAt = 2_000,
                    createdAt = 1_000,
                    updatedAt = 2_000,
                ),
            )
            repository.recordDrop("steak", SESSION_FINISHED, MONDAY_EVENING, sessionId = sessionId)
            repository.recordDrop(PET_EGG, SESSION_FINISHED, MONDAY_EVENING, sessionId = null)

            assertEquals(listOf(sessionId, null), sessionsOfDrops())

            db.openHelper.writableDatabase.execSQL("DELETE FROM workout_session WHERE id = $sessionId")

            assertEquals("the food is kept, without its session", listOf(null, null), sessionsOfDrops())
        }

    private fun sessionsOfDrops(): List<Long?> =
        db.query("SELECT session_id FROM collected_item ORDER BY id", emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(if (cursor.isNull(0)) null else cursor.getLong(0)) }
        }

    @Test
    fun `a drop keeps the moment it was earned`() =
        runBlocking {
            repository.recordDrop(PET_EGG, SESSION_FINISHED, MONDAY_EVENING, sessionId = null)

            val held = repository.observeRecent(PLENTY).first().single()
            assertEquals(PET_EGG, held.collectibleId)
            assertEquals(SESSION_FINISHED, held.source)
            assertEquals(MONDAY_EVENING, held.acquiredAt)
        }

    @Test
    fun `a source this build would not recognise is stored and read back as it was written`() =
        runBlocking {
            // The restore rule: a drop from a newer version is a drop, not a corrupt row, and
            // neither id is decoded on the way out.
            repository.recordDrop(
                "collectible_from_a_later_build",
                "a_trigger_that_does_not_exist_yet",
                MONDAY_EVENING,
                sessionId = null,
            )

            val held = repository.observeRecent(PLENTY).first().single()
            assertEquals("collectible_from_a_later_build", held.collectibleId)
            assertEquals("a_trigger_that_does_not_exist_yet", held.source)
        }

    @Test
    fun `the same collectible dropping twice is held twice, each with its own identity`() =
        runBlocking {
            repository.recordDrop(PET_EGG, SESSION_FINISHED, MONDAY_EVENING, sessionId = null)
            repository.recordDrop(PET_EGG, SESSION_FINISHED, TUESDAY_EVENING, sessionId = null)

            val held = repository.observeRecent(PLENTY).first()
            assertEquals(listOf(PET_EGG, PET_EGG), held.map { it.collectibleId })
            assertNotEquals("two drops of one collectible share an id", held[0].id, held[1].id)
            assertEquals(listOf(TUESDAY_EVENING, MONDAY_EVENING), held.map { it.acquiredAt })
        }

    @Test
    fun `the read is bounded by what it asked for`() =
        runBlocking {
            repository.recordDrop(PET_EGG, SESSION_FINISHED, MONDAY_EVENING, sessionId = null)
            repository.recordDrop(DUMBBELL_CHARM, SESSION_FINISHED, TUESDAY_EVENING, sessionId = null)

            assertEquals(listOf(DUMBBELL_CHARM), repository.observeRecent(limit = 1).first().map { it.collectibleId })
        }

    @Test
    fun `a limit SQLite would read as no limit is refused`() {
        // `LIMIT -1` means *every* row in SQLite, so a caller that computed a limit from an
        // empty screen would silently be handed the whole inventory.
        val failure = assertThrows(IllegalArgumentException::class.java) { repository.observeRecent(limit = -1) }

        assertTrue(failure.message.orEmpty().contains("at least one"))
        assertThrows(IllegalArgumentException::class.java) { repository.observeRecent(limit = 0) }
    }
}
