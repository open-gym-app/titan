package com.titan.core.data.db

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

    /**
     * The `SPEED` and `SWIM` tags and their links, matched by name rather than id — see
     * [backfillPaceTags]. Generated alongside [ASSET_PATH], from the same rule, in the same
     * line-per-statement format.
     */
    const val PACE_TAGS_ASSET_PATH = "seed/pace_tags.sql"

    fun insertInto(
        db: SupportSQLiteDatabase,
        assets: AssetManager,
    ) {
        replay(db, assets, ASSET_PATH)
    }

    /**
     * Gives a catalogue seeded before the pace tags existed the `SPEED` and `SWIM` rows
     * [insertInto] would have written, so a ride's best reads as a speed rather than a pace per
     * kilometre.
     *
     * [insertInto] only runs when a database is created, so it cannot reach a database that was
     * created before those tags were part of the seed — or one that a restore has just filled
     * from a backup taken on such a database. This runs on every open and at the end of every
     * restore instead. It matches each tag by its unique name and each exercise by its seeded
     * name, never by id, because an older database numbered its tags itself; custom exercises
     * are left alone, as the seed leaves them. Every statement is `INSERT OR IGNORE`, so over a
     * database that already has the rows this changes nothing.
     */
    fun backfillPaceTags(
        db: SupportSQLiteDatabase,
        assets: AssetManager,
    ) {
        replay(db, assets, PACE_TAGS_ASSET_PATH)
    }

    private fun replay(
        db: SupportSQLiteDatabase,
        assets: AssetManager,
        path: String,
    ) {
        // A partially applied asset would leave exercises without their
        // links, so each batch commits or none of it does. Room already runs
        // onCreate - and a restore - inside a transaction; SQLite nests this
        // one as a savepoint.
        db.beginTransaction()
        try {
            assets.open(path).bufferedReader().useLines { lines ->
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
