package com.titan.feature.settings

import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession

/**
 * Health Connect with the three things a screen test steers: whether it is there, what is
 * granted, and what other apps saved.
 */
class FakeHealthConnect(
    var availability: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE,
    var granted: Set<HealthConnectAccess> = emptySet(),
) : HealthConnect {

    val records = mutableListOf<HealthConnectRecord>()

    /** Thrown from every suspending call when set. */
    var failure: Throwable? = null

    var revokeCount = 0
        private set

    override fun availability(): HealthConnectAvailability = availability

    override val permissions: Set<String> = setOf(READ, WRITE)

    override suspend fun grantedAccess(): Set<HealthConnectAccess> {
        failure?.let { throw it }
        return if (availability == HealthConnectAvailability.AVAILABLE) granted else emptySet()
    }

    override suspend fun writeSession(
        sessionId: Long,
        version: Long,
        session: HealthConnectSession,
    ) {
        failure?.let { throw it }
    }

    override suspend fun readSessions(
        startedFrom: Long,
        startedUntil: Long,
    ): List<HealthConnectRecord> {
        failure?.let { throw it }
        return records.filter { it.session.startedAt in startedFrom until startedUntil }
    }

    /** False plays Android 14 and up, where the app cannot take its own grant back at once. */
    override var canRevokeAccess: Boolean = true

    override suspend fun revokeAccess() {
        check(canRevokeAccess) { "revoking from Android 14 only takes effect when the process dies" }
        failure?.let { throw it }
        revokeCount++
        granted = emptySet()
    }

    companion object {
        const val READ = "android.permission.health.READ_EXERCISE"
        const val WRITE = "android.permission.health.WRITE_EXERCISE"
    }
}
