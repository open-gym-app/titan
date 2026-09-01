package com.opengym.core.data.db.dao

/**
 * What SQLite's `INSERT OR IGNORE` reports when the row it was handed was already there.
 *
 * Room returns the inserted rowid from an `@Insert(onConflict = IGNORE)` method and -1 when
 * the insert was skipped, which is the whole mechanism behind the idempotent writes in this
 * package: the return value is the only thing that can tell "I wrote this" from "somebody
 * already had", and it says so atomically, where a read followed by an insert could be
 * interleaved between the two.
 */
const val NOT_INSERTED = -1L
