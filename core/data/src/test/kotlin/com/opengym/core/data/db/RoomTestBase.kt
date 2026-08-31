package com.opengym.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base for DAO tests: builds a fresh in-memory [OpenGymDatabase] on a real
 * (Robolectric) SQLite for each test, with foreign-key enforcement enabled so
 * cascade/restrict/set-null behaviour can be asserted, and with `user_progress`
 * rebuilt under its single-row `CHECK` — both are callbacks rather than schema,
 * so a test database that omits them is one the constraints do not hold in.
 *
 * Rows are built with the factories in `TestEntities.kt` and whole histories
 * with [WorkoutHistoryFixture], both of which live in this package so subclasses
 * can call them unqualified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
abstract class RoomTestBase {

    protected lateinit var db: OpenGymDatabase

    @Before
    fun setUpDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenGymDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .addCallback(OpenGymDatabase.singleRowProgressCallback)
            .build()
    }

    @After
    fun tearDownDatabase() {
        db.close()
    }

    /** Seeds a whole workout history through the DAO layer. See [HistorySpec]. */
    protected suspend fun seedHistory(spec: HistorySpec): SeededHistory = WorkoutHistoryFixture(db).seed(spec)

    /**
     * Number of rows in [table]. Orphan-row assertions need a count over a table
     * whose parent rows are gone, which no DAO query exposes (they all filter by
     * a parent id), so they go through raw SQL instead.
     */
    protected fun countRows(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
