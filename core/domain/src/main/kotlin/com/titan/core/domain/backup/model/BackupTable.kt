package com.titan.core.domain.backup.model

/**
 * Every table an export carries, in the order a restore has to write them.
 *
 * ### The order is the schema's, and it is load-bearing
 *
 * `PRAGMA foreign_keys = ON` is applied by `TitanDatabase.foreignKeysCallback` and stays on
 * for the restore, so a child row inserted before its parent is refused by SQLite rather than
 * quietly orphaned. Declaration order here is therefore the dependency order
 * docs/PERSISTENCE.md sets out — reference data, then the exercise library, then templates,
 * then history, then everything that hangs off history — and `entries` is what the importer
 * iterates. Reordering two entries is a schema decision, not a tidy-up; `BackupTableTest`
 * pins the order against the referential actions table so that it cannot be done by accident.
 *
 * The three tables that point at nothing — `user_progress`, `achievement` and
 * `collected_item` — could go anywhere and are placed last, beside `quest_completion`, so the
 * gamification group reads together. `gym` points at nothing either, and follows them.
 *
 * ### The name is the format
 *
 * [tableName] is both the SQLite table and the top-level JSON key its rows are written under,
 * and the two are deliberately the same string: a reader with the export in one hand and
 * `core/data/schemas/…/1.json` in the other should not have to translate. `ExportSchemaTest`
 * asserts that this enum lists exactly the tables `TitanDatabase` declares, so a table added
 * to the schema and not to an export fails a test rather than silently dropping out of every
 * backup taken afterwards.
 */
enum class BackupTable(val tableName: String) {

    /** Reference data. Seeded on a fresh install, but exported so a restore does not depend on the seed. */
    MUSCLE_GROUP("muscle_group"),

    /** Reference data, and the user's own tags with it. */
    TAG("tag"),

    /** The catalogue: seeded exercises and the ones the user typed in. */
    EXERCISE("exercise"),

    /** Needs `exercise` and `muscle_group`. */
    EXERCISE_MUSCLE_GROUP("exercise_muscle_group"),

    /** Needs `exercise` and `tag`. */
    EXERCISE_TAG("exercise_tag"),

    WORKOUT_TEMPLATE("workout_template"),

    /** Needs `workout_template` and `exercise`. */
    TEMPLATE_EXERCISE("template_exercise"),

    /** Needs `template_exercise`. */
    TEMPLATE_SET("template_set"),

    /** Needs `workout_template`, which it references with `ON DELETE SET NULL`. */
    WORKOUT_SESSION("workout_session"),

    /** Needs `workout_session`, `exercise` and `template_exercise`. */
    SESSION_EXERCISE("session_exercise"),

    /** Needs `session_exercise`. */
    WORKOUT_SET("workout_set"),

    /** Needs `exercise` and `workout_set`. Exported rather than re-derived — see [ExportDocument]. */
    PERSONAL_RECORD("personal_record"),

    /** Points at nothing. */
    BODY_MEASUREMENT("body_measurement"),

    /** Needs `workout_session`. The fence that stops a restored session being paid twice. */
    SESSION_XP_CLAIM("session_xp_claim"),

    /** Needs `workout_session`. The same fence over the drop-table roll. */
    SESSION_LOOT_CLAIM("session_loot_claim"),

    /** Points at nothing. Exactly one row, or none at all. */
    USER_PROGRESS("user_progress"),

    /** Points at nothing. */
    ACHIEVEMENT("achievement"),

    /** Points at nothing. */
    QUEST_COMPLETION("quest_completion"),

    /** Points at nothing. */
    COLLECTED_ITEM("collected_item"),

    /** Points at nothing. The user's saved gyms. */
    GYM("gym"),
    ;

    companion object {

        /**
         * The order a wipe deletes in: [entries] reversed, so a child table is emptied before
         * the parent it points at.
         *
         * `PRAGMA defer_foreign_keys` would make the order irrelevant, and the importer
         * deliberately does *not* set it — see `BackupRepositoryImpl` and
         * docs/export-schema.md. Foreign keys stay enforced for the whole wipe, so this order
         * is the only thing keeping a `DELETE FROM` off a `RESTRICT`, and getting it wrong
         * fails a test rather than being covered for by a pragma.
         */
        val DELETE_ORDER: List<BackupTable> get() = entries.reversed()
    }
}
