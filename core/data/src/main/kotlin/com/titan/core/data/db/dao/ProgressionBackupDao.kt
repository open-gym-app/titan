package com.titan.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.BodyMeasurementEntity
import com.titan.core.data.db.entity.PersonalRecordEntity

/**
 * Whole-table reads, inserts and deletes over the two progression tables. See
 * [CatalogueBackupDao].
 *
 * `personal_record` is exported rather than recomputed, against docs/PERSISTENCE.md's note that
 * an export *may* omit it: re-running the PR engine over restored history would produce a
 * plausible record set rather than the user's, since the detection rules can move between the
 * build that wrote the file and the one that reads it. docs/export-schema.md has the argument.
 */
interface ProgressionBackupDao {

    @Query("SELECT * FROM personal_record ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readPersonalRecords(
        limit: Int,
        offset: Int,
    ): List<PersonalRecordEntity>

    @Insert
    suspend fun insertPersonalRecords(rows: List<PersonalRecordEntity>)

    @Query("DELETE FROM personal_record")
    suspend fun deletePersonalRecords()

    @Query("SELECT * FROM body_measurement ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readBodyMeasurements(
        limit: Int,
        offset: Int,
    ): List<BodyMeasurementEntity>

    @Insert
    suspend fun insertBodyMeasurements(rows: List<BodyMeasurementEntity>)

    @Query("DELETE FROM body_measurement")
    suspend fun deleteBodyMeasurements()
}
