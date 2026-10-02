package com.titan.core.data.db

import app.cash.turbine.test
import com.titan.core.data.db.entity.UserProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single progress row: what the schema will hold, and what watching it emits.
 *
 * The constraint tests are the ones worth having. `user_progress` is the only table in the
 * database whose correctness rests on something Room cannot express — see
 * [TitanDatabase.singleRowProgressCallback] — so if the callback stops being applied,
 * nothing else in the suite would notice: every read here filters on `id = 1` and would go on
 * returning the right row while a second one sat beside it.
 */
class UserProgressDaoTest : RoomTestBase() {

    private val dao by lazy { db.userProgressDao() }

    @Test
    fun `there is no row at all until something is written`() =
        runBlocking {
            assertNull(dao.getOnce())
            assertEquals(0, countRows("user_progress"))
        }

    @Test
    fun `the row comes back as it went in`() =
        runBlocking {
            val progress = UserProgressEntity(
                totalXp = 1_250,
                currentLevel = 13,
                currentStreak = 4,
                longestStreak = 21,
                lastCountedAt = 1_700_000_000_000,
            )

            dao.upsert(progress)

            assertEquals(progress, dao.getOnce())
        }

    @Test
    fun `a second upsert replaces the row rather than adding one`() =
        runBlocking {
            dao.upsert(UserProgressEntity(totalXp = 100))
            dao.upsert(UserProgressEntity(totalXp = 400))

            assertEquals(1, countRows("user_progress"))
            assertEquals(400L, dao.getOnce()?.totalXp)
        }

    @Test
    fun `the schema refuses a second row`() =
        runBlocking {
            dao.upsert(UserProgressEntity(totalXp = 100))

            // Not an upsert conflict Room could resolve by updating: a CHECK failure is not a
            // uniqueness failure, so it comes back out rather than being turned into a write
            // against the row that is already there.
            val failure = runCatching { dao.upsert(UserProgressEntity(id = 2, totalXp = 999)) }.exceptionOrNull()

            assertTrue(
                "a row with an id other than ${UserProgressEntity.SINGLE_ROW_ID} was accepted: $failure",
                failure?.message.orEmpty().contains("CHECK constraint failed"),
            )
            assertEquals(1, countRows("user_progress"))
            assertEquals(100L, dao.getOnce()?.totalXp)
        }

    @Test
    fun `observe emits the new value after an XP write`() =
        runBlocking {
            // Asserted on the emission itself rather than against a millisecond budget: a
            // threshold on a JVM flow test measures the test runner, not the DAO.
            dao.observe().test {
                assertNull(awaitItem())

                dao.upsert(UserProgressEntity(totalXp = 250, currentLevel = 3))

                assertEquals(250L, awaitItem()?.totalXp)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `mutate hands the transform an empty row when nothing has been written yet`() =
        runBlocking {
            var seen: UserProgressEntity? = null

            val written = dao.mutate { current ->
                seen = current
                current.copy(totalXp = current.totalXp + 25)
            }

            assertEquals(UserProgressEntity(), seen)
            assertEquals(25L, written.totalXp)
            assertEquals(written, dao.getOnce())
        }

    @Test
    fun `mutate reads what the last one wrote`() =
        runBlocking {
            dao.mutate { it.copy(totalXp = it.totalXp + 25) }
            dao.mutate { it.copy(totalXp = it.totalXp + 75) }

            assertEquals(100L, dao.getOnce()?.totalXp)
        }
}
