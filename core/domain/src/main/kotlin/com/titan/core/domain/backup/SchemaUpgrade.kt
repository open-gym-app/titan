package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.ExportDocument

/**
 * The up-converters that let a file written at an older `schema_version` restore on this build.
 *
 * A reader refuses every version it has no converter for, newer ones always. Each version listed
 * here owes a row in docs/export-schema.md's version-history table.
 *
 * ### 1 → 2
 *
 * Version 2 added tables and changed nothing that version 1 already held, so the conversion is
 * the version stamp and **empty arrays for the new tables** — whatever a version 1 file claims
 * to hold under those keys. A version 1 writer never wrote them, so anything found there was put
 * there by hand, and it is dropped rather than restored unvalidated under an older version's
 * name.
 */
internal object SchemaUpgrade {

    /** The versions below the current one that [toCurrent] carries forward. */
    val READABLE_OLDER_VERSIONS: Set<Int> = setOf(1)

    /**
     * [document] as this build's version would have written it.
     *
     * A document already at the current version is returned as it is.
     *
     * @throws IllegalArgumentException for a version this build cannot read. The decoder asks
     *   only about versions it has already checked against [READABLE_OLDER_VERSIONS].
     */
    fun toCurrent(document: ExportDocument): ExportDocument =
        when (document.schemaVersion) {
            ExportDocument.SCHEMA_VERSION -> document

            1 -> document.copy(
                schemaVersion = ExportDocument.SCHEMA_VERSION,
                sessionPetClaim = emptyList(),
                pet = emptyList(),
                petState = emptyList(),
                petLoadout = emptyList(),
                petFeed = emptyList(),
                foodSeen = emptyList(),
                petUnlock = emptyList(),
            )

            else -> throw IllegalArgumentException("no up-converter from version ${document.schemaVersion}")
        }
}
