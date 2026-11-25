package com.titan.feature.progression.body

import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectBodyRecord
import com.titan.core.domain.healthconnect.HealthConnectBodySyncScheduler
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession

/**
 * Health Connect as far as the body screen asks it anything: whether it is there. Every call
 * that would reach the provider fails the test — the screen hands those to the queued sync.
 */
class AvailabilityOnlyHealthConnect(
    var availability: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE,
) : HealthConnect {

    override fun availability(): HealthConnectAvailability = availability

    override val permissions: Set<String> = emptySet()

    override suspend fun grantedAccess(): Set<HealthConnectAccess> = error("the body screen never reads the grant")

    override suspend fun writeSession(
        sessionId: Long,
        version: Long,
        session: HealthConnectSession,
    ) = error("the body screen never writes a session")

    override suspend fun readSessions(
        startedFrom: Long,
        startedUntil: Long,
    ): List<HealthConnectRecord> = error("the body screen never reads")

    override suspend fun writeBodyReading(
        measurementId: Long,
        version: Long,
        reading: HealthConnectBodyReading,
    ) = error("the body screen queues its writes")

    override suspend fun deleteBodyReading(
        measurementId: Long,
        measuredAt: Long,
        metric: HealthConnectBodyMetric,
    ) = error("the body screen queues its writes")

    override suspend fun readBodyReadings(
        metric: HealthConnectBodyMetric,
        measuredFrom: Long,
        measuredUntil: Long,
    ): List<HealthConnectBodyRecord> = error("the body screen never reads")

    override val canRevokeAccess: Boolean = true

    override suspend fun revokeAccess() = error("the body screen never disconnects")
}

/** The body sync queue as the `(id, measured at)` pairs it was asked for. */
class RecordingBodySyncScheduler : HealthConnectBodySyncScheduler {

    val requested = mutableListOf<Pair<Long, Long>>()

    /** Thrown instead of queueing, when set. */
    var failure: RuntimeException? = null

    override fun requestSync(
        measurementId: Long,
        measuredAt: Long,
    ) {
        failure?.let { throw it }
        requested += measurementId to measuredAt
    }
}
