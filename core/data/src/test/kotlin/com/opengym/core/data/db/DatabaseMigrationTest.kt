package com.opengym.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the migration contract for [OpenGymDatabase]. Version 1 is the only
 * schema that exists today, so there is no upgrade path to replay yet — this
 * suite instead pins down the two properties that make future migrations
 * safe to add:
 *
 *  1. The schema Room generates from the current entities still matches the
 *     exported spec in `schemas/`, so a `Migration` authored later has a
 *     trustworthy starting point ([MigrationTestHelper.createDatabase] fails
 *     loudly on drift).
 *  2. Opening a database file whose on-disk version has no registered
 *     [androidx.room.migration.Migration] throws instead of silently
 *     destroying data, since [OpenGymDatabase] is never built with
 *     `fallbackToDestructiveMigration`.
 *
 * When a real v1 -> v2 migration lands, add a `migrate1To2 preserves data`
 * test here that seeds v1 via [MigrationTestHelper.createDatabase], runs
 * `OpenGymDatabase.MIGRATIONS` against it, and asserts the seeded rows
 * survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseMigrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenGymDatabase::class.java,
    )

    @Test
    fun `current schema matches the exported version 1 spec`() {
        // Throws if the entities Room sees today have drifted from schemas/.../1.json
        helper.createDatabase(testDbName, 1).close()
    }

    @Test
    fun `opening a database at an unmigrated version fails instead of wiping it`() {
        helper.createDatabase(testDbName, 1).apply {
            version = 5
            close()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThrows(IllegalStateException::class.java) {
            Room.databaseBuilder(context, OpenGymDatabase::class.java, testDbName)
                .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
                .build()
                .openHelper
                .writableDatabase
        }
    }
}
