package com.opengym.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Reference data required by the schema (see docs "SEED DATA / ENUM VALUES"):
 * the 7 muscle groups used for body-map highlights and the 6 system tags.
 */
internal object DatabaseSeed {

    private val MUSCLE_GROUPS =
        listOf("CHEST", "BACK", "SHOULDERS", "BICEPS", "TRICEPS", "LEGS", "CORE")

    private val SYSTEM_TAGS =
        listOf("PUSH", "PULL", "STATIC", "CARRY", "COMPOUND", "ISOLATION")

    fun insertInto(db: SupportSQLiteDatabase) {
        MUSCLE_GROUPS.forEach { name ->
            db.execSQL("INSERT OR IGNORE INTO muscle_group (name) VALUES (?)", arrayOf(name))
        }
        SYSTEM_TAGS.forEach { name ->
            db.execSQL("INSERT OR IGNORE INTO tag (name, is_system) VALUES (?, 1)", arrayOf(name))
        }
    }
}
