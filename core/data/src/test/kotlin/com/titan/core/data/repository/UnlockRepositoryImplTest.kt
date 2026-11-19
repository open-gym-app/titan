package com.titan.core.data.repository

import app.cash.turbine.test
import com.titan.core.data.db.RoomTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockRepositoryImplTest : RoomTestBase() {

    private val repository by lazy { UnlockRepositoryImpl(db.petUnlockDao()) }

    @Test
    fun `an unlock is stored once, with the moment it was first earned`() =
        runBlocking {
            assertEquals(setOf("sauna", "dojo"), repository.unlock(setOf("sauna", "dojo"), at = 1))
            assertEquals(setOf("trail"), repository.unlock(setOf("sauna", "trail"), at = 2))

            assertEquals(setOf("sauna", "dojo", "trail"), repository.observeUnlocked().first())
            val sauna = db.query("SELECT unlocked_at FROM pet_unlock WHERE `key` = 'sauna'", emptyArray())
                .use {
                    it.moveToFirst()
                    it.getLong(0)
                }
            assertEquals(1L, sauna)
        }

    @Test
    fun `nothing new is nothing`() =
        runBlocking {
            repository.unlock(setOf("sauna"), at = 1)

            assertEquals(emptySet<String>(), repository.unlock(setOf("sauna"), at = 2))
            assertEquals(emptySet<String>(), repository.unlock(emptySet(), at = 3))
        }

    @Test
    fun `the unlocked keys are observed as they are written`() =
        runBlocking {
            repository.observeUnlocked().test {
                assertEquals(emptySet<String>(), awaitItem())
                repository.unlock(setOf("dojo"), at = 1)
                assertEquals(setOf("dojo"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
