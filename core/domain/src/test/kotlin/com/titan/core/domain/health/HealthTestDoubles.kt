package com.titan.core.domain.health

import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.repository.HealthConnectGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeFeatureToggles(vararg enabled: M8Feature) : FeatureToggles {

    private val state = MutableStateFlow(enabled.toSet())

    override fun observe(feature: M8Feature): Flow<Boolean> = state.map { feature in it }

    override suspend fun isEnabled(feature: M8Feature): Boolean = feature in state.value

    override suspend fun setEnabled(
        feature: M8Feature,
        enabled: Boolean,
    ) {
        state.value = if (enabled) state.value + feature else state.value - feature
    }
}

class FakeHealthConnectGateway(
    var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    var permitted: Boolean = true,
    var stored: List<HealthWorkout> = emptyList(),
) : HealthConnectGateway {

    val written = mutableListOf<HealthWorkout>()
    var failWrites = false
    var lastRange: Pair<Long, Long>? = null

    override fun availability(): HealthAvailability = availability

    override suspend fun hasPermissions(): Boolean = permitted

    override suspend fun write(workout: HealthWorkout) {
        if (failWrites) throw HealthConnectException("Health Connect refused the write")
        written += workout
    }

    override suspend fun readBetween(
        sinceMillis: Long,
        untilMillis: Long,
    ): List<HealthWorkout> {
        lastRange = sinceMillis to untilMillis
        return stored
    }
}
