package com.titan.core.domain.repository

import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthWorkout

/**
 * The door to Health Connect. Implemented in `:core:data` over the platform client.
 *
 * Implementations throw only [com.titan.core.domain.model.HealthConnectException].
 * Nothing here prompts: [hasPermissions] only reports. Asking is a screen's job, because it needs
 * an activity and because the rationale has to be shown first.
 */
interface HealthConnectGateway {

    fun availability(): HealthAvailability

    /** True when both [com.titan.core.domain.model.HealthPermissions.REQUIRED] are currently granted. */
    suspend fun hasPermissions(): Boolean

    /** Writes [workout]; a workout with an [HealthWorkout.externalId] already stored is updated. */
    suspend fun write(workout: HealthWorkout)

    /** Every workout that started in `[sinceMillis, untilMillis)`, whoever wrote it. */
    suspend fun readBetween(
        sinceMillis: Long,
        untilMillis: Long,
    ): List<HealthWorkout>
}
