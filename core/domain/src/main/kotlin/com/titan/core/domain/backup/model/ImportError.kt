package com.titan.core.domain.backup.model

/**
 * Every way an import can refuse a document, as a closed set.
 *
 * ### Why these are values and not exceptions
 *
 * An import is a user action over a file the user chose, so every failure here is something a
 * screen has to *say* — "this backup was written by a newer version of Titan", not a stack
 * trace. `ImportUseCase` therefore emits [ImportProgress.Failed] carrying one of these and
 * completes normally; nothing it calls is allowed to let an exception past, which is what makes
 * the four variants below exhaustive rather than merely typical. A `when` over them in a
 * ViewModel is a `when` over every message the screen will ever need.
 *
 * ### Every one of them leaves the database untouched
 *
 * That is the contract, not an implementation detail. [UnsupportedSchemaVersion] and
 * [MalformedJson] are decided before anything is read from the database at all;
 * [ConstraintViolation] is decided by validating the document's own referential integrity
 * *before* the wipe, and is caught a second time inside the transaction as a backstop, where
 * the rollback is SQLite's. [IoFailure] is raised either by reading the file, which happens
 * before all of those, or by the settings write, which happens after the rows are committed and
 * is the one step whose failure leaves a partial result — see that variant.
 */
sealed interface ImportError {

    /**
     * The document is not JSON, or is not JSON shaped like an [ExportDocument] — a missing
     * required column, a string where a number belongs, a truncated file.
     *
     * @property reason the parser's own message, for a bug report. It names a path and an
     *   offset and is not fit to show a user as-is; a screen shows its own sentence and keeps
     *   this for the log.
     */
    data class MalformedJson(val reason: String) : ImportError

    /**
     * The document parses, and says it was written at a schema version this build does not read.
     *
     * Refused in *both* directions, older included, and that is the compatibility rule rather
     * than an oversight: a v1 reader handed a v2 file cannot know which columns moved, and a v2
     * reader handed a v1 file would have to invent the values v1 never stored. Either is a
     * silent, permanent corruption of the only copy of somebody's training history. When a
     * version does become readable, it is because an up-converter was written for it and listed
     * in docs/export-schema.md's version-history table.
     *
     * @property found what the document claims.
     * @property supported what this build reads — [ExportDocument.SCHEMA_VERSION].
     */
    data class UnsupportedSchemaVersion(
        val found: Int,
        val supported: Int,
    ) : ImportError

    /**
     * The document parses at a version this build reads, and describes a database SQLite would
     * refuse: a foreign key pointing at a row the file does not contain, two rows sharing a
     * primary key, a `user_progress` that is not the single row the table's `CHECK` allows.
     *
     * Detected by validating the document against itself before the wipe, which is possible
     * *because* import replaces rather than merges: the file is the whole database afterwards,
     * so every foreign key in it must resolve inside it. The same failure is caught again from
     * SQLite inside the transaction, so a constraint the validator does not model still arrives
     * here rather than as an untyped exception.
     *
     * @property reason which rule broke and where — "workout_set.session_exercise_id 42 names a
     *   session_exercise the document does not contain". Written to be pasted into an issue.
     */
    data class ConstraintViolation(val reason: String) : ImportError

    /**
     * A store other than the database could not be read or written — the document on the way
     * in, the in-progress draft, or the settings on the way out.
     *
     * The only error that *may* leave work done, and which of the three raised it decides
     * whether it did. The document is read first of all, through an `ImportSource` the use case
     * opens itself, so a revoked content URI or a removed SD card leaves the database exactly
     * as it was — as does a failure clearing the draft, which happens before the transaction.
     * The settings are restored **last**, deliberately and for a reason [ExportDocument] gives,
     * so a failure *there* leaves the rows committed and the settings not — which is the better
     * half to lose, since a user with their whole history back and the wrong units is one
     * setting away from where they wanted to be. A screen must say so either way rather than
     * report a clean restore.
     *
     * @property reason the underlying message, for a log.
     */
    data class IoFailure(val reason: String) : ImportError
}
