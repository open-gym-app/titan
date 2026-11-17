package com.titan.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import app.cash.turbine.test
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.data.db.entity.PetEntity
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.PetGain
import com.titan.core.domain.model.PetStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The pet store against a real Room database: the two rules the table holds on its own, the one
 * the repository holds for it, and the degraded read.
 *
 * Nothing in production inserts a pet yet, so every pet here goes in through the DAO.
 */
class PetRepositoryImplTest : RoomTestBase() {

    private val dao by lazy { db.petDao() }
    private val repository by lazy { PetRepositoryImpl(dao) }

    // ─── What the table refuses on its own ──────────────────────────────────────────────

    @Test
    fun `a second pet from the same egg is refused by the database`() =
        runBlocking {
            val egg = egg()
            dao.insert(pet(egg))

            assertThrows(SQLiteConstraintException::class.java) { runBlocking { dao.insert(pet(egg)) } }
            assertEquals(1, repository.count())
        }

    @Test
    fun `a pet from an egg that was never dropped is refused by the database`() {
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { dao.insert(pet(eggId = 404)) } }
    }

    @Test
    fun `an egg a pet hatched from cannot be deleted from under it`() =
        runBlocking {
            dao.insert(pet(egg()))

            assertThrows(SQLiteConstraintException::class.java) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM collected_item")
            }
            assertEquals(1, repository.count())
        }

    // ─── Reading ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pet comes back as it went in`() =
        runBlocking {
            val id = dao.insert(
                pet(egg(), speciesId = "kettle_ram", xp = 240, nickname = "Brick", isActive = true)
                    .copy(strength = 12, endurance = 3, mobility = 1, hatchedAt = 5_000),
            )

            val stored = repository.observeAll().first().single()

            assertEquals(id, stored.id)
            assertEquals("kettle_ram", stored.speciesId)
            assertEquals(Instant.ofEpochMilli(5_000), stored.hatchedAt)
            assertEquals(240L, stored.xp)
            assertEquals(PetStats(strength = 12, endurance = 3, mobility = 1), stored.stats)
            assertEquals("Brick", stored.nickname)
            assertTrue(stored.isActive)
        }

    @Test
    fun `a species this build does not know still reads`() =
        runBlocking {
            dao.insert(pet(egg(), speciesId = "from_a_newer_version"))

            assertEquals("from_a_newer_version", repository.observeAll().first().single().speciesId)
        }

    @Test
    fun `the active pet is listed first, then the rest oldest first`() =
        runBlocking {
            val first = dao.insert(pet(egg()))
            val second = dao.insert(pet(egg()))
            val third = dao.insert(pet(egg(), isActive = true))

            assertEquals(listOf(third, first, second), repository.observeAll().first().map { it.id })
        }

    @Test
    fun `with no pets there is no active pet and nothing to list`() =
        runBlocking {
            assertEquals(0, repository.count())
            assertNull(repository.observeActive().first())
            assertTrue(repository.observeAll().first().isEmpty())
        }

    // ─── One active pet ─────────────────────────────────────────────────────────────────

    @Test
    fun `setActive leaves exactly one active pet`() =
        runBlocking {
            val first = dao.insert(pet(egg(), isActive = true))
            val second = dao.insert(pet(egg()))

            assertTrue(repository.setActive(second))

            assertEquals(second, repository.observeActive().first()?.id)
            assertEquals(listOf(second), repository.observeAll().first().filter { it.isActive }.map { it.id })
            assertEquals(1, countRows("pet WHERE is_active = 1"))
            assertFalse(repository.observeAll().first().single { it.id == first }.isActive)
        }

    @Test
    fun `setActive for a pet that is not there changes nothing`() =
        runBlocking {
            val active = dao.insert(pet(egg(), isActive = true))

            assertFalse(repository.setActive(active + 100))

            assertEquals(active, repository.observeActive().first()?.id)
        }

    @Test
    fun `two flagged rows read back as one active pet, the lowest id`() =
        runBlocking {
            // A state only a restore or a bug can produce; the read degrades rather than trusts.
            val lower = dao.insert(pet(egg(), isActive = true))
            val higher = dao.insert(pet(egg(), isActive = true))

            assertEquals(lower, repository.observeActive().first()?.id)
            val all = repository.observeAll().first()
            assertEquals(listOf(lower, higher), all.map { it.id })
            assertEquals(listOf(true, false), all.map { it.isActive })
        }

    @Test
    fun `the active pet is observed as it changes`() =
        runBlocking {
            val first = dao.insert(pet(egg(), isActive = true))
            val second = dao.insert(pet(egg()))

            repository.observeActive().test {
                assertEquals(first, awaitItem()?.id)
                repository.setActive(second)
                assertEquals(second, awaitItem()?.id)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Renaming ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a pet is renamed, and unnamed again`() =
        runBlocking {
            val id = dao.insert(pet(egg()))

            assertTrue(repository.rename(id, "Brick"))
            assertEquals("Brick", repository.observeAll().first().single().nickname)

            assertTrue(repository.rename(id, null))
            assertNull(repository.observeAll().first().single().nickname)
        }

    @Test
    fun `renaming a pet that is not there changes nothing`() =
        runBlocking {
            assertFalse(repository.rename(404, "Brick"))
        }

    @Test
    fun `a name that skipped the rule is refused at the store`() =
        runBlocking {
            val id = dao.insert(pet(egg()))

            listOf("", "  Brick ", "a".repeat(25), "Bri\nck").forEach { name ->
                assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.rename(id, name) } }
            }
            assertNull(repository.observeAll().first().single().nickname)
        }

    // ─── A row nothing in the app writes still reads ────────────────────────────────────

    @Test
    fun `stored values outside their ranges read back clamped, without a throw`() =
        runBlocking {
            dao.insert(
                pet(egg(), xp = -5, nickname = "x".repeat(10_000), isActive = true)
                    .copy(strength = -3, endurance = Int.MAX_VALUE, mobility = 7),
            )
            dao.insert(pet(egg(), xp = Long.MAX_VALUE))

            val (negative, huge) = repository.observeAll().first()

            assertEquals(0L, negative.xp)
            assertEquals(PetStats(strength = 0, endurance = PetStats.MAX, mobility = 7), negative.stats)
            assertNull(negative.nickname)
            assertEquals(XpCalculator.MAX_TOTAL_XP, huge.xp)
            assertEquals(negative.id, repository.observeActive().first()?.id)
        }

    // ─── Growing ────────────────────────────────────────────────────────────────────────

    @Test
    fun `growing writes to the active pet and reports what it added`() =
        runBlocking {
            dao.insert(pet(egg()))
            val active = dao.insert(pet(egg(), xp = 10, isActive = true))

            val growth = repository.grow(
                at = 0,
                gain = PetGain(xp = 65, stats = PetStats(strength = 3, endurance = 0, mobility = 1)),
            )!!

            assertEquals(active, growth.pet.id)
            assertEquals(75L, growth.pet.xp)
            assertEquals(65L, growth.xpGained)
            assertEquals(PetStats(3, 0, 1), growth.statsGained)
            assertEquals(growth.pet, repository.observeActive().first())
            assertEquals(0L, repository.observeAll().first().single { !it.isActive }.xp)
        }

    @Test
    fun `growing with no active pet writes nothing`() =
        runBlocking {
            dao.insert(pet(egg()))

            assertNull(repository.grow(PetGain(xp = 65, stats = PetStats(1, 0, 0)), at = 0))
            assertEquals(0L, repository.observeAll().first().single().xp)
        }

    @Test
    fun `growth stops at the bounds, and reports only what it actually added`() =
        runBlocking {
            dao.insert(pet(egg(), xp = XpCalculator.MAX_TOTAL_XP - 1, isActive = true).copy(strength = PetStats.MAX))

            val growth = repository.grow(
                at = 0,
                gain = PetGain(xp = 65, stats = PetStats(strength = 3, endurance = 2, mobility = 0)),
            )!!

            assertEquals(XpCalculator.MAX_TOTAL_XP, growth.pet.xp)
            assertEquals(1L, growth.xpGained)
            assertEquals(PetStats(strength = 0, endurance = 2, mobility = 0), growth.statsGained)
        }

    @Test
    fun `two growths at once lose neither`() =
        runBlocking {
            dao.insert(pet(egg(), isActive = true))

            withContext(Dispatchers.IO) {
                List(20) { async { repository.grow(PetGain(xp = 5, stats = PetStats(1, 0, 0)), at = 0) } }.awaitAll()
            }

            val pet = repository.observeActive().first()!!
            assertEquals(100L, pet.xp)
            assertEquals(20, pet.stats.strength)
        }

    private suspend fun egg(): Long =
        db.collectedItemDao().insert(
            CollectedItemEntity(collectibleId = "pet_egg", source = "session_finished", acquiredAt = 1_000),
        )

    private fun pet(
        eggId: Long,
        speciesId: String = "iron_cub",
        xp: Long = 0,
        nickname: String? = null,
        isActive: Boolean = false,
    ) = PetEntity(
        speciesId = speciesId,
        hatchedFromItemId = eggId,
        hatchedAt = 2_000,
        xp = xp,
        nickname = nickname,
        isActive = isActive,
    )
}
