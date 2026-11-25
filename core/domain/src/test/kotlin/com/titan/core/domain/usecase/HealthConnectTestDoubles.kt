package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectBodyRecord
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.healthconnect.HealthConnectSyncScheduler
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.FinishedSessionActivity
import com.titan.core.domain.repository.HealthConnectSessionRepository

/**
 * Health Connect as a list of records, with the two things a test needs to steer: whether it is
 * there, and what the user granted.
 */
class FakeHealthConnect(
    var availability: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE,
    var granted: Set<HealthConnectAccess> = HealthConnectAccess.entries.toSet(),
) : HealthConnect {

    /** Records other apps wrote, as [readSessions] will return them. */
    val records = mutableListOf<HealthConnectRecord>()

    /** Every [writeSession] call, in order. */
    val written = mutableListOf<Write>()

    /** Every read window asked for, as `from until`. */
    val reads = mutableListOf<LongRange>()

    /** Body records other apps wrote, as [readBodyReadings] will return them. */
    val bodyRecords = mutableListOf<HealthConnectBodyRecord>()

    /** Every [writeBodyReading] call, in order. */
    val bodyWrites = mutableListOf<BodyWrite>()

    /** Every [deleteBodyReading] call, in order. */
    val bodyDeletes = mutableListOf<BodyDelete>()

    /** Thrown from every write and read instead of answering, when set. */
    var failure: Throwable? = null

    var revoked = false
        private set

    override fun availability(): HealthConnectAvailability = availability

    override val permissions: Set<String> = setOf(READ_PERMISSION, WRITE_PERMISSION)

    override suspend fun writeBodyReading(
        measurementId: Long,
        version: Long,
        reading: HealthConnectBodyReading,
    ) {
        failure?.let { throw it }
        bodyWrites += BodyWrite(measurementId, version, reading)
    }

    override suspend fun deleteBodyReading(
        measurementId: Long,
        measuredAt: Long,
        metric: HealthConnectBodyMetric,
    ) {
        failure?.let { throw it }
        bodyDeletes += BodyDelete(measurementId, measuredAt, metric)
    }

    override suspend fun readBodyReadings(
        metric: HealthConnectBodyMetric,
        measuredFrom: Long,
        measuredUntil: Long,
    ): List<HealthConnectBodyRecord> {
        failure?.let { throw it }
        return bodyRecords.filter {
            it.reading.metric == metric &&
                it.reading.measuredAt in measuredFrom until measuredUntil
        }
    }

    override suspend fun grantedAccess(): Set<HealthConnectAccess> =
        if (availability == HealthConnectAvailability.AVAILABLE) granted else emptySet()

    override suspend fun writeSession(
        sessionId: Long,
        version: Long,
        session: HealthConnectSession,
    ) {
        failure?.let { throw it }
        written += Write(sessionId, version, session)
    }

    override suspend fun readSessions(
        startedFrom: Long,
        startedUntil: Long,
    ): List<HealthConnectRecord> {
        failure?.let { throw it }
        reads += startedFrom until startedUntil
        return records.filter { it.session.startedAt in startedFrom until startedUntil }
    }

    override val canRevokeAccess: Boolean = true

    override suspend fun revokeAccess() {
        revoked = true
        granted = emptySet()
    }

    data class Write(
        val sessionId: Long,
        val version: Long,
        val session: HealthConnectSession,
    )

    data class BodyWrite(
        val measurementId: Long,
        val version: Long,
        val reading: HealthConnectBodyReading,
    )

    data class BodyDelete(
        val measurementId: Long,
        val measuredAt: Long,
        val metric: HealthConnectBodyMetric,
    )

    companion object {
        const val READ_PERMISSION = "android.permission.health.READ_EXERCISE"
        const val WRITE_PERMISSION = "android.permission.health.WRITE_EXERCISE"
    }
}

/**
 * The local side, as a list of finished sessions with their disciplines.
 *
 * Holds the unique-id rule `health_connect_id` holds in SQLite, so an import that tries to store a
 * record twice is caught here the way the database would catch it.
 */
class FakeHealthConnectSessionRepository : HealthConnectSessionRepository {

    val sessions = mutableListOf<FinishedSessionActivity>()

    /** Every batch [insertImported] was handed, whatever it kept. */
    val insertBatches = mutableListOf<List<WorkoutSession>>()

    private var nextId = 1_000L

    override suspend fun getFinished(sessionId: Long): FinishedSessionActivity? =
        sessions.firstOrNull { it.session.id == sessionId && !it.session.isInProgress }

    override suspend fun getFinishedStartedBetween(
        startedFrom: Long,
        startedUntil: Long,
    ): List<FinishedSessionActivity> =
        sessions
            .filter { !it.session.isInProgress && it.session.createdAt in startedFrom until startedUntil }
            .sortedBy { it.session.createdAt }

    override suspend fun insertImported(sessions: List<WorkoutSession>): Int {
        insertBatches += sessions
        var inserted = 0
        sessions.forEach { session ->
            require(session.healthConnectId != null) { "an imported session must carry its record id" }
            if (this.sessions.none { it.session.healthConnectId == session.healthConnectId }) {
                this.sessions += FinishedSessionActivity(session.copy(id = nextId++), emptyList())
                inserted++
            }
        }
        return inserted
    }

    /** The sessions stored by [insertImported], in insertion order. */
    val imported: List<WorkoutSession> get() = sessions.map { it.session }.filter { it.isImported }
}

/**
 * The sync queue as a list of the sessions it was asked for. Set [failure] for a queue that
 * refuses the request.
 */
class RecordingHealthConnectSyncScheduler : HealthConnectSyncScheduler {

    val requested = mutableListOf<Long>()

    var failure: RuntimeException? = null

    override fun requestSync(sessionId: Long) {
        failure?.let { throw it }
        requested += sessionId
    }
}
