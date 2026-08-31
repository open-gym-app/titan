package com.opengym.core.data.db

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
 * must still hold once [OpenGymDatabase.MIGRATIONS] has carried it up to the
 * current version. Both halves speak raw SQL on purpose: the entity classes
 * describe the *current* schema only, so a fixture written against them would
 * silently stop testing the version it was written for.
 */
internal class MigrationFixture(
    val seed: (SupportSQLiteDatabase) -> Unit,
    val assertPreserved: (SupportSQLiteDatabase) -> Unit,
)

/**
 * One fixture per released schema version below the current one. Adding a
 * `Migration` without adding the fixture for the version it starts from fails
 * `every migration preserves the data seeded at its start version`, so no
 * version bump can reach main without a data-loss test behind it.
 *
 * Empty today because version 1 is the current version: there is nothing below
 * it to upgrade from.
 */
internal val MIGRATION_FIXTURES: Map<Int, MigrationFixture> = emptyMap()

/**
 * Guards the migration contract for [OpenGymDatabase] without naming any
 * specific version, so the guarantees keep holding as versions are added.
 *
 * Every schema version Room has ever exported to `core/data/schemas/` is
 * replayed: created from its own JSON spec, migrated to the current version,
 * and validated against the current spec. On top of that the suite pins the
 * three properties that make a future migration safe to write:
 *
 *  1. Each version from 1 to the current one has its schema JSON committed, so
 *     there is always a trustworthy starting point to migrate *from*.
 *  2. [OpenGymDatabase.MIGRATIONS] forms an unbroken path from version 1 to the
 *     current version — no gap that only shows up on a user's device.
 *  3. Opening a database whose on-disk version has no registered migration
 *     throws instead of destroying data, since [OpenGymDatabase] is never built
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
        OpenGymDatabase::class.java,
    )

    /**
     * The version Room compiled the current entities against. `@Database` has
     * binary retention so it cannot be read reflectively; opening a throwaway
     * in-memory database and reading its `user_version` asks Room itself.
     */
    private val currentVersion: Int by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OpenGymDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(OpenGymDatabase.singleRowProgressCallback)
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
            val grew = OpenGymDatabase.MIGRATIONS
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
                *OpenGymDatabase.MIGRATIONS,
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
                *OpenGymDatabase.MIGRATIONS,
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
            Room.databaseBuilder(context, OpenGymDatabase::class.java, TEST_DB)
                .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
                .build()
                .openHelper
                .writableDatabase
        }
    }

    /** Versions with a committed schema JSON, oldest first. */
    private fun exportedSchemaVersions(): List<Int> {
        val schemaDir = requireNotNull(OpenGymDatabase::class.java.canonicalName)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return (assets.list(schemaDir) ?: emptyArray())
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .sorted()
    }

    private fun olderExportedVersions(): List<Int> = exportedSchemaVersions().filter { it < currentVersion }
}
