package com.titan.core.domain.marketplace

/**
 * What one ingestion did to the local marketplace table.
 *
 * A template seen again and unchanged is in none of these: re-ingesting the same bundle reports
 * `0, 0, 0`, which is what "idempotent" is observed as.
 *
 * @property inserted templates the table did not have.
 * @property updated templates it had whose content changed, or that were hidden and are back.
 * @property softDeleted templates hidden because they have been absent for longer than the TTL.
 */
data class IngestionResult(
    val inserted: Int,
    val updated: Int,
    val softDeleted: Int,
) {
    init {
        require(inserted >= 0 && updated >= 0 && softDeleted >= 0) { "counts cannot be negative" }
    }
}
