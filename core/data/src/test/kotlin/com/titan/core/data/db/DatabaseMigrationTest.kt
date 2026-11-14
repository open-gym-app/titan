package com.titan.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Data seeded into a database at one schema version, plus the assertions that
 * must still hold once [TitanDatabase.MIGRATIONS] has carried it up to the
 * current version. Both halves speak raw SQL on purpose: the entity classes
 * describe the *current* schema only, so a fixture written against them would
 * silently stop testing the version it was written for.
 */
internal class MigrationFixture(
    val seed: (SupportSQLiteDatabase) -> Unit,
    val assertPreserved: (SupportSQLiteDatabase) -> Unit,
)

/**
 * Version 1, the schema v1.0 shipped with, carried to the version that adds `gym`.
 *
 * The 1 to 2 migration creates one table and touches no other, so what there is to lose is
 * whatever it should have left alone: a logged session and a body measurement stand in for
 * the user's history. The new table has to arrive empty and take a row.
 */
private val V1_FIXTURE = MigrationFixture(
    seed = { db ->
        db.execSQL(
            "INSERT INTO workout_session " +
                "(id, template_id, name, completed_at, is_in_progress, last_active_at, notes, created_at, updated_at) " +
                "VALUES (7, NULL, 'Push day', 1700000003600000, 0, 1700000003600000, 'felt strong', " +
                "1700000000000000, 1700000003600000)",
        )
        db.execSQL(
            "INSERT INTO body_measurement " +
                "(id, weight, chest, waist, arm, thigh, body_fat, notes, created_at, updated_at) " +
                "VALUES (3, 82500, NULL, 850, NULL, NULL, 152, NULL, 1700000000000000, 1700000000000000)",
        )
    },
    assertPreserved = { db ->
        db.query("SELECT name, notes, is_in_progress, completed_at FROM workout_session WHERE id = 7").use { row ->
            assertTrue("the v1 session did not survive the upgrade", row.moveToFirst())
            assertEquals("Push day", row.getString(0))
            assertEquals("felt strong", row.getString(1))
            assertEquals(0, row.getInt(2))
            assertEquals(1_700_000_003_600_000, row.getLong(3))
        }
        db.query("SELECT weight, waist, body_fat FROM body_measurement WHERE id = 3").use { row ->
            assertTrue("the v1 body measurement did not survive the upgrade", row.moveToFirst())
            assertEquals(82_500, row.getInt(0))
            assertEquals(850, row.getInt(1))
            assertEquals(152, row.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM gym").use { row ->
            row.moveToFirst()
            assertEquals("a v1 install has no gyms to arrive with", 0, row.getInt(0))
        }
        db.execSQL(
            "INSERT INTO gym (name, address, latitude_micro, longitude_micro, equipment_tags, created_at) " +
                "VALUES ('Iron Works', '12 Forge Street', 51507400, -127800, '', 1700000000000000)",
        )
        // The pin is optional: a gym is a name and an address.
        db.execSQL(
            "INSERT INTO gym (name, address, equipment_tags, created_at) " +
                "VALUES ('Garage', '3 Home Lane', '', 1700000000000000)",
        )
    },
)

/**
 * One fixture per released schema version below the current one. Adding a
 * `Migration` without adding the fixture for the version it starts from fails
 * `every migration preserves the data seeded at its start version`, so no
 * version bump can reach main without a data-loss test behind it.
 */
internal val MIGRATION_FIXTURES: Map<Int, MigrationFixture> = mapOf(1 to V1_FIXTURE)

/**
 * Guards the migration contract for [TitanDatabase] without naming any
 * specific version, so the guarantees keep holding as versions are added.
 *
 * Every schema version Room has ever exported to `core/data/schemas/` is
 * replayed: created from its own JSON spec, migrated to the current version,
 * and validated against the current spec. On top of that the suite pins the
 * three properties that make a future migration safe to write:
 *
 *  1. Each version from 1 to the current one has its schema JSON committed, so
 *     there is always a trustworthy starting point to migrate *from*.
 *  2. [TitanDatabase.MIGRATIONS] forms an unbroken path from version 1 to the
 *     current version — no gap that only shows up on a user's device.
 *  3. Opening a database whose on-disk version has no registered migration
 *     throws instead of destroying data, since [TitanDatabase] is never built
 *     with `fallbackToDestructiveMigration`.
 *
 * See PERSISTENCE.md for the checklist a schema change has to follow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TitanDatabase::class.java,
    )

    /**
     * The version Room compiled the current entities against. `@Database` has
     * binary retention so it cannot be read reflectively; opening a throwaway
     * in-memory database and reading its `user_version` asks Room itself.
     */
    private val currentVersion: Int by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TitanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(TitanDatabase.singleRowProgressCallback)
            .build()
        try {
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }
    }

    @Test
    fun `every schema version up to the current one is exported`() {
        assertEquals(
            "Schema JSON is missing for some versions — run a build so KSP exports it, and commit it",
            (1..currentVersion).toList(),
            exportedSchemaVersions(),
        )
    }

    @Test
    fun `the current schema matches its exported spec`() {
        // Throws if the entities Room sees today have drifted from schemas/<current>.json
        helper.createDatabase(TEST_DB, currentVersion).close()
    }

    @Test
    fun `migrations form an unbroken path from version 1 to the current version`() {
        val reachable = mutableSetOf(1)
        do {
            val grew = TitanDatabase.MIGRATIONS
                .filter { it.startVersion in reachable }
                .any { reachable.add(it.endVersion) }
        } while (grew)

        assertTrue(
            "No chain of MIGRATIONS reaches version $currentVersion; reachable from 1: " +
                "${reachable.sorted()}. An install on an unreachable version fails to open.",
            currentVersion in reachable,
        )
    }

    @Suppress("SpreadOperator")
    @Test
    fun `every exported version migrates up to the current schema`() {
        olderExportedVersions().forEach { version ->
            helper.createDatabase(TEST_DB, version).close()
            // Validates the migrated database against schemas/<current>.json,
            // including tables a migration should have dropped.
            helper.runMigrationsAndValidate(
                TEST_DB,
                currentVersion,
                true,
                *TitanDatabase.MIGRATIONS,
            ).close()
        }
    }

    @Suppress("SpreadOperator")
    @Test
    fun `every migration preserves the data seeded at its start version`() {
        olderExportedVersions().forEach { version ->
            val fixture = requireNotNull(MIGRATION_FIXTURES[version]) {
                "Version $version has no MigrationFixture. Add one to MIGRATION_FIXTURES that " +
                    "seeds the rows a v$version install would hold and asserts they survive the " +
                    "upgrade to v$currentVersion."
            }
            helper.createDatabase(TEST_DB, version).use(fixture.seed)
            helper.runMigrationsAndValidate(
                TEST_DB,
                currentVersion,
                true,
                *TitanDatabase.MIGRATIONS,
            ).use(fixture.assertPreserved)
        }
    }

    @Test
    fun `opening a database at an unmigrated version fails instead of wiping it`() {
        helper.createDatabase(TEST_DB, currentVersion).apply {
            version = currentVersion + 100
            close()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThrows(IllegalStateException::class.java) {
            Room.databaseBuilder(context, TitanDatabase::class.java, TEST_DB)
                .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
                .build()
                .openHelper
                .writableDatabase
        }
    }

    /** Versions with a committed schema JSON, oldest first. */
    private fun exportedSchemaVersions(): List<Int> {
        val schemaDir = requireNotNull(TitanDatabase::class.java.canonicalName)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return (assets.list(schemaDir) ?: emptyArray())
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .sorted()
    }

    private fun olderExportedVersions(): List<Int> = exportedSchemaVersions().filter { it < currentVersion }
}
