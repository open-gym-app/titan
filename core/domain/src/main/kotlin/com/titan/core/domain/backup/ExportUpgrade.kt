package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.ExportDocument

/**
 * Brings a backup written at an older `schema_version` up to the one this build restores.
 *
 * A reader refuses every version it has no up-converter for, and this is where the
 * up-converters are. Each one answers what the older file could not have said, and has to be
 * able to answer it honestly — which is why there is one per version rather than a general
 * "fill in the defaults".
 *
 * ### 1 to 2
 *
 * Version 2 added the `gym` table and changed nothing else. A version 1 file was written by a
 * build with no gym directory, so the honest reading of its missing `gym` key is the one the
 * decoder already gives it: no gyms. Every other table is laid out as it was, and the document
 * only needs stamping with the version it now is.
 */
internal object ExportUpgrade {

    /** The older versions [toCurrent] accepts. [ExportDocument.SCHEMA_VERSION] itself is not one. */
    val READABLE_VERSIONS: IntRange = 1 until ExportDocument.SCHEMA_VERSION

    /** [document], written at one of [READABLE_VERSIONS], as this build's version of it. */
    fun toCurrent(document: ExportDocument): ExportDocument {
        require(document.schemaVersion in READABLE_VERSIONS) {
            "no up-converter from schema_version ${document.schemaVersion}"
        }
        return document.copy(schemaVersion = ExportDocument.SCHEMA_VERSION)
    }
}
