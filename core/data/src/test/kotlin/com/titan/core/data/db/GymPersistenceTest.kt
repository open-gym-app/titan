package com.titan.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.repository.GymRepositoryImpl
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** That the gym directory is on the disk rather than in the process: written, closed, reopened, read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GymPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun open(): TitanDatabase =
        Room.databaseBuilder(context, TitanDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addCallback(TitanDatabase.foreignKeysCallback)
            .addCallback(TitanDatabase.singleRowProgressCallback)
            .build()

    @After
    fun deleteDatabaseFile() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `saved gyms survive the database being closed and reopened`() =
        runBlocking {
            val first = open()
            val saved = Gym(
                name = "Iron Works",
                address = "12 Rue de la Forge",
                coordinates = Coordinates(48.8566, 2.3522),
                equipmentTags = listOf("barbell", "sauna, steam"),
                userNotes = "Quiet before 9",
                createdAt = 1_788_600_000_000L,
            )
            val id = GymRepositoryImpl(first.gymDao()).save(saved)
            first.close()

            val second = open()
            val restored = GymRepositoryImpl(second.gymDao()).getAll().first()
            second.close()

            assertEquals(listOf(saved.copy(id = id)), restored)
        }

    private companion object {
        const val DB_NAME = "gym-persistence-test.db"
    }
}
