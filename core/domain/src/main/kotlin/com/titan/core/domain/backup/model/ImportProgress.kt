package com.titan.core.domain.backup.model

/**
 * How far an import has got, and how it ended.
 *
 * The counterpart to [ExportProgress] with one difference that matters: this stream carries its
 * own failure, as [Failed], instead of throwing. Everything an import can refuse is a sentence
 * a screen has to show the user — see [ImportError] — so a `Flow` that completed exceptionally
 * would hand the ViewModel a `Throwable` to translate and an easy way to get that wrong. A
 * collector that reaches the end of this flow has seen either [Complete] or [Failed], never
 * both and never neither.
 *
 * The phases are ordered and do not repeat: [Validating], then [Writing] per chunk, then one of
 * the two terminals. Nothing is written before [Validating] has passed, which is what makes
 * "refused" mean "the database is exactly as it was".
 */
sealed interface ImportProgress {

    /**
     * The document is being parsed and checked against itself — version, shape, then every
     * foreign key in it.
     *
     * Indeterminate on purpose. It is over in well under a second even on a 50,000-set file,
     * and the row count it would divide by is not known until the parse it is reporting on has
     * finished.
     */
    data object Validating : ImportProgress

    /**
     * Rows are being inserted, inside the transaction that the wipe also happened in.
     *
     * @property table which table the run is on, for a screen to name.
     * @property rowsWritten rows inserted so far, over every table.
     * @property rowsTotal rows the document holds — [ExportDocument.rowCount], known exactly
     *   because the whole document was parsed before this phase began.
     */
    data class Writing(
        val table: BackupTable,
        val rowsWritten: Long,
        val rowsTotal: Long,
    ) : ImportProgress

    /**
     * The transaction committed and the settings were applied. The database now holds the
     * document and nothing else.
     *
     * @property rowsWritten how many rows went in, which equals the document's own row count.
     */
    data class Complete(val rowsWritten: Long) : ImportProgress

    /**
     * The import was refused, or failed. See [ImportError] for what each case guarantees about
     * the state of the database — every one of them but [ImportError.IoFailure] leaves it
     * exactly as it was.
     */
    data class Failed(val error: ImportError) : ImportProgress
}
