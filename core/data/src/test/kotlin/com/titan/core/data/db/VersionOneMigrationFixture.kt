package com.titan.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

/**
 * A version 1 install with a row in each kind of table the Titans milestone sits beside: the
 * catalogue, a finished session and its claims, the progress row, and the inventory an egg lives
 * in.
 *
 * "Preserved" is asserted on **every table the version 1 database had**, compared whole against
 * a dump taken before the upgrade, so a migration that touched a table it had no business in
 * fails here whichever table it was.
 */
internal fun versionOneFixture(): MigrationFixture {
    var before: Map<String, List<List<String?>>> = emptyMap()
    var columns: Map<String, List<String>> = emptyMap()
    return MigrationFixture(
        seed = { db ->
            SEED_STATEMENTS.forEach(db::execSQL)
            columns = db.userTables().associateWith { db.columnsOf(it) }
            before = columns.mapValues { (table, names) -> db.dump(table, names) }
        },
        assertPreserved = { db ->
            assertTrue("the fixture seeded nothing", before.values.any { it.isNotEmpty() })
            before.forEach { (table, rows) ->
                assertEquals("$table changed across the upgrade", rows, db.dump(table, columns.getValue(table)))
            }
            // The one column a version 1 table gained, null on every row it already held.
            assertEquals(
                listOf(listOf<String?>(null), listOf<String?>(null)),
                db.dump("collected_item", listOf("session_id")),
            )

            // The new table is there, empty, and holds its two rules: an egg that exists, once.
            assertEquals(emptyList<List<String?>>(), db.dump("pet"))
            assertEquals(emptyList<List<String?>>(), db.dump("session_pet_claim"))
            assertEquals(emptyList<List<String?>>(), db.dump("pet_state"))
            assertEquals(emptyList<List<String?>>(), db.dump("pet_feed"))
            assertEquals(emptyList<List<String?>>(), db.dump("food_seen"))
            assertEquals(emptyList<List<String?>>(), db.dump("pet_unlock"))
            assertEquals(emptyList<List<String?>>(), db.dump("pet_loadout"))
            assertEquals(emptyList<List<String?>>(), db.dump("pet_power_snapshot"))
            assertEquals(emptyList<List<String?>>(), db.dump("pet_spar"))
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(PET_INSERT, arrayOf<Any>(1))
            db.execSQL(
                "INSERT INTO pet_state (pet_id, fuel, energy, spirit, activity, activity_started_at, " +
                    "activity_ends_at, resume_activity, updated_at) VALUES (1, 80, 100, 60, 'idle', 3000, NULL, NULL, 3000)",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO pet_state (pet_id, fuel, energy, spirit, activity, activity_started_at, " +
                        "activity_ends_at, resume_activity, updated_at) VALUES (404, 80, 100, 60, 'idle', 0, NULL, NULL, 0)",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) { db.execSQL(PET_INSERT, arrayOf<Any>(1)) }
            assertThrows(SQLiteConstraintException::class.java) { db.execSQL(PET_INSERT, arrayOf<Any>(99)) }

            // The pet claim fences a session that exists, and goes with it.
            db.execSQL("INSERT INTO session_pet_claim (session_id) VALUES (1)")
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO session_pet_claim (session_id) VALUES (404)")
            }
            db.execSQL("DELETE FROM session_xp_claim")
            db.execSQL("DELETE FROM session_loot_claim")
            db.execSQL("DELETE FROM workout_session WHERE id = 1")
            assertEquals(emptyList<List<String?>>(), db.dump("session_pet_claim"))
        },
    )
}

private val SEED_STATEMENTS = listOf(
    "INSERT INTO muscle_group (id, name, icon) VALUES (1, 'CHEST', NULL)",
    "INSERT INTO tag (id, name, color, is_system) VALUES (1, 'PUSH', '#ff0000', 1)",
    "INSERT INTO workout_session " +
        "(id, template_id, name, completed_at, is_in_progress, last_active_at, notes, created_at, updated_at) " +
        "VALUES (1, NULL, 'Push day', 2000, 0, 2000, NULL, 1000, 2000)",
    "INSERT INTO session_xp_claim (session_id) VALUES (1)",
    "INSERT INTO session_loot_claim (session_id) VALUES (1)",
    "INSERT INTO user_progress " +
        "(id, total_xp, current_level, current_streak, longest_streak, last_counted_at, week_sessions, " +
        "week_goal_met, protection_remaining, protection_period, protection_consumed_at) " +
        "VALUES (1, 4200, 8, 3, 11, 2000, 2, 0, 1, '2026-09', NULL)",
    "INSERT INTO achievement (achievement_id, unlocked_at) VALUES ('first_workout', 2000)",
    "INSERT INTO quest_completion (quest_id, period_start, completed_at) VALUES ('weekly_volume', 1000, 2000)",
    "INSERT INTO collected_item (id, collectible_id, source, acquired_at) " +
        "VALUES (1, 'pet_egg', 'session_finished', 2000), (2, 'chalk_block', 'session_finished', 2000)",
)

private const val PET_INSERT =
    "INSERT INTO pet (species_id, hatched_from_item_id, hatched_at, xp, strength, endurance, mobility, is_active) " +
        "VALUES ('iron_cub', ?, 3000, 0, 0, 0, 0, 1)"

/** Every table the app owns in this database, by name. */
private fun SupportSQLiteDatabase.userTables(): List<String> =
    query(
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY name",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

/** The columns [table] has, in declaration order. */
private fun SupportSQLiteDatabase.columnsOf(table: String): List<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }

/** Every row of [table], in rowid order, each as the values of [columns] (all of them by default). */
private fun SupportSQLiteDatabase.dump(
    table: String,
    columns: List<String>? = null,
): List<List<String?>> =
    query("SELECT ${columns?.joinToString { "`$it`" } ?: "*"} FROM `$table` ORDER BY rowid").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
            }
        }
    }
