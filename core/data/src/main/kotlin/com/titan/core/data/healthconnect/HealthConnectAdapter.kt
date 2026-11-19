package com.titan.core.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

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

    private val readPermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val writePermission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

    override val permissions: Set<String> = setOf(readPermission, writePermission)

    override fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NOT_INSTALLED
            else -> HealthConnectAvailability.UNSUPPORTED
        }

    override suspend fun grantedAccess(): Set<HealthConnectAccess> {
        if (availability() != HealthConnectAvailability.AVAILABLE) return emptySet()
        val granted = client.permissionController.getGrantedPermissions()
        return buildSet {
            if (readPermission in granted) add(HealthConnectAccess.READ)
            if (writePermission in granted) add(HealthConnectAccess.WRITE)
        }
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
        val filter = TimeRangeFilter.between(Instant.ofEpochMilli(startedFrom), Instant.ofEpochMilli(startedUntil))
        val records = mutableListOf<ExerciseSessionRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = filter,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)

        // The filter matches a session that *overlaps* the range; the contract is one that
        // *starts* in it, so a workout begun the evening before the window opened is left out.
        return records
            .map { it.toDomain(ownPackage = context.packageName) }
            .filter { it.session.startedAt in startedFrom until startedUntil }
    }

    override suspend fun revokeAccess() {
        if (availability() == HealthConnectAvailability.AVAILABLE) client.permissionController.revokeAllPermissions()
    }

    private companion object {
        /** Health Connect's maximum. A month of anybody's workouts fits in one page. */
        const val PAGE_SIZE = 5_000
    }
}
