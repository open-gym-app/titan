package com.opengym.core.data.db

import android.content.res.AssetManager
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Replays the bundled exercise catalogue into a freshly created database: the
 * muscle group taxonomy, the system tags, and every exercise with its muscle
 * and tag links.
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
 */
internal object DatabaseSeed {

    const val ASSET_PATH = "seed/exercises.sql"

    fun insertInto(db: SupportSQLiteDatabase, assets: AssetManager) {
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
