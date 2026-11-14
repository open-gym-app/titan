package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.BackupTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The insert order is a schema fact, and this is what holds it to one.
 *
 * Foreign keys stay enforced for the whole of a restore — the importer sets no
 * `defer_foreign_keys` — so a child table inserted before its parent is refused by SQLite. The
 * order that makes that work is [BackupTable]'s declaration order, and nothing about the enum
 * itself says so: it is a list of twenty constants that reads as if it could be alphabetised.
 * These tests are the reason it cannot be.
 */
class BackupTableTest {

    @Test
    fun `every parent is declared before the child that points at it`() {
        val position = BackupTable.entries.withIndex().associate { (index, table) -> table to index }

        FOREIGN_KEYS.forEach { (child, parent) ->
            assertTrue(
                "${child.tableName} points at ${parent.tableName}, so ${parent.tableName} must be inserted first",
                position.getValue(parent) < position.getValue(child),
            )
        }
    }

    /** The wipe runs the other way, so every child is emptied before the parent it would restrict. */
    @Test
    fun `every child is deleted before the parent that would restrict it`() {
        val position = BackupTable.DELETE_ORDER.withIndex().associate { (index, table) -> table to index }

        FOREIGN_KEYS.forEach { (child, parent) ->
            assertTrue(
                "${child.tableName} points at ${parent.tableName}, so ${child.tableName} must be deleted first",
                position.getValue(child) < position.getValue(parent),
            )
        }
    }

    @Test
    fun `the delete order is the insert order reversed, and covers every table`() {
        assertEquals(BackupTable.entries.reversed(), BackupTable.DELETE_ORDER)
        assertEquals(BackupTable.entries.size, BackupTable.DELETE_ORDER.toSet().size)
    }

    /** The name is both the SQLite table and the JSON key, so a duplicate would silently merge two tables. */
    @Test
    fun `no two tables share a name`() {
        assertEquals(BackupTable.entries.size, BackupTable.entries.map { it.tableName }.toSet().size)
    }

    @Test
    fun `every table name is a lower snake case identifier`() {
        BackupTable.entries.forEach { table ->
            assertTrue("${table.tableName} is not a plain identifier", table.tableName.matches(IDENTIFIER))
        }
    }

    /** The constant's name is the table's name shouted, which is what makes the enum readable next to the schema. */
    @Test
    fun `every constant is named after its table`() {
        BackupTable.entries.forEach { table ->
            assertEquals(table.name, table.tableName.uppercase())
        }
    }

    private companion object {

        val IDENTIFIER = Regex("[a-z]+(_[a-z0-9]+)*")

        /**
         * Every foreign key docs/PERSISTENCE.md's referential-actions table lists, as
         * child-to-parent pairs.
         *
         * Restated here rather than read off [BackupValidator], deliberately: a test that
         * derived the edges from the code it checks would pass for a validator that had lost
         * one. This list is the schema, transcribed by hand, and `ExportSchemaTest` in
         * `:core:data` is what holds *it* to the exported `1.json`.
         */
        val FOREIGN_KEYS = listOf(
            BackupTable.EXERCISE_MUSCLE_GROUP to BackupTable.EXERCISE,
            BackupTable.EXERCISE_MUSCLE_GROUP to BackupTable.MUSCLE_GROUP,
            BackupTable.EXERCISE_TAG to BackupTable.EXERCISE,
            BackupTable.EXERCISE_TAG to BackupTable.TAG,
            BackupTable.TEMPLATE_EXERCISE to BackupTable.WORKOUT_TEMPLATE,
            BackupTable.TEMPLATE_EXERCISE to BackupTable.EXERCISE,
            BackupTable.TEMPLATE_SET to BackupTable.TEMPLATE_EXERCISE,
            BackupTable.WORKOUT_SESSION to BackupTable.WORKOUT_TEMPLATE,
            BackupTable.SESSION_EXERCISE to BackupTable.WORKOUT_SESSION,
            BackupTable.SESSION_EXERCISE to BackupTable.EXERCISE,
            BackupTable.SESSION_EXERCISE to BackupTable.TEMPLATE_EXERCISE,
            BackupTable.WORKOUT_SET to BackupTable.SESSION_EXERCISE,
            BackupTable.PERSONAL_RECORD to BackupTable.EXERCISE,
            BackupTable.PERSONAL_RECORD to BackupTable.WORKOUT_SET,
            BackupTable.SESSION_XP_CLAIM to BackupTable.WORKOUT_SESSION,
            BackupTable.SESSION_LOOT_CLAIM to BackupTable.WORKOUT_SESSION,
        )
    }
}
