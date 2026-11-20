package com.titan.core.data.healthconnect

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectBodyRecord
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * [HealthConnect] over the Jetpack client — the one class in the app that talks to the provider.
 *
 * ### Asked every time, cached never
 *
 * Availability and the grant are both read when an operation runs, as
 * [com.titan.core.data.notification.AndroidNotificationPermission] reads the notification grant:
 * Health Connect can be installed, updated or uninstalled, and access revoked from its own
 * settings, all while this process is alive and with no callback to say so. The client itself is
 * created on first use and kept, which is what `getOrCreate` is for — but only once the status
 * check has said there is a provider to bind to, because creating it on a device without one
 * throws.
 */
@Singleton
class HealthConnectAdapter @Inject constructor(@param:ApplicationContext private val context: Context) :
    HealthConnect {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /** Each [HealthConnectAccess] as the platform permission it stands for. */
    private val permissionFor: Map<HealthConnectAccess, String> =
        HealthConnectAccess.entries.associateWith { access ->
            when (access) {
                HealthConnectAccess.READ_EXERCISE -> HealthPermission.getReadPermission(ExerciseSessionRecord::class)
                HealthConnectAccess.WRITE_EXERCISE -> HealthPermission.getWritePermission(ExerciseSessionRecord::class)
                HealthConnectAccess.READ_WEIGHT -> HealthPermission.getReadPermission(WeightRecord::class)
                HealthConnectAccess.WRITE_WEIGHT -> HealthPermission.getWritePermission(WeightRecord::class)
                HealthConnectAccess.READ_BODY_FAT -> HealthPermission.getReadPermission(BodyFatRecord::class)
                HealthConnectAccess.WRITE_BODY_FAT -> HealthPermission.getWritePermission(BodyFatRecord::class)
            }
        }

    override val permissions: Set<String> = permissionFor.values.toSet()

    override fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NOT_INSTALLED
            else -> HealthConnectAvailability.UNSUPPORTED
        }

    override suspend fun grantedAccess(): Set<HealthConnectAccess> {
        if (availability() != HealthConnectAvailability.AVAILABLE) return emptySet()
        val granted = client.permissionController.getGrantedPermissions()
        return permissionFor.filterValues { it in granted }.keys
    }

    override suspend fun writeSession(
        sessionId: Long,
        version: Long,
        session: HealthConnectSession,
    ) {
        // The zone the phone is in now, which is the zone the workout that has just ended was
        // done in. The rare exception — a session finished from history after a flight — is
        // stamped with the wrong offset and the right instants, and the instants are the record.
        client.insertRecords(listOf(session.toRecord(sessionId, version, ZoneId.systemDefault())))
    }

    override suspend fun readSessions(
        startedFrom: Long,
        startedUntil: Long,
    ): List<HealthConnectRecord> {
        val records = readAll(ExerciseSessionRecord::class, startedFrom, startedUntil)

        // The filter matches a session that *overlaps* the range; the contract is one that
        // *starts* in it, so a workout begun the evening before the window opened is left out.
        return records
            .map { it.toDomain(ownPackage = context.packageName) }
            .filter { it.session.startedAt in startedFrom until startedUntil }
    }

    override suspend fun writeBodyReading(
        measurementId: Long,
        version: Long,
        reading: HealthConnectBodyReading,
    ) {
        // The zone the phone is in now, for `writeSession`'s reason: the instant is the record.
        val zone = ZoneId.systemDefault()
        val record = when (reading.metric) {
            HealthConnectBodyMetric.WEIGHT -> reading.toWeightRecord(measurementId, version, zone)
            HealthConnectBodyMetric.BODY_FAT -> reading.toBodyFatRecord(measurementId, version, zone)
        }
        client.insertRecords(listOf(record))
    }

    override suspend fun deleteBodyReading(
        measurementId: Long,
        measuredAt: Long,
        metric: HealthConnectBodyMetric,
    ) {
        // By client record id, which only ever names a record this app wrote; an id with no
        // record behind it — a figure the entry never had — is no error, and deletes nothing.
        client.deleteRecords(
            recordType = metric.recordType,
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(bodyClientRecordIdFor(metric, measurementId, measuredAt)),
        )
    }

    override suspend fun readBodyReadings(
        metric: HealthConnectBodyMetric,
        measuredFrom: Long,
        measuredUntil: Long,
    ): List<HealthConnectBodyRecord> {
        val ownPackage = context.packageName
        // A reading is an instant, so the filter's range is the contract's: no overlap to trim.
        return when (metric) {
            HealthConnectBodyMetric.WEIGHT ->
                readAll(WeightRecord::class, measuredFrom, measuredUntil).map { it.toDomain(ownPackage) }

            HealthConnectBodyMetric.BODY_FAT ->
                readAll(BodyFatRecord::class, measuredFrom, measuredUntil).map { it.toDomain(ownPackage) }
        }
    }

    /** Every [type] record in `[from, until)`, page after page. */
    private suspend fun <T : Record> readAll(
        type: KClass<T>,
        from: Long,
        until: Long,
    ): List<T> {
        val filter = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(until))
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    // From Android 14 the client's revokeAllPermissions calls revokeSelfPermissionsOnKill: the
    // grant goes when the process does, not when the call returns.
    override val canRevokeAccess: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override suspend fun revokeAccess() {
        // Refused rather than scheduled: a revocation that lands at some later process death
        // would also undo whatever the user granted again in the meantime.
        check(canRevokeAccess) { "revoking from Android 14 only takes effect when the process dies" }
        if (availability() == HealthConnectAvailability.AVAILABLE) client.permissionController.revokeAllPermissions()
    }

    private companion object {
        /** Health Connect's maximum. A month of anybody's workouts or weigh-ins fits in one page. */
        const val PAGE_SIZE = 5_000

        val HealthConnectBodyMetric.recordType: KClass<out Record>
            get() = when (this) {
                HealthConnectBodyMetric.WEIGHT -> WeightRecord::class
                HealthConnectBodyMetric.BODY_FAT -> BodyFatRecord::class
            }
    }
}
