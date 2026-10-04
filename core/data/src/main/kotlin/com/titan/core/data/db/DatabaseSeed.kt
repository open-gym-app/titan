package com.titan.core.data.db

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.titan.core.data.db.projection.SeededExerciseKeyRow
import com.titan.core.data.mapper.TemplateExerciseMapper

/**
 * Fills a freshly created database with what the app ships: the bundled exercise
 * catalogue — the muscle group taxonomy, the system tags, and every exercise with its
 * muscle and tag links — and then the default templates built over it.
 *
 * The catalogue is not written by hand - [ASSET_PATH] is generated from the
 * public-domain `yuhonas/free-exercise-db` dataset by
 * `tools/seed/generate_seed.py` (`make seed`), so refreshing it is a
 * regeneration rather than an edit. See PERSISTENCE.md for the field mapping.
 *
 * The asset's format is deliberately trivial - every line is blank, a `--`
 * comment, or one complete statement - so replaying it needs no SQL parser.
 * Each statement is `INSERT OR IGNORE`, which the unique indexes on
 * `exercise(name, equipment_type, machine_brand)`, `muscle_group(name)` and
 * `tag(name)` turn into idempotency: a replay over a populated database is a
 * no-op rather than a duplicate.
 *
 * The default templates are the second step, and they are **not** idempotent — see
 * [insertDefaultTemplates].
 */
internal object DatabaseSeed {

    const val ASSET_PATH = "seed/exercises.sql"

    private const val LOG_TAG = "DatabaseSeed"

    /**
     * Seeds the catalogue, then the default templates over it, in one transaction.
     *
     * Runs exactly where a database starts out empty: `onCreate`, and after `clearAllTables`
     * in a wipe. Nowhere else — the template step would duplicate on any database that
     * already has them.
     */
    fun insertInto(
        db: SupportSQLiteDatabase,
        context: Context,
    ) {
        db.beginTransaction()
        try {
            replayCatalogue(db, context.assets)
            insertDefaultTemplates(db, context.resources)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Writes every [DefaultTemplates] entry as an ordinary template, named in [resources]'
     * current language.
     *
     * Through the support database rather than a DAO, because `onCreate` runs before Room
     * has a DAO to hand out; the rows are numbered by [TemplateExerciseMapper], the same code
     * the template repository writes them with.
     *
     * **Not idempotent, and it must not be.** Nothing in the schema makes a template name
     * unique, so a second run over the same database writes every default twice. That is why
     * it runs only from [insertInto], and never on open: a default the user deleted stays
     * deleted.
     *
     * A template whose exercises do not all resolve is **skipped** with a log line rather than
     * failing the seed. An unresolvable reference is a build mistake — `DatabaseSeedTest` fails
     * on one — and a build mistake must not stop the app from opening for the first time.
     *
     * The names are read once, here. Changing the phone's language later renames nothing:
     * that would be a rename, and renames are the user's.
     */
    fun insertDefaultTemplates(
        db: SupportSQLiteDatabase,
        resources: Resources,
    ) {
        val mapper = TemplateExerciseMapper()
        val resolve = DefaultTemplates.resolverOver(db.seededExercises(DefaultTemplates.exerciseNames))
        for (spec in DefaultTemplates.all) {
            val drafts = spec.toDrafts(resolve)
            if (drafts == null) {
                Log.w(LOG_TAG, "Skipped a default template: an exercise it names is not in the catalogue")
                continue
            }
            val templateId = db.insert(
                "workout_template",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("name", resources.getString(spec.nameRes))
                    put("category", spec.category.name)
                },
            )
            for (row in mapper.toEntities(templateId, drafts)) {
                val exercise = row.exercise
                val templateExerciseId = db.insert(
                    "template_exercise",
                    SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("template_id", exercise.templateId)
                        put("exercise_id", exercise.exerciseId)
                        put("position", exercise.position)
                        put("superset_group", exercise.supersetGroup)
                        put("rest_duration", exercise.restDuration)
                        put("notes", exercise.notes)
                    },
                )
                for (set in row.sets) {
                    db.insert(
                        "template_set",
                        SQLiteDatabase.CONFLICT_ABORT,
                        ContentValues().apply {
                            put("template_exercise_id", templateExerciseId)
                            put("set_number", set.setNumber)
                            put("set_type", set.setType)
                            put("reps", set.reps)
                            put("weight", set.weight)
                            put("duration", set.duration)
                            put("distance", set.distance)
                        },
                    )
                }
            }
        }
    }

    /**
     * The seeded exercises called any of [names], in one read rather than one per reference —
     * the same rows, by the same predicate, that `ExerciseDao.getSeededByName` returns to the
     * restore row, so the seed and a restore resolve a default to the same exercise.
     *
     * Seeded rows only: the unique index on `(name, equipment_type, machine_brand)` does not
     * stop a custom exercise sharing a name and an equipment type with a seeded one when
     * neither has a brand, because SQLite treats two NULLs as distinct.
     */
    private fun SupportSQLiteDatabase.seededExercises(names: Collection<String>): List<SeededExerciseKeyRow> {
        val placeholders = names.joinToString(separator = ",") { "?" }
        val sql = "SELECT id, name, equipment_type FROM exercise " +
            "WHERE name IN ($placeholders) AND machine_brand IS NULL AND is_custom = 0"
        return query(sql, names.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(SeededExerciseKeyRow(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
    }

    private fun replayCatalogue(
        db: SupportSQLiteDatabase,
        assets: AssetManager,
    ) {
        // A partially applied catalogue would leave exercises without their
        // muscle links, so the whole batch commits or none of it does. Room
        // already runs onCreate inside a transaction; SQLite nests this one as
        // a savepoint.
        db.beginTransaction()
        try {
            assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("--") }
                    .forEach(db::execSQL)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
