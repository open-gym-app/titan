package com.titan.core.domain.health

import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthSkipReason
import com.titan.core.domain.repository.HealthConnectGateway

/** Why a sync or import must not run right now, or null when it may. Cheapest check first. */
internal suspend fun HealthConnectGateway.blockedReason(toggles: FeatureToggles): HealthSkipReason? =
    when {
        !toggles.isEnabled(M8Feature.HEALTH_CONNECT) -> HealthSkipReason.DISABLED
        availability() != HealthAvailability.AVAILABLE -> HealthSkipReason.UNAVAILABLE
        !hasPermissions() -> HealthSkipReason.NO_PERMISSION
        else -> null
    }
