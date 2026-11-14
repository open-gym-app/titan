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
 * the eight variants below exhaustive rather than merely typical. A `when` over them in a
 * ViewModel is a `when` over every message the screen will ever need.
 *
 * ### What each one leaves behind
 *
 * What a failure leaves behind is part of the contract, not an implementation detail.
 * [DocumentTooLarge],
 * [UnsupportedSchemaVersion] and [MalformedJson] are decided before anything is read from the
 * database at all; [ConstraintViolation] is decided by validating the document against itself
 * *before* the wipe, and is caught a second time inside the transaction as a backstop, where
 * the rollback is SQLite's. [IoFailure] is raised by reading the file, which happens before all
 * of those, or by a device that cannot take the transaction's writes, where the rollback is
 * again SQLite's. Every one of them leaves the database untouched — and the rest of the device
 * too, except for a [ConstraintViolation] or an [IoFailure] the transaction raises, which says
 * so.
 *
 * The other three are the outcomes that leave *something* changed, and each is its own variant
 * so a screen cannot fold it into a clean refusal. [LiveStateNotCleared] stopped before the
 * transaction with the rows untouched, but after the live session may already have been ended.
 * [SettingsNotRestored] and [RemindersNotRescheduled] come after the commit: the rows are in,
 * and the step that follows them did not land.
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
     * refuse or the app could not work with: a foreign key pointing at a row the file does not
     * contain, two rows sharing a primary key, a `user_progress` that is not the single row the
     * table's `CHECK` allows, a value outside the ranges the format defines, more than one
     * session in progress.
     *
     * Detected by validating the document against itself before the wipe, which is possible
     * *because* import replaces rather than merges: the file is the whole database afterwards,
     * so every foreign key in it must resolve inside it. The same failure is caught again from
     * SQLite inside the transaction, so a constraint the validator does not model still arrives
     * here rather than as an untyped exception.
     *
     * The rows are untouched either way. The live session and the two drafts are not always:
     * they are cleared before the transaction opens, so when it is SQLite that refuses, the
     * workout that was open has already been ended. [liveStateCleared] says which happened, so a
     * screen does not promise "nothing was changed" over a workout it closed.
     *
     * @property reason which rule broke and where — "workout_set.session_exercise_id 42 names a
     *   session_exercise the document does not contain". Written to be pasted into an issue.
     * @property liveStateCleared true when the transaction refused it, after the live session
     *   and the drafts were cleared; false when the validator did, before anything was touched.
     */
    data class ConstraintViolation(
        val reason: String,
        val liveStateCleared: Boolean = false,
    ) : ImportError

    /**
     * The file is larger than any backup this build could hold in memory, and was not read past
     * the limit.
     *
     * Decided while the bytes are still being counted, before a single row is decoded, so the
     * cost of refusing a hostile file is the read up to the limit rather than the heap it would
     * have filled.
     *
     * @property limitBytes the largest document this build reads —
     *   `ExportFormat.MAX_DOCUMENT_BYTES`, for a screen to name.
     */
    data class DocumentTooLarge(val limitBytes: Long) : ImportError

    /**
     * The document could not be read, or the device could not take the rows it holds.
     *
     * The document is read first of all, through an `ImportSource` the use case opens itself,
     * so a revoked content URI or a removed SD card fails before anything is touched. A device
     * that runs out of space or hits a disk error mid-transaction fails later: SQLite rolls
     * the rows back, but the live session and the two drafts were cleared before the
     * transaction opened, as for a [ConstraintViolation] SQLite decides. [liveStateCleared]
     * says which happened, so a screen does not promise "nothing was changed" over a workout
     * it closed.
     *
     * @property reason the underlying message, for a log.
     * @property liveStateCleared true when the transaction failed, after the live session and
     *   the drafts were cleared; false when the file could not be read, before anything was
     *   touched.
     */
    data class IoFailure(
        val reason: String,
        val liveStateCleared: Boolean = false,
    ) : ImportError

    /**
     * The live session or one of the two drafts could not be cleared, so the restore stopped
     * before the transaction opened.
     *
     * The rows and the settings are untouched, but this is not [IoFailure]'s "nothing was
     * changed": the session state is reset first and the drafts after it, so by the time one of
     * them fails the live workout may already have been ended and the workout draft discarded.
     * Losing a draft costs the user their place and never a logged set, which is why the order
     * is safe — and why the screen still has to say it happened.
     *
     * @property reason the underlying message, for a log.
     */
    data class LiveStateNotCleared(val reason: String) : ImportError

    /**
     * Every row in the document was restored, and the settings write that follows the rows did
     * not land.
     *
     * The one partial outcome, and a separate variant so a screen cannot report it as either a
     * clean restore or a refused one. The settings are restored **last**, deliberately and for
     * a reason [ExportDocument] gives, so a failure there leaves the rows committed and the
     * settings at whatever the store held when it failed — the defaults, if the reset before the
     * setters had already landed. That is the better half to lose: a user with their whole
     * history back and the wrong units is one setting away from where they wanted to be.
     *
     * @property rowsRestored the rows the transaction committed, which is the document's own
     *   row count.
     * @property reason the underlying message, for a log.
     */
    data class SettingsNotRestored(
        val rowsRestored: Long,
        val reason: String,
    ) : ImportError

    /**
     * Every row and every setting was restored, and the reminder alarms could not be brought
     * into line with them.
     *
     * Restoring the settings starts from a reset that switches the workout reminder off — the
     * reminder is device state and not in the document — so the alarms are re-synced last. When
     * only that fails, the settings are exactly the document's and the alarms are not:
     * [SettingsNotRestored] would tell the user something false about the half that worked. When
     * the settings failed too, that variant wins, because it is the one the user has to act on.
     *
     * @property rowsRestored the rows the transaction committed, which is the document's own
     *   row count.
     * @property reason the underlying message, for a log.
     */
    data class RemindersNotRescheduled(
        val rowsRestored: Long,
        val reason: String,
    ) : ImportError
}
