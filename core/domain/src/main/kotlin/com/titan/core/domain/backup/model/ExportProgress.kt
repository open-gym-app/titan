package com.titan.core.domain.backup.model

/**
 * How far an export has got, as a *determinate* figure.
 *
 * Both fields are rows rather than tables or bytes, and both are known before the first row is
 * written: the exporter counts every table up front so a 50,000-set history draws a bar that
 * fills evenly instead of one that sits at 5/19 for the length of the workout history. Counting
 * costs nineteen `COUNT(*)`s over indexed tables — cheap next to reading the rows themselves,
 * and the alternative is an indeterminate spinner on the one operation a user will not
 * interrupt.
 *
 * [rowsTotal] is the up-front count for every [Writing] and is never revised while the run is
 * in flight. Nothing writes to the database while an export reads it in the app's own use (#38
 * blocks the screen), and a row that arrived after the count would only mean the bar finishes a
 * little past its end — which is a better failure than a total that jumps backwards
 * mid-progress. [Complete] is the one emission that restates it, as the number of rows that
 * actually went into the file; see that variant.
 */
sealed interface ExportProgress {

    /** Rows written so far, over every table. */
    val rowsWritten: Long

    /** Rows the export will write in total, counted before the first was read. */
    val rowsTotal: Long

    /**
     * A table is being written. Emitted once per chunk rather than once per row: a per-row
     * emission on a 50,000-set history is 50,000 conflations for a bar that moves in pixels.
     *
     * @property table the table [rowsWritten] most recently advanced through. It is emitted so a
     *   screen can name what is happening ("workout sets…"), not so anything can branch on it.
     */
    data class Writing(
        val table: BackupTable,
        override val rowsWritten: Long,
        override val rowsTotal: Long,
    ) : ExportProgress

    /**
     * Every table has been written and the document is closed.
     *
     * The terminal emission, and the only one that says the file is complete — a collector that
     * stopped at the last [Writing] would be looking at a truncated document. [rowsWritten] and
     * [rowsTotal] are equal here by construction, because both are what was *written* rather
     * than what was counted up front: the file's own row count is the honest figure to finish
     * on, and it differs from the earlier [Writing.rowsTotal] only if something wrote to the
     * database mid-export. Both are carried so a caller can log the figure without
     * special-casing this variant.
     */
    data class Complete(
        override val rowsWritten: Long,
        override val rowsTotal: Long,
    ) : ExportProgress
}
