package com.opengym.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the onCreate seed populates the reference data the schema requires. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseSeedTest {

    private lateinit var db: OpenGymDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenGymDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .addCallback(OpenGymDatabase.seedCallback)
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `seed inserts the seven muscle groups`() = runBlocking {
        val names = db.muscleGroupDao().getAll().first().map { it.name }.toSet()
        assertEquals(
            setOf("CHEST", "BACK", "SHOULDERS", "BICEPS", "TRICEPS", "LEGS", "CORE"),
            names,
        )
    }

    @Test
    fun `seed inserts the six system tags and marks them system`() = runBlocking {
        val systemTags = db.tagDao().getSystemTags().first().map { it.name }.toSet()
        assertEquals(
            setOf("PUSH", "PULL", "STATIC", "CARRY", "COMPOUND", "ISOLATION"),
            systemTags,
        )
    }
}
