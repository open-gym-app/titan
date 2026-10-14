package com.titan.core.data.health

import android.util.AndroidException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthPermissions
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.repository.HealthConnectGateway
import java.io.IOException
import java.time.Instant

/**
 * [HealthConnectGateway] over the Health Connect client.
 *
 * Takes its collaborators as functions so the whole of it runs on the JVM against a mocked
 * client: [sdkInt] is the device's API level, [sdkStatus] answers
 * `HealthConnectClient.getSdkStatus`, and [client] is only called once both say it is safe to.
 * Health Connect exists as a platform feature from API 28; below that nothing here touches it.
 *
 * Every failure the client can raise is rethrown as [HealthConnectException], which is all the
 * use cases catch — a permission revoked mid-sync is a skipped sync, not a crash.
 */
class HealthConnectGatewayImpl(
    private val sdkInt: Int,
    private val sdkStatus: () -> Int,
    private val client: () -> HealthConnectClient,
    private val nowMillis: () -> Long,
) : HealthConnectGateway {

    override fun availability(): HealthAvailability =
        if (sdkInt < MIN_SDK) HealthAvailability.UNSUPPORTED else statusToAvailability()

    // A platform that has no Health Connect service answers by throwing rather than by saying
    // "unavailable"; both are the same answer to the one question this is asked.
    private fun statusToAvailability(): HealthAvailability =
        try {
            when (sdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NOT_INSTALLED
                else -> HealthAvailability.UNSUPPORTED
            }
        } catch (_: AndroidException) {
            HealthAvailability.UNSUPPORTED
        }

    override suspend fun hasPermissions(): Boolean =
        guarded {
            client().permissionController.getGrantedPermissions().containsAll(REQUIRED)
        }

    override suspend fun write(workout: HealthWorkout) {
        guarded { client().insertRecords(listOf(HealthConnectMapper.toRecord(workout, nowMillis()))) }
    }

    override suspend fun readBetween(
        sinceMillis: Long,
        untilMillis: Long,
    ): List<HealthWorkout> =
        guarded {
            val found = mutableListOf<HealthWorkout>()
            var pageToken: String? = null
            do {
                val response = client().readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(
                            Instant.ofEpochMilli(sinceMillis),
                            Instant.ofEpochMilli(untilMillis),
                        ),
                        pageToken = pageToken,
                    ),
                )
                found += response.records.map(HealthConnectMapper::toWorkout)
                pageToken = response.pageToken
            } while (pageToken != null)
            found
        }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (failure: SecurityException) {
            throw HealthConnectException("Health Connect refused the request", failure)
        } catch (failure: IOException) {
            throw HealthConnectException("Health Connect could not be reached", failure)
        } catch (failure: AndroidException) {
            throw HealthConnectException("Health Connect could not be reached", failure)
        } catch (failure: IllegalStateException) {
            throw HealthConnectException("Health Connect is not in a usable state", failure)
        }

    companion object {
        private const val MIN_SDK = 28

        private val REQUIRED = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        )

        init {
            check(REQUIRED == HealthPermissions.REQUIRED) { "domain and client disagree on the permissions" }
        }
    }
}
