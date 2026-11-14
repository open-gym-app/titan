package com.titan.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.mapper.GymMapper
import com.titan.core.data.repository.GymRepositoryImpl
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gym CRUD against a database *file*, closed and reopened between steps, as the app is when it
 * is killed and relaunched. Robolectric rather than an instrumented test, per the project's
 * testing strategy (#85) - see `GamificationPersistenceTest`, which this is modelled on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GymPersistenceTest {

    private companion object {
        const val DB_NAME = "gym-persistence-test.db"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun open(): TitanDatabase =
        Room.databaseBuilder(context, TitanDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addCallback(TitanDatabase.foreignKeysCallback)
            .addCallback(TitanDatabase.singleRowProgressCallback)
            .build()

    private fun TitanDatabase.gyms() = GymRepositoryImpl(gymDao = gymDao(), gymMapper = GymMapper())

    private val ironWorks = Gym(
        name = "Iron Works",
        address = "12 Forge Street",
        coordinates = Coordinates(51.5074, -0.1278),
        equipmentTags = listOf("squat rack", "plates"),
        userNotes = "closes at 22:00",
        createdAt = 1_700_000_000_000,
    )

    @After
    fun deleteDatabaseFile() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `a saved gym is still there after a restart`() =
        runBlocking {
            val first = open()
            val id = first.gyms().save(ironWorks)
            first.close()

            val reopened = open()
            val stored = reopened.gyms().getById(id).first()
            reopened.close()

            assertEquals(ironWorks.copy(id = id), stored)
        }

    @Test
    fun `an edit survives a restart`() =
        runBlocking {
            val first = open()
            val id = first.gyms().save(ironWorks)
            val edited = ironWorks.copy(id = id, name = "Iron Works 2", equipmentTags = listOf("cable stack"))
            first.gyms().save(edited)
            first.close()

            val reopened = open()
            val stored = reopened.gyms().getAll().first()
            reopened.close()

            assertEquals(listOf(edited), stored)
        }

    @Test
    fun `a deleted gym stays deleted after a restart`() =
        runBlocking {
            val first = open()
            val id = first.gyms().save(ironWorks)
            first.gyms().delete(id)
            first.close()

            val reopened = open()
            val stored = reopened.gyms().getById(id).first()
            reopened.close()

            assertNull(stored)
        }
}
